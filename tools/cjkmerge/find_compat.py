#!/usr/bin/env python3
"""find_compat — 给异体孤儿字形找 CJK 兼容表意字码位（U+F900–FAFF、U+2F800–2FA1F）。

兼容字 C 规范分解为统一字 U，字形是 U 的某个异体（如 塚 FA10/FA7C）。
不同字体设计差异很大，直接比"像不像"分不出一个点的差别，所以比"差在哪里":
  dJ = 孤儿字形 g − 同一来源字体里的 U           （g 与本字差在哪）
  dR = 参考字体的 C − 同一参考字体的 U           （兼容字与本字差在哪）
g 须先像 U（是它的异体），且 dJ 与 dR 方向一致（相关系数 >= corr）。
每个缺失的 C 取最符合的一个孤儿字形，输出候选 + 比对图，人工确认后写进 encode_map。

用法: python3 find_compat.py config.json merged.ttf refdir outdir
"""
import glob, json, os, sys
import numpy as np
from fontTools.ttLib import TTFont
import find_unencoded
from find_unencoded import Raster

find_unencoded.N = 48  # finer grid: the differences are often a dot or one stroke


def main(cfg_path, merged_path, refdir, outdir, corr=0.3, lo=0.7, hi=0.99):
    cfg = json.load(open(cfg_path, encoding="utf-8"))
    here = os.path.dirname(os.path.abspath(__file__))
    mcm = TTFont(merged_path, lazy=True).getBestCmap()
    comp = {}
    for line in open(os.path.join(here, "data", "UnicodeData-17.0.txt"), encoding="utf-8"):
        f = line.split(";")
        if f[1].startswith("CJK COMPATIBILITY IDEOGRAPH") and f[5]:
            c, u = int(f[0], 16), int(f[5], 16)
            if c not in mcm:
                comp[c] = u
    # reference difference vectors, C and U taken from the same reference font
    refs = [(p, TTFont(p, lazy=True)) for p in sorted(glob.glob(os.path.join(refdir, "*.ttf")))]
    allcm = {}
    for p, f in refs:
        for cp, g in f.getBestCmap().items():
            allcm.setdefault(cp, (p, f, g))
    rc, dR = {}, {}

    def rvec(cp):
        p, f, g = allcm[cp]
        rc.setdefault(p, Raster(p))
        return rc[p].vec(f.getGlyphID(g))[0]
    for c, u in comp.items():
        if c in allcm and u in allcm:
            vc, vu = rvec(c), rvec(u)
            if vc is not None and vu is not None and np.linalg.norm(vc - vu) > 0.05:
                dR[c] = vc - vu
    print("合成字体缺的兼容字", len(comp), "参考字体里与本字有差别的", len(dR))
    used = {k.split(":", 1)[1] + "@" + k.split(":", 1)[0] for k in cfg.get("encode_map", {})}
    best = {}
    sources = [(cfg["base"]["tag"], cfg["base"]["path"])] + [(l["tag"], l["path"]) for l in cfg["layers"]]
    by_u = {}
    for c in dR:
        by_u.setdefault(comp[c], []).append(c)
    for tag, path in sources:
        f = TTFont(path)
        if f.flavor:
            f.flavor = None; path = os.path.join(outdir, f"_{tag}.ttf"); f.save(path); f = TTFont(path)
        cmap = f.getBestCmap(); enc = set(cmap.values()); hm = f["hmtx"]; r = Raster(path)
        us = [u for u in by_u if u in cmap]
        if not us:
            continue
        UV = np.stack([r.vec(f.getGlyphID(cmap[u]))[0] for u in us])
        for gid, g in enumerate(f.getGlyphOrder()):
            if g in enc or gid == 0 or f"{g}@{tag}" in used or hm[g][0] != 1000:
                continue
            v, size = r.vec(gid)
            if v is None or size < 60:
                continue
            su = UV @ v
            for i in np.nonzero((su >= lo) & (su < hi))[0]:
                dJ = v - UV[i]
                for c in by_u[us[i]]:
                    k = float(dJ @ dR[c] / np.linalg.norm(dJ) / np.linalg.norm(dR[c]))
                    if k >= corr and (c not in best or k > best[c]["方向相关"]):
                        best[c] = {"cp": f"U+{c:04X}", "char": chr(c), "unified": chr(us[i]), "src": tag,
                                   "glyph": g, "与本字相似": round(float(su[i]), 3), "方向相关": round(k, 3)}
        print(tag, "完成", flush=True)
    out = sorted(best.values(), key=lambda x: -x["方向相关"])
    json.dump(out, open(os.path.join(outdir, "compat_candidates.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=0)
    print("候选", len(out))


if __name__ == "__main__":
    main(*sys.argv[1:5])
