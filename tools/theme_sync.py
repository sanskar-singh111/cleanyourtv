#!/usr/bin/env python3
"""Push a theme like tools/theme.sh, but only what changed: one ADB connection, and only the files whose checksum differs
from the TV's copy are sent; only files that left the theme are deleted on the TV. Home restarts the same way.

Usage: TV_HOST=192.168.1.50 tools/venv/bin/python tools/theme_sync.py themes/aurora [--json] [--full]

    --json   one JSON line per step ({"event": "progress", "done": 3, "total": 12, "file": "logos/a.png",
             "sent_bytes": 81920, "total_bytes": 409600}), the last one the answer ({"ok": true, "sent": 3,
             "deleted": 0, "seconds": 4.2}), for tools such as TV Studio
    --full   send every file, as theme.sh does (still one connection)
    --plan   change nothing: the last JSON line says what a push would send and delete ({"ok": true, "plan": true,
             "send": ["logos/a.png"], "send_bytes": 81920, "delete": ["wallpaper/old.jpg"], "delete_bytes": 409600,
             "big": 1, "files": 52, "known": true}); big counts the files over STALL_ABOVE, which take the push's stall

theme.sh stays the reference: the same files are read (config.json, wallpaper.jpg/.png, wallpapers/ with captions.txt,
and picture folders named with lower-case letters, digits, - and _), and they are written through the launcher's
ThemeProvider with cp as the fallback, using theme.sh's own commands (tests/test_theme_sync.py checks they match).
When the shell can't list the launcher's folder (some firmware locks Android/data), every file is sent.
"""
import base64
import hashlib
import json
import os
import re
import sys
import time
import uuid

PKG = "com.gathin.tvhome"
DIR = f"/sdcard/Android/data/{PKG}/files"
URI = f"content://{PKG}.theme"
TMP = "/data/local/tmp/cyt-theme"
IMAGES = (".jpg", ".jpeg", ".png", ".webp", ".JPG", ".JPEG", ".PNG", ".WEBP")
FONTS = (".ttf", ".otf", ".TTF", ".OTF")
FOLDER_RE = re.compile(r"[a-z0-9_-]+")
FILE_RE = re.compile(r"[A-Za-z0-9_.-]+")
CHUNK = 48 * 1024

# The same restart as theme.sh (see the comments there): Home comes back through the Home intent when it is Home, and
# the accessibility services are switched back on after the force-stop.
RESTART = (f"a=$(settings get secure enabled_accessibility_services); am force-stop {PKG}; case \"$(cmd package "
           f"resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1)\" in "
           f"{PKG}/*) am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null ;; *) am start -n "
           f"{PKG}/.MainActivity >/dev/null ;; esac; case \"$a\" in *{PKG}*) sleep 1; settings put secure "
           f"enabled_accessibility_services \"$a\"; settings put secure accessibility_enabled 1; sleep 1; settings put "
           f"secure enabled_accessibility_services ''; settings put secure accessibility_enabled 0; sleep 1; settings put "
           f"secure enabled_accessibility_services \"$a\"; settings put secure accessibility_enabled 1 ;; esac")


def stage(name):
    """A file's staging path; wallpapers/a.jpg is staged as /data/local/tmp/cyt-theme-wallpapers-a.jpg."""
    return f"{TMP}-{name.replace('/', '-')}"


def put(name):
    """theme.sh's put(): the provider writes the file as the launcher; cp when the firmware has no `content write`."""
    s = stage(name)
    return (f"rm -f {DIR}/{name} 2>/dev/null; {{ o=$(content write --uri {URI}/{name} < {s} 2>&1) && case \"$o\" in "
            f"*'Unsupported operation'*) false ;; esac; }} || cp {s} {DIR}/{name} 2>/dev/null; rm -f {s}")


# On the TV and the emulator alike a sync push of more than about this many bytes stalls, so the file goes by base64.
STALL_ABOVE = 450_000

# Files removed per shell call.
DELETE_BATCH = 30


def delete(name):
    """theme.sh's del()."""
    return f"rm -f {DIR}/{name} 2>/dev/null; content delete --uri {URI}/{name} 2>/dev/null"


def theme_files(theme):
    """Relative name -> local path for every file theme.sh would send, in theme.sh's order."""
    out = {"config.json": os.path.join(theme, "config.json")}
    for name in ("wallpaper.jpg", "wallpaper.png"):
        if os.path.isfile(os.path.join(theme, name)):
            out[name] = os.path.join(theme, name)
    folders = sorted(d for d in os.listdir(theme) if os.path.isdir(os.path.join(theme, d)))
    for folder in ["wallpapers"] + [d for d in folders if d != "wallpapers"]:
        path = os.path.join(theme, folder)
        if not os.path.isdir(path) or not FOLDER_RE.fullmatch(folder):
            continue
        for f in sorted(os.listdir(path)):
            full = os.path.join(path, f)
            if os.path.isfile(full) and carried(folder, f) and FILE_RE.fullmatch(f):
                out[f"{folder}/{f}"] = full
    captions = os.path.join(theme, "wallpapers", "captions.txt")
    if os.path.isfile(captions):
        out["wallpapers/captions.txt"] = captions
    return out


