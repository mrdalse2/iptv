#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
v3 - IPTV-org Korea + Korea EPG -> TiviMate auto-match bundle

Outputs:
  kr-tivimate.m3u
  kr-tivimate-epg.xml
  kr-tivimate-epg-report.txt

Core idea:
- Keep IPTV-org M3U tvg-id values as the final canonical IDs.
- Find the matching EPG channel in globetvapp's korea1.xml.
- Rewrite BOTH:
    <channel id="...">
    <programme channel="...">
  to the IPTV-org tvg-id.
- This removes any need for TiviMate's manual "Assign EPG" feature.

Notes:
- A regional MBC/SBS stream may fall back to the nationwide MBC/SBS guide
  if a dedicated regional guide cannot be identified.
- Only confident mappings are rewritten.
"""

import copy
import re
import unicodedata
import urllib.request
import xml.etree.ElementTree as ET
from difflib import SequenceMatcher
from pathlib import Path

M3U_URL = "https://iptv-org.github.io/iptv/countries/kr.m3u"
EPG_URL = "https://raw.githubusercontent.com/globetvapp/epg/main/Korea/korea1.xml"

OUT_M3U = "kr-tivimate.m3u"
OUT_EPG = "kr-tivimate-epg.xml"
OUT_REPORT = "kr-tivimate-epg-report.txt"

ENABLE_NETWORK_FALLBACK = True

# IPTV-org tvg-id -> candidate source EPG IDs.
# First candidate that actually exists in the downloaded EPG is used.
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

NAME_ALIASES = {
    "arirangradio": ["Arirang Radio.kr"],
    "arirangtv": ["Arirang TV.kr", "ArirangTV.kr"],
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
    "mbcdrama": ["MBC Dramanet.kr"],
    "mbcnet": ["MBC NET.kr"],
    "mtn": ["MTN 머니투데이방송.kr"],
    "oun": ["OUN.kr"],
    "tbsseoul": ["TBS TV.kr"],
    "tvchosun": ["TVCHOSUN.kr"],
    "tvchosun2": ["TVCHOSUN2.kr"],
    "ytn": ["YTN.kr"],
}

DISTINCT_MARKERS = [
    "science", "사이언스", "plus", "플러스", "kids", "키즈",
    "english", "golf", "sports", "biz", "drama", "dramanet",
    "movies", "2", "3"
]

def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=90) as r:
        return r.read()

def strip_annotations(s):
    s = s or ""
    s = re.sub(r"\[[^\]]*\]", " ", s)
    s = re.sub(
        r"\((?:[^)]*?\b(?:2160|1440|1080|720|576|540|480|450|406|400|360|352)p\b[^)]*)\)",
        " ",
        s,
        flags=re.I,
    )
    return re.sub(r"\s+", " ", s).strip()

def norm(s):
    s = unicodedata.normalize("NFKC", strip_annotations(s)).lower()
    replacements = {
        "television": "tv",
        "broadcasting": "",
        "home shopping": "homeshop",
        "home & shopping": "homeshop",
        "shopping": "shop",
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
                "extinf": extinf,
                "extras": extras,
                "url": url,
                "name": name,
                "old_id": old_id,
            })
        i += 1
    return items

def parse_epg(xml_bytes):
    root = ET.fromstring(xml_bytes)
    channels = []
    for ch in root.findall("channel"):
        cid = (ch.get("id") or "").strip()
        names = [(x.text or "").strip() for x in ch.findall("display-name") if (x.text or "").strip()]
        if cid:
            channels.append({"id": cid, "names": names, "element": ch})
    return root, channels

def build_epg_indexes(chans):
    by_id = {c["id"]: c for c in chans}
    return by_id

def first_existing_id(candidates, by_id):
    for cid in candidates:
        if cid in by_id:
            return by_id[cid]
    wanted = [norm(x) for x in candidates]
    for cid, ch in by_id.items():
        if norm(cid) in wanted:
            return ch
    return None

def marker_conflict(a_text, b_text):
    a = unicodedata.normalize("NFKC", a_text or "").lower()
    b = unicodedata.normalize("NFKC", b_text or "").lower()
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
        penalty = 0.16 if marker_conflict(mname, candidate) else 0.0
        if a == b:
            s = 1.0
        elif len(a) >= 4 and len(b) >= 4 and (a in b or b in a):
            s = 0.94
        else:
            s = SequenceMatcher(None, a, b).ratio()
        ta, tb = token_set(mname), token_set(candidate)
        common = {t for t in ta & tb if t not in {"tv", "hd", "sd"}}
        if len(common) >= 2:
            s = min(1.0, s + 0.05)
        best = max(best, s - penalty)
    return max(0.0, best)

def explicit_match(item, by_id):
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

def find_regional_epg(item, chans):
    text = unicodedata.normalize("NFKC", item["name"]).lower()

    region_terms = [
        ["andong", "안동"],
        ["busan", "부산"],
        ["chuncheon", "춘천"],
        ["chungbuk", "충북"],
        ["daegu", "대구"],
        ["daejeon", "대전"],
        ["gangwon", "강원"],
        ["gwangju", "광주"],
        ["gyeongnam", "경남"],
        ["jeju", "제주"],
        ["jeonju", "전주"],
        ["mokpo", "목포"],
        ["yeosu", "여수"],
        ["cjb"],
        ["g1"],
        ["jibs"],
        ["jtv"],
        ["kbc"],
        ["knn"],
        ["tbc"],
        ["ubc"],
    ]

    network = "mbc" if "mbc" in text else ("sbs" if "sbs" in text else None)
    if not network:
        return None

    wanted = None
    for terms in region_terms:
        if any(t in text for t in terms):
            wanted = terms
            break
    if not wanted:
        return None

    candidates = []
    for c in chans:
        hay = " ".join([c["id"]] + c["names"]).lower()
        if network not in hay:
            continue
        if any(t in hay for t in wanted):
            candidates.append(c)

    if not candidates:
        return None
    if len(candidates) == 1:
        return candidates[0]
    return max(candidates, key=lambda c: fuzzy_score(item["name"], c))

def network_fallback(item, by_id):
    if not ENABLE_NETWORK_FALLBACK:
        return None
    name = unicodedata.normalize("NFKC", item["name"]).lower()
    old = item["old_id"]

    if "mbc" in name and old not in {"MBCDrama.kr@SD", "MBCNet.kr@SD"}:
        return first_existing_id(["MBC.kr", "MBC TV.kr"], by_id)

    if "sbs" in name and not any(x in name for x in ["biz", "golf", "sports", "fun", "plus"]):
        return first_existing_id(["SBS.kr", "SBS TV.kr"], by_id)

    return None

def choose_epg_channel(item, chans, by_id):
    ch, method = explicit_match(item, by_id)
    if ch:
        return ch, method, None

    ch = find_regional_epg(item, chans)
    if ch:
        return ch, "REGIONAL", None

    ranked = sorted(
        ((fuzzy_score(item["name"], c), c) for c in chans),
        key=lambda x: x[0],
        reverse=True,
    )
    best_s, best_c = ranked[0] if ranked else (0.0, None)
    second_s = ranked[1][0] if len(ranked) > 1 else 0.0

    if best_c and (
        best_s >= 0.965 or
        (best_s >= 0.90 and best_s - second_s >= 0.10)
    ):
        return best_c, "AUTO", best_s

    ch = network_fallback(item, by_id)
    if ch:
        return ch, "NETWORK-FALLBACK", None

    return None, "MISS", best_s if best_c else None

def rewrite_m3u(items):
    out = [f'#EXTM3U x-tvg-url="https://raw.githubusercontent.com/mrdalse2/iptv/main/{OUT_EPG}"']
    for item in items:
        out.append(item["extinf"])
        out.extend(item["extras"])
        out.append(item["url"])
    return "\n".join(out) + "\n"

def build_rewritten_epg(root, mapping):
    """
    mapping: source_epg_id -> list of IPTV-org ids that should receive that guide

    If multiple IPTV channels share one source guide (e.g. regional fallback),
    duplicate the channel/programme entries so each IPTV tvg-id gets its own
    XMLTV channel id.
    """
    new_root = ET.Element(root.tag, root.attrib)

    source_channels = {ch.get("id"): ch for ch in root.findall("channel")}
    source_programmes = {}
    for p in root.findall("programme"):
        source_programmes.setdefault(p.get("channel"), []).append(p)

    # Preserve non-channel/non-programme top-level elements if any.
    for child in root:
        if child.tag not in {"channel", "programme"}:
            new_root.append(copy.deepcopy(child))

    used_targets = set()

    for source_id, target_ids in mapping.items():
        src_ch = source_channels.get(source_id)
        if src_ch is None:
            continue

        for target_id in target_ids:
            if not target_id or target_id in used_targets:
                continue
            used_targets.add(target_id)

            ch_copy = copy.deepcopy(src_ch)
            ch_copy.set("id", target_id)
            new_root.append(ch_copy)

            for prog in source_programmes.get(source_id, []):
                p_copy = copy.deepcopy(prog)
                p_copy.set("channel", target_id)
                new_root.append(p_copy)

    return new_root

def indent_xml(elem, level=0):
    # Works on Python 3.9+, but keep a fallback for safety.
    try:
        ET.indent(elem, space="  ")
    except AttributeError:
        pass

def main():
    print("Downloading IPTV-org Korea M3U...")
    m3u_text = fetch(M3U_URL).decode("utf-8-sig", errors="replace")

    print("Downloading Korea EPG XML...")
    epg_bytes = fetch(EPG_URL)

    items = parse_m3u(m3u_text)
    root, chans = parse_epg(epg_bytes)
    by_id = build_epg_indexes(chans)

    # source EPG id -> IPTV-org target ids
    mapping = {}
    report = []

    counts = {
        "manual": 0,
        "regional": 0,
        "auto": 0,
        "fallback": 0,
        "miss": 0,
    }

    for item in items:
        if not item["old_id"]:
            counts["miss"] += 1
            report.append(f"MISS             {item['name']}  reason=no-tvg-id")
            continue

        chosen, method, score = choose_epg_channel(item, chans, by_id)

        if chosen:
            mapping.setdefault(chosen["id"], []).append(item["old_id"])

            if method in {"MANUAL-ID", "MANUAL-NAME"}:
                counts["manual"] += 1
            elif method == "REGIONAL":
                counts["regional"] += 1
            elif method == "AUTO":
                counts["auto"] += 1
            elif method == "NETWORK-FALLBACK":
                counts["fallback"] += 1

            score_txt = f" score={score:.3f}" if score is not None else ""
            display = chosen["names"][0] if chosen["names"] else chosen["id"]
            report.append(
                f"{method:<16} {item['name']}  IPTV={item['old_id']} "
                f"<= EPG={display} [{chosen['id']}]{score_txt}"
            )
        else:
            counts["miss"] += 1
            report.append(
                f"{'MISS':<16} {item['name']}  IPTV={item['old_id']}"
                + (f" best-score={score:.3f}" if score is not None else "")
            )

    # M3U remains IPTV-org ids; only x-tvg-url points to our rewritten EPG.
    Path(OUT_M3U).write_text(rewrite_m3u(items), encoding="utf-8")

    new_root = build_rewritten_epg(root, mapping)
    indent_xml(new_root)

    tree = ET.ElementTree(new_root)
    tree.write(OUT_EPG, encoding="utf-8", xml_declaration=True)

    matched = len(items) - counts["miss"]

    Path(OUT_REPORT).write_text(
        "\n".join([
            f"M3U channels: {len(items)}",
            f"EPG source channels: {len(chans)}",
            f"Matched total: {matched}",
            f"Manual aliases: {counts['manual']}",
            f"Dedicated regional: {counts['regional']}",
            f"Automatic strong matches: {counts['auto']}",
            f"Network fallback: {counts['fallback']}",
            f"Unmatched: {counts['miss']}",
            f"Network fallback enabled: {ENABLE_NETWORK_FALLBACK}",
            "",
            "IMPORTANT:",
            "The generated XMLTV uses IPTV-org tvg-id values as channel IDs.",
            "TiviMate should therefore auto-match without Assign EPG.",
            "",
            *report,
            "",
        ]),
        encoding="utf-8",
    )

    print(f"Done: {OUT_M3U}")
    print(f"Done: {OUT_EPG}")
    print(f"Report: {OUT_REPORT}")
    print(
        f"Matched {matched}/{len(items)} "
        f"(manual={counts['manual']}, regional={counts['regional']}, "
        f"auto={counts['auto']}, fallback={counts['fallback']}, miss={counts['miss']})"
    )
    print()
    print("Upload these two files to GitHub:")
    print(f"  {OUT_M3U}")
    print(f"  {OUT_EPG}")
    print()
    print("Then use in TiviMate:")
    print("M3U:")
    print(f"  https://raw.githubusercontent.com/mrdalse2/iptv/main/{OUT_M3U}")
    print("EPG:")
    print(f"  https://raw.githubusercontent.com/mrdalse2/iptv/main/{OUT_EPG}")

if __name__ == "__main__":
    main()
