---
name: coloros-font-injector
description: Free, non-root, locked-bootloader system font injector for ColorOS 15/16 (OnePlus, OPPO, Realme) using a read-count TOCTOU inode swap against Theme Store (swap after the MD5-validation read burst, before the copy re-opens the file).
---

# ColorOS Non-Root Font Injector

This skill guides an AI agent to replace the ColorOS system font (e.g. on OnePlus 12, Find X7, OnePlus Ace, Realme GT) with any custom TTF font **without root, without bootloader unlocking, and without modifying system partitions**.

## 1. Background & Mechanism

ColorOS 15/16 loads customized fonts from `/data/format_unclear/font/Customized-Regular.ttf` via symlinks:
- `/system/fonts/SysFont-*.ttf` -> `OplusOSUI-Regular.ttf` -> `/data/format_unclear/font/Customized-Regular.ttf`
- The directory `/data/format_unclear/font/` is SELinux-restricted (`oem_font_data_file`) and cannot be written directly by `shell (uid=2000)`.
- However, when the user clicks "应用" (Apply) on a DIY font in Theme Store (`com.heytap.themestore`), the privileged system service `com.oplus.uxdesign` copies the file from `/sdcard/Android/data/com.heytap.themestore/files/Themes/.data/temp/<uuid>/colorFonts/<uuid>_colorFont_<timestamp>.ttf` to `/data/format_unclear/font/Customized-Regular.ttf`.

### The Security Check: MD5 Validation
Before applying, Theme Store validates the DIY record file: it opens it, reads the **whole file** (hundreds of `read` events) to compute its MD5, compares against the stored `historyMd5`, and on mismatch aborts with `-15 (资源包已损坏)`.

### The Exploit: swap after the validation read burst, before the copy open
Verified with `inotifyd` on-device, the event order after tapping "应用" is:
1. **Validation**: `open` the record file → hundreds of `read` events (full-file MD5) → **`close`**.
2. **Copy**: the file is **re-opened** and read, then written into `/data/format_unclear/font/`.

Unix inode semantics: once a process `open()`s the file, its fd is bound to that inode; a later `mv` (atomic rename) over the same name does **not** redirect that already-open fd. So the swap must land in the ~30 ms window **after the validation close and before the copy open**:
1. Watch the record file for read/close events: `/system/bin/inotifyd - "$TARGET:aw0r"` and count `read` (`a`) events per open.
2. Keep the genuine file in place during validation (so MD5 passes).
3. On the **first close that follows a large read burst (> 100 reads)** — i.e. the validation close — immediately atomic-rename the custom font in:
   `/system/bin/mv -f "$STAGED_CUSTOM_FONT" "$TARGET_FILE"`
4. Validation already read the genuine inode (passes MD5); the subsequent copy `open()` resolves the path to the *new inode* (the custom font).
5. `/data/format_unclear/font/Customized-Regular.ttf` receives the custom font.

> ⚠️ Do **not** swap on the "2nd open" — by the time that open fires, its fd is already bound to the old inode, so the swap is too late. The window is only ~30 ms, so a swap occasionally loses the race; **retry** the whole apply until the system font MD5 equals the target.

---

## 2. Prerequisites & Critical Constraints

### 2.1 Target Device & System
- **Supported Brands**: OnePlus, OPPO, Realme.
- **OS Version**: ColorOS 15 or 16 (Android 15 or 16).
- **Security State**: **No root required, locked bootloader compatible** (Green launch verify status).

### 2.2 Critical Prerequisite: Theme Store DIY Font (Mandatory)
> **Why is a DIY font required?**
> Standard fonts downloaded from Theme Store are saved in private app data (`/data/data/com.heytap.themestore/`) or system caches, inaccessible without root.
> In contrast, **DIY Fonts** (`colorFonts`) are uncompressed into the shared external storage path:
> `/sdcard/Android/data/com.heytap.themestore/files/Themes/.data/temp/<uuid>/colorFonts/`
> This path carries `ext_data_rw` permissions, granting `shell (uid=2000)` read, write, rename, and inotify monitoring capabilities.
> When "应用" (Apply) is pressed, `com.oplus.uxdesign` copies from this directory into `/data/format_unclear/font/Customized-Regular.ttf`.

