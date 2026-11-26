#!/usr/bin/env python3
"""find_unencoded — 给源字体里的未编码汉字字形找 Unicode 码位。

做法:
  1. 日系源若字形序号就是 Adobe-Japan1 CID，用 IVD (Adobe-Japan1) 直接得到
     异体字选择器序列（IVS），写进 cmap format 14。
  2. 其余所有未编码字形（含被 OpenType 特性引用的、被当部件的）只要像汉字
     （全宽且墨迹足够大），就栅格化后按墨迹框归一，与参考字体中"合成字体里
     尚未收录"的全部汉字码位（统一汉字各扩展区 + 兼容汉字，按 Unicode 17）比对。
  3. 输出候选表 + 比对图，人工确认后写入 config 的 encode_map。

用法: python3 find_unencoded.py config.json merged.ttf refdir outdir
"""
import glob
import json
import os
import sys

import freetype
import numpy as np
from fontTools.ttLib import TTFont

N = 32


def han_ranges(ucd_path):
    """CJK unified (all extensions) + compatibility ideograph code points assigned in UnicodeData."""
    cps, first = set(), None
    for line in open(ucd_path, encoding="utf-8"):
        f = line.split(";")
        cp, name = int(f[0], 16), f[1]
        if "CJK Ideograph" in name and name.endswith("First>"):
            first = cp
        elif "CJK Ideograph" in name and name.endswith("Last>"):
            cps.update(range(first, cp + 1))
        elif name.startswith("CJK COMPATIBILITY IDEOGRAPH"):
            cps.add(cp)
    return cps


class Raster:
    def __init__(self, path, wght=None):
        self.face = freetype.Face(path)
        self.face.set_pixel_sizes(96, 96)
        if wght is not None:
            try:
                self.face.set_var_design_coords((wght,))
            except Exception:
                pass  # static font

    def vec(self, gid):
        self.face.load_glyph(gid, freetype.FT_LOAD_RENDER | freetype.FT_LOAD_NO_HINTING)
        bm = self.face.glyph.bitmap
        if not bm.width or not bm.rows:
            return None, 0
        a = np.array(bm.buffer, "float32").reshape(bm.rows, bm.pitch)[:, :bm.width] / 255
        # normalise ink box to N x N (keeps aspect by padding)
        h, w = a.shape
        s = max(h, w)
        pad = np.zeros((s, s), "float32")
        pad[(s - h) // 2:(s - h) // 2 + h, (s - w) // 2:(s - w) // 2 + w] = a
        idx = (np.arange(N) * s / N).astype(int)
        small = pad[np.ix_(idx, idx)]
        v = small.flatten() - small.mean()
        n = np.linalg.norm(v)
        return (v / n if n else v), max(h, w)


def main(cfg_path, merged_path, refdir, outdir):
    cfg = json.load(open(cfg_path, encoding="utf-8"))
    here = os.path.dirname(os.path.abspath(__file__))
    han = han_ranges(os.path.join(here, "data", "UnicodeData-17.0.txt"))
    merged = set(TTFont(merged_path, lazy=True).getBestCmap())
    want = han - merged
    print("Unicode 17 汉字码位", len(han), "合成字体未收录", len(want))
    # reference vectors
    refs, vecs = [], []
    for p in sorted(glob.glob(os.path.join(refdir, "*.ttf"))):
        f = TTFont(p, lazy=True)
        cm = {c: g for c, g in f.getBestCmap().items() if c in want}
        if not cm:
            continue
        r = Raster(p)
        for c, g in cm.items():
            v, _ = r.vec(f.getGlyphID(g))
            if v is not None:
                refs.append(c)
                vecs.append(v)
    R = np.stack(vecs)
    print("参考字形", len(refs))
    sources = [(cfg["base"]["tag"], cfg["base"]["path"])] + [(l["tag"], l["path"]) for l in cfg["layers"]]
    ivs_skip = set()
    ivs_cfg = cfg.get("ivs")
    if ivs_cfg:
        for line in open(os.path.join(here, ivs_cfg["table"]), encoding="utf-8").readlines()[1:]:
            ivs_skip.add((ivs_cfg["tag"], int(line.split("\t")[2])))
    results = []
    for tag, path in sources:
        f = TTFont(path)
        if f.flavor:  # freetype needs plain sfnt
            f.flavor = None
            tmp = os.path.join(outdir, f"_{tag}.ttf")
            f.save(tmp)
            path = tmp
            f = TTFont(path)
        enc = set(f.getBestCmap().values())
        hm = f["hmtx"]
        r = Raster(path)
        order = f.getGlyphOrder()
        for gid, g in enumerate(order):
            if g in enc or gid == 0 or (tag, gid) in ivs_skip:
                continue
            if hm[g][0] != 1000:
                continue
            v, size = r.vec(gid)
            if v is None or size < 60:  # too small to be a full ideograph
                continue
            sims = R @ v
            top = np.argsort(-sims)[:3]
            results.append({"src": tag, "glyph": g, "best": f"U+{refs[top[0]]:04X}",
                            "char": chr(refs[top[0]]), "sim": round(float(sims[top[0]]), 3),
                            "alts": [(chr(refs[j]), round(float(sims[j]), 3)) for j in top[1:]]})
        print(tag, "完成")
    results.sort(key=lambda x: -x["sim"])
    json.dump(results, open(os.path.join(outdir, "unencoded_candidates.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=0)
    print("候选", len(results), "sim>=0.85:", sum(1 for x in results if x["sim"] >= 0.85))


if __name__ == "__main__":
    main(*sys.argv[1:5])
