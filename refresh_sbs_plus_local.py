#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import json
import subprocess
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

API = "https://apis.sbs.co.kr/play-api/1.0/onair/channel/S03"
REFERER = "https://www.sbs.co.kr/live/S03"
STATE = Path("sbs-plus-route.txt")


def headers():
    return {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131 Safari/537.36",
        "Accept": "application/json,text/plain,*/*",
        "Referer": REFERER,
        "Origin": "https://www.sbs.co.kr",
    }


def collect(obj):
    out = []
    if isinstance(obj, dict):
        u = obj.get("mediaurl")
        if isinstance(u, str) and u.startswith(("http://", "https://")):
            out.append(u)
        for v in obj.values():
            out.extend(collect(v))
    elif isinstance(obj, list):
        for v in obj:
            out.extend(collect(v))
    return out


def resolve():
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
    with urllib.request.urlopen(req, timeout=20) as r:
        data = json.loads(r.read().decode("utf-8", "replace"))
    urls = collect(data)
    if not urls:
        raise SystemExit("S03 API returned no mediaurl. Confirm this PC is using a Korean network and SBS Plus is on-air.")
    return urls[0]


def git(*args):
    return subprocess.run(["git", *args], check=True, text=True, capture_output=True)


def main():
    mediaurl = resolve()
    stamp = datetime.now(timezone.utc).isoformat()
    STATE.write_text(f"resolved_at={stamp}\nurl={mediaurl}\n", encoding="utf-8")

    subprocess.run(["python", "apply_sbs_plus_route.py"], check=True)

    print("SBS Plus official route resolved and applied")
    print(mediaurl)

    # If this is a git checkout with credentials already configured, publish it.
    try:
        git("add", "sbs-plus-route.txt", "kr-tivimate.m3u", "official-channel-report.txt")
        diff = subprocess.run(["git", "diff", "--cached", "--quiet"])
        if diff.returncode != 0:
            git("commit", "-m", "chore: refresh SBS Plus from Korean network")
            git("push")
            print("GitHub push complete")
        else:
            print("No git changes to publish")
    except Exception as e:
        print(f"Route updated locally; automatic git push skipped: {e}")


if __name__ == "__main__":
    main()
