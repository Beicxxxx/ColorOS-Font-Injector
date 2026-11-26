#!/usr/bin/env python3
"""ext_fill — 从外部西文可变字体补字，并按基础字体的几何数据"和谐化"。

和谐化在基础字体的每个母版（wght 归一化 -1 / 0 / 0.5 / 1）分别做:
  * 笔画粗细: 测基础字体 'l' 的竖笔宽，在外部字体里找竖笔宽相同的 wght
    （超出外部字体字重范围时按相邻两个实例线性外推）。
  * 竖向尺寸: 小写按 x 高、大写按大写高、其余按大写高缩放到基础字体。
  * 横向比例: 按参照字母（小写 n、大写 H）的字宽比缩放，侧边距同比缩放。
  * 缩放会改变笔画粗细，所以先按缩放比反算目标竖笔宽再找 wght。
得到的四个母版轮廓写成一个简单字形 + gvar，字重轴与基础字体完全一致。
先补附加符号（让 synth_western 用基础字体自己的字母去拼），再补整字。
"""
import copy
import unicodedata as ud

from fontTools.pens.recordingPen import DecomposingRecordingPen
from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools.ttLib import TTFont
from fontTools.ttLib.tables.TupleVariation import TupleVariation
from fontTools.varLib.instancer import instantiateVariableFont

from synth_western import MASTERS, REGIONS

LOWER_REF, UPPER_REF = "n", "H"


class ExtSource:
    def __init__(self, path, fixed=None):
        self.path, self.fixed = path, fixed
        f = TTFont(path)
        if fixed:
            f = instantiateVariableFont(f, fixed)
        self.vf = f
        ax = {a.axisTag: a for a in f["fvar"].axes}["wght"]
        self.wmin, self.wmax = ax.minValue, ax.maxValue
        self._inst = {}
        self.cmap = f.getBestCmap()
        self.stem_table = [(w, self.stem(w)) for w in range(int(self.wmin), int(self.wmax) + 1, 50)]

    def inst(self, w):
        w = round(w, 1)
        if w not in self._inst:
            self._inst[w] = instantiateVariableFont(copy.deepcopy(self.vf), {"wght": w})
        return self._inst[w]

    def bbox(self, w, ch):
        f = self.inst(w)
        g = f["glyf"][self.cmap[ord(ch)]]
        g.recalcBounds(f["glyf"])
        return g.xMin, g.yMin, g.xMax, g.yMax

    def stem(self, w):
        b = self.bbox(w, "l")
        return b[2] - b[0]

    def weight_for_stem(self, s):
        """Return (w_a, w_b, t): outline = P(w_a) + t*(P(w_b)-P(w_a)); t may be <0 or >1 (extrapolate)."""
        tab = self.stem_table
        if s <= tab[0][1]:
            (w0, s0), (w1, s1) = tab[0], tab[1]
        elif s >= tab[-1][1]:
            (w0, s0), (w1, s1) = tab[-2], tab[-1]
        else:
            for (w0, s0), (w1, s1) in zip(tab, tab[1:]):
                if s0 <= s <= s1:
                    break
        t = (s - s0) / (s1 - s0) if s1 != s0 else 0
        # limited extrapolation only: far beyond the source's weight range counters close up
        if s >= tab[-1][1]:
            t = min(t, 1.15)
        if s <= tab[0][1]:
            t = max(t, -0.15)
        return w0, w1, t

    def weight_at(self, s):
        """Single (clamped) wght value whose stem is closest to s — used for metrics/kerning."""
        w0, w1, t = self.weight_for_stem(s)
        return min(max(w0 + (w1 - w0) * t, self.wmin), self.wmax)

    def outline(self, w0, w1, t, ch):
        """Decomposed point list + contour ends + advance at the (inter/extra)polated weight."""
        res = []
        for w in (w0, w1):
            f = self.inst(w)
            gs = f.getGlyphSet()
            gn = self.cmap[ord(ch)]
            rp = DecomposingRecordingPen(gs)
            gs[gn].draw(rp)
            tp = TTGlyphPen(None)
            rp.replay(tp)
            g = tp.glyph()
            coords = list(g.coordinates) if g.numberOfContours > 0 else []
            res.append((coords, g, f["hmtx"][gn][0]))
        (c0, g0, a0), (c1, g1, a1) = res
        pts = [(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t) for (x0, y0), (x1, y1) in zip(c0, c1)]
        return pts, g0, a0 + (a1 - a0) * t


