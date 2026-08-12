#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import argparse, copy, re, unicodedata, urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime, timezone, timedelta
from difflib import SequenceMatcher
from pathlib import Path

M3U_URL = "https://iptv-org.github.io/iptv/countries/kr.m3u"
SOURCE_CHANNEL_URLS = [
    ("tving", "https://raw.githubusercontent.com/iptv-org/epg/master/sites/m.tving.com/m.tving.com.channels.xml"),
    ("wavve", "https://raw.githubusercontent.com/iptv-org/epg/master/sites/wavve.com/wavve.com.channels.xml"),
    ("arirang", "https://raw.githubusercontent.com/iptv-org/epg/master/sites/arirang.com/arirang.com.channels.xml"),
    ("kr1", "https://raw.githubusercontent.com/iptv-org/epg/master/sites/epgshare01.online/epgshare01.online_KR1.channels.xml"),
]
SOURCE_PRIORITY = {"tving": 0, "wavve": 1, "arirang": 2, "kr1": 3}
SEP = "__SRC__"

ALIASES = {
    "BBSTV.kr@SD": ["BBS.불교방송.kr", "BBS불교방송.kr"],
    "ChannelA.kr@SD": ["Channel A.kr", "ChannelA.kr"],
    "CJOnStyle.kr@SD": ["CJ 온스타일.kr", "CJOnStyle.kr"],
    "CJOnStylePlus.kr@SD": ["CJ 온스타일플러스.kr", "CJOnStylePlus.kr"],
    "EBS1TV.kr@SD": ["EBS.kr", "EBS1.kr"],
    "EBS2TV.kr@SD": ["EBS2.kr"],
    "EBSEnglish.kr@SD": ["EBS English.kr"],
    "EBSKids.kr@SD": ["EBS KIDS.kr", "EBSKids.kr"],
    "EBSPlus1.kr@SD": ["EBS플러스1.kr", "EBS PLUS1.kr"],
    "EBSPlus2.kr@SD": ["EBS플러스2.kr", "EBS PLUS2.kr"],
    "BTNTV.kr@SD": ["BTN불교TV.kr"],
    "GoodTV.kr@SD": ["GOODTV.kr", "GoodTV.kr"],
    "GSMyShop.kr@SD": ["GS MY SHOP.kr"],
    "GSShop.kr@SD": ["GS SHOP.kr"],
    "GugakTV.kr@SD": ["국악방송.kr"],
    "KTV.kr@SD": ["KTV.kr"],
    "KShopping.kr@SD": ["KT알파쇼핑.kr", "K쇼핑.kr"],
    "LotteOneTV.kr@SD": ["LOTTE OneTV.kr"],
    "MBCDrama.kr@SD": ["MBC Dramanet.kr", "MBC Drama.kr"],
    "MBCNet.kr@SD": ["MBC NET.kr"],
    "MTN.kr@SD": ["MTN.kr", "MTN 머니투데이방송.kr"],
    "NSHomeShopping.kr@SD": ["NS홈쇼핑.kr", "NS Shop.kr"],
    "NSShopPlus.kr@SD": ["NS Shop+.kr"],
    "OUN.kr@SD": ["OUN.kr"],
    "HLQSDTV.kr@SD": ["OBS.kr", "OBS경인TV.kr"],
    "TBSTV.kr@SD": ["TBS TV.kr", "TBS.kr"],
    "TVChosun.kr@SD": ["TVCHOSUN.kr", "TV조선.kr"],
    "TVChosun2.kr@SD": ["TVCHOSUN2.kr", "TV조선2.kr"],
    "WShopping.kr@SD": ["W SHOPPING.kr", "W쇼핑.kr"],
    "YTN.kr@SD": ["YTN.kr"],
}
SPECIALTY_MBC_IDS = {"MBCDrama.kr@SD", "MBCNet.kr@SD"}

