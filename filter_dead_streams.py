#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Remove definitely dead streams from kr-tivimate.m3u.

The filter is intentionally conservative:
- expired signed URLs, HTTP 404/410, and HTML/non-stream responses are removed;
- auth/rate-limit/server errors and timeouts are kept as uncertain;
- private/local URLs are kept because GitHub Actions cannot reach the user's LAN.
"""

from __future__ import annotations

import base64
import concurrent.futures
import ipaddress
import json
import re
import socket
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path

PLAYLIST = Path("kr-tivimate.m3u")
REPORT = Path("dead-channel-report.txt")
MAX_WORKERS = 12
TIMEOUT = 7
ATTEMPTS = 2


@dataclass
class Entry:
    extinf: str
    opts: list[str]
    url: str

    @property
    def name(self) -> str:
        return self.extinf.rsplit(",", 1)[-1].strip() if "," in self.extinf else self.extinf

    @property
    def referer(self) -> str | None:
        for opt in self.opts:
            if opt.startswith("#EXTVLCOPT:http-referrer="):
                return opt.split("=", 1)[1].strip()
        return None


@dataclass(frozen=True)
class Health:
    state: str  # alive | dead | uncertain | local
    detail: str


def parse_entries(text: str) -> tuple[str, list[Entry]]:
    lines = text.replace("\r\n", "\n").splitlines()
    if not lines or not lines[0].startswith("#EXTM3U"):
        raise SystemExit("Invalid M3U playlist")

    header = lines[0]
    entries: list[Entry] = []
    i = 1
    while i < len(lines):
        if not lines[i].startswith("#EXTINF:"):
            i += 1
            continue
        extinf = lines[i].strip()
        opts: list[str] = []
        url = None
        i += 1
        while i < len(lines) and not lines[i].startswith("#EXTINF:"):
            line = lines[i].strip()
            if line.startswith("#EXT"):
                opts.append(line)
            elif line.startswith(("http://", "https://")):
                url = line
                i += 1
                break
            i += 1
        if url:
            entries.append(Entry(extinf, opts, url))
    return header, entries


def request_headers(referer: str | None) -> dict[str, str]:
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131 Safari/537.36",
        "Accept": "application/vnd.apple.mpegurl,application/x-mpegURL,video/mp2t,*/*",
        "Range": "bytes=0-8191",
        "Cache-Control": "no-cache",
    }
    if referer:
        headers["Referer"] = referer
    return headers


def is_private_or_local(url: str) -> bool:
    try:
        host = urllib.parse.urlsplit(url).hostname
        if not host:
            return False
        if host.lower().endswith(".local") or host.lower() == "localhost":
            return True
        try:
            ip = ipaddress.ip_address(host)
            return ip.is_private or ip.is_loopback or ip.is_link_local
        except ValueError:
            return False
    except Exception:
        return False


def find_epoch_times(value) -> list[int]:
    found: list[int] = []
    if isinstance(value, dict):
        for key, item in value.items():
            if key.lower() in ("aws:epochtime", "epochtime"):
                try:
                    found.append(int(item))
                except (TypeError, ValueError):
                    pass
            found.extend(find_epoch_times(item))
    elif isinstance(value, list):
        for item in value:
            found.extend(find_epoch_times(item))
    return found


def expired_cloudfront_policy(query: dict[str, list[str]], now: int) -> tuple[bool, str]:
    values = query.get("Policy") or query.get("policy")
    if not values:
        return False, ""
    raw = values[0].strip()
    if not raw:
        return False, ""
    try:
        # CloudFront's URL-safe alphabet maps + -> -, / -> ~, = -> _.
        padded = raw.replace("-", "+").replace("~", "/").replace("_", "=")
        padded += "=" * ((4 - len(padded) % 4) % 4)
        policy = json.loads(base64.b64decode(padded).decode("utf-8"))
        epochs = find_epoch_times(policy)
        if epochs and max(epochs) < now - 30:
            return True, f"expired CloudFront Policy epoch={max(epochs)}"
    except Exception:
        # An undecodable policy is not enough evidence to delete a stream.
        pass
    return False, ""


def expired_time_signature(url: str) -> tuple[bool, str]:
    """Detect common query-string and CloudFront epoch signatures already expired."""
    try:
        query = urllib.parse.parse_qs(urllib.parse.urlsplit(url).query, keep_blank_values=True)
    except Exception:
        return False, ""

    now = int(time.time())
    for key in ("time", "expires", "expire", "exp"):
        values = query.get(key) or query.get(key.capitalize())
        if not values:
            continue
        raw = values[0].strip()
        if not re.fullmatch(r"\d{9,13}", raw):
            continue
        try:
            epoch = int(raw)
            if epoch > 10_000_000_000:  # milliseconds
                epoch //= 1000
            if epoch < now - 30:
                return True, f"expired {key}={epoch}"
        except ValueError:
            pass

    return expired_cloudfront_policy(query, now)


def looks_html(content_type: str, sample: bytes) -> bool:
    ctype = content_type.lower()
    text = sample[:2048].decode("utf-8", "ignore").lstrip().lower()
    return (
        "text/html" in ctype
        or text.startswith("<!doctype html")
        or text.startswith("<html")
        or "<html" in text[:256]
    )


def looks_stream(url: str, content_type: str, sample: bytes) -> bool:
    ctype = content_type.lower()
    text = sample.decode("utf-8", "ignore")
    if "#EXTM3U" in text:
        return True
    if "mpegurl" in ctype or "vnd.apple.mpegurl" in ctype:
        return True
    if "video/mp2t" in ctype or "video/mpeg" in ctype:
        return True
    if "application/octet-stream" in ctype and sample and not looks_html(content_type, sample):
        return True
    return False


def dns_is_definitely_dead(exc: BaseException) -> bool:
    text = str(exc).lower()
    permanent_markers = (
        "name or service not known",
        "nodename nor servname provided",
        "no address associated with hostname",
        "non-recoverable failure in name resolution",
    )
    return any(marker in text for marker in permanent_markers)


def probe(url: str, referer: str | None) -> Health:
    if is_private_or_local(url):
        return Health("local", "private/local address; preserved")

    expired, reason = expired_time_signature(url)
    if expired:
        return Health("dead", reason)

    last_detail = "unknown"
    for attempt in range(1, ATTEMPTS + 1):
        req = urllib.request.Request(url, headers=request_headers(referer))
        try:
            with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
                status = getattr(r, "status", 200)
                content_type = r.headers.get("Content-Type", "") or ""
                final_url = r.geturl()
                sample = r.read(8192)

                if status >= 400:
                    last_detail = f"HTTP {status}"
                    continue
                if looks_stream(final_url, content_type, sample):
                    return Health("alive", f"HTTP {status} {content_type or 'stream'}")
                if looks_html(content_type, sample):
                    return Health("dead", f"HTTP {status} HTML/non-stream response")

                path = urllib.parse.urlsplit(final_url).path.lower()
                if path.endswith(".m3u8"):
                    return Health("dead", f"HTTP {status} invalid HLS body ({content_type or 'unknown type'})")
                return Health("uncertain", f"HTTP {status} unrecognized content ({content_type or 'unknown type'})")

        except urllib.error.HTTPError as e:
            code = e.code
            last_detail = f"HTTP {code}"
            if code in (404, 410):
                return Health("dead", last_detail)
            if code in (401, 403, 405, 406, 408, 425, 429, 451) or 500 <= code <= 599:
                return Health("uncertain", last_detail)
            if 400 <= code <= 499 and attempt == ATTEMPTS:
                return Health("dead", last_detail)
        except urllib.error.URLError as e:
            last_detail = f"URLError {e.reason}"
            if dns_is_definitely_dead(e.reason):
                if attempt == ATTEMPTS:
                    return Health("dead", last_detail)
            else:
                if attempt == ATTEMPTS:
                    return Health("uncertain", last_detail)
        except (TimeoutError, socket.timeout) as e:
            last_detail = type(e).__name__
            if attempt == ATTEMPTS:
                return Health("uncertain", last_detail)
        except OSError as e:
            last_detail = f"OSError {e}"
            if dns_is_definitely_dead(e):
                if attempt == ATTEMPTS:
                    return Health("dead", last_detail)
            elif attempt == ATTEMPTS:
                return Health("uncertain", last_detail)
        except Exception as e:
            last_detail = f"{type(e).__name__}: {e}"
            if attempt == ATTEMPTS:
                return Health("uncertain", last_detail)

        if attempt < ATTEMPTS:
            time.sleep(0.35 * attempt)

    return Health("uncertain", last_detail)


def main() -> None:
    text = PLAYLIST.read_text(encoding="utf-8-sig")
    header, entries = parse_entries(text)

    keys = {(entry.url, entry.referer) for entry in entries}
    results: dict[tuple[str, str | None], Health] = {}

    with concurrent.futures.ThreadPoolExecutor(max_workers=MAX_WORKERS) as pool:
        futures = {
            pool.submit(probe, url, referer): (url, referer)
            for url, referer in keys
        }
        for future in concurrent.futures.as_completed(futures):
            key = futures[future]
            try:
                results[key] = future.result()
            except Exception as e:
                results[key] = Health("uncertain", f"probe exception {type(e).__name__}: {e}")

    kept: list[Entry] = []
    removed: list[tuple[Entry, Health]] = []
    counts = {"alive": 0, "dead": 0, "uncertain": 0, "local": 0}

    for entry in entries:
        health = results[(entry.url, entry.referer)]
        counts[health.state] = counts.get(health.state, 0) + 1
        if health.state == "dead":
            removed.append((entry, health))
        else:
            kept.append(entry)

    out = [header]
    for entry in kept:
        out.append(entry.extinf)
        out.extend(entry.opts)
        out.append(entry.url)
    PLAYLIST.write_text("\n".join(out).rstrip() + "\n", encoding="utf-8")

    report = [
        f"checked={len(entries)} kept={len(kept)} removed={len(removed)} "
        f"alive={counts['alive']} uncertain={counts['uncertain']} local={counts['local']}",
        "",
        "REMOVED DEFINITELY DEAD CHANNELS",
    ]
    if removed:
        for entry, health in removed:
            report.append(f"- {entry.name} | {health.detail} | {entry.url}")
    else:
        report.append("- none")

    REPORT.write_text("\n".join(report) + "\n", encoding="utf-8")
    print("\n".join(report))


if __name__ == "__main__":
    main()
