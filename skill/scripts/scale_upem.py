import sys
from fontTools.ttLib import TTFont
from fontTools.ttLib.scaleUpem import scale_upem


def scale_font_upem(input_path: str, output_path: str, target_upem: int = 256):
    """
    Scale a TTF font's unitsPerEm to target_upem (ColorOS DIY fonts use 256).

    This uses fontTools' built-in scaleUpem so that EVERY table is rescaled
    proportionally: glyf, hmtx, GPOS/kern, GDEF, BASE, vhea/vmtx, etc.

    Do NOT hand-roll a scaler that only touches glyf/hmtx/head/hhea/OS2. Such a
    scaler leaves the kerning values (GPOS pair adjustments, or the legacy
    'kern' table) at the OLD em scale. After unitsPerEm shrinks to 256 the
    kerning is then (old_upem / 256) times too large, which on the device looks
    like badly spaced / over-kerned text. Always scale the whole font.
    """
    font = TTFont(input_path)
    current_upem = font['head'].unitsPerEm
    print(f"Current unitsPerEm: {current_upem}, Target: {target_upem}")

    if current_upem == target_upem:
        print("Font already at target unitsPerEm. Copying directly.")
        font.save(output_path)
        return

    scale_upem(font, target_upem)
    font.save(output_path)
    print(f"Saved scaled font to: {output_path}")


if __name__ == '__main__':
    if len(sys.argv) < 3:
        print("Usage: python scale_upem.py <input.ttf> <output.ttf> [target_upem=256]")
        sys.exit(1)
    target = int(sys.argv[3]) if len(sys.argv) > 3 else 256
    scale_font_upem(sys.argv[1], sys.argv[2], target)
