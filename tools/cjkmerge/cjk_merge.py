#!/usr/bin/env python3
"""cjk_merge — 多源 CJK 可变字体按码位逐级补字，合成单个 VF。

用法:  python3 cjk_merge.py config.json

流程（每一步都由 config 控制）:
  1. 读取 base（主字形来源）和若干 layers（按顺序的补字来源）。
  2. 每个来源先"补编码"：encode_map 里登记的未编码字形加入该来源的 cmap。
  3. 每个来源再按规则剔除不该由它提供的字：
       never_from      指定字符不从指定来源取（如 关复门 不取日系）
       skip_narrow_han 汉字 advance < 阈值×UPM 视为半宽/残缺，跳过
       force           指定字符强制取自某来源
       remap           UCS重複漢字：来源在 A 上放的其实是 B 的字形，就把它移到 B，
                       A 交给下一个来源（{"JP:U+5668": "U+20F96"}，由 fix_duplicates.py 产出）
       alias           多码位共用一个字形：{"U+B": "U+A"}，B 没有字形时直接指向 A 的字形
       drop_emoji      去掉所有 Emoji=Yes 的码位（keep 列表除外），让系统 emoji 字体显示
  4. 按 base → layers 顺序，每个码位取第一个可用来源；字形连同
     glyf / hmtx / vmtx / gvar 及复合字形部件一起拷入。
  5. 来源 avar 与 base 不同时，给拷入的字形补中间 gvar 元组，使
     每个字重值下的字形与原字体一致。
  5b. 字面对齐（layer 的 "fit": "auto"）: 用双方共有的统一汉字量出补字来源与 base 的
     字面框比例和中心偏移（中位数），补入字形按此缩放平移，使大小与 base 一致。
  6. quotes: 引号上下文切换（calt）。西文中保持比例宽，紧邻 CJK 时换全角。
  7. 改名、重算 OS/2、写出，并输出逐字来源报告。

扩展点（预留，尚未实现）:
  - layers 可以继续追加"更大的相似字库"做多层补字。
  - transform 钩子: config["transform"] = "模块名:函数名"，签名
    fn(glyph, source_tag, glyph_name, ctx) -> glyph，用于对补入字形做几何
    调整（字面、重心、笔画粗细等），让补字与主字体协调。
"""
import copy
import importlib
import json
import sys
import unicodedata
from collections import Counter

from fontTools.ttLib import TTFont
from fontTools.ttLib.tables import otTables as ot
from fontTools.ttLib.tables.TupleVariation import TupleVariation
from fontTools.ttLib.tables._c_m_a_p import CmapSubtable


# ---------------------------------------------------------------- helpers
def is_han(cp):
    return (0x3400 <= cp <= 0x4DBF or 0x4E00 <= cp <= 0x9FFF or 0xF900 <= cp <= 0xFAFF
            or 0x20000 <= cp <= 0x3FFFF)


def is_cjk_context(cp):
    """Characters after/before which a quote should become full-width."""
    return (is_han(cp) or 0x2E80 <= cp <= 0x2FDF or 0x3000 <= cp <= 0x30FF
            or 0x3100 <= cp <= 0x31FF or 0x3200 <= cp <= 0x33FF or 0xAC00 <= cp <= 0xD7AF
            or 0x1100 <= cp <= 0x11FF or 0x3130 <= cp <= 0x318F
            or 0xFE10 <= cp <= 0xFE1F or 0xFE30 <= cp <= 0xFE4F
            or 0xFF01 <= cp <= 0xFF60 or 0xFFE0 <= cp <= 0xFFE6)


def piecewise(segs, x):
    pts = sorted(segs.items())
    for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
        if x0 <= x <= x1:
            return y0 if x1 == x0 else y0 + (y1 - y0) * (x - x0) / (x1 - x0)
    raise ValueError(x)


def avar_of(font):
    if "avar" in font:
        return dict(font["avar"].segments["wght"])
    return {-1.0: -1.0, 0.0: 0.0, 1.0: 1.0}


