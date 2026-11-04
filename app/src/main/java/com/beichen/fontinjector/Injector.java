package com.beichen.fontinjector;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TOCTOU 注入。
 *
 * 点「应用」后主题商店会先读满整份 DIY 记录文件算 MD5（数百个 read 事件）再关闭，
 * 校验通过后文件被再次打开、由系统服务拷进受 SELinux 保护的
 * /data/format_unclear/font/Customized-Regular.ttf。我们在「读满后的第一个关闭」
 * 这一毫秒级窗口里 mv -f 换入自定义字体：校验读到的是正版，拷贝读到的是我们的字体。
 *
 * 整套编排（拉回主题商店 DIY 历史页、识别「应用」按钮、换文件、重试）由一个经
 * Shizuku 运行的自包含脚本完成。该脚本是 shizuku_server 的子进程、不是本 App 的
 * 子进程，所以即便 ColorOS 在 App 退到后台时把它杀掉，脚本仍会跑完并把结果写入
 * 结果文件。换文件绝不能卡在「第 2 次 open」——那次 open 的 fd 已绑定旧 inode，
 * 为时已晚；窗口只有约 30ms，偶尔抢不到会自动重试。
 */
final class Injector {

    interface Callback {
        void onLog(String line);

        void onFinish(boolean success, String systemInfo);
    }

    static final String RESULT_FILE = "/data/local/tmp/app_inject_result.txt";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Injector() {}

    static void apply(Context ctx, File font, String fontName, Callback cb) {
        EXECUTOR.execute(() -> run(ctx, font, fontName, cb));
    }

    private static void run(Context ctx, File font, String fontName, Callback cb) {
        String fontMd5 = TtfUtil.md5(font).toLowerCase();
        try {
            // Step 1: 暂存字体到 /data/local/tmp
            log(cb, "[1/5] 暂存字体文件至设备运行环境...");
            Process pushProc = Shell.process(
                    "cat > /data/local/tmp/app_font_inject.ttf && chmod 666 /data/local/tmp/app_font_inject.ttf");
            try (FileInputStream fis = new FileInputStream(font);
                 FileOutputStream pos = (FileOutputStream) pushProc.getOutputStream()) {
                byte[] buf = new byte[65536];
                int len;
                while ((len = fis.read(buf)) != -1) pos.write(buf, 0, len);
                pos.flush();
            }
            pushProc.waitFor();

            // Step 2: 定位主题商店 DIY 目录
            log(cb, "[2/5] 定位主题商店 DIY 数据目录...");
            String diyDirRes = Shell.run("ls -d " + FontLibrary.THEME_TMP + "/*/colorFonts 2>/dev/null").trim();
            if (diyDirRes.isEmpty()) {
                log(cb, "[错误] 未找到主题商店 DIY 目录！请先在主题商店生成一款 DIY 字体。");
                finish(cb, false, null);
                return;
            }
            String colorFontsDir = diyDirRes.split("\n")[0].trim();

            // Step 3: 找当前有效的 DIY 记录模板（最新一条）
            log(cb, "[3/5] 查找当前有效的 DIY 记录模板...");
            String recordsRes = Shell.run("ls -t " + colorFontsDir + "/*_colorFont_*.ttf 2>/dev/null").trim();
            if (recordsRes.isEmpty()) {
                log(cb, "[错误] colorFonts 目录下无历史记录文件！请先在主题商店生成一条 DIY 记录。");
                finish(cb, false, null);
                return;
            }
            String targetName = new File(recordsRes.split("\n")[0].trim()).getName();
            log(cb, "[目标] 记录文件: " + targetName);

            // Step 4: 启动自包含注入脚本（由 Shizuku 托管，独立于本 App 的生死）
            log(cb, "[4/5] 启动自包含注入脚本（商店会被拉到前台，本应用可能短暂退到后台）...");
            String script = buildScript(colorFontsDir, targetName, fontMd5);
            Shell.run("rm -f " + RESULT_FILE);
            Process scriptProc = Shell.process(
                    "cat > /data/local/tmp/run_app_inject.sh && chmod 755 /data/local/tmp/run_app_inject.sh && /data/local/tmp/run_app_inject.sh");
            try (FileOutputStream fos = (FileOutputStream) scriptProc.getOutputStream()) {
                fos.write(script.getBytes("UTF-8"));
                fos.flush();
            }

            // Step 5: 轮询结果文件。脚本独立于本 App，被杀也会写完结果。
            log(cb, "[5/5] 等待注入结果（最多重试 6 次）...");
            String result = "";
            for (int i = 0; i < 65; i++) {
                Thread.sleep(2000);
                result = Shell.run("cat " + RESULT_FILE + " 2>/dev/null").trim();
                if (result.startsWith("SUCCESS") || result.startsWith("FAILED")) break;
            }
            try { scriptProc.destroy(); } catch (Exception ignored) {}

            boolean success = result.startsWith("SUCCESS");
            if (success || result.startsWith("FAILED")) {
                Shell.run("rm -f " + RESULT_FILE);
            }
            String verifyRes = Shell.run(
                    "ls -la " + FontLibrary.SYS_FONT + " && md5sum " + FontLibrary.SYS_FONT).trim();
            log(cb, "[结果] " + result);
            log(cb, verifyRes);
            log(cb, success ? "✅ 系统字体 MD5 已匹配：" + fontName
                            : "⚠️ 系统字体 MD5 未匹配（窗口竞态或记录不一致），可重试或新建 DIY 记录");
            finish(cb, success, verifyRes);

            History.Entry e = new History.Entry();
            e.ts = History.now();
            e.action = "apply";
            e.font = fontName;
            e.upem = String.valueOf(TtfUtil.read(font).upem);
            e.ok = success;
            e.note = TtfUtil.size(font.length());
            History.add(ctx, e);
        } catch (Exception ex) {
            log(cb, "[致命错误] " + ex.getMessage());
            finish(cb, false, null);
        }
    }

