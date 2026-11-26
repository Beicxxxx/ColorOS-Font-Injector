#!/usr/bin/env python3
"""synth_western — 用字体自身的字形补扩展西文（拉丁 / 希腊 / 西里尔）。

用法:  python3 synth_western.py in.ttf out.ttf [--report report.json]

分三层，循环到不再有新字为止:
  1. alias      借形：同形字直接引用（Cyrillic Ѕ = Latin S 等）
  2. compose    拼合：按 Unicode 标准分解（NFD）= 基础字母 + 附加符号
  3. geometric  几何生成：旋转 180°、水平镜像、加横杠、两字拼接、撇号式软音符
生成的都是复合字形（引用原字形），所以每个字重下都和原字形一起变化；
部件位置在每个母版（wght 归一化 -1 / 0 / 0.5 / 1）分别计算，写进 gvar。
仍生成不了的字写入报告，交给外部字体补（ext_fill.py）。
"""
import json
import sys
import unicodedata as ud

from fontTools.pens.boundsPen import BoundsPen
from fontTools.ttLib import TTFont
from fontTools.ttLib.tables._g_l_y_f import Glyph, GlyphComponent
from fontTools.ttLib.tables.TupleVariation import TupleVariation
from fontTools.varLib.iup import iup_delta

MASTERS = [-1.0, 0.0, 0.5, 1.0]
REGIONS = {-1.0: (-1.0, -1.0, 0.0), 0.5: (0.0, 0.5, 1.0), 1.0: (0.5, 1.0, 1.0)}

RANGES = [(0x00A0, 0x024F), (0x0250, 0x02AF), (0x0370, 0x03FF), (0x0400, 0x052F),
          (0x1E00, 0x1EFF), (0x1F00, 0x1FFF), (0x2C60, 0x2C7F), (0xA720, 0xA7FF)]

ABOVE = {0x300, 0x301, 0x302, 0x303, 0x304, 0x306, 0x307, 0x308, 0x30A, 0x30B, 0x30C,
         0x30F, 0x311, 0x313, 0x314, 0x342, 0x344, 0x385}
# marks we can derive from other marks/glyphs: mark -> (source char, kind)
DERIVED_MARKS = {
    0x323: (0x307, "below"),   # dot below
    0x324: (0x308, "below"),   # diaeresis below
    0x325: (0x30A, "below"),   # ring below
    0x32D: (0x302, "below"),   # circumflex below
    0x32E: (0x306, "below"),   # breve below
    0x330: (0x303, "below"),   # tilde below
    0x331: (0x304, "below"),   # macron below
    0x326: (0x2C, "commabelow"),
    0x313: (0x2BC, "breath"),  # psili  = ʼ
    0x314: (0x2BB, "breath"),  # dasia  = ʻ
    0x342: (0x303, "same"),    # perispomeni ~ tilde
    0x30F: (0x300, "double"),  # double grave
    0x311: (0x306, "rot"),     # inverted breve = breve rotated
}

ALIAS = {
    # Cyrillic <- Latin / Greek homoglyphs
    "Ѕ": "S", "ѕ": "s", "І": "I", "і": "i", "Ј": "J", "ј": "j", "һ": "h", "ԁ": "d",
    "Ԛ": "Q", "ԛ": "q", "Ԝ": "W", "ԝ": "w", "Ӏ": "I", "ӏ": "l", "Ү": "Y", "Ө": "Θ",
    "Ԍ": "G", "Ƨ": None,
    # Latin <- others
    "ĸ": "к", "Đ": "Ð", "ǀ": "|", "ǃ": "!", "ʼ": "’", "Ɩ": None,
    "ϲ": "c", "Ϲ": "C", "ϳ": "j", "Ϳ": "J", "ℓ": None,
    "ә": "ə", "Ӏ": "I", "ӏ": "l",
}
ALIAS = {k: v for k, v in ALIAS.items() if v and k != v}