def get(url):
    req = urllib.request.Request(url, headers={"User-Agent":"Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=90) as r:
        return r.read()

def norm(s):
    s = unicodedata.normalize("NFKC", s or "").lower()
    s = re.sub(r"\[[^\]]*\]", " ", s)
    s = re.sub(r"\([^)]*\)", " ", s)
    for a,b in {
        "television":"tv","broadcasting":"","home shopping":"homeshop",
        "shopping":"shop","korea":"","대한민국":"",
        "광주":"gwangju","부산":"busan","대구":"daegu","대전":"daejeon",
        "제주":"jeju","전주":"jeonju","목포":"mokpo","여수":"yeosu",
        "춘천":"chuncheon","충북":"chungbuk","경남":"gyeongnam","강원영동":"gangwon"
    }.items():
        s=s.replace(a,b)
    return re.sub(r"[^0-9a-z가-힣]+","",s)

def parse_m3u():
    txt=get(M3U_URL).decode("utf-8-sig","replace")
    out=[]
    for line in txt.replace("\r\n","\n").splitlines():
        if not line.startswith("#EXTINF:"):
            continue
        m=re.search(r'tvg-id="([^"]+)"',line)
        if not m:
            continue
        name=line.split(",",1)[1] if "," in line else ""
        out.append((m.group(1),name.strip()))
    return out

def prepare():
    merged = ET.Element("channels")
    per_source = {}
    total = 0
    for source_name, url in SOURCE_CHANNEL_URLS:
        root = ET.fromstring(get(url))
        added = 0
        for ch in root.findall("channel"):
            c = copy.deepcopy(ch)
            xid = (c.get("xmltv_id") or "").strip()
            if source_name == "kr1":
                sid = (c.get("site_id") or "").strip()
                xid = sid.split("#",1)[1] if "#" in sid else sid
            if not xid:
                continue
            c.set("xmltv_id", f"{source_name}{SEP}{xid}")
            merged.append(c)
            added += 1
            total += 1
        per_source[source_name] = added
    ET.indent(merged, space="  ")
    ET.ElementTree(merged).write("korea.channels.xml", encoding="utf-8", xml_declaration=True)
    print(f"Prepared {total} source entries: {per_source}")

def split_source_id(raw_id):
    if SEP in raw_id:
        source, base = raw_id.split(SEP,1)
        return source, base
    return "unknown", raw_id

def parse_xmltv_time(value):
    if not value:
        return None
    m = re.match(r"^(\d{14})\s*([+-]\d{4})?", value)
    if not m:
        return None
    dt = datetime.strptime(m.group(1), "%Y%m%d%H%M%S")
    off = m.group(2)
    if off:
        sign = 1 if off[0] == "+" else -1
        delta = timedelta(hours=int(off[1:3]), minutes=int(off[3:5])) * sign
        tz = timezone(delta)
    else:
        tz = timezone.utc
    return dt.replace(tzinfo=tz).astimezone(timezone.utc)

def useful_programmes(programmes, now_utc):
    cutoff = now_utc - timedelta(hours=6)
    useful=[]
    for p in programmes:
        stop = parse_xmltv_time(p.get("stop"))
        start = parse_xmltv_time(p.get("start"))
        if (stop and stop >= cutoff) or (not stop and start and start >= cutoff):
            useful.append(p)
    return useful

def match_score(target_id, target_name, base_id):
    if base_id == target_id:
        return 1000, "exact-id"
    if norm(base_id) == norm(target_id):
        return 980, "exact-id-normalized"
    for cand in ALIASES.get(target_id, []):
        if base_id == cand:
            return 950, "alias"
        if norm(base_id) == norm(cand):
            return 930, "alias-normalized"

    low=target_name.lower()
    network = "mbc" if "mbc" in low else ("sbs" if "sbs" in low else None)
    if target_id in SPECIALTY_MBC_IDS:
        network = None
    if network:
        national = "MBC.kr" if network=="mbc" else "SBS.kr"
        if base_id == national:
            return 700, "network-fallback"

    a=norm(target_name); b=norm(base_id)
    if not a or not b:
        return -1, "miss"
    score = 1.0 if a==b else SequenceMatcher(None,a,b).ratio()
    if a in b or b in a:
        score=max(score,0.93)
    for marker in ["science","사이언스","golf","sports","biz","drama","kids","plus","2","3"]:
        if (marker in a)!=(marker in b):
            score-=0.15
    if score >= 0.88:
        return int(score*500), f"fuzzy:{score:.2f}"
    return -1, "miss"

def choose_source(target_id, target_name, candidates):
    ranked=[]
    for raw_id, meta in candidates.items():
        score, method = match_score(target_id, target_name, meta["base_id"])
        if score < 0:
            continue
        useful_count = len(meta["useful"])
        if useful_count == 0:
            continue
        ranked.append((score, -SOURCE_PRIORITY.get(meta["source"], 99), useful_count, raw_id, method))
    if not ranked:
        return None
    ranked.sort(reverse=True)
    return ranked[0]

def finalize():
    src=ET.parse("fresh-source.xml").getroot()
    channels_by_raw = {c.get("id"): c for c in src.findall("channel") if c.get("id")}
    progs_by_raw = {}
    for p in src.findall("programme"):
        progs_by_raw.setdefault(p.get("channel"),[]).append(p)

    now_utc = datetime.now(timezone.utc)
    candidates={}
    for raw_id, ch in channels_by_raw.items():
        source, base = split_source_id(raw_id)
        all_progs = progs_by_raw.get(raw_id, [])
        candidates[raw_id] = {
            "source": source,
            "base_id": base,
            "channel": ch,
            "all": all_progs,
            "useful": useful_programmes(all_progs, now_utc),
        }

    playlist=parse_m3u()
    new=ET.Element("tv", {
        "generator-info-name":"mrdalse2/iptv + iptv-org/epg live-source fallback",
        "generator-info-url":"https://github.com/iptv-org/epg"
    })
    report=[]
    effective=0
    id_matched_zero=0
    miss=0
    out_channels=[]
    out_programmes=[]

    for tid,name in playlist:
        chosen = choose_source(tid, name, candidates)
        if not chosen:
            any_id_match=False
            for raw_id, meta in candidates.items():
                score,_ = match_score(tid,name,meta["base_id"])
                if score >= 0:
                    any_id_match=True
                    break
            if any_id_match:
                id_matched_zero += 1
                report.append(f"ZERO {tid} | {name}")
            else:
                miss += 1
                report.append(f"MISS {tid} | {name}")
            continue

        score, negprio, useful_count, raw_id, method = chosen
        meta=candidates[raw_id]
        effective += 1
        ch=copy.deepcopy(meta["channel"])
        ch.set("id", tid)
        out_channels.append(ch)
        for p in meta["useful"]:
            q=copy.deepcopy(p)
            q.set("channel", tid)
            out_programmes.append(q)
        report.append(
            f"OK {tid} <= {meta['source']}:{meta['base_id']} [{method}] "
            f"programmes={useful_count} | {name}"
        )

    # XMLTV canonical order: all channel definitions first, then all programmes.
    for ch in out_channels:
        new.append(ch)
    for p in out_programmes:
        new.append(p)

    ET.indent(new, space="  ")
    ET.ElementTree(new).write("kr-tivimate-epg.xml", encoding="utf-8", xml_declaration=True)
    total=len(playlist)
    empty=total-effective
    header=(
        f"Effective EPG channels: {effective}/{total}\n"
        f"ID matched but zero current/future programmes: {id_matched_zero}\n"
        f"No matching EPG source: {miss}\n"
        f"Still empty: {empty}\n\n"
    )
    Path("epg-update-report.txt").write_text(header+"\n".join(report)+"\n",encoding="utf-8")
    print(header.strip())

if __name__=="__main__":
    ap=argparse.ArgumentParser()
    ap.add_argument("mode", choices=["prepare","finalize"])
    args=ap.parse_args()
    prepare() if args.mode=="prepare" else finalize()
