#!/usr/bin/env python3
"""
ColorOS Non-Root Font Injector
Applies custom TTF fonts on ColorOS 15/16 without root or unlocking bootloader
using TOCTOU (Time-Of-Check to Time-Of-Use) inode swap against Theme Store.
"""

import os
import sys
import time
import argparse
import hashlib
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

DEFAULT_ADB_PATHS = [
    r"C:\Users\Beichen\AppData\Local\Android\Sdk\platform-tools\adb.exe",
    "adb"
]

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
        raise RuntimeError("No Theme Store DIY directory found. Please download or create at least one DIY font in Theme Store first.")
    lines = [l.strip() for l in out.splitlines() if l.strip()]
    return os.path.dirname(lines[0])

def find_target_record(adb, serial, diy_dir):
    code, out, _ = run_adb(adb, serial, ["shell", f"ls -t {diy_dir}/colorFonts/*_colorFont_*.ttf 2>/dev/null"])
    if code != 0 or not out:
        raise RuntimeError("No colorFont records found in " + diy_dir + "/colorFonts.")
    records = [l.strip() for l in out.splitlines() if l.strip()]
    return records[0]

def find_button_coordinate(adb, serial, target_record):
    target_ts = target_record.split("_")[-1].replace(".ttf", "")
    run_adb(adb, serial, ["shell", "uiautomator dump /data/local/tmp/uidump.xml"])
    code, xml_data, _ = run_adb(adb, serial, ["shell", "cat /data/local/tmp/uidump.xml"])
    if code != 0 or not xml_data:
        return None
    try:
        root = ET.fromstring(xml_data)
        for card in root.iter("node"):
            if "item" in card.attrib.get("resource-id", ""):
                b_bounds = None
                for child in card.iter("node"):
                    if child.attrib.get("resource-id", "").endswith("use"):
                        b_bounds = child.attrib.get("bounds", "")
                if b_bounds and target_ts in xml_data:
                    import re
                    m = re.findall(r"\[(\d+),(\d+)\]", b_bounds)
                    if len(m) == 2:
                        x1, y1 = int(m[0][0]), int(m[0][1])
                        x2, y2 = int(m[1][0]), int(m[1][1])
                        return (x1 + x2) // 2, (y1 + y2) // 2
    except Exception:
        pass
    return None