def carried(folder, name):
    """A picture in any of the theme's folders, or a font in fonts/."""
    return name.endswith(IMAGES) or folder == "fonts" and name.endswith(FONTS)


def managed(name):
    """A file on the TV that a theme push owns, so it may be deleted when the theme no longer has it."""
    if name in ("wallpaper.jpg", "wallpaper.png", "wallpapers/captions.txt"):
        return True
    folder, _, f = name.partition("/")
    return bool(f) and "/" not in f and FOLDER_RE.fullmatch(folder) is not None and carried(folder, f)


def md5(path):
    with open(path, "rb") as f:
        return hashlib.md5(f.read()).hexdigest()


def remote_sums(dev):
    """Relative name -> md5 of the TV's copy, or None when the shell can't read the launcher's folder."""
    out = dev.shell(f"cd {DIR} 2>/dev/null && echo LISTED && find . -type f 2>/dev/null | while read f; do md5sum \"$f\"; done",
                    read_timeout_s=120.0)
    if "LISTED" not in out:
        return None
    sums = {}
    for line in out.splitlines():
        parts = line.split(None, 1)
        if len(parts) == 2 and re.fullmatch(r"[0-9a-f]{32}", parts[0]) and parts[1].startswith("./"):
            sums[parts[1][2:].strip()] = parts[0]
    return sums


