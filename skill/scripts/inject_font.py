#!/usr/bin/env python3
"""
ColorOS Non-Root Font Injector
Applies custom TTF fonts on ColorOS 15/16 without root or unlocking bootloader
using a TOCTOU (Time-Of-Check to Time-Of-Use) inode swap against Theme Store.

How the swap is timed (verified on OnePlus 12 / PJD110, ColorOS 16):
  When you tap "应用" on a DIY record, Theme Store first reads the WHOLE record
  file to validate its MD5 (hundreds of read() events), CLOSES it, then re-opens
  it to copy it into the protected system font path. We keep the genuine record
  in place during validation (so the MD5 check passes), then swap our font in on
  the first close that follows that large read burst -- before the copy re-opens
  the file. Swapping on the "2nd open" is too late: that open's fd is already
  bound to the old inode, so mv cannot redirect it.

  The window between validation-close and copy-open is small (~30 ms), so the
  swap occasionally loses the race. We therefore retry the whole apply a few
  times and stop as soon as the system font MD5 matches the target.

Font scaling:
  ColorOS DIY colorFonts are 256 upem and the apply pipeline expects 256, so a
  non-256 font is rejected. We scale with fontTools' scaleUpem (see
  scale_upem.py), which rescales GPOS/kern too, preserving kerning.
"""

import os
import sys
import time
import argparse
import hashlib
import subprocess
import re
from pathlib import Path

DEFAULT_ADB_PATHS = [
    r"C:\Users\Beichen\AppData\Local\Android\Sdk\platform-tools\adb.exe",
    "adb"
]

MAX_ATTEMPTS = 6
SYSTEM_FONT = "/data/format_unclear/font/Customized-Regular.ttf"


def get_adb(custom_adb=None):
    if custom_adb and os.path.exists(custom_adb):
        return custom_adb
    for p in DEFAULT_ADB_PATHS:
        try:
            res = subprocess.run([p, "version"], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            if res.returncode == 0:
                return p
        except Exception:
            continue
    raise RuntimeError("adb not found in PATH or standard Android SDK directories.")


def run_adb(adb, serial, args, capture=True):
    cmd = [adb]
    if serial:
        cmd.extend(["-s", serial])
    cmd.extend(args)
    if capture:
        p = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, encoding="utf-8", errors="replace")
        return p.returncode, p.stdout.strip(), p.stderr.strip()
    else:
        return subprocess.run(cmd).returncode, "", ""


def get_file_md5(filepath):
    h = hashlib.md5()
    with open(filepath, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest().lower()


def locate_diy_dir(adb, serial):
    code, out, _ = run_adb(adb, serial, ["shell", "ls -d /sdcard/Android/data/com.heytap.themestore/files/Themes/.data/temp/*/colorFonts 2>/dev/null"])
    if code != 0 or not out:
        raise RuntimeError("No Theme Store DIY directory found. Create at least one DIY font in Theme Store first.")
    lines = [l.strip() for l in out.splitlines() if l.strip()]
    return os.path.dirname(lines[0])


def find_target_record(adb, serial, diy_dir):
    """Newest colorFont record = the card the newest '应用' button applies."""
    code, out, _ = run_adb(adb, serial, ["shell", f"ls -t {diy_dir}/colorFonts/*_colorFont_*.ttf 2>/dev/null"])
    if code != 0 or not out:
        raise RuntimeError("No colorFont records found in " + diy_dir + "/colorFonts. Create a new DIY record first.")
    records = [l.strip() for l in out.splitlines() if l.strip()]
    return records[0]


def _dump_ui(adb, serial):
    run_adb(adb, serial, ["shell", "uiautomator dump /data/local/tmp/uidump.xml >/dev/null 2>&1"])
    code, xml_data, _ = run_adb(adb, serial, ["shell", "cat /data/local/tmp/uidump.xml"])
    return xml_data if code == 0 else ""


def _node_center(node_str):
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node_str)
    if not b:
        return None
    x1, y1, x2, y2 = map(int, b.groups())
    return (x1 + x2) // 2, (y1 + y2) // 2


