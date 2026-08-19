#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import json
import re
import urllib.parse
import urllib.request
from pathlib import Path

PLAYLIST = Path("kr-tivimate.m3u")
REPORT = Path("official-channel-report.txt")
BASE_URL = "https://iptv-org.github.io/iptv/countries/kr.m3u"
SECONDARY_URL = "https://raw.githubusercontent.com/mjpark-dev/iptv/master/korean.m3u"
EPG_URL = "https://raw.githubusercontent.com/mrdalse2/iptv/main/kr-tivimate-epg.xml"

SBS_API = "https://apis.sbs.co.kr/play-api/1.0/onair/channel/S01"
SBS_ID = "SBS.kr@SD"
SBS_REFERER = "https://www.sbs.co.kr/live/S01"

SBS_PLUS_API = "https://apis.sbs.co.kr/play-api/1.0/onair/channel/S03"
SBS_PLUS_ID = "SBSPlus.kr@SD"
SBS_PLUS_REFERER = "https://www.sbs.co.kr/live/S03"

MBN_ID = "MBN.kr@SD"
MBN_REFERER = "https://www.mbn.co.kr/vod/onair"
MBN_AUTH = (
    "https://www.mbn.co.kr/player/mbnStreamAuth_new_live.mbn?vod_url="
    "https%3A%2F%2Fhls-live.mbn.co.kr%2Fmbn-on-air%2F1000k%2Fplaylist.m3u8"
)


def fetch(url, params=None, referer=None):
    if params:
        url += "?" + urllib.parse.urlencode(params)
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131 Safari/537.36",
        "Accept": "application/json,text/plain,*/*",
    }
    if "sbs.co.kr" in url:
        headers["Origin"] = "https://www.sbs.co.kr"
    if referer:
        headers["Referer"] = referer
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read(), r.geturl(), r.headers.get("Content-Type", "")


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


def resolve_sbs_channel(api_url, referer):
    params = {
        "v_type": "2",
        "platform": "pcweb",
        "protocol": "hls",
        "ssl": "N",
        "rscuse": "",
        "jwt-token": "",
        "sbsmain": "",
    }
    body, _, _ = fetch(api_url, params=params, referer=referer)
    data = json.loads(body.decode("utf-8", "replace"))
    candidates = media_candidates(data)
    if not candidates:
        return None
    candidates.sort(key=lambda x: x[0], reverse=True)
    return candidates[0][1]


def resolve_sbs():
    return resolve_sbs_channel(SBS_API, SBS_REFERER)


def resolve_sbs_plus():
    return resolve_sbs_channel(SBS_PLUS_API, SBS_PLUS_REFERER)


def resolve_mbn():
    body, final_url, content_type = fetch(MBN_AUTH, referer=MBN_REFERER)
    if final_url != MBN_AUTH and ".m3u8" in final_url:
        return final_url
    text = body.decode("utf-8", "replace").strip()
    m = re.search(r"https?://[^\s\"'<>]+\.m3u8(?:\?[^\s\"'<>]*)?", text)
    if m:
        return m.group(0).replace("&amp;", "&")
    if "mpegurl" in content_type.lower() or text.startswith("#EXTM3U"):
        return MBN_AUTH
    return None


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


def has_channel(lines, tvg_id):
    return any(line.startswith("#EXTINF:") and f'tvg-id="{tvg_id}"' in line for line in lines)


def upsert_official(lines, tvg_id, name, stream_url, referer, group="General;Official"):
    lines = strip_channel(lines, tvg_id)
    block = [
        f'#EXTINF:-1 tvg-id="{tvg_id}" group-title="{group}",{name}',
        f'#EXTVLCOPT:http-referrer={referer}',
        stream_url,
    ]
    return lines[:1] + block + lines[1:]


def channel_name(extinf):
    if "," not in extinf:
        return ""
    return extinf.rsplit(",", 1)[1].strip()


def normalize_name(name):
    return re.sub(r"[^0-9a-z가-힣]", "", name.lower())


