# ColorOS Non-Root Font Injector (ColorOS 字体注入器)

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![ColorOS](https://img.shields.io/badge/ColorOS-15%20%7C%2016-green.svg)](https://www.oppo.com/cn/coloros/)
[![Android](https://img.shields.io/badge/Android-15%20%7C%2016-brightgreen.svg)](https://developer.android.com)
[![Shizuku](https://img.shields.io/badge/Shizuku-API-orange.svg)](https://shizuku.rikka.app/)

> **免 Root、免解锁 Bootloader**，基于原子 TOCTOU（Time-Of-Check to Time-Of-Use）Inode 竞态劫持技术，为搭载 **ColorOS 15 / 16（Android 15 / 16）** 的 OnePlus（一加）、OPPO、Realme 设备替换任意自定义 TTF 界面字体。

---

## 💡 背景与破解原理

在 ColorOS 16 系统中：
1. 系统全局界面字体链接到 `/data/format_unclear/font/Customized-Regular.ttf`。该目录具有严格的 SELinux 策略（`oem_font_data_file`），即便是 `shell (uid=2000)` 也无权直接写入。
2. 官方主题商店（`com.heytap.themestore`）支持“DIY 字体”功能，并在应用时由高权限系统服务 `com.oplus.uxdesign` 将字体拷贝入上述目录。
3. **安全拦截**：新版主题商店在应用前加入了 MD5 校验机制（`sp.h.validate()`）。它会计算目标文件的 MD5 并与私有数据库中的原记录比对，只要文件被篡改或留空即弹窗拦截并报错 `-15 (资源包已损坏)`。

### 核心突破：第二次读事件原子 Inode 劫持（Double-Open TOCTOU Swap）

逆向分析表明，主题商店在触发应用时会执行**两次连续的 MD5 读取操作**（间隔约 50ms），之后才通过 `FileProvider` 移交给 `UxThemeService`：
1. **第一次读取 (`r`)**：主题商店打开文件，校验文件头与 MD5；
2. **第二次读取 (`r`)**：主题商店再次打开文件，完成第二轮二次校验；
3. **第三次读取 (`r`)**：`com.oplus.uxdesign` 打开文件并将流直接拷贝至系统字体目录。

利用 Linux 文件系统的 Inode 语义与 `/system/bin/inotifyd`：
* 我们将监听器挂载在目录读事件上（`colorFonts:r`）。
* 在主题商店触发**第二次打开（Second Open）**时，旧进程的文件描述符已绑定原 Inode，我们在毫秒级窗口内执行原子重命名：
  ```bash
  /system/bin/mv -f "$CUSTOM_FONT" "$TARGET_FILE"
  ```
* 主题商店基于旧 Inode 的读取全部通过校验；紧接着 `UxThemeService` 依据文件路径打开文件时，直接解析至包含我们自定义字体的新 Inode！
* 成功实现**无需 Root 即可写入受保护的系统字体目录**。

---

## 📦 项目组成

本项目包含两个开箱即用的交付形式：

1. **Android 手机客户端 (`app/`)**：在手机上直接配合 Shizuku 使用，随时选取任意本地 `.ttf` 字体一键注入，彻底脱离电脑。
2. **通用 AI Agent 技能包 (`skill/`)**：遵循通用 Agent Skill 规范（包含 `SKILL.md` 与自动化脚本），支持**任意 Coding Harness 与 AI Agent**（如 Claude Code, Cursor, Windsurf, Aider, Antigravity, OpenHands 等）直接读取与自动化执行。

---

## ⚠️ 核心前置条件与原理限制（必读）

本项目之所以能实现“免 Root 替换系统全局界面字体”，依赖于 ColorOS 特定系统链路的权限组合。在开始前，**必须满足以下所有前提条件**：

### 1. 设备与系统兼容性
* **支持品牌**：一加 (OnePlus)、OPPO、真我 (Realme) 全系手机及平板。
* **系统版本**：ColorOS 15 或 ColorOS 16（基于 Android 15 / 16）。
* **系统安全状态**：**无需 Root**，**无需解锁 Bootloader**（保持官方 Locked、Green 启动校验状态完全兼容）。

### 2. 关键前提：必须在主题商店拥有至少一款“可 DIY”的字体
> 📌 **为什么普通字体不行？**
> * 主题商店中普通商业字体的下载与应用链路完全封闭在私有内部目录（`/data/data/com.heytap.themestore/`）及系统加密缓存中，非 Root 的 Shell 权限根本无法读写；
> * **唯独“DIY 字体”具有特殊的开放架构**：为了支持用户在本地调整字重、贴图和预览，主题商店会将 DIY 字体的实际文件解压到公共外部存储空间：
>   `/sdcard/Android/data/com.heytap.themestore/files/Themes/.data/temp/<uuid>/colorFonts/`
>   该路径带有 `ext_data_rw` 权限标签，无论是通过 ADB 还是 Shizuku，`shell (uid=2000)` 对其拥有**毫秒级读写、重命名以及 Inotify 文件事件监听**的完整控制权！
> * 当你点击应用时，高权限的系统服务（`com.oplus.uxdesign`，uid 10232）会从这个临时目录中读取字体文件，并由系统底层直接写入受 SELinux 保护的系统字体目录（`/data/format_unclear/font/Customized-Regular.ttf`）。

#### 准备操作（只需操作一次）：
1. 打开系统自带的 **「主题商店」**（`com.heytap.themestore`）；
2. 搜索并获取任意一款带有 **「可 DIY」** 或 **「彩色字体」** 标签的字体（如官方常见的“樱落粗黑体”或其它支持 DIY 样式的字体）；
3. 点击进入字体详情页，点击下方的 **「开始 DIY」**；
4. 随意调整一下粗细或直接点击 **「保存」**，生成至少一条历史 DIY 记录；
5. 进入该字体的 **「历史 DIY 记录」** 界面，确保能看到生成的历史记录卡片。此时外部存储中已生成了对应的 `colorFonts/<uuid>_colorFont_<时间戳>.ttf` 模板文件，后续所有注入都将以此为跳板。

### 3. Shell 调试权限接入（二选一）
* **方案 A（手机端脱机使用 App）**：手机安装并激活 **[Shizuku](https://shizuku.rikka.app/)**。
  * 打开手机「设置」->「关于本机」-> 连击版本号开启「开发者选项」；
  * 进入「开发者选项」-> 开启「无线调试」；
  * 在 Shizuku 中点击配对并启动服务即可（纯手机端完成，无需连接电脑）。
* **方案 B（电脑端 / AI Agent 自动化执行）**：
  * 开启「开发者选项」中的「USB 调试」或「无线调试」；
  * 电脑通过 ADB 正常连接手机（终端运行 `adb devices` 能看到设备在线）。

### 4. 待注入字体的格式规范
* **文件格式**：必须为标准 TrueType 字体（`.ttf`），具备常规 Unicode / CJK 字符集映射。
* **UPEM (unitsPerEm) 建议**：ColorOS 的 DIY 字体模块采用 **256 UPEM**。
  * 现代开源或商用字体（如思源、苹方、霞鹜等）通常采用 1000 或 2048 UPEM；
  * 虽然部分应用可直接显示高 UPEM 字体，但可能在某些旧版应用文本框内出现垂直居中微小偏差；
  * **建议**：使用项目内置脚本 `scale_upem.py`（`python scale_upem.py input.ttf output.ttf 256`）进行无损缩放后再注入。

### 5. 注入后效果与注意事项
* **持久性**：替换成功后，系统字体写入在 `/data/format_unclear/font/Customized-Regular.ttf`，**手机重启、关机、系统 OTA 增量更新均不会失效**。
* **恢复与防误触**：只要不在主题商店中随意点击其他未被劫持的字体，新字体就会一直生效。若在主题商店内再次点击了官方字体，系统字体会被覆盖恢复；此时只需重新运行一次 App 或脚本注入即可。

---

## 📱 一、手机端 App 使用指南

### 使用步骤
1. 前往 [Releases](https://github.com/Beicxxxx/ColorOS-Font-Injector/releases) 下载并安装本项目的 APK（`ColorOS-Font-Injector-v1.0.0.apk`）。
2. 打开 App，点击「请求 Shizuku 授权」，授予 Shell 权限。
3. 点击 **「选择字体 (.ttf)」**，选取你准备好的字体文件。
   * App 会自动读取 TTF `head` 表检测 `unitsPerEm`，并显示文件大小和 MD5。
4. 点击 **「一键注入并应用字体」**。
5. App 将在后台启动精确 Inode 监听并自动处理替换；完成后弹窗提示成功，系统全局字体即刻刷新生效！

---

## 🤖 二、AI Agent 技能使用指南 (Harness-Agnostic Agent Skill)

本 Skill 采用开放通用的 Agent Skill 标准定义（YAML Frontmatter 元数据 + 结构化指引 + 可执行脚本），**不绑定任何特定厂商或 Harness**。任何具备终端执行能力或连接了 ADB/无线调试的 AI Agent 均可零门槛加载：

### 支持的 Agent / Harness
* **Claude Code / Anthropic Agent**
* **Cursor / Windsurf / Copilot**
* **Aider / OpenHands**
* **Google Antigravity / Gemini CLI**
* **任意自定义 Agent 框架与脚本**

### 安装与集成方式
将 `skill/` 目录挂载或复制到你所使用的 Harness 技能路径，例如：
* **Antigravity / Gemini 全局技能**：`~/.gemini/config/skills/coloros-font-injector/`
* **Claude Code /通用项目工作区**：放置于工作区 `.skills/coloros-font-injector/` 或直接在对话中将 `skill/SKILL.md` 加入上下文
* **Cursor / Windsurf**：在 `.cursorrules` 或工作区提示词中直接引用 `skill/SKILL.md`

### 自动化 CLI 脚本
Agent 或开发者可在电脑终端直接执行配套脚本：
```bash
# 自动检测设备、UPEM 缩放、部署 Inode 监听并执行注入
python skill/scripts/inject_font.py --font /path/to/myfont.ttf [-s <device_serial>]
```

参数说明：
* `--font`：待应用的 TTF 字体绝对路径。
* `-s` / `--serial`：目标设备序列号或无线调试 IP:端口。
* `--skip-scale`：跳过 UPEM 缩放（如果字体本身已是 256 UPEM）。

---

## 🛠️ 从源码编译 Android App

项目基于标准 Gradle 构建系统：

```bash
git clone https://github.com/Beicxxxx/ColorOS-Font-Injector.git
cd ColorOS-Font-Injector

# 编译 Debug APK
./gradlew assembleDebug
```
产物输出于：`app/build/outputs/apk/debug/app-debug.apk`

---

## ❓ 常见问题 (FAQ)

#### Q1: 这与旧时代的“缅甸语字体”替换有什么区别？
> 传统缅甸语（Myanmar）替换法需要修改系统语言地区，且会导致缅甸语字符集映射混乱、字形对齐异常，ColorOS 15+ 上已基本失效。
> 本项目替换的是官方正统的 `Customized-Regular.ttf`，系统判定为合法 DIY 字体，语言地区保持中国大陆，全局中英文对齐与行高完全正常。

#### Q2: 换完字体后，在主题商店随便点其他字体会怎样？
> 系统字体会被主题商店还原为你点的字体。如需再次使用自定义字体，只需重新打开 App 或执行脚本注入一次即可。

#### Q3: 为什么建议将字体缩放到 256 UPEM？
> ColorOS 主题商店的 DIY 模块默认字体（如樱落粗黑体）采用 256 UPEM。部分未缩放至 256 的字体在某些旧版应用文本框内可能会出现垂直基线偏差，使用项目内置的 `scale_upem.py` 可实现无损缩放。

---

## 📄 开源许可证

本项目采用 [MIT License](LICENSE) 开源。仅供 Android 系统机制逆向研究与无害个性化定制交流使用。
