#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import socket
import subprocess
from pathlib import Path

STATE = Path("sbs-plus-route.txt")
PUBLISH_FILES = ["sbs-plus-route.txt", "kr-tivimate.m3u", "official-channel-report.txt"]


def detect_lan_ip():
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.connect(("8.8.8.8", 80))
        return sock.getsockname()[0]
    finally:
        sock.close()


def run(cmd, check=True, capture=False):
    print(">", " ".join(cmd))
    return subprocess.run(cmd, check=check, text=True, capture_output=capture)


def publish():
    # Stage only SBS Plus generated files. Do not sweep unrelated worktree changes.
    run(["git", "add", "--", *PUBLISH_FILES])
    staged = subprocess.run(["git", "diff", "--cached", "--quiet"])
    if staged.returncode == 0:
        print("No Git changes to publish")
        return

    run(["git", "commit", "-m", "chore: configure SBS Plus local resolver"])

    first_push = run(["git", "push"], check=False, capture=True)
    if first_push.returncode == 0:
        print("GitHub push complete")
        return

    print("Initial push failed; syncing remote and retrying once...")
    sync = run(["git", "pull", "--rebase", "--autostash"], check=False, capture=True)
    if sync.returncode != 0:
        raise RuntimeError(
            "git pull --rebase failed: " + (sync.stderr or sync.stdout or "unknown error").strip()
        )

    second_push = run(["git", "push"], check=False, capture=True)
    if second_push.returncode != 0:
        raise RuntimeError(
            "git push retry failed: " + (second_push.stderr or second_push.stdout or "unknown error").strip()
        )
    print("GitHub push complete after rebase")


def main():
    ip = detect_lan_ip()
    proxy_url = f"http://{ip}:8787/sbsplus.m3u8"
    STATE.write_text(f"mode=local-proxy\nurl={proxy_url}\n", encoding="utf-8")
    run(["python", "apply_sbs_plus_route.py"])
    print("SBS Plus playlist route:", proxy_url)

    try:
        publish()
    except Exception as exc:
        print("GitHub push failed:", exc)
        print("Local proxy route is still configured locally.")
        raise SystemExit(2)


if __name__ == "__main__":
    main()
