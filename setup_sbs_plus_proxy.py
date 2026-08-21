#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import socket
import subprocess
from pathlib import Path

STATE = Path("sbs-plus-route.txt")


def detect_lan_ip():
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.connect(("8.8.8.8", 80))
        return sock.getsockname()[0]
    finally:
        sock.close()


def run(cmd):
    print(">", " ".join(cmd))
    return subprocess.run(cmd, check=True)


def main():
    ip = detect_lan_ip()
    proxy_url = f"http://{ip}:8787/sbsplus.m3u8"
    STATE.write_text(f"mode=local-proxy\nurl={proxy_url}\n", encoding="utf-8")
    run(["python", "apply_sbs_plus_route.py"])
    print("SBS Plus playlist route:", proxy_url)

    try:
        run(["git", "add", "sbs-plus-route.txt", "kr-tivimate.m3u", "official-channel-report.txt"])
        diff = subprocess.run(["git", "diff", "--cached", "--quiet"])
        if diff.returncode != 0:
            run(["git", "commit", "-m", "chore: configure SBS Plus local resolver"])
            run(["git", "push"])
            print("GitHub push complete")
        else:
            print("No Git changes to publish")
    except Exception as exc:
        print("GitHub push failed:", exc)
        print("The local files are updated. Run: git push")


if __name__ == "__main__":
    main()