def active_secondary_entries(text):
    src = text.replace("\r\n", "\n").splitlines()
    entries = []
    i = 0
    while i < len(src):
        if not src[i].startswith("#EXTINF:"):
            i += 1
            continue
        extinf = src[i].strip()
        name = channel_name(extinf)
        i += 1
        url = None
        while i < len(src) and not src[i].startswith("#EXTINF:"):
            candidate = src[i].strip()
            if candidate and not candidate.startswith("#") and candidate.startswith(("http://", "https://")):
                url = candidate
                break
            i += 1
        if name and url:
            entries.append((name, extinf, url))
    return entries


def merge_secondary(lines):
    body, _, _ = fetch(SECONDARY_URL)
    secondary = body.decode("utf-8-sig", "replace")

    # Drop the old generated SBS Plus block when the official API did not work;
    # the mjpark list is then allowed to provide its current fallback entry.
    lines = strip_channel(lines, SBS_PLUS_ID)

    existing = set()
    for line in lines:
        if line.startswith("#EXTINF:"):
            n = channel_name(line)
            if n:
                existing.add(normalize_name(n))

    added = []
    for name, extinf, url in active_secondary_entries(secondary):
        key = normalize_name(name)
        if not key or key in existing:
            continue

        if key == "sbsplus":
            extinf = '#EXTINF:-1 tvg-id="SBSPlus.kr@SD" group-title="Entertainment;Secondary",SBS Plus'
        else:
            extinf = re.sub(r'group-title="[^"]*"', 'group-title="Secondary"', extinf)

        lines.extend([extinf, url])
        existing.add(key)
        added.append(name)

    return lines, added


def main():
    if PLAYLIST.exists():
        text = PLAYLIST.read_text(encoding="utf-8-sig")
    else:
        body, _, _ = fetch(BASE_URL)
        text = body.decode("utf-8-sig", "replace")

    lines = text.replace("\r\n", "\n").splitlines()
    if not lines or not lines[0].startswith("#EXTM3U"):
        raise SystemExit("Invalid playlist")
    lines[0] = f'#EXTM3U x-tvg-url="{EPG_URL}"'
    status = []

    try:
        sbs_url = resolve_sbs()
    except Exception as e:
        status.append(f"SBS ERROR {type(e).__name__}: {e}")
        sbs_url = None
    if sbs_url:
        lines = upsert_official(lines, SBS_ID, "SBS", sbs_url, SBS_REFERER)
        status.append("SBS OK official API HLS refreshed")
    else:
        status.append(f"SBS KEEP existing={has_channel(lines, SBS_ID)}")

    try:
        sbs_plus_url = resolve_sbs_plus()
    except Exception as e:
        status.append(f"SBS Plus ERROR {type(e).__name__}: {e}")
        sbs_plus_url = None
    if sbs_plus_url:
        lines = upsert_official(
            lines,
            SBS_PLUS_ID,
            "SBS Plus",
            sbs_plus_url,
            SBS_PLUS_REFERER,
            group="Entertainment;Official",
        )
        status.append("SBS Plus OK official API HLS refreshed")
    else:
        try:
            lines, added = merge_secondary(lines)
            status.append(f"SECONDARY OK added={len(added)} source=mjpark-dev/iptv")
            status.append(f"SBS Plus secondary={has_channel(lines, SBS_PLUS_ID)}")
        except Exception as e:
            status.append(f"SECONDARY ERROR {type(e).__name__}: {e}")
            status.append(f"SBS Plus KEEP existing={has_channel(lines, SBS_PLUS_ID)}")

    try:
        mbn_url = resolve_mbn()
    except Exception as e:
        status.append(f"MBN ERROR {type(e).__name__}: {e}")
        mbn_url = None
    if mbn_url:
        lines = upsert_official(lines, MBN_ID, "MBN", mbn_url, MBN_REFERER)
        status.append("MBN OK official on-air HLS refreshed")
    else:
        status.append(f"MBN KEEP existing={has_channel(lines, MBN_ID)}")

    PLAYLIST.write_text("\n".join(lines).rstrip() + "\n", encoding="utf-8")
    REPORT.write_text("\n".join(status) + "\n", encoding="utf-8")
    print("\n".join(status))


if __name__ == "__main__":
    main()
