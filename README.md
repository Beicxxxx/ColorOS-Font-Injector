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

## 📱 一、手机端 App 使用指南

### 前置要求
1. 手机已安装并激活 [Shizuku](https://shizuku.rikka.app/)（通过无线调试一键启动，无需 Root）。
2. 主题商店中已有至少一条 DIY 字体记录（在主题商店搜索“DIY”或“樱落粗黑体”，生成过一次即可）。

### 使用步骤
1. 下载并安装本项目的 Release APK（`ColorOS 字体注入器.apk`）。
2. 打开 App，授予 Shizuku 权限（Shell UID=2000）。
3. 点击 **「选择字体 (.ttf)」**，选取你准备好的字体文件。
   * App 会自动读取 TTF `head` 表检测 `unitsPerEm`。ColorOS DIY 字体建议为 256 UPEM（可使用项目内置脚本一键缩放）。
4. 点击 **「一键注入并应用字体」**。
5. 注入完成后，系统字体立即生效！**支持重启设备与系统 OTA 升级，效果永久保留**。

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