def find_button_coordinate(adb, serial):
    """Center of the newest DIY record's '应用' button.

    The newest colorFont file (ls -t) is the top-most / left-most card, so we
    return the first '应用' button in reading order.
    """
    xml_data = _dump_ui(adb, serial)
    if not xml_data:
        return None
    btns = []
    for m in re.finditer(r'<node\b[^>]*?>', xml_data):
        s = m.group(0)
        if ('content-desc="应用"' in s) or re.search(r'resource-id="[^"]*/use"', s):
            c = _node_center(s)
            if c:
                btns.append(c)
    if not btns:
        return None
    btns.sort(key=lambda c: (c[1], c[0]))
    return btns[0]


def dismiss_dialogs(adb, serial):
    """Dismiss a leftover '资源包已损坏 (-15)' dialog if one is showing."""
    xml_data = _dump_ui(adb, serial)
    if not xml_data:
        return
    for label in ("知道了", "确定"):
        for m in re.finditer(r'<node\b[^>]*?>', xml_data):
            s = m.group(0)
            if f'text="{label}"' in s:
                c = _node_center(s)
                if c:
                    run_adb(adb, serial, ["shell", f"input tap {c[0]} {c[1]}"])
                    time.sleep(1)
                    return


def ensure_history_page(adb, serial, max_back=4):
    """Bring the DIY font history page to the foreground (dismiss dialogs / H5)."""
    for _ in range(max_back):
        dismiss_dialogs(adb, serial)
        _, fg, _ = run_adb(adb, serial, ["shell", "dumpsys activity activities | grep topResumedActivity | head -1"])
        if "DiyFontHistoryActivity" in fg:
            return True
        run_adb(adb, serial, ["shell", "input keyevent 4"])
        time.sleep(1.5)
    _, fg, _ = run_adb(adb, serial, ["shell", "dumpsys activity activities | grep topResumedActivity | head -1"])
    return "DiyFontHistoryActivity" in fg


def build_inject_script(diy_dir, target_name, remote_staged, tap_coord):
    return f"""#!/system/bin/sh
COLOR_FONTS="{diy_dir}/colorFonts"
TARGET_NAME="{target_name}"
TARGET="$COLOR_FONTS/$TARGET_NAME"
ORIG="/data/local/tmp/.orig_genuine.ttf"
SWAP_TMP="$COLOR_FONTS/.swap_ready.dat"
LOG="/data/local/tmp/swap_events.log"

pkill -f inotifyd 2>/dev/null
: > "$LOG"

# Back up the CURRENT record (it must be consistent -- its on-disk MD5 still
# equals the stored historyMd5, e.g. a freshly created DIY record) so the
# store's MD5 validation passes, and so we can restore it afterwards.
cp "$TARGET" "$ORIG"
cp "{remote_staged}" "$SWAP_TMP"
chmod 666 "$SWAP_TMP" 2>/dev/null

# Swap on the first close that follows a large read burst (the MD5 validation
# reading the whole file). The copy re-opens the file right after, and reads our
# font. 'a'=read, 'r'=open (resets the counter), 'w'/'0'=close.
reads=0
/system/bin/inotifyd - "$TARGET:aw0r" | while read -r ev path rest; do
    case "$ev" in
        *a*) reads=$((reads+1)) ;;
        *r*) reads=0 ;;
        *)
            if [ -z "$DONE" ] && [ "${{reads:-0}}" -gt 100 ]; then
                /system/bin/mv -f "$SWAP_TMP" "$TARGET"
                echo "SWAPPED after $reads reads at $(date +%H:%M:%S.%3N)" >> "$LOG"
                DONE=1
            fi
            reads=0 ;;
    esac
done &
PIPE_PID=$!

sleep 0.4
input tap {tap_coord[0]} {tap_coord[1]}
sleep 8

kill $PIPE_PID 2>/dev/null
pkill -f inotifyd 2>/dev/null

# Restore the genuine record so it stays consistent for next time.
cp "$ORIG" "$TARGET" 2>/dev/null
rm -f "$SWAP_TMP"
"""


def run_inject_script(adb, target_serial, sh_script):
    p = subprocess.Popen(
        [adb] + (["-s", target_serial] if target_serial else []) +
        ["shell", "cat > /data/local/tmp/run_inject.sh && chmod 755 /data/local/tmp/run_inject.sh && /data/local/tmp/run_inject.sh"],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE
    )
    out, _ = p.communicate(input=sh_script.encode("utf-8").replace(b"\r\n", b"\n"))
    return out.decode("utf-8", errors="replace")


