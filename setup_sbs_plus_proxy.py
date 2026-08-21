#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import shutil
import socket
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
STATE_NAME = "sbs-plus-route.txt"
PUBLISH_FILES = [STATE_NAME, "kr-tivimate.m3u", "official-channel-report.txt"]
REPO_URL = "https://github.com/mrdalse2/iptv.git"


def detect_lan_ip():
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.connect(("8.8.8.8", 80))
        return sock.getsockname()[0]
    finally:
        sock.close()


def run(cmd, *, cwd=None, check=True, capture=False):
    print(">", " ".join(str(x) for x in cmd))
    return subprocess.run(
        [str(x) for x in cmd],
        cwd=str(cwd) if cwd else None,
        check=check,
        text=True,
        capture_output=capture,
    )


def write_route(workdir, proxy_url):
    workdir = Path(workdir)
    (workdir / STATE_NAME).write_text(
        f"mode=local-proxy\nurl={proxy_url}\n", encoding="utf-8"
    )
    run([sys.executable, "apply_sbs_plus_route.py"], cwd=workdir)


def git_root(path):
    probe = run(
        ["git", "-C", path, "rev-parse", "--show-toplevel"],
        check=False,
        capture=True,
    )
    if probe.returncode == 0:
        return Path(probe.stdout.strip())
    return None


def try_enable_git_credentials():
    if not shutil.which("gh"):
        return
    auth = run(["gh", "auth", "status"], check=False, capture=True)
    if auth.returncode == 0:
        run(["gh", "auth", "setup-git"], check=False, capture=True)


def publish(repo_dir):
    repo_dir = Path(repo_dir)
    run(["git", "add", "--", *PUBLISH_FILES], cwd=repo_dir)
    staged = run(["git", "diff", "--cached", "--quiet"], cwd=repo_dir, check=False)
    if staged.returncode == 0:
        print("No Git changes to publish")
        return

    # Supply a local identity when the machine has no global git identity yet.
    run(["git", "config", "user.name", "mrdalse2-local"], cwd=repo_dir)
    run(["git", "config", "user.email", "mrdalse2-local@users.noreply.github.com"], cwd=repo_dir)
    run(["git", "commit", "-m", "chore: configure SBS Plus local resolver"], cwd=repo_dir)

    first_push = run(["git", "push", "origin", "HEAD:main"], cwd=repo_dir, check=False, capture=True)
    if first_push.returncode == 0:
        print("GitHub push complete")
        return

    try_enable_git_credentials()
    sync = run(
        ["git", "pull", "--rebase", "--autostash", "origin", "main"],
        cwd=repo_dir,
        check=False,
        capture=True,
    )
    if sync.returncode != 0:
        raise RuntimeError(
            "git pull --rebase failed: " + (sync.stderr or sync.stdout or "unknown error").strip()
        )

    second_push = run(["git", "push", "origin", "HEAD:main"], cwd=repo_dir, check=False, capture=True)
    if second_push.returncode != 0:
        raise RuntimeError(
            "git push retry failed: " + (second_push.stderr or second_push.stdout or "unknown error").strip()
        )
    print("GitHub push complete after credential/rebase retry")


def publish_from_any_folder(proxy_url):
    root = git_root(ROOT)
    if root:
        print("Git checkout detected:", root)
        write_route(root, proxy_url)
        publish(root)
        return

    print("No .git directory detected. Cloning a temporary publishing copy...")
    with tempfile.TemporaryDirectory(prefix="sbsplus-push-") as td:
        clone = Path(td) / "iptv"
        try_enable_git_credentials()
        result = run(["git", "clone", "--depth", "1", REPO_URL, clone], check=False, capture=True)
        if result.returncode != 0:
            raise RuntimeError(
                "temporary git clone failed: " + (result.stderr or result.stdout or "unknown error").strip()
            )
        write_route(clone, proxy_url)
        publish(clone)


def main():
    ip = detect_lan_ip()
    proxy_url = f"http://{ip}:8787/sbsplus.m3u8"

    # Always configure the folder the user actually launched from.
    write_route(ROOT, proxy_url)
    print("SBS Plus playlist route:", proxy_url)

    try:
        publish_from_any_folder(proxy_url)
    except Exception as exc:
        print("GitHub push failed:", exc)
        print("Local proxy route is still configured locally.")
        raise SystemExit(2)


if __name__ == "__main__":
    main()
