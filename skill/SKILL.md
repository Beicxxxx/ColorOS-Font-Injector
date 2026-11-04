---
name: coloros-font-injector
description: Free, non-root, locked bootloader system font injector for ColorOS 15/16 (OnePlus, OPPO, Realme) using double-open TOCTOU inode swap against Theme Store.
---

# ColorOS Non-Root Font Injector

This skill guides an AI agent to replace the ColorOS system font (e.g. on OnePlus 12, Find X7, OnePlus Ace, Realme GT) with any custom TTF font **without root, without bootloader unlocking, and without modifying system partitions**.

## 1. Background & Mechanism

ColorOS 15/16 loads customized fonts from `/data/format_unclear/font/Customized-Regular.ttf` via symlinks:
- `/system/fonts/SysFont-*.ttf` -> `OplusOSUI-Regular.ttf` -> `/data/format_unclear/font/Customized-Regular.ttf`
- The directory `/data/format_unclear/font/` is SELinux-restricted (`oem_font_data_file`) and cannot be written directly by `shell (uid=2000)`.
- However, when the user clicks "应用" (Apply) on a DIY font in Theme Store (`com.heytap.themestore`), the privileged system service `com.oplus.uxdesign` copies the file from `/sdcard/Android/data/com.heytap.themestore/files/Themes/.data/temp/<uuid>/colorFonts/<uuid>_colorFont_<timestamp>.ttf` to `/data/format_unclear/font/Customized-Regular.ttf`.

### The Security Check: Double-Read MD5 Validation
Theme Store 17.x+ validates the font file before calling `UxThemeService`:
1. **1st Read (`r`)**: Theme Store opens and reads the file to calculate MD5 against private SQLite DB `diyFontFileKey`.
2. **2nd Read (`r`)**: ~50ms later, Theme Store performs a second MD5 validation check.
3. **3rd Read (`r`)**: Theme Store hands off the `FileProvider` URI to `UxThemeService` (`com.oplus.uxdesign`), which opens the file by path and copies it into `/data/format_unclear/font/`.

### The Exploit: Second-Open TOCTOU Inode Swap
Because `inotifyd` on the parent directory can catch read events with sub-millisecond precision:
1. Start an inotify watcher: `/system/bin/inotifyd - "$COLOR_FONTS_DIR:r"`
2. Keep the valid original font in place for the **1st open** and the beginning of the **2nd open**.
3. Upon detecting the **2nd `r` event** for the target file, immediately execute atomic rename:
   `/system/bin/mv -f "$STAGED_CUSTOM_FONT" "$TARGET_FILE"`
4. In Unix semantics, Theme Store's 2nd check is already reading the opened file descriptor pointing to the *old inode* (passes MD5 check).
5. When `UxThemeService` subsequently calls `open()`, the path resolves to the *new inode* (the custom font).
6. `/data/format_unclear/font/Customized-Regular.ttf` receives the custom font!

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

### 2.4 Font Format & UPEM Specifications
- Standard TrueType font (`.ttf`) with valid Unicode / CJK mappings.
- **UPEM Standard**: ColorOS DIY font module expects `unitsPerEm = 256`.
  - Typical fonts use 1000 or 2048 UPEM.
  - While many fonts render, high UPEM can cause minor vertical alignment discrepancies in some text input fields.
  - Scale to 256 using `scripts/scale_upem.py input.ttf output.ttf 256` before injection.

### 2.5 Persistence & Precautions
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

### Step 4: Open Theme Store DIY History Activity
Ensure the screen is active and open to `DiyFontHistoryActivity`:
```bash
adb shell "am start -n com.heytap.themestore/com.nearme.themespace.activities.DiyFontHistoryActivity"
```
Dump UI hierarchy to find the "应用" button coordinate:
```bash
adb shell "uiautomator dump /data/local/tmp/uidump.xml"
```
Or use the coordinate directly (e.g. `(1056, 672)` for top-right card or `(384, 672)` for top-left card).

### Step 5: Execute Inode Swap Script
```bash
adb shell
DIR="/sdcard/Android/data/com.heytap.themestore/files/Themes/.data/temp/<uuid>"
COLOR_FONTS="$DIR/colorFonts"
TARGET_NAME="<target_file_name>.ttf"
TARGET="$COLOR_FONTS/$TARGET_NAME"
ORIG="$DIR/.orig_backup.ttf"
SWAP_TMP="$COLOR_FONTS/.swap_ready.dat"

# Dismiss any transparent overlay
input keyevent 4
sleep 0.5
pkill -f inotifyd 2>/dev/null

if [ ! -f "$ORIG" ]; then
    cp "$TARGET" "$ORIG"
fi

cp "$ORIG" "$TARGET"
cp /data/local/tmp/font_to_inject.ttf "$SWAP_TMP"
chmod 666 "$TARGET" "$SWAP_TMP"

/system/bin/inotifyd - "$COLOR_FONTS:r" | while read -r ev d f; do
    if [ "$f" = "$TARGET_NAME" ]; then
        COUNT=$((COUNT + 1))
        if [ "$COUNT" -eq 2 ]; then
            /system/bin/mv -f "$SWAP_TMP" "$TARGET"
        fi
    fi
done &
PIPE_PID=$!

sleep 0.4
input tap <TAP_X> <TAP_Y>
sleep 3.5

kill $PIPE_PID 2>/dev/null
pkill -f inotifyd 2>/dev/null

# Restore original file for Theme Store UI safety
cp "$ORIG" "$TARGET"
chmod 666 "$TARGET"
rm -f "$SWAP_TMP"
```

### Step 6: Verify System Font
```bash
adb shell "ls -la /data/format_unclear/font/Customized-Regular.ttf && md5sum /data/format_unclear/font/Customized-Regular.ttf"
```
Confirm the MD5 matches the injected font.

---

## 5. Troubleshooting & Edge Cases

1. **`FullTransparentWebViewActivity` blocks clicks**:
   Theme Store sometimes displays an invisible full-screen webview after certain operations. Send `input keyevent 4` (Back key) to dismiss it before tapping.
2. **Error code `-15` (资源包已损坏)**:
   This occurs if the swap happens before the 2nd MD5 validation completes or if the target file was modified beforehand. Always ensure `$ORIG` contains the intact, valid original font.
3. **Card Reordering**:
   When new DIY fonts are created, Theme Store inserts them at index 0 (top-left). Existing cards move to the right or next row. Always verify UI coordinates via `uiautomator dump` or visual inspection.
4. **Font Persistence**:
   The injected font in `/data/format_unclear/font/Customized-Regular.ttf` persists across phone reboots and system OTA updates. Do not click "应用" on DIY fonts inside Theme Store casually without the injector, or it will re-copy the template font.