**Preparation Steps (One-time only)**:
1. Open Theme Store (`com.heytap.themestore`).
2. Search and acquire any font supporting **"可 DIY" / "彩色字体"** (e.g. "樱落粗黑体" or other customizable fonts).
3. Click "开始 DIY" (Customize), make a minor tweak or simply click "完成" / "保存" to create at least one history record.
4. Open "历史 DIY 记录" (History records) and confirm the card exists. The template file `/sdcard/Android/data/com.heytap.themestore/files/Themes/.data/temp/<uuid>/colorFonts/<uuid>_colorFont_<timestamp>.ttf` is now generated.

### 2.3 Access Method
- **Method A (ADB)**: Wireless or USB Debugging connected (`adb devices` shows device).
- **Method B (On-device Shizuku)**: Shizuku active with `uid=2000` shell permission granted.
  - On ColorOS, if Shizuku shows the red warning that the OEM restricts adb permissions, the user must first lift it in Developer Options (e.g. disable "permission monitoring") and restart Shizuku before the app's permission can be granted.

### 2.4 Font Format & UPEM Specifications
- Standard TrueType font (`.ttf`) with valid Unicode / CJK mappings.
- **UPEM must be 256**: ColorOS DIY fonts use `unitsPerEm = 256` and the apply pipeline only accepts 256. Injecting a 1000/2048-UPEM font does **not** take effect (the system font stays unchanged).
  - Typical fonts use 1000 or 2048 UPEM, so scale them to 256 first.
  - **Preserve kerning when scaling**: scale `GPOS`/`kern`/`vmtx` and every other table proportionally, not just `glyf`/`hmtx` — otherwise kerning values stay at the old em scale and become ~(old_upem/256)x too wide. `scripts/scale_upem.py` uses fontTools' `scaleUpem`, which does this correctly:
    `python scripts/scale_upem.py input.ttf output-256.ttf 256`
  - `inject_font.py` auto-scales to 256 by default (`--skip-scale` to opt out).

### 2.5 Target record must be consistent
- The record you apply must be **consistent**: its on-disk MD5 still equals the stored `historyMd5` (a freshly created DIY record). A record a previous injection left "dirty" (file replaced, MD5 no longer matching) will always fail with `-15`. When in doubt, create a new DIY record first.

### 2.6 Persistence & Precautions
- Injected font at `/data/format_unclear/font/Customized-Regular.ttf` survives device reboots and OTA updates.
- Avoid clicking "应用" on DIY fonts inside Theme Store without the injector active, as this restores the default template font.

---

## 3. Automated Injection via Agent Script

The skill bundles `scripts/inject_font.py`, which executes the entire pipeline end-to-end:

```bash
python scripts/inject_font.py --font /path/to/custom_font.ttf [-s <device_serial>]
```

### Script Execution Parameters:
- `--font`: Path to input TTF file (required).
- `-s` / `--serial`: Device serial or wireless address (e.g. `192.168.5.125:43621`).
- `--adb`: Path to custom `adb.exe` if not in system PATH.
- `--tap-x`, `--tap-y`: Specific button coordinates if UI hierarchy detection fails.
- `--skip-scale`: Skip scaling UPEM to 256 if font is already prepared.

---

## 4. Manual Step-by-Step Procedure for AI Agent

If executing directly via shell or Shizuku without the wrapper:

### Step 1: Prepare and Scale Font
```python
from fontTools.ttLib import TTFont
# Check UPEM
font = TTFont("myfont.ttf")
print(font['head'].unitsPerEm)
# If != 256, run:
# python scripts/scale_upem.py myfont.ttf myfont_256.ttf 256
```

### Step 2: Push Staging Font to Device
```bash
adb push myfont_256.ttf /data/local/tmp/font_to_inject.ttf
```

### Step 3: Locate Theme Store DIY Directory & Target File
```bash
adb shell "ls -d /sdcard/Android/data/com.heytap.themestore/files/Themes/.data/temp/*/colorFonts"
# Returns e.g.: /sdcard/Android/data/com.heytap.themestore/files/Themes/.data/temp/b0bcadfabacb81554910f961cd3e934a/colorFonts
```
Find the most recent DIY font file:
```bash
adb shell "ls -t <DIR>/colorFonts/*_colorFont_*.ttf | head -n 1"
# Returns e.g.: b0bcadfabacb81554910f961cd3e934a_colorFont_1793777330108.ttf
```

