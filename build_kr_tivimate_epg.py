#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
IPTV-org Korea M3U -> TiviMate EPG-friendly M3U builder

Matching order:
1) Explicit IPTV-org tvg-id -> EPG id aliases (highest confidence)
2) Explicit name aliases
3) Safe canonical/name matching with token guards
4) Optional network fallback for regional MBC/SBS stations

The script NEVER assigns an alias unless that EPG channel id actually exists
in the downloaded XMLTV file.
"""

import re
import unicodedata
import urllib.request
import xml.etree.ElementTree as ET
from difflib import SequenceMatcher
from pathlib import Path

M3U_URL = "https://iptv-org.github.io/iptv/countries/kr.m3u"
EPG_URL = "https://raw.githubusercontent.com/globetvapp/epg/main/Korea/korea1.xml"

OUT_M3U = "kr-tivimate-epg.m3u"
OUT_REPORT = "kr-tivimate-epg-report.txt"

# True = regional MBC/SBS streams may use the nationwide MBC/SBS guide when
# a dedicated regional guide cannot be found. This is useful in TiviMate
# because national schedules overlap heavily, but local inserts may differ.
ENABLE_NETWORK_FALLBACK = True

# Candidate EPG ids. The first id that really exists in korea1.xml is used.
# This makes the rules resilient to small naming changes in the source.
ID_ALIASES = {
    "ChannelA.kr@SD": ["Channel A.kr", "ChannelA.kr"],
    "CJOnStyle.kr@SD": ["CJ ONSTYLE.kr", "CJ OnStyle.kr"],
    "CJOnStylePlus.kr@SD": ["CJ ONSTYLE PLUS.kr", "CJ OnStyle Plus.kr"],
    "EBS1TV.kr@SD": ["EBS.kr", "EBS1.kr", "EBS 1.kr"],
    "EBS2TV.kr@SD": ["EBS2.kr", "EBS 2.kr"],
    "EBSEnglish.kr@SD": ["EBS English.kr"],
    "EBSKids.kr@SD": ["EBS KIDS.kr", "EBS Kids.kr"],
    "EBSPlus1.kr@SD": ["EBS PLUS1.kr", "EBS Plus1.kr"],
    "EBSPlus2.kr@SD": ["EBS PLUS2.kr", "EBS Plus2.kr"],
    "BTNTV.kr@SD": ["BTN불교TV.kr", "BTN TV.kr"],
    "GugakTV.kr@SD": ["국악방송.kr", "GugakTV.kr"],
    "KTV.kr@SD": ["KTV.kr"],
    "KShopping.kr@SD": ["KT알파쇼핑.kr", "K쇼핑.kr", "K Shopping.kr"],
    "LotteOneTV.kr@SD": ["LOTTE OneTV.kr", "Lotte OneTV.kr"],
    "MBCDrama.kr@SD": ["MBC Dramanet.kr", "MBC Drama.kr"],
    "MBCNet.kr@SD": ["MBC NET.kr"],
    "MTN.kr@SD": ["MTN 머니투데이방송.kr", "MTN.kr"],
    "NHTV.kr@SD": ["NHTV.kr", "농협방송.kr"],
    "NSHomeShopping.kr@SD": ["NS Shop.kr", "NS홈쇼핑.kr"],
    "NSShopPlus.kr@SD": ["NS Shop+.kr", "NS Shop Plus.kr"],
    "OUN.kr@SD": ["OUN.kr"],
    "HLQSDTV.kr@SD": ["OBS.kr", "OBS경인TV.kr", "OBS TV.kr"],
    "TBSTV.kr@SD": ["TBS TV.kr", "TBS.kr"],
    "TVChosun.kr@SD": ["TVCHOSUN.kr", "TV CHOSUN.kr", "TV조선.kr"],
    "TVChosun2.kr@SD": ["TVCHOSUN2.kr", "TV CHOSUN2.kr", "TV조선2.kr"],
    "WShopping.kr@SD": ["W SHOPPING.kr", "W Shopping.kr"],
    "YTN.kr@SD": ["YTN.kr"],
}

# Name aliases are useful when IPTV-org changes its tvg-id while the visible
# channel name remains recognizable.
NAME_ALIASES = {
    "abntv": ["ABN.kr", "ABN TV.kr"],
    "arirangradio": ["Arirang Radio.kr"],
    "arirangtv": ["Arirang TV.kr", "ArirangTV.kr"],
    "bb buddhistbroadcasting": ["BBS불교방송.kr", "BBS TV.kr"],
    "btntv": ["BTN불교TV.kr"],
    "channel a": ["Channel A.kr", "ChannelA.kr"],
    "ebs1": ["EBS.kr", "EBS1.kr"],
    "ebs2": ["EBS2.kr"],
    "ebse": ["EBS English.kr"],
    "ebskids": ["EBS KIDS.kr"],
    "ebsplus1": ["EBS PLUS1.kr"],
    "ebsplus2": ["EBS PLUS2.kr"],
    "goodtv": ["Good TV.kr"],
    "gsmyshop": ["GS MY SHOP.kr"],
    "gsshop": ["GS SHOP.kr"],
    "gugaktv국악방송": ["국악방송.kr"],
    "koreatv": ["KTV.kr"],
    "mbc drama": ["MBC Dramanet.kr"],
    "mbc net": ["MBC NET.kr"],
    "mtn": ["MTN 머니투데이방송.kr"],
    "oun": ["OUN.kr"],
    "tbsseoul": ["TBS TV.kr"],
    "tvchosun": ["TVCHOSUN.kr"],
    "tvchosun2": ["TVCHOSUN2.kr"],
    "ytn": ["YTN.kr"],
}

# Prevent dangerous fuzzy matches between similarly named but different
# channels (e.g. YTN -> YTN Science, TV Chosun -> TV Chosun 2).
DISTINCT_MARKERS = [
    "science", "사이언스", "plus", "플러스", "kids", "키즈", "english",
    "golf", "sports", "biz", "drama", "dramanet", "movies", "2", "3"
]

def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=90) as r:
        return r.read()

def strip_annotations(s):
    s = s or ""
    s = re.sub(r"\[[^\]]*\]", " ", s)
    # IPTV-org resolution / availability suffixes
    s = re.sub(r"\((?:[^)]*?\b(?:2160|1440|1080|720|576|540|480|450|406|400|360|352)p\b[^)]*)\)", " ", s, flags=re.I)
    return re.sub(r"\s+", " ", s).strip()

def norm(s):
    s = unicodedata.normalize("NFKC", strip_annotations(s)).lower()
    replacements = {
        "television": "tv",
        "broadcasting": "",
        "home shopping": "homeshop",
        "home & shopping": "homeshop",
        "shopping": "shop",
        "onstyle": "onstyle",
        "korea": "",
        "대한민국": "",
        "서울": "seoul",
        "광주": "gwangju",
        "부산": "busan",
        "대구": "daegu",
        "대전": "daejeon",
        "제주": "jeju",
        "전주": "jeonju",
        "목포": "mokpo",
        "여수": "yeosu",
        "춘천": "chuncheon",
        "충북": "chungbuk",
        "경남": "gyeongnam",
        "강원영동": "gangwon",
    }
    for a, b in replacements.items():
        s = s.replace(a, b)
    return re.sub(r"[^0-9a-z가-힣]+", "", s)

def token_set(s):
    s = unicodedata.normalize("NFKC", strip_annotations(s)).lower()
    return set(re.findall(r"[0-9a-z]+|[가-힣]+", s))

def parse_m3u(text):
    lines = text.replace("\r\n", "\n").replace("\r", "\n").splitlines()
    items = []
    i = 0
    while i < len(lines):
        if lines[i].startswith("#EXTINF:"):
            extinf = lines[i]
            extras = []
            i += 1
            while i < len(lines) and lines[i].startswith("#") and not lines[i].startswith("#EXTINF:"):
                extras.append(lines[i])
                i += 1
            url = lines[i] if i < len(lines) else ""
            name = extinf.split(",", 1)[1].strip() if "," in extinf else ""
            m = re.search(r'tvg-id="([^"]*)"', extinf)
            old_id = m.group(1) if m else ""
            items.append({
                "extinf": extinf, "extras": extras, "url": url,
                "name": name, "old_id": old_id,
            })
        i += 1
    return items

def parse_epg(xml_bytes):
    root = ET.fromstring(xml_bytes)
    chans = []
    for ch in root.findall("channel"):
        cid = ch.get("id", "").strip()
        names = [(x.text or "").strip() for x in ch.findall("display-name") if (x.text or "").strip()]
        if cid:
            chans.append({"id": cid, "names": names})
    return chans

def build_epg_indexes(chans):
    by_id = {c["id"]: c for c in chans}
    by_norm = {}
    for c in chans:
        for value in [c["id"]] + c["names"]:
            n = norm(value)
            if n:
                by_norm.setdefault(n, []).append(c)
    return by_id, by_norm

def first_existing_id(candidates, by_id):
    for cid in candidates:
        if cid in by_id:
            return by_id[cid]
    # also allow normalized exact lookup if source changed spaces/case
    wanted = [norm(x) for x in candidates]
    for cid, ch in by_id.items():
        if norm(cid) in wanted:
            return ch
    return None

def exact_alias_match(item, by_id):
    candidates = ID_ALIASES.get(item["old_id"])
    if candidates:
        ch = first_existing_id(candidates, by_id)
        if ch:
            return ch, "MANUAL-ID"

    nname = norm(item["name"])
    for alias_name, candidates in NAME_ALIASES.items():
        if nname == norm(alias_name):
            ch = first_existing_id(candidates, by_id)
            if ch:
                return ch, "MANUAL-NAME"
    return None, None

def marker_conflict(a_text, b_text):
    a = unicodedata.normalize("NFKC", a_text or "").lower()
    b = unicodedata.normalize("NFKC", b_text or "").lower()

    # A marker present only on one side is a warning. Numeric "2" is checked
    # as a standalone-ish channel suffix to avoid TV Chosun -> TV Chosun 2.
    for m in DISTINCT_MARKERS:
        if m.isdigit():
            pa = bool(re.search(rf"(?<!\d){re.escape(m)}(?!\d)", a))
            pb = bool(re.search(rf"(?<!\d){re.escape(m)}(?!\d)", b))
        else:
            pa = m in a
            pb = m in b
        if pa != pb:
            return True
    return False

def fuzzy_score(mname, ch):
    a = norm(mname)
    if not a:
        return 0.0

    best = 0.0
    for candidate in [ch["id"]] + ch["names"]:
        b = norm(candidate)
        if not b:
            continue

        if marker_conflict(mname, candidate):
            penalty = 0.16
        else:
            penalty = 0.0

        if a == b:
            s = 1.0
        elif len(a) >= 4 and len(b) >= 4 and (a in b or b in a):
            s = 0.94
        else:
            s = SequenceMatcher(None, a, b).ratio()

        # Token overlap bonus helps regional/named stations without letting
        # one generic token (TV/MBC/SBS) dominate.
        ta, tb = token_set(mname), token_set(candidate)
        common = {t for t in ta & tb if t not in {"tv", "hd", "sd"}}
        if len(common) >= 2:
            s = min(1.0, s + 0.05)

        best = max(best, s - penalty)
    return max(0.0, best)

def find_regional_epg(item, chans):
    """Try dedicated regional MBC/SBS guides before nationwide fallback."""
    text = unicodedata.normalize("NFKC", item["name"]).lower()

    regions = {
        "andong": ["andong", "안동"],
        "busan": ["busan", "부산"],
        "chuncheon": ["chuncheon", "춘천"],
        "chungbuk": ["chungbuk", "충북"],
        "daegu": ["daegu", "대구"],
        "daejeon": ["daejeon", "대전"],
        "gangwon": ["gangwon", "강원"],
        "gwangju": ["gwangju", "광주"],
        "gyeongnam": ["gyeongnam", "경남"],
        "jeju": ["jeju", "제주"],
        "jeonju": ["jeonju", "전주"],
        "mokpo": ["mokpo", "목포"],
        "yeosu": ["yeosu", "여수"],
        "cjb": ["cjb"],
        "g1": ["g1"],
        "jibs": ["jibs"],
        "jtv": ["jtv"],
        "kbc": ["kbc"],
        "knn": ["knn"],
        "tbc": ["tbc"],
        "ubc": ["ubc"],
    }

    network = "mbc" if "mbc" in text else ("sbs" if "sbs" in text else None)
    if not network:
        return None

    wanted_region_terms = []
    for aliases in regions.values():
        if any(a in text for a in aliases):
            wanted_region_terms = aliases
            break

    if not wanted_region_terms:
        return None

    candidates = []
    for c in chans:
        hay = " ".join([c["id"]] + c["names"]).lower()
        if network not in hay:
            continue
        if any(term in hay for term in wanted_region_terms):
            candidates.append(c)

    if len(candidates) == 1:
        return candidates[0]
    if candidates:
        return max(candidates, key=lambda c: fuzzy_score(item["name"], c))
    return None

def network_fallback(item, by_id):
    if not ENABLE_NETWORK_FALLBACK:
        return None

    name = unicodedata.normalize("NFKC", item["name"]).lower()
    old = item["old_id"]

    # IPTV-org regional MBC station IDs are mostly call signs (HLA*/HLC*...)
    if "mbc" in name and old not in {"MBCDrama.kr@SD", "MBCNet.kr@SD"}:
        return first_existing_id(["MBC.kr", "MBC TV.kr"], by_id)

    # Regional SBS affiliates. Avoid SBS-branded specialty channels.
    if "sbs" in name and not any(x in name for x in ["biz", "golf", "sports", "fun", "plus"]):
        return first_existing_id(["SBS.kr", "SBS TV.kr"], by_id)

    return None

def replace_tvg_id(extinf, new_id):
    if re.search(r'tvg-id="[^"]*"', extinf):
        return re.sub(r'tvg-id="[^"]*"', f'tvg-id="{new_id}"', extinf, count=1)
    return re.sub(r'^(#EXTINF:[^ ]+)', r'\1 tvg-id="' + new_id + '"', extinf, count=1)

def epg_label(ch):
    return ch["names"][0] if ch["names"] else ch["id"]

def main():
    print("Downloading IPTV-org Korea M3U...")
    m3u = fetch(M3U_URL).decode("utf-8-sig", errors="replace")
    print("Downloading Korea EPG XML...")
    epg = fetch(EPG_URL)

    items = parse_m3u(m3u)
    chans = parse_epg(epg)
    by_id, _ = build_epg_indexes(chans)

    out = [f'#EXTM3U x-tvg-url="{EPG_URL}"']
    report = []

    counts = {
        "manual": 0,
        "regional": 0,
        "exact_fuzzy": 0,
        "network_fallback": 0,
        "miss": 0,
    }

    for item in items:
        chosen = None
        method = None
        chosen_score = None

        # 1. Explicit curated aliases.
        chosen, method = exact_alias_match(item, by_id)

        # 2. Dedicated regional guide if present.
        if not chosen:
            chosen = find_regional_epg(item, chans)
            if chosen:
                method = "REGIONAL"

        # 3. Conservative generic matching.
        if not chosen:
            ranked = sorted(
                ((fuzzy_score(item["name"], c), c) for c in chans),
                key=lambda x: x[0],
                reverse=True,
            )
            best_s, best_c = ranked[0] if ranked else (0.0, None)
            second_s = ranked[1][0] if len(ranked) > 1 else 0.0

            # Stricter than before for ambiguous names, but allows very strong
            # exact/canonical matches.
            if best_c and (
                best_s >= 0.965 or
                (best_s >= 0.90 and best_s - second_s >= 0.10)
            ):
                chosen = best_c
                method = "AUTO"
                chosen_score = best_s

        # 4. Regional network fallback, only when no dedicated/strong match.
        if not chosen:
            chosen = network_fallback(item, by_id)
            if chosen:
                method = "NETWORK-FALLBACK"

        if chosen:
            extinf = replace_tvg_id(item["extinf"], chosen["id"])

            if method in {"MANUAL-ID", "MANUAL-NAME"}:
                counts["manual"] += 1
            elif method == "REGIONAL":
                counts["regional"] += 1
            elif method == "AUTO":
                counts["exact_fuzzy"] += 1
            elif method == "NETWORK-FALLBACK":
                counts["network_fallback"] += 1

            score_txt = f" score={chosen_score:.3f}" if chosen_score is not None else ""
            report.append(
                f"{method:<16} {item['name']}  =>  {epg_label(chosen)} "
                f"[{chosen['id']}]{score_txt}"
            )
        else:
            extinf = item["extinf"]
            counts["miss"] += 1

            ranked = sorted(
                ((fuzzy_score(item["name"], c), c) for c in chans),
                key=lambda x: x[0],
                reverse=True,
            )
            hint = ""
            if ranked:
                s, c = ranked[0]
                hint = f" best={epg_label(c)} [{c['id']}] score={s:.3f}"
            report.append(
                f"{'MISS':<16} {item['name']} old={item['old_id']}{hint}"
            )

        out.append(extinf)
        out.extend(item["extras"])
        out.append(item["url"])

    matched = len(items) - counts["miss"]

    Path(OUT_M3U).write_text("\n".join(out) + "\n", encoding="utf-8")
    Path(OUT_REPORT).write_text(
        "\n".join([
            f"M3U channels: {len(items)}",
            f"EPG channels: {len(chans)}",
            f"Matched total: {matched}",
            f"Manual aliases: {counts['manual']}",
            f"Dedicated regional: {counts['regional']}",
            f"Automatic strong matches: {counts['exact_fuzzy']}",
            f"Network fallback: {counts['network_fallback']}",
            f"Unmatched: {counts['miss']}",
            f"Network fallback enabled: {ENABLE_NETWORK_FALLBACK}",
            "",
            *report,
            "",
        ]),
        encoding="utf-8",
    )

    print(f"Done: {OUT_M3U}")
    print(f"Report: {OUT_REPORT}")
    print(
        f"Matched {matched}/{len(items)} "
        f"(manual={counts['manual']}, regional={counts['regional']}, "
        f"auto={counts['exact_fuzzy']}, fallback={counts['network_fallback']}, "
        f"miss={counts['miss']})"
    )

if __name__ == "__main__":
    main()
