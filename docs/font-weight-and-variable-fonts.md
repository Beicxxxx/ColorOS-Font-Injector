# 多字重与可变字体注入：机制、边界与结论

> 设计笔记。记录 ColorOS 15/16 下系统字体的槽位结构、注入可写边界，以及"多字重 / 可变字体替代"的可行性结论。全部结论基于真机（ColorOS，Shizuku shell uid 2000，无 root）实测。

## 0. 速查结论

- 注入只有**一个可写槽**：`/data/format_unclear/font/Customized-Regular.ttf`。其余字重槽与所有系统配置均只读 / 受 SELinux 保护。
- 注入是**逐字节原样落地**（成功判据 = 系统字体 MD5 == 注入字体 MD5），所以能注入任意字体，包括**可变字体**。
- 默认 UI 家族 `sans-serif` 的 100–900 全字重都指向这个槽、且每档带 `<axis tag="wght">`，**系统本就按 `wght` 轴向该槽要各字重**。
- 注入**可变字体**后：**自己按 `wght` 读文件 `fvar` 的 APP 能拿到真多字重**；但走系统 Typeface 集合的路径（`style=bold`、全局加粗键）**只会合成假粗**。
- 根因：系统把注入的自定义字体**登记为静态 400**，不读文件里的 `fvar`。因此系统字重滑块对自定义字体禁用、全局加粗键也只假粗。
- **免 root 的上限**：真多字重只惠及"规矩读 `fvar`"的 APP；全 APP 真多字重 + 可用滑块需让 ColorOS 把字体登记为可变——该分类开关疑在主题商店私有库 / 框架，**基本需 root**。

## 1. 字体槽位与映射链

映射链（实测）：

```
默认家族 sans-serif  (/system/etc/fonts.xml, familyset v23)
  → /system/fonts/SysFont-Regular.ttf        (root 软链, /system 只读)
    → /data/format_unclear/font/OplusOSUI-Regular.ttf   (SELinux: oem_font_data_file, shell 拒写)
      → /data/format_unclear/font/Customized-Regular.ttf  ← 唯一可写（经主题商店 DIY 应用写入）
```

- `/data/format_unclear/font/` 目录权限 0777，但 SELinux `Enforcing` + 标签 `oem_font_data_file`，`shell` 直接写**被拒**（`Permission denied`）。唯一写入口是主题商店 DIY「字体」，且它只写 `Customized-Regular.ttf` 这一个槽。
- 该目录里其余字重槽 `OplusOSUI-Thin/Light/Medium/Bold/Black`(+Italic) 均为 **init.rc 开机建立**的、指向 `/system/fonts/Roboto-*` 的软链；`OplusOSUI-Hans/Hant-Regular` 指向 `SysFont-Hans/Hant-Regular`。建链见 `/system_ext/etc/init/init.oplus.rootdir.rc`（root、开机）。
- **不存在任何用户可写的映射配置文件**：映射要么在 init.rc（root），要么在只读的 `/system`。想"改映射把各字重重指到自定义文件"⇒ 需 root。

## 2. 默认家族已按 `wght` 轴驱动

`/system/etc/fonts.xml` 默认家族 `sans-serif` 把 100–900 全部字重指向同一个 `SysFont-Regular.ttf`，每档带显式轴：

```xml
<family name="sans-serif">
    <font weight="400" style="normal">SysFont-Regular.ttf
        <axis tag="wght" stylevalue="400" />
    </font>
    <font weight="700" style="normal">SysFont-Regular.ttf
        <axis tag="wght" stylevalue="700" />
    </font>
    <!-- 100…900 同理 -->
</family>
```

即系统**已经在按 `wght` 轴**向自定义槽要各字重。系统自带 UI 字体（OPSans / SysSans）本身就是"一个可变字体文件 + 每档轴"的方式实现多字重——说明渲染层完全支持可变字体，瓶颈不在渲染层。

## 3. 注入可变字体的实测结果

测试字体：HarmonyOS Sans SC（可变，单轴 `wght` 40–900，9 个命名实例）。用 `skill/scripts/scale_upem.py`（fontTools `scale_upem`，逐表缩放、保 `fvar`、正确缩放 `gvar`、**不实例化**）缩到 256 upem 后注入。