# geometric recipes: char -> (op, base, *params)
GEOM = {
    "ə": ("rot", "e"), "ǝ": ("rot", "e"), "ɐ": ("rot", "a"), "ɥ": ("rot", "h"),
    "ɯ": ("rot", "m"), "ɹ": ("rot", "r"), "ʇ": ("rot", "t"), "ʌ": ("rot", "v"),
    "ʍ": ("rot", "w"), "ʎ": ("rot", "y"), "ʞ": ("rot", "k"), "Ʌ": ("rot", "V"),
    "ɟ": ("rot", "f"), "ɓ": None, "Ɐ": ("rot", "A"), "Ɯ": ("rot", "M"),
    "ɔ": ("mirror", "c"), "Ɔ": ("mirror", "C"), "Є": ("mirror", "Э"), "є": ("mirror", "э"),
    "ɘ": ("mirror", "e"), "ɜ": ("mirror", "ɛ"), "ʁ": None, "Я": None,
    # bars: (op, base, y_ref, y_frac, where, width_frac)
    "đ": ("bar", "d", "xa", 0.5, "rstem", 0.55), "ħ": ("bar", "h", "xa", 0.5, "lstem", 0.55),
    "ƀ": ("bar", "b", "xa", 0.5, "lstem", 0.55), "Ħ": ("bar", "H", "cap", 0.74, "full", 1.12),
    "Ŧ": ("bar", "T", "cap", 0.45, "center", 0.62), "ŧ": ("bar", "t", "xh", 0.45, "center", 0.72),
    "Ɨ": ("bar", "I", "cap", 0.5, "center", 2.6), "ɨ": ("bar", "ı", "xh", 0.5, "center", 2.6),
    "Ƶ": ("bar", "Z", "cap", 0.5, "center", 0.8), "ƶ": ("bar", "z", "xh", 0.5, "center", 0.8),
    "Ғ": ("bar", "Г", "cap", 0.45, "lstem", 0.6), "ғ": ("bar", "г", "xh", 0.45, "lstem", 0.6),
    "Ұ": ("bar", "Y", "cap", 0.34, "center", 0.62), "ө": ("bar", "o", "xh", 0.5, "inner", 0.98),
    "Ɵ": ("bar", "O", "cap", 0.5, "inner", 0.98), "ɵ": ("bar", "o", "xh", 0.5, "inner", 0.98),
    "Ꝁ": ("bar", "K", "cap", 0.62, "lstem", 0.5),
    # two-letter joins (Л+Ь etc.): (op, first, second)
    "Љ": ("join", "Л", "Ь"), "Њ": ("join", "Н", "Ь"), "љ": ("join", "л", "ь"), "њ": ("join", "н", "ь"),
    "Ĳ": ("pair", "I", "J"), "ĳ": ("pair", "i", "j"), "Ǳ": ("pair", "D", "Z"), "ǲ": ("pair", "D", "z"),
    "ǳ": ("pair", "d", "z"), "Ǆ": ("pair", "D", "Ž"), "ǅ": ("pair", "D", "ž"), "ǆ": ("pair", "d", "ž"),
    "Ǉ": ("pair", "L", "J"), "ǈ": ("pair", "L", "j"), "ǉ": ("pair", "l", "j"),
    "Ǌ": ("pair", "N", "J"), "ǋ": ("pair", "N", "j"), "ǌ": ("pair", "n", "j"),
    "ŉ": ("pair", "ʼ", "n"), "Ŀ": ("middot", "L"), "ŀ": ("middot", "l"),
    "ȷ": ("dotless_j", "j"),
}
GEOM = {k: v for k, v in GEOM.items() if v}
APOSTROPHE_CARON = set("ďľĽť")
# Greek: breathing / diaeresis + accent are drawn as one combined mark (as in good Greek fonts),
# taken from the spacing forms U+1FBF..1FFE.
# Vietnamese tone beside the circumflex: (side, overlap into the circumflex as a fraction of its
# width, tone-mark bottom as a fraction of the circumflex height)
VIET_TONE = {0x301: ("right", 0.15, 0.55), 0x300: ("left", 0.15, 0.55), 0x309: ("right", 0.05, 0.5)}
GREEK_COMBO = {(0x313,): 0x1FBF, (0x314,): 0x1FFE,
               (0x313, 0x300): 0x1FCD, (0x313, 0x301): 0x1FCE, (0x313, 0x342): 0x1FCF,
               (0x314, 0x300): 0x1FDD, (0x314, 0x301): 0x1FDE, (0x314, 0x342): 0x1FDF,
               (0x308, 0x300): 0x1FED, (0x308, 0x301): 0x1FEE, (0x308, 0x342): 0x1FC1}
GREEK_COMBO_CPS = set(GREEK_COMBO.values())


