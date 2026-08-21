#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import re
from pathlib import Path

PLAYLIST = Path("kr-tivimate.m3u")
STATE = Path("sbs-plus-route.txt")
REPORT = Path("official-channel-report.txt")
SBS_PLUS_ID = "SBSPlus.kr@SD"
SBS_PLUS_REFERER = "https://www.sbs.co.kr/live/S03"


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
    m = re.search(r'tvg-id="([^"]+)"', extinf)
    if m and re.sub(r"@(sd|hd|fhd)$", "", m.group(1).lower()) == "sbsplus.kr":
        return True
    return extinf.rsplit(",", 1)[-1].strip().lower().replace(" ", "") in {"sbsplus", "sbs플러스"}


def state_url():
    if not STATE.exists():
        return None
    for line in STATE.read_text(encoding="utf-8").splitlines():
        if line.startswith("url="):
            url = line[4:].strip()
            if url.startswith(("http://", "https://")):
                return url
    return None


def append_report(message):
    old = REPORT.read_text(encoding="utf-8") if REPORT.exists() else ""
    old = "\n".join(line for line in old.splitlines() if not line.startswith("SBS Plus STATE "))
    REPORT.write_text((old.rstrip() + "\n" if old.strip() else "") + message + "\n", encoding="utf-8")


def main():
    url = state_url()
    if not PLAYLIST.exists():
        raise SystemExit("playlist missing")
    lines = PLAYLIST.read_text(encoding="utf-8-sig").replace("\r\n", "\n").splitlines()
    header = lines[0] if lines else "#EXTM3U"
    entries = [b for b in parse_entries(lines) if not is_sbs_plus(b)]
    out = [header]
    if url:
        out += [
            f'#EXTINF:-1 tvg-id="{SBS_PLUS_ID}" group-title="Entertainment;Official;LocalResolved",SBS Plus',
            f'#EXTVLCOPT:http-referrer={SBS_PLUS_REFERER}',
            url,
        ]
        append_report("SBS Plus STATE RESTORED local official route")
    else:
        append_report("SBS Plus STATE EMPTY")
    for block in entries:
        out.extend(block)
    PLAYLIST.write_text("\n".join(out).rstrip() + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
