import sys
from fontTools.ttLib import TTFont

def scale_font_upem(input_path: str, output_path: str, target_upem: int = 256):
    """
    Scale a TTF font's unitsPerEm to target_upem (ColorOS DIY font typically uses 256).
    """
    font = TTFont(input_path)
    current_upem = font['head'].unitsPerEm
    print(f"Current unitsPerEm: {current_upem}, Target: {target_upem}")
    
    if current_upem == target_upem:
        print("Font already has target unitsPerEm. Copying directly.")
        font.save(output_path)
        return

    scale_factor = target_upem / current_upem

    # Scale head table
    font['head'].unitsPerEm = target_upem

    # Scale hhea table
    if 'hhea' in font:
        for attr in ['ascent', 'descent', 'lineGap', 'advanceWidthMax', 'minLeftSideBearing', 'minRightSideBearing', 'xMaxExtent']:
            if hasattr(font['hhea'], attr):
                setattr(font['hhea'], attr, int(round(getattr(font['hhea'], attr) * scale_factor)))

    # Scale OS/2 table
    if 'OS/2' in font:
        os2_attrs = [
            'sTypoAscender', 'sTypoDescender', 'sTypoLineGap',
            'usWinAscent', 'usWinDescent', 'sxHeight', 'sCapHeight',
            'xAvgCharWidth'
        ]
        for attr in os2_attrs:
            if hasattr(font['OS/2'], attr):
                setattr(font['OS/2'], attr, int(round(getattr(font['OS/2'], attr) * scale_factor)))

    # Scale glyf table
    if 'glyf' in font:
        for glyph_name in font['glyf'].glyphs:
            glyph = font['glyf'][glyph_name]
            if hasattr(glyph, 'coordinates') and glyph.coordinates is not None:
                glyph.coordinates.scale((scale_factor, scale_factor))
            if hasattr(glyph, 'xMin'):
                glyph.xMin = int(round(glyph.xMin * scale_factor))
                glyph.yMin = int(round(glyph.yMin * scale_factor))
                glyph.xMax = int(round(glyph.xMax * scale_factor))
                glyph.yMax = int(round(glyph.yMax * scale_factor))

    # Scale hmtx table
    if 'hmtx' in font:
        metrics = font['hmtx'].metrics
        for glyph_name in metrics:
            advance, lsb = metrics[glyph_name]
            metrics[glyph_name] = (int(round(advance * scale_factor)), int(round(lsb * scale_factor)))

    font.save(output_path)
    print(f"Saved scaled font to: {output_path}")

if __name__ == '__main__':
    if len(sys.argv) < 3:
        print("Usage: python scale_upem.py <input.ttf> <output.ttf> [target_upem=256]")
        sys.exit(1)
    target = int(sys.argv[3]) if len(sys.argv) > 3 else 256
    scale_font_upem(sys.argv[1], sys.argv[2], target)
