#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import json
import re
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

PLAYLIST = Path("kr-tivimate.m3u")
REPORT = Path("official-channel-report.txt")
BASE_URL = "https://iptv-org.github.io/iptv/countries/kr.m3u"
SECONDARY_SOURCES = [
    ("mjpark-dev/iptv", "https://raw.githubusercontent.com/mjpark-dev/iptv/master/korean.m3u"),
    ("GoonhoLee/koreaiptv-auto-updater", "https://raw.githubusercontent.com/GoonhoLee/koreaiptv-auto-updater/main/korean_tv.m3u"),
]
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

HEALTH_CACHE = {}


def request_headers(url, referer=None):
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131 Safari/537.36",
        "Accept": "application/vnd.apple.mpegurl,application/x-mpegURL,application/json,text/plain,*/*",
    }
    if "sbs.co.kr" in url:
        headers["Origin"] = "https://www.sbs.co.kr"
    if referer:
        headers["Referer"] = referer
    return headers


def fetch(url, params=None, referer=None, timeout=30):
    if params:
        url += "?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, headers=request_headers(url, referer))
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read(), r.geturl(), r.headers.get("Content-Type", "")


def is_stream_alive(url, referer=None):
    cache_key = (url, referer or "")
    if cache_key in HEALTH_CACHE:
        return HEALTH_CACHE[cache_key]
    if not url.startswith(("http://", "https://")):
        HEALTH_CACHE[cache_key] = False
        return False

    headers = request_headers(url, referer)
    headers["Range"] = "bytes=0-4095"
    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=6) as r:
            status = getattr(r, "status", 200)
            content_type = (r.headers.get("Content-Type", "") or "").lower()
            sample = r.read(4096)
            text = sample.decode("utf-8", "ignore")
            alive = status < 400
            if ".m3u8" in url.lower() or "mpegurl" in content_type:
                alive = alive and ("#EXTM3U" in text or "mpegurl" in content_type)
    except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, OSError, ValueError):
        alive = False

    HEALTH_CACHE[cache_key] = alive
    return alive


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


def channel_name(extinf):
    if "," not in extinf:
        return ""
    return extinf.rsplit(",", 1)[1].strip()


def tvg_id(extinf):
    m = re.search(r'tvg-id="([^"]+)"', extinf)
    return m.group(1).strip() if m else ""


def normalize_name(name):
    name = re.sub(r"[（(].*?[)）]", "", name)
    return re.sub(r"[^0-9a-z가-힣]", "", name.lower())


def normalize_tvg_id(value):
    value = value.lower().strip()
    value = re.sub(r"@(sd|hd|fhd)$", "", value)
    return value


def canonical_key(extinf):
    tid = normalize_tvg_id(tvg_id(extinf))
    if tid:
        return "id:" + tid
    name = normalize_name(channel_name(extinf))
    return "name:" + name if name else ""


def parse_entries(text, source):
    src = text.replace("\r\n", "\n").splitlines()
    entries = []
    i = 0
    while i < len(src):
        if not src[i].startswith("#EXTINF:"):
            i += 1
            continue
        extinf = src[i].strip()
        opts = []
        i += 1
        url = None
        while i < len(src) and not src[i].startswith("#EXTINF:"):
            candidate = src[i].strip()
            if candidate.startswith("#EXT"):
                opts.append(candidate)
            elif candidate and not candidate.startswith("#") and candidate.startswith(("http://", "https://")):
                url = candidate
                i += 1
                break
            i += 1
        if url:
            entries.append({
                "extinf": extinf,
                "opts": opts,
                "url": url,
                "source": source,
                "key": canonical_key(extinf),
                "name": channel_name(extinf),
            })
    return entries


def entry_referer(entry):
    for opt in entry.get("opts", []):
        if opt.startswith("#EXTVLCOPT:http-referrer="):
            return opt.split("=", 1)[1]
    return None


def pick_preferred(old, new):
    old_alive = is_stream_alive(old["url"], entry_referer(old))
    new_alive = is_stream_alive(new["url"], entry_referer(new))
    if new_alive and not old_alive:
        return new, "replaced-dead"
    if old_alive and not new_alive:
        return old, "kept-live"
    if new_alive and old_alive:
        # Both work: prefer the later/fresher source.
        return new, "replaced-live"
    # Neither could be verified now: keep the existing entry for stability.
    return old, "kept-unverified"


