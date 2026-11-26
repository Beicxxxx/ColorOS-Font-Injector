# cjkmerge — CJK 可变字体合成与补字

用多款 CJK 可变字体按地区顺序逐级补字，合成单个 VF；再补扩展西文、给未编码字形分配码位、
修正 UCS 重複漢字、去掉 emoji 码位、加随字重变化的 kerning，最后删掉用不到的字形。
所有处理都按字体自身几何计算，换别的字体只需写一份新配置（示例：`xianghe_jp.json`）。

依赖：`pip install fonttools uharfbuzz freetype-py numpy pillow opentype-sanitizer`

| 步骤 | 命令 |
|---|---|
| 合成 | `python3 cjk_merge.py config.json` |
| 找未编码字形（人工确认后写 encode_map） | `python3 find_unencoded.py config.json merged.ttf refdir outdir` / `find_compat.py` 同参数 |
| UCS 重複漢字（人工确认后写 rules.remap，alias 自动） | `python3 fix_duplicates.py config.json refdir out.json merged.ttf` |
| 扩展西文 + kerning | `python3 build_western.py stage1.ttf out.ttf NotoSans-VF.ttf report.json` |
| 删未用字形（保留 cmap/GSUB/IVS/部件闭包） | `python3 prune_unused.py in.ttf out.ttf` |

配置项（`rules`）：`never_from`、`skip_narrow_han`、`force`、`range_order`、`remap`、`alias`、`drop_emoji`；
每个 `layers[]` 可加 `"fit": "auto"` 自动对齐字面大小。

`data/`：UnicodeData 17.0、emoji-data 17.0、Adobe-Japan1 IVS、cjkvi duplicate-chars、
`ucs-duplicates.tsv`（cjkvi + Unihan kZVariant/kSpoofingVariant，Unicode 18 草案 Unihan）。
参考字形字体（Jigmo、Plangothic）不随仓库提供：`npm pack fontpkg-jigmo @free-fonts/plangothic`。