def inject(font_path, target_serial=None, custom_adb=None, tap_coord=None, skip_scale=False):
    adb = get_adb(custom_adb)
    print(f"[*] Using ADB: {adb}")

    # Verify font exists
    font_file = Path(font_path).resolve()
    if not font_file.exists():
        raise FileNotFoundError(f"Font file not found: {font_file}")

    local_font_to_push = str(font_file)
    if not skip_scale:
        try:
            from fontTools.ttLib import TTFont
            f = TTFont(str(font_file))
            upem = f['head'].unitsPerEm
            if upem != 256:
                print(f"[*] Font UPEM is {upem}, scaling to 256 for ColorOS compatibility...")
                scaled_tmp = font_file.parent / f"{font_file.stem}_256.ttf"
                from scale_upem import scale_font_upem
                scale_font_upem(str(font_file), str(scaled_tmp), 256)
                local_font_to_push = str(scaled_tmp)
        except ImportError:
            print("[!] fontTools not installed, skipping UPEM check.")

    expected_md5 = get_file_md5(local_font_to_push)
    expected_size = os.path.getsize(local_font_to_push)
    print(f"[*] Target font: {local_font_to_push} (Size: {expected_size}, MD5: {expected_md5})")

    # Push font to device
    remote_staged = "/data/local/tmp/font_to_inject.ttf"
    print(f"[*] Pushing font to device: {remote_staged}")
    run_adb(adb, target_serial, ["push", local_font_to_push, remote_staged])

    # Locate Theme Store DIY folder
    diy_dir = locate_diy_dir(adb, target_serial)
    target_record = find_target_record(adb, target_serial, diy_dir)
    target_name = os.path.basename(target_record)
    print(f"[*] Theme Store DIY Dir: {diy_dir}")
    print(f"[*] Target Record: {target_name}")

    # Determine tap coordinate
    if not tap_coord:
        tap_coord = find_button_coordinate(adb, target_serial, target_record)
        if not tap_coord:
            tap_coord = (1056, 672) # fallback
            print(f"[!] Could not parse UI coordinates automatically; using fallback: {tap_coord}")
        else:
            print(f"[*] Detected '应用' button coordinate: {tap_coord}")
    else:
        print(f"[*] Using provided tap coordinate: {tap_coord}")

    # Shell script for TOCTOU injection
    sh_script = f"""#!/system/bin/sh
DIR="{diy_dir}"
COLOR_FONTS="$DIR/colorFonts"
TARGET_NAME="{target_name}"
TARGET="$COLOR_FONTS/$TARGET_NAME"
ORIG="$DIR/.orig_backup_clean.ttf"
SWAP_TMP="$COLOR_FONTS/.swap_ready.dat"

input keyevent 4
sleep 0.5
pkill -f inotifyd 2>/dev/null

if [ ! -f "$ORIG" ]; then
    cp "$TARGET" "$ORIG"
fi

cp "$ORIG" "$TARGET"
cp "{remote_staged}" "$SWAP_TMP"
chmod 666 "$TARGET" "$SWAP_TMP"

rm -f /data/local/tmp/swap_events.log

/system/bin/inotifyd - "$COLOR_FONTS:r" | while read -r ev d f; do
    if [ "$f" = "$TARGET_NAME" ]; then
        echo "$(date +%H:%M:%S.%3N) $ev $f" >> /data/local/tmp/swap_events.log
        COUNT=$((COUNT + 1))
        if [ "$COUNT" -eq 2 ]; then
            /system/bin/mv -f "$SWAP_TMP" "$TARGET"
            echo "SWAPPED AT $(date +%H:%M:%S.%3N)" >> /data/local/tmp/swap_events.log
        fi
    fi
done &
PIPE_PID=$!

sleep 0.4
echo "TAPPING {tap_coord[0]} {tap_coord[1]}"
input tap {tap_coord[0]} {tap_coord[1]}
sleep 3.5

kill $PIPE_PID 2>/dev/null
pkill -f inotifyd 2>/dev/null

# Restore original file in Theme Store data
cp "$ORIG" "$TARGET"
chmod 666 "$TARGET"
rm -f "$SWAP_TMP"
"""

    print("[*] Executing injection script on device...")
    p = subprocess.Popen(
        [adb] + (["-s", target_serial] if target_serial else []) + ["shell", "cat > /data/local/tmp/run_inject.sh && chmod 755 /data/local/tmp/run_inject.sh && /data/local/tmp/run_inject.sh"],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE
    )
    out, err = p.communicate(input=sh_script.encode("utf-8").replace(b"\r\n", b"\n"))
    print(out.decode("utf-8", errors="replace"))

    # Verify system font
    code, sys_font_info, _ = run_adb(adb, target_serial, ["shell", "ls -l /data/format_unclear/font/Customized-Regular.ttf && md5sum /data/format_unclear/font/Customized-Regular.ttf"])
    print("[*] System Font Status:\n" + sys_font_info)
    
    if expected_md5 in sys_font_info:
        print("\n[SUCCESS] Font applied successfully! System font MD5 matches target font.")
        return True
    else:
        print("\n[FAILED] System font MD5 does not match target. Check logcat or DIY record status.")
        return False

def main():
    parser = argparse.ArgumentParser(description="ColorOS Non-Root Font Injector")
    parser.add_argument("--font", required=True, help="Path to TTF font file to inject")
    parser.add_argument("-s", "--serial", help="Device serial / IP:PORT")
    parser.add_argument("--adb", help="Path to adb executable")
    parser.add_argument("--tap-x", type=int, help="X coordinate of apply button")
    parser.add_argument("--tap-y", type=int, help="Y coordinate of apply button")
    parser.add_argument("--skip-scale", action="store_true", help="Skip UPEM scaling")

    args = parser.parse_args()
    tap_coord = (args.tap_x, args.tap_y) if (args.tap_x and args.tap_y) else None
    success = inject(args.font, args.serial, args.adb, tap_coord, args.skip_scale)
    sys.exit(0 if success else 1)

if __name__ == '__main__':
    main()