def merge_sources(lines):
    current = parse_entries("\n".join(lines), "current")
    ordered = []
    index = {}
    for entry in current:
        key = entry["key"] or "url:" + entry["url"]
        if key in index:
            continue
        index[key] = len(ordered)
        ordered.append(entry)

    stats = []
    for source_name, source_url in SECONDARY_SOURCES:
        body, _, _ = fetch(source_url)
        incoming = parse_entries(body.decode("utf-8-sig", "replace"), source_name)
        added = replaced = kept = live_added = unverified_added = 0
        for entry in incoming:
            key = entry["key"] or "url:" + entry["url"]
            if key not in index:
                # Include every active EXTINF+URL item from secondary playlists.
                # Health is advisory for new channels; it only decides preference
                # when duplicate routes exist for the same channel.
                alive = is_stream_alive(entry["url"], entry_referer(entry))
                index[key] = len(ordered)
                ordered.append(entry)
                added += 1
                if alive:
                    live_added += 1
                else:
                    unverified_added += 1
                continue

            pos = index[key]
            chosen, reason = pick_preferred(ordered[pos], entry)
            if chosen is entry:
                ordered[pos] = entry
                replaced += 1
            else:
                kept += 1
        stats.append((source_name, len(incoming), added, live_added, unverified_added, replaced, kept))

    out = [f'#EXTM3U x-tvg-url="{EPG_URL}"']
    for entry in ordered:
        extinf = entry["extinf"]
        if entry["source"] != "current":
            if 'group-title="' in extinf:
                extinf = re.sub(r'group-title="[^"]*"', f'group-title="Secondary;{entry["source"]}"', extinf)
            else:
                extinf = extinf.replace(",", f' group-title="Secondary;{entry["source"]}",', 1)
        out.append(extinf)
        out.extend(entry["opts"])
        out.append(entry["url"])
    return out, stats


def strip_channel(lines, channel_tvg_id):
    target = normalize_tvg_id(channel_tvg_id)
    out = []
    entries = parse_entries("\n".join(lines), "strip")
    for entry in entries:
        if normalize_tvg_id(tvg_id(entry["extinf"])) == target:
            continue
        out.append(entry["extinf"])
        out.extend(entry["opts"])
        out.append(entry["url"])
    return [lines[0]] + out if lines else out


def has_channel(lines, channel_tvg_id):
    target = normalize_tvg_id(channel_tvg_id)
    for line in lines:
        if line.startswith("#EXTINF:") and normalize_tvg_id(tvg_id(line)) == target:
            return True
    return False


def upsert_official(lines, channel_tvg_id, name, stream_url, referer, group="General;Official"):
    lines = strip_channel(lines, channel_tvg_id)
    block = [
        f'#EXTINF:-1 tvg-id="{channel_tvg_id}" group-title="{group}",{name}',
        f'#EXTVLCOPT:http-referrer={referer}',
        stream_url,
    ]
    return lines[:1] + block + lines[1:]


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
        lines, merge_stats = merge_sources(lines)
        for source_name, total, added, live_added, unverified_added, replaced, kept in merge_stats:
            status.append(
                f"SOURCE OK {source_name} total={total} added={added} "
                f"live_added={live_added} unverified_added={unverified_added} "
                f"replaced={replaced} kept={kept}"
            )
    except Exception as e:
        status.append(f"SOURCE ERROR {type(e).__name__}: {e}")

    try:
        sbs_url = resolve_sbs()
    except Exception as e:
        status.append(f"SBS ERROR {type(e).__name__}: {e}")
        sbs_url = None
    if sbs_url and is_stream_alive(sbs_url, SBS_REFERER):
        lines = upsert_official(lines, SBS_ID, "SBS", sbs_url, SBS_REFERER)
        status.append("SBS OK official API HLS refreshed")
    else:
        status.append(f"SBS KEEP merged={has_channel(lines, SBS_ID)}")

    try:
        sbs_plus_url = resolve_sbs_plus()
    except Exception as e:
        status.append(f"SBS Plus ERROR {type(e).__name__}: {e}")
        sbs_plus_url = None
    if sbs_plus_url and is_stream_alive(sbs_plus_url, SBS_PLUS_REFERER):
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
        status.append(f"SBS Plus KEEP merged={has_channel(lines, SBS_PLUS_ID)}")

    try:
        mbn_url = resolve_mbn()
    except Exception as e:
        status.append(f"MBN ERROR {type(e).__name__}: {e}")
        mbn_url = None
    if mbn_url and is_stream_alive(mbn_url, MBN_REFERER):
        lines = upsert_official(lines, MBN_ID, "MBN", mbn_url, MBN_REFERER)
        status.append("MBN OK official on-air HLS refreshed")
    else:
        status.append(f"MBN KEEP merged={has_channel(lines, MBN_ID)}")

    PLAYLIST.write_text("\n".join(lines).rstrip() + "\n", encoding="utf-8")
    REPORT.write_text("\n".join(status) + "\n", encoding="utf-8")
    print("\n".join(status))


if __name__ == "__main__":
    main()