class ExtFill:
    def __init__(self, synth, ext):
        self.s = synth
        self.e = ext

    def scales(self, ch, n):
        m = self.s.metrics(n)
        cat = ud.category(ch)
        # reference proportions measured at a middle weight of the external font
        mid = (self.e.wmin + self.e.wmax) / 2 if self.e.wmin < 400 < self.e.wmax else self.e.wmin
        e_xh = self.e.bbox(400, "x")[3]
        e_cap = self.e.bbox(400, "H")[3]
        if cat == "Ll" or cat == "Lm" or ch in "ςϐϑϕϖ":
            sy = m["xh"] / e_xh
            ref = LOWER_REF
        else:
            sy = m["cap"] / e_cap
            ref = UPPER_REF
        if ud.category(ch).startswith("M"):  # combining marks: follow lowercase proportions
            sy = m["xh"] / e_xh
            ref = LOWER_REF
        our_w = self.s.adv(self.s.cmap[ord(ref)], n)
        ext_w = self.e.inst(400)["hmtx"][self.e.cmap[ord(ref)]][0]
        sx = our_w / ext_w
        return sx, sy

    def master_outline(self, ch, n):
        sx, sy = self.scales(ch, n)
        target_stem = self.s.metrics(n)["stem"] / sx
        w0, w1, t = self.e.weight_for_stem(target_stem)
        pts, g, adv = self.e.outline(w0, w1, t, ch)
        return [(x * sx, y * sy) for x, y in pts], g, adv * sx

    def add(self, cp):
        ch = chr(cp)
        outs = {n: self.master_outline(ch, n) for n in MASTERS}
        pts0, g, adv0 = outs[0.0]
        if any(len(outs[n][0]) != len(pts0) for n in MASTERS):
            return False
        name = f"ext.uni{cp:04X}" if cp <= 0xFFFF else f"ext.u{cp:05X}"
        ng = copy.deepcopy(g)
        from fontTools.ttLib.tables._g_l_y_f import GlyphCoordinates
        if ng.numberOfContours > 0:
            ng.coordinates = GlyphCoordinates([(round(x), round(y)) for x, y in pts0])
            ng.program = None
            from fontTools.ttLib.tables import ttProgram
            ng.program = ttProgram.Program(); ng.program.fromBytecode(b"")
        s = self.s
        s.glyf.glyphs[name] = ng
        ng.recalcBounds(s.glyf)
        s.order.append(name)
        lsb = ng.xMin if ng.numberOfContours > 0 else 0
        s.hmtx.metrics[name] = (round(adv0), lsb)
        s.vmtx.metrics[name] = (1000, 0)
        vars_ = []
        for n, region in REGIONS.items():
            pn, _, an = outs[n]
            d = [(round(a[0]) - round(b[0]), round(a[1]) - round(b[1])) for a, b in zip(pn, pts0)]
            d += [(0, 0), (round(an) - round(adv0), 0), (0, 0), (0, 0)]
            if any(x != (0, 0) for x in d):
                vars_.append(TupleVariation({"wght": region}, d))
        s.gvar.variations[name] = vars_
        s.cmap[cp] = name
        s.new[cp] = name
        return True


def needed_marks(synth, targets):
    marks = set()
    for cp in targets:
        if cp in synth.cmap:
            continue
        for m in ud.normalize("NFD", chr(cp))[1:]:
            mc = ord(m)
            if synth.mark_glyph(mc) is None:
                marks.add(mc)
    return sorted(marks)


PARTNERS = ("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
            ".,;:!?-()[]{}'\"‘’“”/«»")