def greek_combine(marks):
    out, i = [], 0
    while i < len(marks):
        two = tuple(marks[i:i + 2])
        if len(two) == 2 and two in GREEK_COMBO:
            out.append(GREEK_COMBO[two]); i += 2
        elif (marks[i],) in GREEK_COMBO:
            out.append(GREEK_COMBO[(marks[i],)]); i += 1
        else:
            out.append(marks[i]); i += 1
    return out


class Synth:
    def __init__(self, font):
        self.f = font
        self.glyf, self.gvar = font["glyf"], font["gvar"]
        self.hmtx, self.vmtx = font["hmtx"], font["vmtx"]
        self.cmap = dict(font.getBestCmap())
        self.new = {}
        self._cache = {}
        self.order = list(font.getGlyphOrder())
        self.gs = font.getGlyphSet()
        self.horn_ratio = None  # {"lower": (rx, ry), "upper": (rx, ry)} learnt from a reference font
        self.kern_src = {}  # new glyph -> base letter glyph whose kerning it inherits
        self.horned = set()  # glyphs whose bbox includes a horn
        self.horn_adv = None  # {"o": 0.018, "u": 0.09, ...}: advance growth of the horned letter
        # Vietnamese circumflex + tone: tone sits beside the circumflex, not on top.
        # {tone mark: (side, dx, dy)} in units of x-height, learnt from a reference font
        self.viet = None

    def drop_fullwidth(self, cps, ratio=0.6):
        """Letters that the CJK source drew in a full-width box (JIS IPA etc.): regenerate them."""
        out = []
        for cp in cps:
            gn = self.cmap.get(cp)
            if gn is None:
                continue
            adv = self.hmtx[gn][0]
            if adv < 900:
                continue
            b = self.bbox(gn, 0.0)
            if b[2] - b[0] < ratio * adv:
                del self.cmap[cp]
                out.append(cp)
        return out

    # ------------------------------------------------ geometry at masters
    def coords(self, gn, n):
        key = (gn, n)
        if key in self._cache:
            return self._cache[key]
        g = self.glyf[gn]
        if g.isComposite():
            pts = []
            offs = self.comp_offsets(gn, n)
            for c, (dx, dy) in zip(g.components, offs):
                sub = self.coords(c.glyphName, n)[0]
                t = getattr(c, "transform", [[1, 0], [0, 1]])
                for x, y in sub:
                    pts.append((x * t[0][0] + y * t[1][0] + dx, x * t[0][1] + y * t[1][1] + dy))
            adv = self.adv(gn, n)
            res = (pts, adv)
        else:
            base, ctrl = g.getCoordinates(self.glyf)[0], g.getCoordinates(self.glyf)
            pts = [list(p) for p in base]
            adv = self.adv(gn, n)
            ends = list(ctrl[1])
            for v in self.gvar.variations.get(gn, []):
                s = scalar(v.axes["wght"], n)
                if not s:
                    continue
                d = v.coordinates[:len(pts)]
                if any(x is None for x in d):
                    d = iup_delta(d, [tuple(p) for p in base], ends)
                for i, dd in enumerate(d):
                    pts[i][0] += dd[0] * s
                    pts[i][1] += dd[1] * s
            res = ([tuple(p) for p in pts], adv)
        self._cache[key] = res
        return res

    def comp_offsets(self, gn, n):
        g = self.glyf[gn]
        offs = [[c.x, c.y] for c in g.components]
        for v in self.gvar.variations.get(gn, []):
            s = scalar(v.axes["wght"], n)
            if s:
                for i in range(len(offs)):
                    d = v.coordinates[i]
                    if d:
                        offs[i][0] += d[0] * s
                        offs[i][1] += d[1] * s
        return offs

    def adv(self, gn, n):
        a = self.hmtx[gn][0]
        g = self.glyf[gn]
        npts = (len(g.components) if g.isComposite() else
                (len(g.getCoordinates(self.glyf)[0]) if g.numberOfContours else 0))
        for v in self.gvar.variations.get(gn, []):
            s = scalar(v.axes["wght"], n)
            if s and len(v.coordinates) >= npts + 2:
                d1, d0 = v.coordinates[npts + 1], v.coordinates[npts]
                if d1 and d0:
                    a += (d1[0] - d0[0]) * s
        return a

    def bbox(self, gn, n, t=None):
        pts = self.coords(gn, n)[0]
        if t:
            pts = [(x * t[0][0] + y * t[1][0], x * t[0][1] + y * t[1][1]) for x, y in pts]
        if not pts:
            return (0, 0, 0, 0)
        xs, ys = [p[0] for p in pts], [p[1] for p in pts]
        return (min(xs), min(ys), max(xs), max(ys))

    def top(self, ch, n):
        return self.bbox(self.cmap[ord(ch)], n)[3]

    def metrics(self, n):
        xh = self.top("x", n)
        cap = self.top("H", n)
        asc = self.top("l", n)
        stem = self.bbox(self.cmap[ord("l")], n)
        return dict(xh=xh, cap=cap, asc=asc, stem=stem[2] - stem[0], xa=(xh + asc) / 2)

    # ------------------------------------------------ building composites
    def add(self, cp, comps, advance_fn):
        """comps: list of (glyphname, transform or None, offset_fn(n)->(dx,dy))."""
        name = f"syn.uni{cp:04X}" if cp <= 0xFFFF else f"syn.u{cp:05X}"
        offs = {n: [fn(n) for _, _, fn in comps] for n in MASTERS}
        advs = {n: advance_fn(n) for n in MASTERS}
        g = Glyph()
        g.numberOfContours = -1
        g.components = []
        for (gname, t, _), (dx, dy) in zip(comps, offs[0.0]):
            c = GlyphComponent()
            c.glyphName = gname
            c.x, c.y = round(dx), round(dy)
            c.flags = 0x4  # ROUND_XY_TO_GRID
            if t:
                c.transform = t
            g.components.append(c)
        self.glyf.glyphs[name] = g
        self.order.append(name)
        adv0 = round(advs[0.0])
        self.hmtx.metrics[name] = (adv0, 0)
        self.vmtx.metrics[name] = (1000, 0)
        vars_ = []
        for n, region in REGIONS.items():
            deltas = [(round(a[0] - b[0]), round(a[1] - b[1])) for a, b in zip(offs[n], offs[0.0])]
            if n == 1.0:  # region (0.5,1,1): subtract contribution of (0,0.5,1) at n=1 -> 0
                pass
            dadv = round(advs[n] - advs[0.0])
            deltas += [(0, 0), (dadv, 0), (0, 0), (0, 0)]
            if any(d != (0, 0) for d in deltas):
                vars_.append(TupleVariation({"wght": region}, deltas))
        self.gvar.variations[name] = vars_
        self.cmap[cp] = name
        self._cache = {k: v for k, v in self._cache.items() if k[0] != name}
        g.recalcBounds(self.glyf)
        # hmtx lsb must equal xMin, otherwise renderers shift the glyph (phantom point pp1 != 0)
        self.hmtx.metrics[name] = (adv0, getattr(g, "xMin", 0))
        self.new[cp] = name
        return name

    def g(self, ch):
        return self.cmap.get(ord(ch)) if isinstance(ch, str) else self.cmap.get(ch)

    # ------------------------------------------------ layer 1: alias
    def do_alias(self):
        n = 0
        for ch, src in ALIAS.items():
            cp = ord(ch)
            if cp in self.cmap or not self.g(src):
                continue
            gn = self.g(src)
            self.add(cp, [(gn, None, lambda m: (0, 0))], lambda m, gn=gn: self.adv(gn, m))
            n += 1
        return n

    # ------------------------------------------------ marks
    def mark_glyph(self, mcp):
        """Return (glyphname, transform, kind) for a combining mark."""
        if mcp in GREEK_COMBO_CPS:
            return (self.cmap[mcp], None, "gcombo") if mcp in self.cmap else None
        if mcp in self.cmap:
            return self.cmap[mcp], None, "above" if mcp in ABOVE or mcp < 0x316 else "below"
        if mcp in DERIVED_MARKS:
            src, kind = DERIVED_MARKS[mcp]
            if src not in self.cmap:
                return None
            gn = self.cmap[src]
            if kind == "below":
                return gn, None, "below_from_above"
            if kind == "commabelow":
                return gn, [[0.8, 0], [0, 0.8]], "commabelow"
            if kind == "breath":
                return gn, [[0.8, 0], [0, 0.8]], "breath"
            if kind == "same":
                return gn, None, "above"
            if kind == "double":
                return gn, None, "double"
            if kind == "rot":
                return gn, [[-1, 0], [0, -1]], "rot_above"
        return None

    def do_compose(self, cps):
        made = 0
        for cp in cps:
            if cp in self.cmap:
                continue
            ch = chr(cp)
            nfd = ud.normalize("NFD", ch)
            if len(nfd) < 2:
                continue
            base, marks = nfd[0], [ord(m) for m in nfd[1:]]
            greek = 0x370 <= ord(base) <= 0x3FF and any(m in (0x313, 0x314, 0x308) for m in marks)
            viet = bool(self.viet) and any(a == 0x302 and b in self.viet for a, b in zip(marks, marks[1:]))
            if greek:
                marks = greek_combine(marks)
            # prefer a precomposed partial base (e.g. ấ = â + ́ ) when available
            for k in (range(len(nfd) - 1, 0, -1) if not (greek or viet) else ()):
                pre = ud.normalize("NFC", nfd[:k])
                if len(pre) == 1 and ord(pre) in self.cmap and k > 1:
                    base, marks = pre, [ord(m) for m in nfd[k:]]
                    break
            if base in "ij" and any(m in ABOVE for m in marks):
                base = {"i": "ı", "j": "ȷ"}[base]
            bg = self.g(base)
            if not bg:
                continue
            mg = [self.mark_glyph(m) for m in marks]
            if any(x is None for x in mg):
                continue
            if ch in APOSTROPHE_CARON:
                ap = self.g("’")
                if not ap:
                    continue
                self.add(cp, [(bg, None, lambda n: (0, 0)),
                              (ap, [[0.85, 0], [0, 0.85]], lambda n, bg=bg, ap=ap: self.apos_pos(bg, ap, n))],
                         lambda n, bg=bg: self.adv(bg, n) + (60 if ch in "ľť" else 40))
                made += 1
                continue
            is_greek_cap = 0x370 <= ord(base) <= 0x3FF and base.isupper()
            comps = [(bg, None, lambda n: (0, 0))]
            stack = []
            for idx, ((mgn, t, kind), mcp) in enumerate(zip(mg, marks)):
                comps.append((mgn, t, self.mark_placer(bg, mgn, t, kind, mcp, stack[:], is_greek_cap, marks)))
                stack.append((mgn, t, kind, mcp, comps[-1][2]))
            shift = self.greek_cap_shift(bg, mg, marks) if is_greek_cap else None
            if shift:
                comps = [(comps[0][0], None, lambda n, s=shift: (s(n), 0))] + comps[1:]
                adv = lambda n, bg=bg, s=shift: self.adv(bg, n) + s(n)
            elif 0x31B in marks and self.horn_ratio:
                # the horn sticks out to the right: widen the advance by the overhang (as Noto does)
                rev = {g: c for c, g in self.cmap.items()}
                key = ud.normalize("NFD", chr(rev.get(bg, 0x6F)))[0]

                def adv(n, bg=bg, key=key):
                    # widen like the reference font does (horn may overhang into the sidebearing),
                    # but always keep at least 60% of the letter's right sidebearing after the horn
                    r = (self.horn_adv or {}).get(key, 0.0)
                    a = self.adv(bg, n)
                    b = self.bbox(bg, n)
                    rx, _ = self.horn_ratio.get(key) or self.horn_ratio["upper" if b[3] > self.metrics(n)["xh"] + 100 else "lower"]
                    horn_right = b[2] + rx * (b[2] - b[0])
                    return max(a * (1 + r), horn_right + 0.6 * (a - b[2]))
            else:
                adv = lambda n, bg=bg: self.adv(bg, n)
            self.add(cp, comps, adv)
            self.kern_src[self.cmap[cp]] = bg
            if 0x31B in marks:
                self.horned.add(self.cmap[cp])
            made += 1
        return made

    def apos_pos(self, bg, ap, n):
        b = self.bbox(bg, n)
        a = self.bbox(ap, n, [[0.85, 0], [0, 0.85]])
        m = self.metrics(n)
        top = max(b[3], m["asc"])
        return (b[2] + 50 - a[0], top - a[3])

    def greek_cap_shift(self, bg, mg, marks):
        def fn(n):
            w = 0
            for (mgn, t, kind), mcp in zip(mg, marks):
                if kind in ("above", "breath", "gcombo") and mcp != 0x308:
                    bb = self.bbox(mgn, n, t)
                    w += (bb[2] - bb[0]) + 20
            lsb = self.bbox(bg, n)[0]
            return max(0, w - lsb * 0.6) if w else 0
        return fn

    def mark_placer(self, bg, mgn, t, kind, mcp, below_stack, greek_cap, all_marks):
        def fn(n):
            m = self.metrics(n)
            b = self.bbox(bg, n)
            mb = self.bbox(mgn, n, t)
            # centre marks on the letter body: ignore a horn (ơ ư) that widens the bbox
            rb = self.bbox(self.kern_src.get(bg, bg), n) if bg in self.horned else b
            cx = (rb[0] + rb[2]) / 2
            mcx = (mb[0] + mb[2]) / 2
            gap = max(mb[1] - m["xh"], 40)
            if self.viet and mcp in self.viet and below_stack and below_stack[-1][3] == 0x302:
                pg, pt, pk, pcp, pf = below_stack[-1]
                pdx, pdy = pf(n)
                cb = self.bbox(pg, n, pt)
                cb = (cb[0] + pdx, cb[1] + pdy, cb[2] + pdx, cb[3] + pdy)
                side, ov, fy = self.viet[mcp]
                cw, ch_ = cb[2] - cb[0], cb[3] - cb[1]
                y = cb[1] + fy * ch_ - mb[1]
                if side == "right":
                    return (cb[2] - ov * cw - mb[0], y)
                return (cb[0] + ov * cw - mb[2], y)
            if kind == "gcombo" and not greek_cap:
                lift = max(0, b[3] - m["xh"]) if b[3] > m["xh"] + 30 else 0
                return (cx - mcx, max(0, m["xh"] + 60 - mb[1]) + lift)
            if kind in ("above", "breath", "double", "rot_above") and not (greek_cap and mcp != 0x308):
                lift = max(0, b[3] - m["xh"]) if b[3] > m["xh"] + 30 else 0
                dy = lift
                if kind in ("breath", "rot_above"):
                    dy = (m["xh"] + gap + lift) - mb[1]
                # stack above earlier above-marks
                for (pg, pt, pk, pcp, _pf) in below_stack:
                    if pk in ("above", "breath", "double", "rot_above"):
                        pb = self.bbox(pg, n, pt)
                        if kind in ("breath",) or pk in ("breath",):
                            continue  # breathing + accent sit side by side
                        dy += (pb[3] - pb[1]) + gap * 0.5
                dx = cx - mcx
                # greek lowercase: breathing left, accent right
                if 0x313 in all_marks or 0x314 in all_marks:
                    w = mb[2] - mb[0]
                    dx += -w * 0.55 if kind == "breath" else (w * 0.45 if mcp != 0x342 else 0)
                if kind == "double":
                    w = mb[2] - mb[0]
                    return (dx - w * 0.3, dy)
                return (dx, dy)
            if greek_cap:
                # accents/breathings to the left of a capital, top aligned to cap height
                xs = 0
                for (pg, pt, pk, pcp, _pf) in below_stack:
                    if pk in ("above", "breath", "gcombo") and pcp != 0x308:
                        pb = self.bbox(pg, n, pt)
                        xs += (pb[2] - pb[0]) + 20
                return (xs - mb[0], m["cap"] - mb[3])
            if kind == "below_from_above":
                return (cx - mcx, -(mb[1] - m["xh"]) * 0.8 - mb[3])
            if kind == "commabelow":
                return (cx - mcx, -60 - mb[3])
            if mcp == 0x31B and self.horn_ratio:  # horn: same attachment as the reference font
                rev = {g: c for c, g in self.cmap.items()}
                key = ud.normalize("NFD", chr(rev.get(bg, 0x6F)))[0]
                rx, ry = self.horn_ratio.get(key) or self.horn_ratio["upper" if b[3] > m["xh"] + 100 else "lower"]
                return (b[2] + rx * (b[2] - b[0]) - mb[2], b[3] + ry * (b[3] - b[1]) - mb[3])
            if mcp == 0x31B:  # horn: attach to the upper right of o / u / O / U
                st = m["stem"]
                return (b[2] - st * 1.1 - mb[0], b[3] + (b[3] - b[1]) * 0.06 - mb[3])
            if kind == "below":
                if mcp == 0x328:  # ogonek: right side
                    return (b[2] - mb[2] - 10, 0)
                return (cx - mcx, 0)
            return (cx - mcx, 0)
        return fn

    # ------------------------------------------------ layer 3: geometric
    def do_geom(self):
        made = 0
        hy = self.g("-")
        for ch, rec in GEOM.items():
            cp = ord(ch)
            if cp in self.cmap:
                continue
            op = rec[0]
            if op in ("rot", "mirror"):
                bg = self.g(rec[1])
                if not bg:
                    continue
                t = [[-1, 0], [0, -1]] if op == "rot" else [[-1, 0], [0, 1]]

                def off(n, bg=bg, op=op):
                    a = self.adv(bg, n)
                    b = self.bbox(bg, n)
                    return (a, b[1] + b[3]) if op == "rot" else (a, 0)
                self.add(cp, [(bg, t, off)], lambda n, bg=bg: self.adv(bg, n))
            elif op == "bar":
                _, base, yref, yfrac, where, wfrac = rec
                bg = self.g(base)
                if not bg or not hy:
                    continue
                hb0 = self.bbox(hy, 0.0)
                bb0 = self.bbox(bg, 0.0)
                st0 = self.metrics(0.0)["stem"]
                width = {"full": (bb0[2] - bb0[0]) * wfrac, "inner": (bb0[2] - bb0[0]) * wfrac,
                         "center": (bb0[2] - bb0[0]) * wfrac if base not in "Iı" else st0 * wfrac,
                         "lstem": st0 + (bb0[2] - bb0[0]) * wfrac * 0.5,
                         "rstem": st0 + (bb0[2] - bb0[0]) * wfrac * 0.5}[where]
                sx = min(1.99, width / max(1, hb0[2] - hb0[0]))
                t = [[sx, 0], [0, 1]]

                def off(n, bg=bg, yref=yref, yfrac=yfrac, where=where, t=t):
                    m = self.metrics(n)
                    b = self.bbox(bg, n)
                    h = self.bbox(hy, n, t)
                    y = (m[yref] * yfrac) if yref != "xa" else m["xa"] + (m["asc"] - m["xa"]) * (yfrac - 0.5)
                    hw = h[2] - h[0]
                    if where in ("full", "inner", "center"):
                        cx = (b[0] + b[2]) / 2
                    elif where == "lstem":
                        cx = b[0] + m["stem"] / 2 + hw * 0.18
                    else:
                        cx = b[2] - m["stem"] / 2 - hw * 0.18
                    return (cx - (h[0] + h[2]) / 2, y - (h[1] + h[3]) / 2)
                self.add(cp, [(bg, None, lambda n: (0, 0)), (hy, t, off)], lambda n, bg=bg: self.adv(bg, n))
            elif op in ("join", "pair"):
                g1, g2 = self.g(rec[1]), self.g(rec[2])
                if not g1 or not g2:
                    continue

                def off2(n, g1=g1, g2=g2, op=op, rec=rec):
                    if op == "pair" and rec[1] == "ʼ":  # ŉ: apostrophe sits close to the n
                        b1, b2 = self.bbox(g1, n), self.bbox(g2, n)
                        return (b1[2] + self.metrics(n)["stem"] * 0.6 - b2[0], 0)
                    if op == "pair":
                        return (self.adv(g1, n), 0)
                    b1, b2 = self.bbox(g1, n), self.bbox(g2, n)
                    st = self.metrics(n)["stem"]
                    return (b1[2] - st * 1.05 - b2[0], 0)
                self.add(cp, [(g1, None, lambda n: (0, 0)), (g2, None, off2)],
                         lambda n, g2=g2, off2=off2: off2(n)[0] + self.adv(g2, n))
            elif op == "middot":
                bg, dot = self.g(rec[1]), self.g("·")
                if not bg or not dot:
                    continue

                def offd(n, bg=bg, dot=dot):
                    b, d = self.bbox(bg, n), self.bbox(dot, n)
                    m = self.metrics(n)
                    x = (b[0] + m["stem"] + 40) if rec[1] == "L" else (b[2] + 40)
                    return (x - d[0], m["xh"] * 0.5 - (d[1] + d[3]) / 2 + (m["cap"] - m["xh"]) * 0.25)
                self.add(cp, [(bg, None, lambda n: (0, 0)), (dot, None, offd)],
                         lambda n, bg=bg, dot=dot, offd=offd: max(self.adv(bg, n), offd(n)[0] + self.bbox(dot, n)[2] + 60))
            elif op == "dotless_j":
                continue  # needs outline surgery; left for external fill
            made += 1
        return made

    def run(self, targets):
        total = 0
        while True:
            made = self.do_alias() + self.do_geom() + self.do_compose(targets)
            total += made
            if not made:
                break
        missing = [cp for cp in targets if cp not in self.cmap]
        return total, missing

    def horn_kerning(self, right_cps, keep=0.7):
        """Open up pairs where a horn (ơ ư) would run into the next letter: measure, per master,
        the gap between the horn's right edge and the next glyph's ink inside the horn's height
        band, and kern apart to at least `keep` x the normal o|o gap. Returns {(l, r): {n: v}}."""
        o = self.cmap.get(ord("o"))
        rights = sorted({self.cmap[c] for c in right_cps if c in self.cmap})
        pairs = {}
        for left in sorted(self.horned):
            body = self.kern_src.get(left, left)
            per = {}
            for n in MASTERS:
                ob = self.bbox(o, n)
                ref = (self.adv(o, n) - ob[2]) + ob[0]
                bx = self.bbox(body, n)[2]
                horn = [p for p in self.coords(left, n)[0] if p[0] > bx]
                if not horn:
                    continue
                y0, y1 = min(p[1] for p in horn), max(p[1] for p in horn)
                hr = max(p[0] for p in horn)
                adv = self.adv(left, n)
                for r in rights:
                    xs = [p[0] for p in self.coords(r, n)[0] if y0 <= p[1] <= y1]
                    if not xs:
                        continue
                    gap = adv - hr + min(xs)
                    need = keep * ref - gap
                    if need > 0:
                        per.setdefault(r, {})[n] = round(need)
            for r, d in per.items():
                v = {n: d.get(n, 0) for n in MASTERS}
                if max(v.values()) >= 5:
                    pairs[(left, r)] = v
        return pairs

    def inherit_kerning(self):
        """Put composed letters into the kern classes of their base letter (class-based PairPos)."""
        if "GPOS" not in self.f:
            return 0
        n = 0
        for lk in self.f["GPOS"].table.LookupList.Lookup:
            subs, typ = lk.SubTable, lk.LookupType
            if typ == 9:
                typ = subs[0].ExtensionLookupType if subs else 0
                subs = [s.ExtSubTable for s in subs]
            if typ != 2:
                continue
            for st in subs:
                if st.Format != 2:
                    continue
                cov = st.Coverage.glyphs
                cd1, cd2 = st.ClassDef1.classDefs, st.ClassDef2.classDefs
                covset = set(cov)
                for new, base in self.kern_src.items():
                    if base in covset and new not in covset:
                        cov.append(new); covset.add(new)
                        if base in cd1:
                            cd1[new] = cd1[base]
                        n += 1
                    if base in cd2 and new not in cd2:
                        cd2[new] = cd2[base]
                order = {g: i for i, g in enumerate(self.order)}
                cov.sort(key=lambda g: order.get(g, 1 << 30))
        return n

    def finish(self):
        self.inherit_kerning()
        self.f.setGlyphOrder(self.order)
        self.glyf.glyphOrder = self.order
        for t in self.f["cmap"].tables:
            if t.format in (4, 12) and t.platformID in (0, 3):
                for cp, gn in self.new.items():
                    if t.format == 12 or cp <= 0xFFFF:
                        t.cmap[cp] = gn
        self.f["maxp"].recalc(self.f)


def scalar(region, n):
    st, pk, en = region
    if pk == 0:
        return 0
    if n == pk:
        return 1.0
    if n <= st or n >= en:
        return 0.0
    return (n - st) / (pk - st) if n < pk else (en - n) / (en - pk)


def targets():
    out = []
    for a, b in RANGES:
        for cp in range(a, b + 1):
            ch = chr(cp)
            if ud.category(ch).startswith("L") or ud.category(ch) in ("Lm",):
                out.append(cp)
    return out


if __name__ == "__main__":
    src, dst = sys.argv[1], sys.argv[2]
    f = TTFont(src)
    s = Synth(f)
    tg = targets()
    before = sum(1 for c in tg if c in s.cmap)
    total, missing = s.run(tg)
    s.finish()
    f.save(dst)
    rep = {"目标字母数": len(tg), "原有": before, "生成": total,
           "仍缺": len(missing), "仍缺列表": "".join(chr(c) for c in missing)}
    print(json.dumps({k: v for k, v in rep.items() if k != "仍缺列表"}, ensure_ascii=False))
    if len(sys.argv) > 4 and sys.argv[3] == "--report":
        json.dump(rep, open(sys.argv[4], "w", encoding="utf-8"), ensure_ascii=False, indent=1)