def send(dev, local, remote, reconnect):
    """The file to `remote` with ADB's file sync, checked by md5; base64 chunks through the shell if that fails.
    Returns the connection to go on with: a new one from `reconnect()` when the sync push broke the old one."""
    want = md5(local)
    # A stalled push is given up on: some adbd builds (the Android TV emulator's, for one) never answer a sync push of
    # more than about 1 MB. About 1 MB/s is the slowest TV seen, so this is plenty for a push that is moving.
    stall = max(20.0, os.path.getsize(local) / 1e6 * 4.0)
    try:
        dev.push(local, remote, transport_timeout_s=stall, read_timeout_s=stall)
        if dev.shell(f"md5sum {remote}", read_timeout_s=60.0).split()[:1] == [want]:
            return dev
    except Exception:   # some TVs' adbd refuse sync pushes: fall back to the shell, as theme.sh does for pictures
        # The failed push leaves the connection mid-stream: start a new one for the shell.
        try:
            dev.close()
        except Exception:
            pass
        dev = reconnect()
    with open(local, "rb") as f:
        data = f.read()
    chunk = min(CHUNK, getattr(dev, "max_chunk_size", 4096) * 3 // 4 - 64)
    chunk -= chunk % 3
    b64 = remote + ".b64.part"
    dev.shell(f"rm -f {b64} {remote} && : > {b64}")
    for i in range(0, len(data), chunk):
        piece = base64.b64encode(data[i:i + chunk]).decode()
        if f"ok{i}" not in dev.shell(f"echo '{piece}' >> {b64} && echo ok{i}", read_timeout_s=60.0):
            raise RuntimeError(f"sending {os.path.basename(local)} failed")
    got = dev.shell(f"base64 -d < {b64} > {remote} && md5sum {remote}; rm -f {b64}", read_timeout_s=120.0).split()[:1]
    if got != [want]:
        dev.shell(f"rm -f {remote}")
        raise RuntimeError(f"{os.path.basename(local)} arrived damaged")
    return dev


def plan(local, remote, full=False):
    """What to send and what to delete: everything when the TV's copy is unknown (or --full)."""
    if remote is None or full:
        stale = [] if remote is None else sorted(n for n in remote if managed(n) and n not in local)
        return list(local), stale
    sums = {n: md5(p) for n, p in local.items()}
    changed = [n for n in local if remote.get(n) != sums[n]]
    stale = sorted(n for n in remote if managed(n) and n not in local)
    return changed, stale


def remote_size(dev, names):
    """Total bytes of these files on the TV (0 for any it can't say)."""
    total = 0
    for i in range(0, len(names), DELETE_BATCH):
        batch = " ".join(f"'./{n}'" for n in names[i:i + DELETE_BATCH])
        out = dev.shell(f"cd {DIR} 2>/dev/null && stat -c '%s' {batch} 2>/dev/null", read_timeout_s=30.0)
        total += sum(int(line) for line in out.split() if line.isdigit())
    return total


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    as_json, full, plan_only = "--json" in sys.argv, "--full" in sys.argv, "--plan" in sys.argv
    if len(args) != 1:
        sys.stderr.write(__doc__)
        return 2
    theme = os.path.abspath(args[0])

    def say(**fields):
        if as_json:
            print(json.dumps(fields), flush=True)
        elif fields.get("event") == "progress":
            print(f"{fields['done']}/{fields['total']} {fields['file']}", flush=True)
        elif "detail" in fields:
            print(fields["detail"], file=sys.stderr if fields.get("ok") is False else sys.stdout, flush=True)

    config = os.path.join(theme, "config.json")
    if not os.path.isfile(config):
        say(ok=False, kind="theme", detail=f"no config.json in {args[0]}")
        return 1
    try:
        json.load(open(config, encoding="utf-8"))
    except ValueError:
        say(ok=False, kind="theme", detail=f"{args[0]}/config.json is not valid JSON")
        return 1

    # Imported only now: tv.py exits at import time when TV_HOST is unset.
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import tv  # noqa: E402

    started = time.time()
    local = theme_files(theme)
    dev = tv.connect()
    try:
        remote = remote_sums(dev)
        changed, stale = plan(local, remote, full)
        if plan_only:
            say(ok=True, plan=True, send=changed, send_bytes=sum(os.path.getsize(local[n]) for n in changed),
                delete=stale, delete_bytes=remote_size(dev, stale), big=sum(os.path.getsize(local[n]) > STALL_ABOVE for n in changed),
                files=len(local), known=remote is not None)
            return 0
        if remote is None:
            # The TV's copy is unknown: clear each picture folder as theme.sh does, so nothing stale stays behind.
            folders = sorted({n.split("/")[0] for n in local if "/" in n} | {"wallpapers"})
            dev.shell("; ".join(f"rm -f {DIR}/{f}/* 2>/dev/null; content delete --uri {URI}/{f} 2>/dev/null" for f in folders),
                      read_timeout_s=max(30.0, 3.0 * len(folders)))
        say(event="plan", send=len(changed), delete=len(stale), known=remote is not None)
        folders = sorted({n.split("/")[0] for n in changed if "/" in n})
        dev.shell(f"mkdir -p {DIR}" + "".join(f" {DIR}/{f}" for f in folders) + " 2>/dev/null")
        # Bytes too, so a tool can tell how long the rest will take (most of the time is the pictures' size).
        total_bytes = sum(os.path.getsize(local[n]) for n in changed)
        sent_bytes = 0
        for i, name in enumerate(changed):
            say(event="progress", done=i, total=len(changed), file=name, sent_bytes=sent_bytes, total_bytes=total_bytes)
            dev = send(dev, local[name], stage(name), tv.connect)
            # The provider copies the file over: a big picture (a 4K PNG) takes longer than the usual 10 s read.
            dev.shell(put(name), read_timeout_s=max(30.0, os.path.getsize(local[name]) / 1e6 * 4.0))
            sent_bytes += os.path.getsize(local[name])
        if stale:
            # Each `content delete` starts a process (about a second on a TV), so a long list goes in batches: one
            # shell call that runs for minutes outlives the connection's read timeout.
            for i in range(0, len(stale), DELETE_BATCH):
                batch = stale[i:i + DELETE_BATCH]
                say(event="removing", done=i, total=len(stale))
                dev.shell("; ".join(delete(n) for n in batch), read_timeout_s=max(30.0, 3.0 * len(batch)))
        if changed and remote is not None:
            # The TV's copy now matches, file by file.
            after = remote_sums(dev) or {}
            wrong = [n for n in changed if after.get(n) != md5(local[n])]
            if wrong:
                say(ok=False, kind="verify", detail="the TV's copy differs after sending: " + ", ".join(wrong))
                return 1
        say(event="progress", done=len(changed), total=len(changed), file="", sent_bytes=sent_bytes, total_bytes=total_bytes)
        revision = uuid.uuid4().hex
        dev.shell(f"printf '%s' '{revision}' > {stage('theme_revision')}; {put('theme_revision')}")
        say(event="restart")
        dev.shell(RESTART, read_timeout_s=60.0)
        say(ok=True, sent=len(changed), deleted=len(stale), files=len(local), seconds=round(time.time() - started, 1),
            detail=f"Theme {os.path.basename(theme)} is on the TV: {len(changed)} of {len(local)} files sent, "
                   f"{len(stale)} removed, {time.time() - started:.1f} s.")
        return 0
    except Exception as e:
        say(ok=False, kind="transport", detail=f"{type(e).__name__}: {e}")
        return 1
    finally:
        dev.close()


if __name__ == "__main__":
    sys.exit(main())