- **部分 APP：真多字重。** 这些 APP 自己用字重 API（`Typeface.create(family, weight, italic)` / `textFontWeight` / `fontVariationSettings`）、直接读文件 `fvar`，于是拿到真实各档 `wght`。
- **部分 APP：仍是 400 上的假粗。** 对照实验关键：**同一批"假粗"APP，在系统默认字体时渲染的是真粗体。** 故差别不在 APP 调用方式，而在系统怎么登记这只字体。
- **设置里**：字体名仍显示上一次商店应用的名字（元数据未同步），且**字重滑块禁用**。

## 4. 根因：系统把自定义字体登记为静态 400

- ColorOS 的 DIY「字体」通路按**静态字体**设计：字重在**建 DIY 记录时就烘焙**（`.../diy/config.json` 里的 `"weight":{"type":0,"default":0,"min":-256,"max":192}` 即建记录时的烘焙范围）。
- 于是在系统 Typeface 集合层，注入的自定义字体只是**一个 400 主体、无各档变体实例**：
  - `style=BOLD` 的 APP → 系统发现 found=400、want=700、差 ≥ 300 → **算法合成假粗**。
  - 直接读文件 `fvar` 的 APP → 读到真 `fvar` → 真字重。
- **设置里名字旧 + 滑块灰**：系统字体元数据仍指向上次商店应用的 DIY 字体、被归类为"不可调自定义字体"，滑块据此禁用——**它并不去读我们换进文件里的 `fvar`**。

## 5. `settings` 键写入实测（均已还原）

ColorOS 的字重相关 `settings` 键（Shizuku `shell` 可 `settings put` 读写）：

| 命名空间 | 键 | 原值 | 含义 |
|---|---|---|---|
| system | `font_variation_settings` | 4738 | 系统默认字体（OPSans）的变体值 |
| system | `font_op_sans_settings` | 4096 | 内置 OPSans 轴设置 |
| secure | `font_weight_adjustment` | 0 | AOSP 标准全局加权重（"加粗文字"用） |

实测：

- `font_variation_settings` 写 9000 → 被框架回写 8692（**有效范围、实时受控**），但**界面无变化** ⇒ 只驱动系统自带 OPSans，对自定义字体无效。
- `font_weight_adjustment=300`（AOSP 标准全局加权重）→ 整屏变重，但是**假粗** ⇒ 坐实：系统 Typeface 集合把自定义字体登记为静态 400，任何系统级"加权重"对它都只能合成假粗，拿不到真 `wght` 实例。

## 6. 回答两个问题

**Q1 多字重如何替代？**
- 免 root 下**只有 Regular 一个槽可写**；真·分字重槽（Light/Medium/Bold…）系统所有、SELinux 保护，改不了。
- 注入**可变字体**可让"规矩读 `fvar`"的 APP 得到真多字重（免 root 上限）；走系统集合的路径仍假粗。
- 全 APP 真多字重需让系统把字体**登记为可变**，基本需 root。

**Q2 可变字体如何替代？**
- 可变字体能**原样注入**（逐字节落地，不被管线重压），工具链（`scale_upem.py`）已保 `fvar/gvar`。
- 但"系统端实时可变 / 可被字重滑块调节"**不行**：系统把自定义字体当静态登记，系统级权重键只给假粗，且滑块对自定义字体禁用。
- 免 root 可落地的替代：若只想"默认更粗"，把该 VF 在某档（如 `wght` 500/600）**实例化成静态**再注入——固定一档、不可调。

## 7. 待探索（多数指向 root）

1. 定位 ColorOS 判定一只字体"可变 / 字重可调"的**分类开关**（控制滑块开放 + 变体实例登记）。疑在主题商店私有库（`/data/data/com.heytap.themestore`，无 root 不可读）或框架。若能让系统按可变登记自定义字体，则 Q1/Q2 一并解决。
2. 若将来有 root：直接改 `OplusOSUI-*` 字重槽软链 / 定制家族 XML，为各字重指向自定义（可变）文件，彻底解决。

## 附：关键事实索引

- 系统字体：`/data/format_unclear/font/Customized-Regular.ttf`（SELinux `oem_font_data_file`）。
- 映射：`/system/etc/fonts.xml`（sans-serif + wght 轴）、`/system_ext/etc/init/init.oplus.rootdir.rc`（建软链）、`/system_ext/etc/fonts_base.xml`（OPSans/SysSans 可变家族）。
- 字重 settings：`system.font_variation_settings` / `system.font_op_sans_settings` / `secure.font_weight_adjustment`。
- 相关实现：注入逻辑见 `app/.../Injector.java`；DIY 记录选择须按**文件名时间戳**而非 mtime（见提交 978a1a3）；UPEM 缩放见 `skill/scripts/scale_upem.py`。
