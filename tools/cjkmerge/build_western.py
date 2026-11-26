#!/usr/bin/env python3
"""扩展西文三层补字: 借形/拼合/几何生成 -> 外部字体补附加符号后再拼合 -> 外部字体补整字。
用法: python3 build_western.py in.ttf out.ttf ext_vf.ttf report.json"""
import json, sys
sys.path.insert(0, __import__("os").path.dirname(__file__))
from fontTools.ttLib import TTFont
from synth_western import Synth, targets
from ext_fill import ExtSource, ExtFill, needed_marks

src, dst, ext_path, rep_path = sys.argv[1:5]
f = TTFont(src)
s = Synth(f)
tg = targets()
redo = s.drop_fullwidth(tg)
before = sum(1 for c in tg if c in s.cmap)
ext = ExtSource(ext_path, fixed={"wdth": 100})
ef = ExtFill(s, ext)
# Greek combined breathing/accent marks first, so clusters use one well-drawn mark
from synth_western import GREEK_COMBO_CPS
greek_marks = [c for c in sorted(GREEK_COMBO_CPS) if c not in s.cmap and c in ext.cmap and ef.add(c)]
# horn attachment learnt from the reference font
def _ratio(base, horned):
    a, h = ext.bbox(400, base), ext.bbox(400, horned)
    return ((h[2] - a[2]) / (a[2] - a[0]), (h[3] - a[3]) / (a[3] - a[1]))
s.horn_ratio = {"lower": _ratio("o", "ơ"), "upper": _ratio("O", "Ơ"),
               # per base letter: a straight stem (u, U) takes the horn differently from a bowl (o, O)
               "o": _ratio("o", "ơ"), "u": _ratio("u", "ư"), "O": _ratio("O", "Ơ"), "U": _ratio("U", "Ư")}
_h = ext.inst(400)["hmtx"]
s.horn_adv = {c: _h[ext.cmap[ord(h)]][0] / _h[ext.cmap[ord(c)]][0] - 1 for c, h in zip("ouOU", "ơưƠƯ")}
# Vietnamese circumflex + tone: tone sits beside the circumflex (rule relative to the font's own
# circumflex, so it works for any base font)
from synth_western import VIET_TONE
s.viet = VIET_TONE
# letters with circumflex + tone that came from a CJK source (e.g. pinyin ế ề stacked Chinese-style)
# are rebuilt so all Vietnamese letters share one design
import unicodedata as _ud
redo_viet = [c for c in tg if c in s.cmap and any(a == "\u0302" and ord(b_) in VIET_TONE for a, b_ in
             zip(_ud.normalize("NFD", chr(c))[1:], _ud.normalize("NFD", chr(c))[2:]))]
for c in redo_viet:
    del s.cmap[c]
print("viet", s.viet)
n1, _ = s.run(tg)
marks = [m for m in needed_marks(s, tg) if m in ext.cmap]
added_marks = [m for m in marks if ef.add(m)]
n2, _ = s.run(tg)
rest = [c for c in tg if c not in s.cmap and c in ext.cmap]
ext_ok = [c for c in rest if ef.add(c)]
n3, missing = s.run(tg)
from ext_fill import add_ext_kerning
s.finish()
n_kern = add_ext_kerning(s, ext, ext_ok)
from ext_fill import add_var_pairs
horn_pairs = s.horn_kerning([c for c in tg] + [ord(c) for c in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"])
n_horn = add_var_pairs(s, horn_pairs)
f.save(dst)
rep = {"目标字母数": len(tg), "原有": before, "自身生成(借形/拼合/几何)": n1 + n2 + n3,
       "外部补附加符号": "".join(chr(c) for c in added_marks + greek_marks),
       "重做的全宽字母": "".join(chr(c) for c in redo),
       "外部补整字数": len(ext_ok), "外部补字的字偶数": n_kern, "带角字母防撞字偶数": n_horn, "外部补整字": "".join(chr(c) for c in ext_ok),
       "仍缺": "".join(chr(c) for c in missing)}
json.dump(rep, open(rep_path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
print(json.dumps({k: (v if not isinstance(v, str) or len(v) < 80 else v[:80] + "…") for k, v in rep.items()}, ensure_ascii=False, indent=1))
