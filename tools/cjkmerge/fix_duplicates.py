#!/usr/bin/env python3
"""fix_duplicates — 找出主字体（日系）里"码位分配错"的 UCS 重複漢字。

候选对来自 data/ucs-duplicates.tsv（cjkvi duplicate-chars + Unihan kZVariant +
kSpoofingVariant，Unicode 18 草案 Unihan）。每对 A,B 是同一字（或几乎同形）的两个码位。

判断（以 器 U+5668 / 𠾖 U+20F96 为例）:
  dJ = 主字体的 A − 同一设计的其他来源（KR/TC/CN）的 A      → 日系字形"哪里不一样"
  dR = 参考字体的 B − 参考字体的 A                           → 这两个码位"本该哪里不一样"
  若 dJ 与 dR 方向一致（相关系数 >= corr）且差异足够大，就说明日系在 A 上放的是 B 的字形。
输出 rules.remap = {"JP:U+5668": "U+20F96"}，合成时 JP 的字形移到 B，A 由补字链下一个来源提供。

另一类（多码位共用一个字形）: 合成字体只有 A、没有 B，且参考字体里 A 与 B 画得几乎一样
（相似度 >= 0.98），就让 B 直接指向 A 的字形，不新增字形。输出 rules.alias = {"U+B": "U+A"}。
需要给第 4 个参数（合成后的字体）才会做这一步。

用法: python3 fix_duplicates.py config.json refdir out.json [merged.ttf]
"""
import glob
import json
import os
import sys

import numpy as np
from fontTools.ttLib import TTFont

from find_unencoded import Raster


def load_pairs(path):
    pairs = []
    for line in open(path, encoding="utf-8"):
        if line.startswith(("#", "A\t")):
            continue
        a, b, src = line.rstrip("\n").split("\t")
        pairs.append((int(a.split()[0][2:], 16), int(b.split()[0][2:], 16), src))
    return pairs


def plain(path, tmpdir, tag):
    f = TTFont(path)
    if f.flavor:
        f.flavor = None
        p = os.path.join(tmpdir, f"_{tag}.ttf")
        f.save(p)
        return p, TTFont(p)
    return path, f


def main(cfg_path, refdir, out_path, corr=0.35, min_diff=0.08):
    here = os.path.dirname(os.path.abspath(__file__))
    cfg = json.load(open(cfg_path, encoding="utf-8"))
    tmp = os.path.dirname(os.path.abspath(out_path))
    srcs = [(cfg["base"]["tag"], cfg["base"]["path"])] + [(l["tag"], l["path"]) for l in cfg["layers"]]
    fonts = []
    for tag, p in srcs:
        pp, f = plain(p, tmp, tag)
        fonts.append((tag, f, Raster(pp), f.getBestCmap()))
    refs = []
    for p in sorted(glob.glob(os.path.join(refdir, "*.ttf"))):
        f = TTFont(p, lazy=True)
        refs.append((set(f.getBestCmap()), p, f))
    rcache = {}

    def ref_vec(cp):
        for cm, p, f in refs:
            if cp in cm:
                if p not in rcache:
                    rcache[p] = Raster(p)
                return rcache[p].vec(f.getGlyphID(f.getBestCmap()[cp]))[0]
        return None

    def font_vec(i, cp):
        tag, f, r, cm = fonts[i]
        return r.vec(f.getGlyphID(cm[cp]))[0] if cp in cm else None

    base_tag, base_cmap = fonts[0][0], fonts[0][3]
    remap, log = {}, []
    for a, b, src in load_pairs(os.path.join(here, "data", "ucs-duplicates.tsv")):
        for x, y in ((a, b), (b, a)):
            jx = font_vec(0, x)
            if jx is None:
                continue
            rx, ry = ref_vec(x), ref_vec(y)
            if rx is None or ry is None:
                continue
            dR = ry - rx
            if np.linalg.norm(dR) < min_diff:
                continue  # reference draws A and B the same: nothing to tell apart
            best = None
            for i in range(1, len(fonts)):
                ox = font_vec(i, x)
                if ox is None:
                    continue
                dJ = jx - ox
                if np.linalg.norm(dJ) < min_diff:
                    c = 0.0
                else:
                    c = float(dJ @ dR / np.linalg.norm(dJ) / np.linalg.norm(dR))
                if best is None or c > best[0]:
                    best = (c, fonts[i][0], float(np.linalg.norm(dJ)))
            if best is None:
                continue
            moved = best[0] >= corr
            log.append({"A": f"U+{x:04X} {chr(x)}", "B": f"U+{y:04X} {chr(y)}", "来源": src,
                        "对照": best[1], "日系差异": round(best[2], 3), "方向相关": round(best[0], 3),
                        "日系已有B": y in base_cmap, "判定": "错配" if moved else "正常"})
            if moved:
                remap[f"{base_tag}:U+{x:04X}"] = f"U+{y:04X}"
    log.sort(key=lambda r: -r["方向相关"])
    json.dump({"remap": remap, "log": log}, open(out_path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    for l in log[:40]:
        print(l)
    print(len(log), "对在日系中出现; remap", remap)


def find_alias(merged_path, refdir, out_path, same=0.98):
    import find_unencoded
    find_unencoded.N = 48
    here = os.path.dirname(os.path.abspath(__file__))
    cm = TTFont(merged_path, lazy=True).getBestCmap()
    allcm = {}
    for p in sorted(glob.glob(os.path.join(refdir, "*.ttf"))):
        f = TTFont(p, lazy=True)
        for c, g in f.getBestCmap().items():
            allcm.setdefault(c, (p, f, g))
    rc = {}

    def rv(c):
        p, f, g = allcm[c]
        rc.setdefault(p, Raster(p))
        return rc[p].vec(f.getGlyphID(g))[0]
    alias, log = {}, []
    for a, b, src in load_pairs(os.path.join(here, "data", "ucs-duplicates.tsv")):
        for x, y in ((a, b), (b, a)):
            if x in cm and y not in cm and x in allcm and y in allcm:
                s = float(rv(x) @ rv(y))
                log.append({"有": f"U+{x:04X} {chr(x)}", "缺": f"U+{y:04X} {chr(y)}", "参考字体中相似": round(s, 3),
                            "来源": src, "判定": "共用字形" if s >= same else "字形不同，不共用"})
                if s >= same:
                    alias[f"U+{y:04X}"] = f"U+{x:04X}"
    d = json.load(open(out_path, encoding="utf-8"))
    d["alias"], d["alias_log"] = alias, log
    json.dump(d, open(out_path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print("alias", alias)


if __name__ == "__main__":
    main(*sys.argv[1:4])
    if len(sys.argv) > 4:
        find_alias(sys.argv[4], sys.argv[2], sys.argv[3])
