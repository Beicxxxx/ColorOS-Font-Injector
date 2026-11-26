#!/usr/bin/env python3
"""prune_unused — 删掉"用不到"的字形以减小体积（不是删"未编码"字形）。

保留: cmap 里所有码位的字形 → 它们经 GSUB（所有特性、所有书写系统）能替换到的字形
→ cmap format 14（IVS）引用的字形 → 以上字形作为复合部件引用的字形，反复闭包。
其余字形（例如被换掉的原字形、被去掉的 emoji 码位原来用的字形）删除。GPOS / GDEF /
VarStore / gvar / HVAR 等一并精简，名字、hinting、所有 OpenType 特性原样保留。

用法: python3 prune_unused.py in.ttf out.ttf
"""
import sys
from fontTools import subset
from fontTools.ttLib import TTFont


def main(src, dst):
    f = TTFont(src)
    n0 = f["maxp"].numGlyphs
    unicodes, ivs_glyphs = set(), set()
    uvs = [t for t in f["cmap"].tables if t.format == 14]
    for t in f["cmap"].tables:
        if t.format != 14:
            unicodes.update(t.cmap)
    for t in uvs:  # glyphs reachable only through IVS must stay
        for lst in t.uvsDict.values():
            ivs_glyphs.update(g for _, g in lst if g)
    opt = subset.Options()
    opt.layout_features = ["*"]
    opt.layout_scripts = ["*"]
    opt.name_IDs = ["*"]
    opt.name_languages = ["*"]
    opt.name_legacy = True
    opt.glyph_names = True
    opt.notdef_glyph = True
    opt.notdef_outline = True
    opt.recalc_bounds = True
    opt.hinting = True
    opt.legacy_kern = True
    opt.drop_tables = []
    opt.passthrough_tables = True
    opt.prune_unicode_ranges = False
    opt.prune_codepage_ranges = False
    s = subset.Subsetter(opt)
    s.populate(unicodes=unicodes, glyphs=ivs_glyphs)
    import copy
    uvs = copy.deepcopy(uvs)
    s.subset(f)
    if uvs and not any(t.format == 14 for t in f["cmap"].tables):  # put the IVS table back if dropped
        keep = set(f.getGlyphOrder())
        for t in uvs:
            t.uvsDict = {k: [(c, g) for c, g in v if g is None or g in keep] for k, v in t.uvsDict.items()}
        f["cmap"].tables.extend(uvs)
    f.flavor = None
    f.save(dst)
    print("字形", n0, "->", f["maxp"].numGlyphs)


if __name__ == "__main__":
    main(*sys.argv[1:3])