# ------------------------------------------------------------ avar fixing
class AvarFixer:
    """Re-express a source glyph's gvar (in source-avar space) in base-avar space.

    Desired outline at base-normalised n is the source outline at f(n), where
    f = src_avar ∘ base_avar⁻¹. f is piecewise linear; adding hat-shaped
    correction tuples at f's breakpoints makes the result exact there and
    linear in between (= exact everywhere when both sides are linear)."""

    def __init__(self, src_avar, dst_avar):
        inv_dst = {v: k for k, v in dst_avar.items()}
        self.f = lambda n: piecewise(src_avar, piecewise(inv_dst, n))
        bps = {round(v, 5) for v in dst_avar.values()}
        bps |= {round(piecewise(dst_avar, u), 5) for u in src_avar}
        bps = sorted(bps)
        self.neg = [b for b in bps if b <= 0]
        self.pos = [b for b in bps if b >= 0]
        self.active = any(abs(self.f(b) - b) > 1e-4 for b in bps)

    @staticmethod
    def _delta_at(vars_, s, npts):
        tot = [[0.0, 0.0] for _ in range(npts)]
        for v in vars_:
            st, pk, en = v.axes["wght"]
            if pk == 0:
                continue
            if s == pk:
                w = 1.0
            elif s <= st or s >= en:
                w = 0.0
            elif s < pk:
                w = (s - st) / (pk - st)
            else:
                w = (en - s) / (en - pk)
            if w:
                for i, d in enumerate(v.coordinates):
                    if d is not None:
                        tot[i][0] += d[0] * w
                        tot[i][1] += d[1] * w
        return tot

    def __call__(self, vars_):
        if not self.active or not vars_:
            return vars_
        if any(d is None for v in vars_ for d in v.coordinates):
            return vars_  # IUP-optimised deltas: leave untouched
        npts = len(vars_[0].coordinates)
        extra = []
        for side in (self.neg, self.pos):
            for i in range(1, len(side) - 1):
                b = side[i]
                want = self._delta_at(vars_, self.f(b), npts)
                have = self._delta_at(vars_, b, npts)
                corr = [(round(w[0] - h[0]), round(w[1] - h[1])) for w, h in zip(want, have)]
                if any(x or y for x, y in corr):
                    extra.append(TupleVariation({"wght": (side[i - 1], b, side[i + 1])}, corr))
        return vars_ + extra


def han_fit(base, src):
    """(scale, (cx_src, cy_src), (cx_base, cy_base)) so that src Han glyphs match base face size."""
    import statistics as st
    bc, sc = base.getBestCmap(), src.getBestCmap()
    common = [c for c in bc if 0x4E00 <= c <= 0x9FFF and c in sc][:4000]
    if len(common) < 200:
        return None

    def bb(f, cm, c):
        g = f["glyf"][cm[c]]
        g.recalcBounds(f["glyf"])
        return g.xMin, g.yMin, g.xMax, g.yMax
    rs, bcx, bcy, scx, scy = [], [], [], [], []
    for c in common:
        a, b = bb(base, bc, c), bb(src, sc, c)
        if a[3] - a[1] > 0 and b[3] - b[1] > 0:
            rs.append(((a[2] - a[0]) + (a[3] - a[1])) / ((b[2] - b[0]) + (b[3] - b[1])))
            bcx.append((a[0] + a[2]) / 2); bcy.append((a[1] + a[3]) / 2)
            scx.append((b[0] + b[2]) / 2); scy.append((b[1] + b[3]) / 2)
    return st.median(rs), (st.median(scx), st.median(scy)), (st.median(bcx), st.median(bcy))


