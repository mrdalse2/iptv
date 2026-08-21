#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import json
import re
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

PLAYLIST = Path("kr-tivimate.m3u")
REPORT = Path("official-channel-report.txt")
SBS_PLUS_ID = "SBSPlus.kr@SD"
SBS_PLUS_API = "https://apis.sbs.co.kr/play-api/1.0/onair/channel/S03"
SBS_PLUS_REFERER = "https://www.sbs.co.kr/live/S03"

# Public redirect/proxy candidates seen in currently maintained IPTV lists.
# They are accepted only if they resolve to a real HLS playlist.
FALLBACKS = [
    "http://itskoi.dothome.co.kr/sbs.php?id=S03",
    "http://han.ddkdxmkj.com/api/sbs.php?id=3",
]


def headers(referer=None):
    h = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131 Safari/537.36",
        "Accept": "application/vnd.apple.mpegurl,application/x-mpegURL,application/json,text/plain,*/*",
    }
    if referer:
        h["Referer"] = referer
    return h


def request(url, referer=None, timeout=12):
    req = urllib.request.Request(url, headers=headers(referer))
    with urllib.request.urlopen(req, timeout=timeout) as r:
        body = r.read(65536)
        return body, r.geturl(), (r.headers.get("Content-Type", "") or "").lower()


def collect_mediaurls(obj):
    found = []
    if isinstance(obj, dict):
        url = obj.get("mediaurl")
        if isinstance(url, str) and url.startswith(("http://", "https://")):
            q = obj.get("quality") or obj.get("resolution") or ""
            m = re.search(r"(\d{3,4})", str(q))
            found.append((int(m.group(1)) if m else 0, url))
        for value in obj.values():
            found.extend(collect_mediaurls(value))
    elif isinstance(obj, list):
        for value in obj:
            found.extend(collect_mediaurls(value))
    return found


def resolve_official():
    params = {
        "v_type": "2",
        "platform": "pcweb",
        "protocol": "hls",
        "ssl": "N",
        "rscuse": "",
        "jwt-token": "",
        "sbsmain": "",
    }
    url = SBS_PLUS_API + "?" + urllib.parse.urlencode(params)
    body, _, _ = request(url, SBS_PLUS_REFERER)
    data = json.loads(body.decode("utf-8", "replace"))
    candidates = collect_mediaurls(data)
    if not candidates:
        return None
    candidates.sort(key=lambda x: x[0], reverse=True)
    return candidates[0][1]


def resolve_hls(url):
    """Follow redirects and accept only a real HLS response."""
    try:
        body, final_url, content_type = request(url, SBS_PLUS_REFERER)
    except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, OSError, ValueError):
        return None
    text = body.decode("utf-8", "ignore")
    if "#EXTM3U" not in text and "mpegurl" not in content_type:
        return None
    return final_url


def parse_entries(lines):
    entries = []
    i = 1 if lines and lines[0].startswith("#EXTM3U") else 0
    while i < len(lines):
        if not lines[i].startswith("#EXTINF:"):
            i += 1
            continue
        block = [lines[i]]
        i += 1
        while i < len(lines) and not lines[i].startswith("#EXTINF:"):
            block.append(lines[i])
            i += 1
        entries.append(block)
    return entries


def is_sbs_plus(block):
    if not block:
        return False
    extinf = block[0]
    tid = re.search(r'tvg-id="([^"]+)"', extinf)
    if tid and re.sub(r"@(sd|hd|fhd)$", "", tid.group(1).lower()) == "sbsplus.kr":
        return True
    name = extinf.rsplit(",", 1)[-1].strip().lower().replace(" ", "")
    return name in {"sbsplus", "sbs플러스"}


def write_playlist(stream_url):
    lines = PLAYLIST.read_text(encoding="utf-8-sig").replace("\r\n", "\n").splitlines()
    header = lines[0] if lines else "#EXTM3U"
    entries = [b for b in parse_entries(lines) if not is_sbs_plus(b)]
    out = [header]
    if stream_url:
        out += [
            f'#EXTINF:-1 tvg-id="{SBS_PLUS_ID}" group-title="Entertainment;Official",SBS Plus',
            f'#EXTVLCOPT:http-referrer={SBS_PLUS_REFERER}',
            stream_url,
        ]
    for block in entries:
        out.extend(block)
    PLAYLIST.write_text("\n".join(out).rstrip() + "\n", encoding="utf-8")


def append_report(message):
    old = REPORT.read_text(encoding="utf-8") if REPORT.exists() else ""
    old = "\n".join(line for line in old.splitlines() if not line.startswith("SBS Plus FINAL "))
    REPORT.write_text((old.rstrip() + "\n" if old.strip() else "") + message + "\n", encoding="utf-8")


def main():
    source = None
    stream = None
    try:
        official = resolve_official()
    except Exception:
        official = None

    if official:
        # Official mediaurl is authoritative. Write it even if this runner cannot
        # fetch the geo-restricted playlist; playback occurs from the user's KR network.
        stream = official
        source = "official-S03"
    else:
        for candidate in FALLBACKS:
            resolved = resolve_hls(candidate)
            if resolved:
                stream = resolved
                source = candidate
                break

    write_playlist(stream)
    if stream:
        append_report(f"SBS Plus FINAL OK source={source} url={stream}")
        print(f"SBS Plus FINAL OK source={source}")
    else:
        append_report("SBS Plus FINAL DROP no verified route")
        print("SBS Plus FINAL DROP no verified route")


if __name__ == "__main__":
    main()