def add_ext_kerning(synth, ext, imported, min_abs=8):
    """Kern pairs for letters taken from the external font, varying with the weight axis.
    For every master of the base font the external font is shaped at the weight whose stem
    matches (same rule as the outlines), kern-on minus kern-off advance, scaled by the glyph's
    horizontal fit. Default value + deltas go into GDEF VarStore (regions shared with gvar)."""
    import uharfbuzz as hb
    from fontTools.otlLib.builder import buildPairPosGlyphs, buildLookup
    from fontTools.ttLib.tables import otTables as ot
    from fontTools.varLib.builder import buildVarData
    from synth_western import MASTERS, REGIONS
    face = hb.Face(hb.Blob.from_file_path(ext.path))
    font = hb.Font(face)
    fill = ExtFill(synth, ext)
    # external weight per master (stem-matched, lowercase reference)
    wts = {}
    for n in MASTERS:
        sx, _ = fill.scales("n", n)
        wts[n] = ext.weight_at(synth.metrics(n)["stem"] / sx)
    fixed = dict(ext.fixed or {})

    def kern(text, w):
        font.set_variations({**fixed, "wght": w})
        out = []
        for k in (True, False):
            b = hb.Buffer(); b.add_str(text); b.guess_segment_properties()
            hb.shape(font, b, {"kern": k})
            out.append([p.x_advance for p in b.glyph_positions])
        return out[0][0] - out[1][0] if len(out[0]) == 2 else 0
    our = synth.cmap
    partners = [c for c in PARTNERS if ord(c) in our and ord(c) in ext.cmap]
    imp = [cp for cp in imported if cp in our and cp in ext.cmap]
    sx = {(cp, n): fill.scales(chr(cp), n)[0] for cp in imp for n in MASTERS}
    pairs = {}
    for cp in imp:
        a = chr(cp)
        for p in partners + [chr(c) for c in imp]:
            for l, r in ((a, p), (p, a)):
                if (l, r) in pairs:
                    continue
                v0 = kern(l + r, wts[0.0])
                if abs(v0 * sx[(cp, 0.0)]) < min_abs:
                    pairs[(l, r)] = None
                    continue
                pairs[(l, r)] = {n: round((v0 if n == 0.0 else kern(l + r, wts[n])) * sx[(cp, n)]) for n in MASTERS}
    pairs = {k: v for k, v in pairs.items() if v}
    if not pairs:
        return 0
    add_var_pairs(synth, {(our[ord(l)], our[ord(r)]): v for (l, r), v in pairs.items()})
    return len(pairs)


def add_var_pairs(synth, pairs):
    """pairs {(left glyph, right glyph): {master: value}} -> one new PairPos lookup in every
    'kern' feature; values vary along wght through GDEF VarStore (regions as in synth REGIONS)."""
    from fontTools.otlLib.builder import buildPairPosGlyphs, buildLookup
    from fontTools.ttLib.tables import otTables as ot
    from fontTools.varLib.builder import buildVarData
    from synth_western import REGIONS
    if not pairs:
        return 0
    store = synth.f["GDEF"].table.VarStore
    regions = []
    for reg in REGIONS.values():
        for i, r in enumerate(store.VarRegionList.Region):
            ax = r.VarRegionAxis[0]
            if (ax.StartCoord, ax.PeakCoord, ax.EndCoord) == reg:
                regions.append(i)
                break
        else:
            raise ValueError("region not in VarStore")
    keys = list(pairs)
    store.VarData.append(buildVarData(regions, [[pairs[k][n] - pairs[k][0.0] for n in REGIONS] for k in keys],
                                      optimize=False))
    store.VarDataCount = len(store.VarData)
    outer = len(store.VarData) - 1
    vr = {}
    for inner, k in enumerate(keys):
        rec = ot.ValueRecord()
        rec.XAdvance = pairs[k][0.0]
        dev = ot.Device()
        dev.StartSize, dev.EndSize, dev.DeltaFormat = outer, inner, 0x8000
        rec.XAdvDevice = dev
        vr[k] = (rec, None)
    gmap = {g: i for i, g in enumerate(synth.order)}
    gpos = synth.f["GPOS"].table
    gpos.LookupList.Lookup.append(buildLookup(buildPairPosGlyphs(vr, gmap)))
    gpos.LookupList.LookupCount = len(gpos.LookupList.Lookup)
    idx = len(gpos.LookupList.Lookup) - 1
    for fr in gpos.FeatureList.FeatureRecord:
        if fr.FeatureTag == "kern" and idx not in fr.Feature.LookupListIndex:
            fr.Feature.LookupListIndex.append(idx)
            fr.Feature.LookupCount = len(fr.Feature.LookupListIndex)
    return len(pairs)