def system_font_md5(adb, serial):
    _, out, _ = run_adb(adb, serial, ["shell", f"md5sum {SYSTEM_FONT}"])
    m = re.search(r"\b([0-9a-fA-F]{32})\b", out)
    return m.group(1).lower() if m else ""


def inject(font_path, target_serial=None, custom_adb=None, tap_coord=None, skip_scale=False):
    adb = get_adb(custom_adb)
    print(f"[*] Using ADB: {adb}")

    font_file = Path(font_path).resolve()
    if not font_file.exists():
        raise FileNotFoundError(f"Font file not found: {font_file}")

    # Scale to 256 upem unless told not to. ColorOS DIY fonts are 256 and the
    # apply pipeline rejects other upems. scale_upem preserves kerning.
    local_font_to_push = str(font_file)
    if not skip_scale:
        try:
            from fontTools.ttLib import TTFont
            f = TTFont(str(font_file))
            upem = f['head'].unitsPerEm
            if upem != 256:
                print(f"[*] Font UPEM is {upem}, scaling to 256 (kerning-preserving)...")
                scaled_tmp = font_file.parent / f"{font_file.stem}_256.ttf"
                from scale_upem import scale_font_upem
                scale_font_upem(str(font_file), str(scaled_tmp), 256)
                local_font_to_push = str(scaled_tmp)
        except ImportError:
            print("[!] fontTools not installed; pushing font as-is (apply may fail if upem != 256).")

    expected_md5 = get_file_md5(local_font_to_push)
    print(f"[*] Target font: {local_font_to_push} (Size: {os.path.getsize(local_font_to_push)}, MD5: {expected_md5})")

    remote_staged = "/data/local/tmp/font_to_inject.ttf"
    print(f"[*] Pushing font to device: {remote_staged}")
    run_adb(adb, target_serial, ["push", local_font_to_push, remote_staged])

    diy_dir = locate_diy_dir(adb, target_serial)
    target_record = find_target_record(adb, target_serial, diy_dir)
    target_name = os.path.basename(target_record)
    print(f"[*] Theme Store DIY Dir: {diy_dir}")
    print(f"[*] Target Record: {target_name}")

    for attempt in range(1, MAX_ATTEMPTS + 1):
        print(f"\n[*] Attempt {attempt}/{MAX_ATTEMPTS}")
        ensure_history_page(adb, target_serial)
        coord = tap_coord or find_button_coordinate(adb, target_serial) or (384, 672)
        print(f"[*] '应用' button coordinate: {coord}")
        sh_script = build_inject_script(diy_dir, target_name, remote_staged, coord)
        print(run_inject_script(adb, target_serial, sh_script).strip())

        cur = system_font_md5(adb, target_serial)
        print(f"[*] System font MD5: {cur}")
        if cur == expected_md5:
            print(f"\n[SUCCESS] Font applied on attempt {attempt}! System font matches target.")
            return True
        print("[*] Not applied yet (lost the TOCTOU race); retrying...")

    print(f"\n[FAILED] Font not applied after {MAX_ATTEMPTS} attempts.")
    print("    - Make sure the target DIY record is consistent (create a NEW DIY record,")
    print("      do not reuse one a previous injection left dirty).")
    print("    - Check /data/local/tmp/swap_events.log and logcat for -15 validation errors.")
    return False


def main():
    parser = argparse.ArgumentParser(description="ColorOS Non-Root Font Injector")
    parser.add_argument("--font", required=True, help="Path to TTF font file to inject")
    parser.add_argument("-s", "--serial", help="Device serial / IP:PORT")
    parser.add_argument("--adb", help="Path to adb executable")
    parser.add_argument("--tap-x", type=int, help="X of apply button (auto-detected if omitted)")
    parser.add_argument("--tap-y", type=int, help="Y of apply button (auto-detected if omitted)")
    parser.add_argument("--skip-scale", action="store_true", help="Do not scale to 256 upem (font must already be 256)")

    args = parser.parse_args()
    tap_coord = (args.tap_x, args.tap_y) if (args.tap_x and args.tap_y) else None
    success = inject(args.font, args.serial, args.adb, tap_coord, args.skip_scale)
    sys.exit(0 if success else 1)


if __name__ == '__main__':
    main()