    /**
     * 自包含编排脚本：拉回 DIY 历史页 → 关 -15 弹框 → 识别「应用」按钮 →
     * 读计数换文件 → 点击 → 重试 → 写结果文件。整段在 shizuku_server 下运行。
     */
    private static String buildScript(String colorFontsDir, String targetName, String wantMd5) {
        return "#!/system/bin/sh\n"
                + "COLOR_FONTS=\"" + colorFontsDir + "\"\n"
                + "TARGET=\"$COLOR_FONTS/" + targetName + "\"\n"
                + "NEW=/data/local/tmp/app_font_inject.ttf\n"
                + "ORIG=/data/local/tmp/.orig_genuine.ttf\n"
                + "SWAP=\"$COLOR_FONTS/.swap_ready.dat\"\n"
                + "SYS=" + FontLibrary.SYS_FONT + "\n"
                + "UIXML=/data/local/tmp/app_ui.xml\n"
                + "RESULT=" + RESULT_FILE + "\n"
                + "WANT=\"" + wantMd5 + "\"\n"
                + "echo RUNNING > \"$RESULT\"\n"
                + "pkill -f inotifyd 2>/dev/null\n"
                + "cp \"$TARGET\" \"$ORIG\" 2>/dev/null\n"
                + "attempt=0\n"
                + "while [ \"$attempt\" -lt 6 ]; do\n"
                + "  attempt=$((attempt+1))\n"
                + "  j=0\n"
                + "  while [ \"$j\" -lt 6 ]; do\n"
                + "    FG=$(dumpsys activity activities | grep topResumedActivity | head -1)\n"
                + "    case \"$FG\" in *DiyFontHistoryActivity*) break;; esac\n"
                + "    uiautomator dump \"$UIXML\" >/dev/null 2>&1\n"
                + "    if grep -q \"知道了\" \"$UIXML\" 2>/dev/null; then\n"
                + "      DB=$(grep -oE 'text=\"知道了\"[^>]*bounds=\"\\[[0-9]+,[0-9]+\\]\\[[0-9]+,[0-9]+\\]\"' \"$UIXML\" | head -1)\n"
                + "      set -- $(echo \"$DB\" | grep -oE '[0-9]+' | tail -4)\n"
                + "      [ \"$#\" -ge 4 ] && input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 ))\n"
                + "    fi\n"
                + "    input keyevent 4\n"
                + "    sleep 1.5\n"
                + "    j=$((j+1))\n"
                + "  done\n"
                + "  uiautomator dump \"$UIXML\" >/dev/null 2>&1\n"
                + "  LINE=$(grep -oE 'resource-id=\"[^\"]*/use\"[^>]*bounds=\"\\[[0-9]+,[0-9]+\\]\\[[0-9]+,[0-9]+\\]\"' \"$UIXML\" | head -1)\n"
                + "  set -- $(echo \"$LINE\" | grep -oE '[0-9]+' | tail -4)\n"
                + "  if [ \"$#\" -ge 4 ]; then TX=$(( ($1+$3)/2 )); TY=$(( ($2+$4)/2 )); else TX=384; TY=672; fi\n"
                + "  cp \"$NEW\" \"$SWAP\"; chmod 666 \"$SWAP\" 2>/dev/null\n"
                + "  reads=0; DONE=\n"
                + "  /system/bin/inotifyd - \"$TARGET:aw0r\" | while read -r ev p r; do\n"
                + "    case \"$ev\" in\n"
                + "      *a*) reads=$((reads+1)) ;;\n"
                + "      *r*) reads=0 ;;\n"
                + "      *) if [ -z \"$DONE\" ] && [ \"${reads:-0}\" -gt 100 ]; then /system/bin/mv -f \"$SWAP\" \"$TARGET\"; DONE=1; fi; reads=0 ;;\n"
                + "    esac\n"
                + "  done &\n"
                + "  PID=$!\n"
                + "  sleep 0.4\n"
                + "  input tap \"$TX\" \"$TY\"\n"
                + "  sleep 8\n"
                + "  kill \"$PID\" 2>/dev/null; pkill -f inotifyd 2>/dev/null\n"
                + "  cp \"$ORIG\" \"$TARGET\" 2>/dev/null; rm -f \"$SWAP\"\n"
                + "  set -- $(md5sum \"$SYS\")\n"
                + "  if [ \"$1\" = \"$WANT\" ]; then echo \"SUCCESS $1\" > \"$RESULT\"; exit 0; fi\n"
                + "done\n"
                + "set -- $(md5sum \"$SYS\")\n"
                + "echo \"FAILED $1\" > \"$RESULT\"\n";
    }

    private static void log(Callback cb, String line) {
        if (cb != null) MAIN.post(() -> cb.onLog(line));
    }

    private static void finish(Callback cb, boolean ok, String info) {
        if (cb != null) MAIN.post(() -> cb.onFinish(ok, info));
    }
}
