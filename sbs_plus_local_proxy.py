#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import json
import re
import threading
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HOST = "0.0.0.0"
PORT = 8787
API = "https://apis.sbs.co.kr/play-api/1.0/onair/channel/S03"
REFERER = "https://www.sbs.co.kr/live/S03"
REMOTE_M3U = "https://raw.githubusercontent.com/mrdalse2/iptv/main/kr-tivimate.m3u"
ALLOWED = set()
ALLOWED_LOCK = threading.Lock()


def headers(accept="*/*"):
    return {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131 Safari/537.36",
        "Accept": accept,
        "Referer": REFERER,
        "Origin": "https://www.sbs.co.kr",
    }


def collect_mediaurls(obj):
    out = []
    if isinstance(obj, dict):
        url = obj.get("mediaurl")
        if isinstance(url, str) and url.startswith(("http://", "https://")):
            out.append(url)
        for value in obj.values():
            out.extend(collect_mediaurls(value))
    elif isinstance(obj, list):
        for value in obj:
            out.extend(collect_mediaurls(value))
    return out


def resolve_sbs_plus():
    params = {
        "v_type": "2", "platform": "pcweb", "protocol": "hls", "ssl": "N",
        "rscuse": "", "jwt-token": "", "sbsmain": "",
    }
    url = API + "?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, headers=headers("application/json,text/plain,*/*"))
    with urllib.request.urlopen(req, timeout=15) as response:
        data = json.loads(response.read().decode("utf-8", "replace"))
    urls = collect_mediaurls(data)
    if not urls:
        raise RuntimeError("S03 API returned no mediaurl")
    return urls[0]


def register(url):
    with ALLOWED_LOCK:
        ALLOWED.add(url)


def is_allowed(url):
    with ALLOWED_LOCK:
        return url in ALLOWED


def localize(url):
    register(url)
    return "/hls?u=" + urllib.parse.quote(url, safe="")


def rewrite_playlist(text, base_url):
    uri_attr = re.compile(r'(URI=")([^"]+)(")')
    def repl(match):
        absolute = urllib.parse.urljoin(base_url, match.group(2))
        return match.group(1) + localize(absolute) + match.group(3)
    out = []
    for raw in text.splitlines():
        line = uri_attr.sub(repl, raw)
        stripped = line.strip()
        if stripped and not stripped.startswith("#"):
            line = localize(urllib.parse.urljoin(base_url, stripped))
        out.append(line)
    return "\n".join(out) + "\n"


def fetch(url, accept="*/*"):
    req = urllib.request.Request(url, headers=headers(accept))
    with urllib.request.urlopen(req, timeout=20) as response:
        body = response.read()
        content_type = response.headers.get_content_type() or "application/octet-stream"
        final_url = response.geturl()
    return body, content_type, final_url


def looks_like_playlist(url, content_type, body):
    if "mpegurl" in content_type.lower() or urllib.parse.urlsplit(url).path.lower().endswith(".m3u8"):
        return True
    return body.lstrip().startswith(b"#EXTM3U")


def is_sbs_plus_extinf(line):
    lower = line.lower()
    if 'tvg-id="sbsplus.kr@sd"' in lower or 'tvg-id="sbsplus.kr"' in lower:
        return True
    name = line.rsplit(",", 1)[-1].strip().lower() if "," in line else ""
    return name in {"sbs plus", "sbsplus", "sbs 플러스"}


def aggregate_playlist(local_sbs_url):
    body, _, _ = fetch(REMOTE_M3U, "application/x-mpegURL,text/plain,*/*")
    lines = body.decode("utf-8", "replace").replace("\r\n", "\n").replace("\r", "\n").split("\n")
    out = []
    pending = False
    found = False
    for line in lines:
        stripped = line.strip()
        if stripped.startswith("#EXTINF:"):
            pending = is_sbs_plus_extinf(stripped)
            found = found or pending
            out.append(line)
            continue
        if pending and stripped and not stripped.startswith("#"):
            out.append(local_sbs_url)
            pending = False
            continue
        out.append(line)
    if not found:
        out.extend([
            '#EXTINF:-1 tvg-id="SBSPlus.kr@SD" group-title="Entertainment;Official;LocalProxy",SBS Plus',
            local_sbs_url,
        ])
    return ("\n".join(out).rstrip() + "\n").encode("utf-8")


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        parsed = urllib.parse.urlsplit(self.path)
        try:
            if parsed.path in {"/playlist.m3u", "/playlist.m3u8"}:
                host = self.headers.get("Host") or f"127.0.0.1:{PORT}"
                body = aggregate_playlist(f"http://{host}/sbsplus.m3u8")
                self.send_response(200)
                self.send_header("Content-Type", "application/x-mpegURL; charset=utf-8")
                self.send_header("Content-Length", str(len(body)))
                self.send_header("Cache-Control", "no-store, no-cache, must-revalidate")
                self.send_header("Access-Control-Allow-Origin", "*")
                self.send_header("Connection", "close")
                self.end_headers()
                self.wfile.write(body)
                return
            if parsed.path in {"/sbsplus", "/sbsplus.m3u8", "/"}:
                target = resolve_sbs_plus()
                register(target)
                self.proxy(target, root=True)
                return
            if parsed.path == "/hls":
                query = urllib.parse.parse_qs(parsed.query)
                target = query.get("u", [""])[0]
                if not target.startswith(("http://", "https://")) or not is_allowed(target):
                    self.send_error(403, "Unknown HLS resource")
                    return
                self.proxy(target, root=False)
                return
            self.send_error(404)
        except Exception as exc:
            body = ("Local IPTV proxy error: " + str(exc)).encode("utf-8")
            self.send_response(502)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(body)

    def proxy(self, target, root=False):
        body, content_type, final_url = fetch(target)
        if looks_like_playlist(final_url, content_type, body):
            body = rewrite_playlist(body.decode("utf-8", "replace"), final_url).encode("utf-8")
            content_type = "application/vnd.apple.mpegurl"
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store, no-cache, must-revalidate" if root or "mpegurl" in content_type else "private, max-age=5")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        print("[proxy]", fmt % args)


if __name__ == "__main__":
    print(f"Local IPTV aggregate playlist: http://{HOST}:{PORT}/playlist.m3u")
    print(f"SBS Plus seamless HLS proxy: http://{HOST}:{PORT}/sbsplus.m3u8")
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
