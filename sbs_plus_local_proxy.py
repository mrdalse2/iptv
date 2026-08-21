#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import json
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HOST = "0.0.0.0"
PORT = 8787
API = "https://apis.sbs.co.kr/play-api/1.0/onair/channel/S03"
REFERER = "https://www.sbs.co.kr/live/S03"


def headers():
    return {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131 Safari/537.36",
        "Accept": "application/json,text/plain,*/*",
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
        "v_type": "2",
        "platform": "pcweb",
        "protocol": "hls",
        "ssl": "N",
        "rscuse": "",
        "jwt-token": "",
        "sbsmain": "",
    }
    url = API + "?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, headers=headers())
    with urllib.request.urlopen(req, timeout=15) as response:
        data = json.loads(response.read().decode("utf-8", "replace"))
    urls = collect_mediaurls(data)
    if not urls:
        raise RuntimeError("S03 API returned no mediaurl")
    return urls[0]


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path.split("?", 1)[0] not in {"/sbsplus", "/sbsplus.m3u8"}:
            self.send_response(404)
            self.end_headers()
            return
        try:
            target = resolve_sbs_plus()
            self.send_response(302)
            self.send_header("Location", target)
            self.send_header("Cache-Control", "no-store, no-cache, must-revalidate")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            print("SBS Plus ->", target)
        except Exception as exc:
            body = ("SBS Plus resolver error: " + str(exc)).encode("utf-8")
            self.send_response(502)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    def log_message(self, fmt, *args):
        print("[proxy]", fmt % args)


if __name__ == "__main__":
    print(f"SBS Plus local resolver listening on http://{HOST}:{PORT}/sbsplus.m3u8")
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