### Step 4: Open Theme Store "历史 DIY 记录" page
`DiyFontHistoryActivity` is **not exported** — launching it directly is rejected (`am start -n com.heytap.themestore/...DiyFontHistoryActivity` throws a SecurityException). The user must navigate Theme Store to the "历史 DIY 记录" page manually; if the Theme Store task is already there in the background you may bring it forward with:
```bash
adb shell "monkey -p com.heytap.themestore -c android.intent.category.LAUNCHER 1"
```
The swap script can only `input keyevent 4` back to that page (and dismiss a `-15` dialog); it cannot navigate into it.

Find the newest record's "应用" button center from the UI dump (first node whose `resource-id` ends in `/use`, top-most then left-most). `inject_font.py` detects this automatically; the hardcoded fallback is `(384, 672)` (top-left card on a 1440-wide panel).
```bash
adb shell "uiautomator dump /data/local/tmp/uidump.xml"
```

### Step 5: Execute the read-count inode swap
```bash
adb shell
COLOR_FONTS="/sdcard/Android/data/com.heytap.themestore/files/Themes/.data/temp/<uuid>/colorFonts"
TARGET="$COLOR_FONTS/<target_file_name>.ttf"
ORIG=/data/local/tmp/.orig_genuine.ttf
SWAP="$COLOR_FONTS/.swap_ready.dat"

pkill -f inotifyd 2>/dev/null
cp "$TARGET" "$ORIG"                           # back up the genuine (consistent) record
cp /data/local/tmp/font_to_inject.ttf "$SWAP"
chmod 666 "$SWAP" 2>/dev/null

reads=0
# Swap on the first close that follows the full-file MD5 read burst. 'a'=read,
# 'r'=open (resets the counter), 'w'/'0'=close.
/system/bin/inotifyd - "$TARGET:aw0r" | while read -r ev p r; do
  case "$ev" in
    *a*) reads=$((reads+1)) ;;
    *r*) reads=0 ;;
    *)
      if [ -z "$DONE" ] && [ "${reads:-0}" -gt 100 ]; then
        /system/bin/mv -f "$SWAP" "$TARGET"; DONE=1
      fi
      reads=0 ;;
  esac
done &
PID=$!

sleep 0.4
input tap <TAP_X> <TAP_Y>                      # tap "应用" on the newest card
sleep 8

kill $PID 2>/dev/null; pkill -f inotifyd 2>/dev/null
cp "$ORIG" "$TARGET"; rm -f "$SWAP"            # restore the genuine record
```
Afterwards verify the system font MD5 (Step 6). If it does not match, the swap lost the ~30 ms race (or the record was dirty) — repeat the apply.

### Step 6: Verify System Font
```bash
adb shell "ls -la /data/format_unclear/font/Customized-Regular.ttf && md5sum /data/format_unclear/font/Customized-Regular.ttf"
```
Confirm the MD5 matches the injected font.

---

## 5. Troubleshooting & Edge Cases

1. **Swap lost the race (system font unchanged, no `-15`)**:
   The ~30 ms window between the validation close and the copy open was missed, so the copy read the genuine file. Just retry; `inject_font.py` retries automatically. Swapping on the close *after the big read burst* (not on the "2nd open") maximizes the hit rate.
2. **Error code `-15` (资源包已损坏)**:
   Either the swap landed too early (before validation finished reading, so the MD5 was computed over the custom font), or the target record is "dirty" (its on-disk MD5 no longer equals the stored `historyMd5`, e.g. a previous injection left the custom font in place). Keep the genuine file in place during validation, and prefer a freshly created DIY record. A stale `-15` dialog is modal and blocks the next tap — dismiss it (tap "知道了") before retrying.
3. **"历史 DIY 记录" page not reachable programmatically**:
   The activity is not exported; `am start` cannot open it. The user must open it in Theme Store first. The script can only return to it via `input keyevent 4` and bring the store forward via `monkey`.
4. **ColorOS restricts adb permissions (Shizuku red warning)**:
   On ColorOS, Shizuku-based apps cannot be granted permission until the user lifts the OEM adb-permission restriction in Developer Options (e.g. disable "permission monitoring") and restarts Shizuku. The ADB path is unaffected.
5. **Card Reordering**:
   When new DIY fonts are created, Theme Store inserts them at index 0 (top-left). Always resolve the "应用" coordinate from a fresh `uiautomator dump` (newest record = top-most/left-most), and watch the newest `ls -t` record file so the tapped card and the watched file match.
6. **Font Persistence**:
   The injected font in `/data/format_unclear/font/Customized-Regular.ttf` persists across phone reboots and system OTA updates. Do not click "应用" on DIY fonts inside Theme Store casually without the injector, or it will re-copy the template font.
