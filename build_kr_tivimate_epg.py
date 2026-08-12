#!/usr/bin/env python3
import re
import sys
import unicodedata
import urllib.request
import xml.etree.ElementTree as ET
from difflib import SequenceMatcher
from pathlib import Path

M3U_URL = "https://iptv-org.github.io/iptv/countries/kr.m3u"
EPG_URL = "https://raw.githubusercontent.com/globetvapp/epg/main/Korea/korea1.xml"

OUT_M3U = "kr-tivimate-epg.m3u"
OUT_REPORT = "kr-tivimate-epg-report.txt"

def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read()

def norm(s):
    s = unicodedata.normalize("NFKC", s or "")
    s = s.lower()
    # remove quality / availability annotations often present in IPTV-org display names
    s = re.sub(r"\[[^\]]*\]", " ", s)
    s = re.sub(r"\([^)]*(?:p|hd|sd|uhd|4k|not 24/7|geo-blocked)[^)]*\)", " ", s, flags=re.I)
    # common semantic normalization
    repl = {
        "television": "tv",
        "channel": "",
        "shopping": "shop",
        "home shopping": "home shop",
        "korea": "",
        "대한민국": "",
    }
    for a,b in repl.items():
        s = s.replace(a,b)
    s = re.sub(r"[^0-9a-z가-힣]+", "", s)
    return s

def parse_m3u(text):
    lines = text.replace("\r\n","\n").replace("\r","\n").splitlines()
    items = []
    pre = []
    i = 0
    while i < len(lines):
        line = lines[i]
        if line.startswith("#EXTINF:"):
            extinf = line
            extras = []
            i += 1
            while i < len(lines) and lines[i].startswith("#") and not lines[i].startswith("#EXTINF:"):
                extras.append(lines[i]); i += 1
            url = lines[i] if i < len(lines) else ""
            name = extinf.split(",",1)[1].strip() if "," in extinf else ""
            m = re.search(r'tvg-id="([^"]*)"', extinf)
            old_id = m.group(1) if m else ""
            items.append({"extinf":extinf,"extras":extras,"url":url,"name":name,"old_id":old_id})
        i += 1
    return items

def parse_epg(xml_bytes):
    root = ET.fromstring(xml_bytes)
    chans = []
    for ch in root.findall("channel"):
        cid = ch.get("id","")
        names = [(x.text or "").strip() for x in ch.findall("display-name") if (x.text or "").strip()]
        if cid:
            chans.append({"id":cid, "names":names})
    return chans

def score(mname, ch):
    a = norm(mname)
    best = 0.0
    for n in [ch["id"]] + ch["names"]:
        b = norm(n)
        if not a or not b:
            continue
        if a == b:
            return 1.0
        if a in b or b in a:
            best = max(best, 0.94 if min(len(a),len(b)) >= 4 else 0.86)
        best = max(best, SequenceMatcher(None,a,b).ratio())
    return best

def replace_tvg_id(extinf, new_id):
    if re.search(r'tvg-id="[^"]*"', extinf):
        return re.sub(r'tvg-id="[^"]*"', f'tvg-id="{new_id}"', extinf, count=1)
    # insert immediately after #EXTINF duration token
    return re.sub(r'^(#EXTINF:[^ ]+)', r'\1 tvg-id="' + new_id + '"', extinf, count=1)

def main():
    print("Downloading IPTV-org M3U...")
    m3u = fetch(M3U_URL).decode("utf-8-sig", errors="replace")
    print("Downloading Korea EPG...")
    epg = fetch(EPG_URL)

    items = parse_m3u(m3u)
    chans = parse_epg(epg)

    out = ['#EXTM3U x-tvg-url="' + EPG_URL + '"']
    report = []
    matched = 0

    for it in items:
        ranked = sorted(((score(it["name"], c), c) for c in chans), key=lambda x:x[0], reverse=True)
        best_s, best_c = ranked[0] if ranked else (0, None)
        second_s = ranked[1][0] if len(ranked) > 1 else 0

        # Conservative threshold + margin to avoid wrong auto-matches.
        confident = best_c is not None and (
            best_s >= 0.93 or (best_s >= 0.84 and best_s - second_s >= 0.08)
        )

        if confident:
            extinf = replace_tvg_id(it["extinf"], best_c["id"])
            matched += 1
            epg_name = best_c["names"][0] if best_c["names"] else best_c["id"]
            report.append(f"OK   {it['name']}  =>  {epg_name}  [{best_c['id']}]  score={best_s:.3f}")
        else:
            # Leave original tvg-id intact rather than assigning a wrong programme guide.
            extinf = it["extinf"]
            cand = ""
            if best_c:
                epg_name = best_c["names"][0] if best_c["names"] else best_c["id"]
                cand = f" best={epg_name} [{best_c['id']}] score={best_s:.3f}"
            report.append(f"MISS {it['name']}  old={it['old_id']}{cand}")

        out.append(extinf)
        out.extend(it["extras"])
        out.append(it["url"])

    Path(OUT_M3U).write_text("\n".join(out) + "\n", encoding="utf-8")
    Path(OUT_REPORT).write_text(
        f"M3U channels: {len(items)}\nEPG channels: {len(chans)}\nAuto-matched: {matched}\nUnmatched: {len(items)-matched}\n\n"
        + "\n".join(report) + "\n",
        encoding="utf-8"
    )
    print(f"Done: {OUT_M3U}")
    print(f"Report: {OUT_REPORT}")
    print(f"Matched {matched}/{len(items)} channels.")

if __name__ == "__main__":
    main()
