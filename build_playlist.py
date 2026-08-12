#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import json
import re
import urllib.parse
import urllib.request
from pathlib import Path

PLAYLIST = Path("kr-tivimate.m3u")
BASE_URL = "https://iptv-org.github.io/iptv/countries/kr.m3u"
EPG_URL = "https://raw.githubusercontent.com/mrdalse2/iptv/main/kr-tivimate-epg.xml"
SBS_API = "https://apis.sbs.co.kr/play-api/1.0/onair/channel/S01"
SBS_ID = "SBS.kr@SD"


def fetch(url, params=None):
    if params:
        url += "?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(
        url,
        headers={
            "User-Agent": "Mozilla/5.0",
            "Accept": "application/json,text/plain,*/*",
            "Referer": "https://www.sbs.co.kr/live/S01",
        },
    )
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read()


def media_candidates(obj):
    out = []
    if isinstance(obj, dict):
        url = obj.get("mediaurl")
        if isinstance(url, str) and url.startswith(("http://", "https://")):
            q = obj.get("quality") or obj.get("resolution") or ""
            m = re.search(r"(\d{3,4})", str(q))
            score = int(m.group(1)) if m else 0
            out.append((score, url))
        for v in obj.values():
            out.extend(media_candidates(v))
    elif isinstance(obj, list):
        for v in obj:
            out.extend(media_candidates(v))
    return out


def resolve_sbs():
    params = {
        "platform": "pcweb",
        "protocol": "hls",
        "ssl": "Y",
    }
    data = json.loads(fetch(SBS_API, params).decode("utf-8", "replace"))
    candidates = media_candidates(data)
    if not candidates:
        return None
    candidates.sort(key=lambda x: x[0], reverse=True)
    return candidates[0][1]


def strip_channel(lines, tvg_id):
    out = []
    i = 0
    while i < len(lines):
        line = lines[i]
        if line.startswith("#EXTINF:") and f'tvg-id="{tvg_id}"' in line:
            i += 1
            while i < len(lines) and lines[i].startswith("#EXT") and not lines[i].startswith("#EXTINF:"):
                i += 1
            if i < len(lines) and not lines[i].startswith("#"):
                i += 1
            continue
        out.append(line)
        i += 1
    return out


def main():
    if PLAYLIST.exists():
        text = PLAYLIST.read_text(encoding="utf-8-sig")
    else:
        text = fetch(BASE_URL).decode("utf-8-sig", "replace")

    lines = text.replace("\r\n", "\n").splitlines()
    if not lines or not lines[0].startswith("#EXTM3U"):
        raise SystemExit("Invalid playlist")

    lines[0] = f'#EXTM3U x-tvg-url="{EPG_URL}"'
    lines = strip_channel(lines, SBS_ID)

    try:
        sbs_url = resolve_sbs()
    except Exception as e:
        print(f"SBS official API unavailable: {e}")
        sbs_url = None

    if sbs_url:
        official = [
            f'#EXTINF:-1 tvg-id="{SBS_ID}" group-title="General;Official",SBS',
            '#EXTVLCOPT:http-referrer=https://www.sbs.co.kr/live/S01',
            sbs_url,
        ]
        lines = lines[:1] + official + lines[1:]
        print("Added SBS from official SBS API")
    else:
        print("SBS was not added because the official API returned no playable HLS URL")

    PLAYLIST.write_text("\n".join(lines).rstrip() + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
