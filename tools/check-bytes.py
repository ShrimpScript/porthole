#!/usr/bin/env python3
"""Fails if any tracked text file carries a raw control byte.

Not a style rule. A single control byte makes git and grep classify a source file as
binary, and both then skip its contents in silence - `git grep` finds nothing there and
reports success. The pre-publish scrub (tools/scrub-check.sh) is a `git grep`, so a file
containing one raw ESC could carry a home path or a tailnet address straight into a
public repo while the gate said the repo was clean.

Escape sequences belong in source as \\u001B, not as the byte itself.
"""
import os
import subprocess
import sys

BINARY = (".png", ".jpg", ".jpeg", ".webp", ".gif", ".ico",
          ".ttf", ".otf", ".woff", ".woff2", ".jar", ".zip")
ALLOWED = {0x09, 0x0A, 0x0D}  # tab, newline, carriage return


def main() -> int:
    # --others --exclude-standard includes files not yet committed, so a new file is
    # checked before the commit that adds it.
    listing = subprocess.run(
        ["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
        capture_output=True, check=True,
    ).stdout
    bad = []
    for raw in listing.split(b"\0"):
        if not raw:
            continue
        path = raw.decode()
        if path.endswith(BINARY):
            continue
        # A tracked file deleted in the working tree is still listed; nothing to scan.
        if not os.path.exists(path):
            continue
        with open(path, "rb") as fh:
            data = fh.read()
        found = sorted({b for b in data if (b < 0x20 or b == 0x7F) and b not in ALLOWED})
        if found:
            bad.append((path, [f"0x{b:02X}" for b in found]))

    if not bad:
        print(f"clean: no raw control bytes in tracked text files")
        return 0
    print("raw control bytes found - these files are invisible to git grep:")
    for path, bytes_found in bad:
        print(f"  {path}: {', '.join(bytes_found)}")
    print("\nwrite them as escapes (\\u001B, \\u007F) instead.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
