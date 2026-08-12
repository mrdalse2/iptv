#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import argparse, re, unicodedata, urllib.request
import xml.etree.ElementTree as ET
from difflib import SequenceMatcher
from pathlib import Path

M3U_URL = "https://iptv-org.github.io/iptv/countries/kr.m3u"
KR1_URL = "https://raw.githubusercontent.com/iptv-org/epg/master/sites/epgshare01.online/epgshare01.online_KR1.channels.xml"

ALIASES = {
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
    lines=txt.replace("\r\n","\n").splitlines()
    out=[]
    for line in lines:
        if not line.startswith("#EXTINF:"): continue
        m=re.search(r'tvg-id="([^"]+)"',line)
        if not m: continue
        name=line.split(",",1)[1] if "," in line else ""
        out.append((m.group(1),name.strip()))
    return out

def prepare():
    root=ET.fromstring(get(KR1_URL))
    kept=0
    for ch in root.findall("channel"):
        sid=ch.get("site_id","")
        xid=sid.split("#",1)[1] if "#" in sid else sid
        ch.set("xmltv_id", xid)
        kept += 1
    ET.indent(root, space="  ")
    ET.ElementTree(root).write("korea.channels.xml", encoding="utf-8", xml_declaration=True)
    print(f"Prepared {kept} KR1 channels")

def best_source(target_id, target_name, source_ids):
    for cand in ALIASES.get(target_id, []):
        if cand in source_ids:
            return cand, "alias"
        nc=norm(cand)
        for sid in source_ids:
            if norm(sid)==nc:
                return sid, "alias-normalized"
    low=target_name.lower()
    network = "mbc" if "mbc" in low else ("sbs" if "sbs" in low else None)
    if network:
        regions = ["andong","busan","chuncheon","chungbuk","daegu","daejeon",
                   "gangwon","gwangju","gyeongnam","jeju","jeonju","mokpo","yeosu",
                   "cjb","g1","jibs","jtv","kbc","knn","tbc","ubc"]
        tn=norm(target_name)
        dedicated=[sid for sid in source_ids if network in norm(sid) and any(r in tn and r in norm(sid) for r in regions)]
        if dedicated:
            return dedicated[0], "regional"
        national = "MBC.kr" if network=="mbc" else "SBS.kr"
        if national in source_ids:
            return national, "network-fallback"
    a=norm(target_name)
    ranked=[]
    for sid in source_ids:
        b=norm(sid)
        if not a or not b: continue
        score=1.0 if a==b else SequenceMatcher(None,a,b).ratio()
        if a in b or b in a:
            score=max(score,0.93)
        for marker in ["science","사이언스","golf","sports","biz","drama","kids","plus","2","3"]:
            if (marker in a)!=(marker in b):
                score-=0.15
        ranked.append((score,sid))
    ranked.sort(reverse=True)
    if ranked and ranked[0][0]>=0.88 and (len(ranked)==1 or ranked[0][0]-ranked[1][0]>=0.06):
        return ranked[0][1], f"fuzzy:{ranked[0][0]:.2f}"
    return None, "miss"

def finalize():
    src=ET.parse("fresh-source.xml").getroot()
    src_channels={c.get("id"):c for c in src.findall("channel")}
    src_programmes={}
    for p in src.findall("programme"):
        src_programmes.setdefault(p.get("channel"),[]).append(p)
    playlist=parse_m3u()
    new=ET.Element("tv", {
        "generator-info-name":"mrdalse2/iptv + iptv-org/epg KR1",
        "generator-info-url":"https://github.com/iptv-org/epg"
    })
    report=[]
    matched=0
    import copy
    for tid,name in playlist:
        sid,method=best_source(tid,name,set(src_channels))
        if not sid:
            report.append(f"MISS {tid} | {name}")
            continue
        matched+=1
        c=copy.deepcopy(src_channels[sid]); c.set("id",tid); new.append(c)
        for p in src_programmes.get(sid,[]):
            q=copy.deepcopy(p); q.set("channel",tid); new.append(q)
        report.append(f"OK {tid} <= {sid} [{method}] | {name}")
    ET.indent(new, space="  ")
    ET.ElementTree(new).write("kr-tivimate-epg.xml", encoding="utf-8", xml_declaration=True)
    Path("epg-update-report.txt").write_text(
        f"Matched {matched}/{len(playlist)}\n\n"+"\n".join(report)+"\n", encoding="utf-8")
    print(f"Final EPG matched {matched}/{len(playlist)} channels")

if __name__=="__main__":
    ap=argparse.ArgumentParser()
    ap.add_argument("mode", choices=["prepare","finalize"])
    args=ap.parse_args()
    prepare() if args.mode=="prepare" else finalize()