def apply_fit(g, variations, fit, glyf):
    """Scale glyph g (and its gvar deltas) about the fitted centres. Phantom-point deltas untouched."""
    s, (sx, sy), (bx, by) = fit
    kx, ky = bx - s * sx, by - s * sy
    if g.isComposite():
        for c in g.components:
            m = c.transform if hasattr(c, "transform") else [[1, 0], [0, 1]]
            mk = (m[0][0] * kx + m[1][0] * ky, m[0][1] * kx + m[1][1] * ky)
            c.x = round(s * c.x + kx - mk[0])
            c.y = round(s * c.y + ky - mk[1])
    elif g.numberOfContours > 0:
        from fontTools.ttLib.tables._g_l_y_f import GlyphCoordinates
        g.coordinates = GlyphCoordinates([(round(s * x + kx), round(s * y + ky)) for x, y in g.coordinates])
    for tv in variations:
        n = len(tv.coordinates)
        tv.coordinates = [d if (d is None or i >= n - 4) else (round(d[0] * s), round(d[1] * s))
                          for i, d in enumerate(tv.coordinates)]
    return g


# ----------------------------------------------------------------- merger
class Merger:
    def __init__(self, cfg):
        self.cfg = cfg
        self.base_tag = cfg["base"]["tag"]
        self.base = TTFont(cfg["base"]["path"])
        self.sources = [(self.base_tag, self.base)]
        for layer in cfg.get("layers", []):
            self.sources.append((layer["tag"], TTFont(layer["path"])))
        self.order = list(self.base.getGlyphOrder())
        self.dst_avar = avar_of(self.base)
        self.out_cmap = {}
        self.origin = {}  # cp -> source tag
        self.skipped = Counter()
        self.skip_log = {}
        self.transform = None
        if cfg.get("transform"):
            mod, fn = cfg["transform"].split(":")
            self.transform = getattr(importlib.import_module(mod), fn)

    # -- step 2: extra encodings for unencoded glyphs
    def source_cmap(self, tag, font):
        cmap = dict(font.getBestCmap())
        for key, cp in self.cfg.get("encode_map", {}).items():
            src, gname = key.split(":", 1)
            if src == tag and gname in font.getGlyphOrder():
                cp = int(cp.replace("U+", ""), 16) if isinstance(cp, str) else cp
                cmap.setdefault(cp, gname)
        for key, dst in self.cfg.get("rules", {}).get("remap", {}).items():
            src, a = key.split(":", 1)
            a, b = int(a.replace("U+", ""), 16), int(dst.replace("U+", ""), 16)
            if src == tag and a in cmap:
                cmap[b] = cmap.pop(a)
        return cmap

    def emoji_set(self):
        rule = self.cfg.get("rules", {}).get("drop_emoji")
        if not rule:
            return set()
        here = __import__("os").path.dirname(__import__("os").path.abspath(__file__))
        out = set()
        for line in open(__import__("os").path.join(here, rule["data"]), encoding="utf-8"):
            line = line.split("#")[0].strip()
            if not line:
                continue
            rng, prop = (x.strip() for x in line.split(";"))
            if prop != "Emoji":
                continue
            a, _, b = rng.partition("..")
            out.update(range(int(a, 16), int(b or a, 16) + 1))
        return out - {ord(c) for c in rule.get("keep", "")}

    # -- step 3: per-source eligibility
    def range_winners(self):
        """rules.range_order: 指定码位范围改用另一套来源优先级（如希腊/西里尔取比例宽来源）。"""
        win = {}
        cmaps = {tag: self.source_cmap(tag, f) for tag, f in self.sources}
        for rule in self.cfg.get("rules", {}).get("range_order", []):
            for a, b in rule["ranges"]:
                a, b = int(str(a), 0), int(str(b), 0)
                for cp in range(a, b + 1):
                    for tag in rule["order"]:
                        if cp in cmaps.get(tag, {}):
                            win[cp] = tag
                            break
        return win

    def allowed(self, tag, font, cp, gname):
        rules = self.cfg.get("rules", {})
        if cp in self.emoji:
            return False, "emoji"
        force = {ord(c): t for c, t in rules.get("force", {}).items()}
        if cp in force:
            return force[cp] == tag, "force"
        if cp in self.winners:
            return self.winners[cp] == tag, "range_order"
        if chr(cp) in rules.get("never_from", {}).get(tag, ""):
            return False, "never_from"
        thr = rules.get("skip_narrow_han")
        if thr and is_han(cp):
            upem = font["head"].unitsPerEm
            if font["hmtx"][gname][0] < thr * upem:
                return False, "narrow"
        return True, None

    def run(self):
        glyf, hmtx, vmtx, gvar = (self.base[t] for t in ("glyf", "hmtx", "vmtx", "gvar"))
        self.winners = self.range_winners()
        self.emoji = self.emoji_set()
        for tag, font in self.sources:
            cmap = self.source_cmap(tag, font)
            is_base = font is self.base
            fixer = None if is_base else AvarFixer(avar_of(font), self.dst_avar)
            renamed = {}
            layer_cfg = next((l for l in self.cfg.get("layers", []) if l["tag"] == tag), {})
            fit = None
            if not is_base and layer_cfg.get("fit") == "auto":
                fit = han_fit(self.base, font)
                if fit and abs(fit[0] - 1) < 0.01 and abs(fit[1][0] - fit[2][0]) < 5 and abs(fit[1][1] - fit[2][1]) < 5:
                    fit = None
                if fit:
                    print(f"{tag}: 字面对齐 缩放 {fit[0]:.4f} 中心 {fit[1]} -> {fit[2]}", flush=True)

            def copy_glyph(gn, tag=tag, font=font, fixer=fixer, renamed=renamed, fit=fit):
                if gn in renamed:
                    return renamed[gn]
                new = f"{tag.lower()}.{gn}"
                renamed[gn] = new
                g = copy.deepcopy(font["glyf"][gn])
                if g.isComposite():
                    for c in g.components:
                        c.glyphName = copy_glyph(c.glyphName)
                if self.transform:
                    g = self.transform(g, tag, gn, self)
                vars_ = fixer(copy.deepcopy(font["gvar"].variations.get(gn, [])))
                adv, lsb = font["hmtx"].metrics[gn]
                vadv, tsb = font["vmtx"].metrics[gn]
                if fit:
                    g0 = copy.deepcopy(font["glyf"][gn]); g0.recalcBounds(font["glyf"])
                    top0 = getattr(g0, "yMax", 0)
                    g = apply_fit(g, vars_, fit, glyf)
                glyf.glyphs[new] = g
                if fit:
                    g.recalcBounds(glyf)
                    if hasattr(g, "xMin"):
                        lsb = g.xMin
                        tsb = tsb - (g.yMax - top0)
                self.order.append(new)
                hmtx.metrics[new] = (adv, lsb)
                vmtx.metrics[new] = (vadv, tsb)
                gvar.variations[new] = vars_
                return new

            n = 0
            for cp, gn in sorted(cmap.items()):
                if cp in self.out_cmap:
                    continue
                ok, why = self.allowed(tag, font, cp, gn)
                if not ok:
                    self.skipped[(tag, why)] += 1
                    self.skip_log.setdefault(tag, {}).setdefault(why, []).append(chr(cp))
                    continue
                self.out_cmap[cp] = gn if is_base else copy_glyph(gn)
                self.origin[cp] = tag
                n += 1
            print(f"{tag}: {n} 个码位" + (" (avar 已校正)" if fixer and fixer.active else ""), flush=True)

        for b, a in self.cfg.get("rules", {}).get("alias", {}).items():
            a, b = int(a[2:], 16), int(b[2:], 16)
            if b not in self.out_cmap and a in self.out_cmap:
                self.out_cmap[b] = self.out_cmap[a]
                self.origin[b] = self.origin[a] + "(共用" + chr(a) + ")"
        self.finish_glyph_order()
        if self.cfg.get("quotes"):
            self.add_quote_calt()
        self.write_cmap()
        self.write_names()
        self.base.flavor = None  # sources may be WOFF; always write plain sfnt
        self.base.save(self.cfg["out"])
        return self.report()

    def finish_glyph_order(self):
        nbase = len(self.base.getGlyphOrder())
        rev = {}
        for cp, gn in self.out_cmap.items():
            rev[gn] = min(cp, rev.get(gn, cp))
        added = sorted(self.order[nbase:], key=lambda g: (rev.get(g, 0x7FFFFFFF), g))
        self.order = self.order[:nbase] + added
        assert len(self.order) <= 65535, len(self.order)
        self.base.setGlyphOrder(self.order)
        self.base["glyf"].glyphOrder = self.order

    # -- step 6: contextual full-width quotes
    def add_quote_calt(self):
        qcfg = self.cfg["quotes"]
        gsub = self.base["GSUB"].table
        cmap = self.out_cmap
        full = {}
        # full-width forms: from the base font's own 'fwid' feature
        fwid = {}
        for fr in gsub.FeatureList.FeatureRecord:
            if fr.FeatureTag != "fwid":
                continue
            for li in fr.Feature.LookupListIndex:
                for st in gsub.LookupList.Lookup[li].SubTable:
                    if st.LookupType == 7 or hasattr(st, "ExtSubTable"):
                        st = st.ExtSubTable
                    fwid.update(getattr(st, "mapping", {}) or {})
        for ch in qcfg.get("chars", "‘’“”"):
            g = cmap.get(ord(ch))
            if g and g in fwid:
                full[g] = fwid[g]
        if not full:
            print("quotes: base has no fwid quotes, skipped")
            return
        qin = sorted(full, key=self.base.getGlyphID)
        cjk = {g for cp, g in cmap.items() if is_cjk_context(cp)} | set(full.values())
        cjk = sorted(cjk, key=self.base.getGlyphID)

        def cov(glyphs):
            c = ot.Coverage(); c.glyphs = list(glyphs); return c

        single = ot.SingleSubst(); single.mapping = full; single.Format = 2
        lk_single = ot.Lookup(); lk_single.LookupType = 1; lk_single.LookupFlag = 0
        lk_single.SubTable = [single]; lk_single.SubTableCount = 1
        gsub.LookupList.Lookup.append(lk_single)
        single_idx = len(gsub.LookupList.Lookup) - 1

        def chain(back, inp, ahead):
            st = ot.ChainContextSubst(); st.Format = 3
            st.BacktrackCoverage = [cov(x) for x in back]; st.BacktrackGlyphCount = len(back)
            st.InputCoverage = [cov(x) for x in inp]; st.InputGlyphCount = len(inp)
            st.LookAheadCoverage = [cov(x) for x in ahead]; st.LookAheadGlyphCount = len(ahead)
            rec = ot.SubstLookupRecord(); rec.SequenceIndex = 0; rec.LookupListIndex = single_idx
            st.SubstLookupRecord = [rec]; st.SubstCount = 1
            return st

        lk = ot.Lookup(); lk.LookupType = 6; lk.LookupFlag = 0
        lk.SubTable = [
            chain([cjk], [qin], []),            # 汉字“  汉字”
            chain([], [qin], [cjk]),            # “汉字  ”汉字
            chain([], [qin], [qin, cjk]),       # ”“汉字 等连续引号
        ]
        lk.SubTableCount = len(lk.SubTable)
        gsub.LookupList.Lookup.append(lk)
        calt_lookup = len(gsub.LookupList.Lookup) - 1
        gsub.LookupList.LookupCount = len(gsub.LookupList.Lookup)

        recs = gsub.FeatureList.FeatureRecord
        calt = [fr for fr in recs if fr.FeatureTag == "calt"]
        if calt:
            for fr in calt:
                fr.Feature.LookupListIndex.append(calt_lookup)
                fr.Feature.LookupCount += 1
            return
        fr = ot.FeatureRecord(); fr.FeatureTag = "calt"
        fr.Feature = ot.Feature(); fr.Feature.FeatureParams = None
        fr.Feature.LookupListIndex = [calt_lookup]; fr.Feature.LookupCount = 1
        recs.append(fr)
        # keep FeatureList sorted by tag, remap LangSys indices
        old = list(recs)
        recs.sort(key=lambda r: r.FeatureTag)  # stable sort
        pos = {id(r): i for i, r in enumerate(recs)}
        remap = {i: pos[id(r)] for i, r in enumerate(old)}
        new_idx = pos[id(fr)]
        for sr in gsub.ScriptList.ScriptRecord:
            langs = [sr.Script.DefaultLangSys] + [l.LangSys for l in sr.Script.LangSysRecord]
            for ls in langs:
                if ls is None:
                    continue
                ls.FeatureIndex = sorted(remap[i] for i in ls.FeatureIndex) + [new_idx]
                ls.FeatureIndex.sort()
                ls.FeatureCount = len(ls.FeatureIndex)
        gsub.FeatureList.FeatureCount = len(recs)

    def write_cmap(self):
        uvs = [t for t in self.base["cmap"].tables if t.format == 14]
        base_cmap = self.base.getBestCmap()
        for t in uvs:  # keep IVS pointing at the base glyph even where the base cmap entry moved away
            for sel, lst in list(t.uvsDict.items()):
                new = []
                for cp, g in lst:
                    if cp in self.emoji:
                        continue
                    if g is None and self.out_cmap.get(cp) != base_cmap.get(cp):
                        g = base_cmap.get(cp)
                    if g is None and cp not in self.out_cmap:
                        continue
                    new.append((cp, g))
                t.uvsDict[sel] = new
        bmp = {k: v for k, v in self.out_cmap.items() if k <= 0xFFFF}
        tabs = []
        for pid, eid, fmt, m in ((0, 3, 4, bmp), (0, 4, 12, self.out_cmap),
                                 (3, 1, 4, bmp), (3, 10, 12, self.out_cmap)):
            t = CmapSubtable.newSubtable(fmt)
            t.platformID, t.platEncID, t.language, t.cmap = pid, eid, 0, dict(m)
            tabs.append(t)
        self.base["cmap"].tables = tabs + uvs

    def write_names(self):
        n = self.cfg["names"]
        nm = self.base["name"]
        nm.names = [r for r in nm.names if r.platformID == 3 and r.nameID not in
                    (1, 2, 3, 4, 5, 6, 16, 17, 21, 22, 25)]
        for lang, fam in ((0x409, n["family_en"]), (0x804, n["family_zh"])):
            for nid, val in ((1, fam), (2, "Regular"), (4, fam), (16, fam), (17, "Regular")):
                nm.setName(val, nid, 3, 1, lang)
        nm.setName(f"{n['ps_name']};{n['version']}", 3, 3, 1, 0x409)
        nm.setName(f"Version {n['version']}", 5, 3, 1, 0x409)
        nm.setName(n["ps_name"], 6, 3, 1, 0x409)
        nm.setName(n["ps_name"], 25, 3, 1, 0x409)
        for inst in self.base["fvar"].instances:
            inst.postscriptNameID = 0xFFFF
        os2 = self.base["OS/2"]
        os2.recalcUnicodeRanges(self.base)
        os2.ulCodePageRange1 |= sum(1 << b for b in (17, 18, 19, 20, 21))
        os2.usFirstCharIndex = min(self.out_cmap)
        os2.usLastCharIndex = min(max(self.out_cmap), 0xFFFF)
        self.base["head"].fontRevision = float(n["version"])
        self.base["maxp"].recalc(self.base)

    def report(self):
        by = Counter(self.origin.values())
        rep = {
            "总码位": len(self.out_cmap),
            "总字形": len(self.order),
            "各来源码位": dict(by),
            "跳过": {f"{t}/{w}": c for (t, w), c in self.skipped.items()},
            "跳过明细": {t: {w: "".join(v) if len(v) < 400 else f"{len(v)} 个" for w, v in d.items()}
                     for t, d in self.skip_log.items()},
        }
        with open(self.cfg["out"] + ".origin.tsv", "w", encoding="utf-8") as f:
            f.write("codepoint\tchar\tsource\n")
            for cp in sorted(self.origin):
                f.write(f"U+{cp:04X}\t{chr(cp)}\t{self.origin[cp]}\n")
        return rep


if __name__ == "__main__":
    cfg = json.load(open(sys.argv[1], encoding="utf-8"))
    print(json.dumps(Merger(cfg).run(), ensure_ascii=False, indent=1))
