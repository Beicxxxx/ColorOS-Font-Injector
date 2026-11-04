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
 * 半自动注入：App 只负责「挂监听」，真正的「应用」由用户在主题商店里手动点。
 *
 * 为什么不自动点：自动点依赖屏幕坐标（识别的是位置不是控件），换机型、甚至同机型
 * 不同 DPI 都会点偏；而且脚本跑在 shizuku_server 下、独立于 App，一旦自动点+返回+
 * 重试就会和用户抢屏幕、杀 App 也停不掉。所以这里脚本**全程不执行 input tap /
 * keyevent / uiautomator、不做任何导航**，只用 inotifyd 监听记录文件，在校验读满后的
 * 关闭瞬间把暂存字体 mv 换上。约 120 秒内每轮自动重挂并把记录还原成正版，用户在
 * 主题商店点几次「应用」都能被接住，命中目标 MD5 即成功、随后还原记录。
 */
final class Injector {

    interface Callback {
        void onLog(String line);

        void onFinish(boolean success, String systemInfo);
    }

    static final String RESULT_FILE = "/data/local/tmp/app_inject_result.txt";
    static final int WINDOW_SECONDS = 120;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Injector() {}

    static void apply(Context ctx, File font, String fontName, Callback cb) {
        EXECUTOR.execute(() -> run(ctx, font, fontName, cb));
    }

    private static void run(Context ctx, File font, String fontName, Callback cb) {
        String fontMd5 = TtfUtil.md5(font).toLowerCase();
        try {
            log(cb, "[1/4] 暂存字体文件至设备运行环境...");
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

            log(cb, "[2/4] 定位主题商店 DIY 数据目录...");
            String diyDirRes = Shell.run("ls -d " + FontLibrary.THEME_TMP + "/*/colorFonts 2>/dev/null").trim();
            if (diyDirRes.isEmpty()) {
                log(cb, "[错误] 未找到主题商店 DIY 目录！请先在主题商店生成一款 DIY 字体。");
                finish(cb, false, null);
                return;
            }
            String colorFontsDir = diyDirRes.split("\n")[0].trim();

            log(cb, "[3/4] 查找当前有效的 DIY 记录模板（最新一条）...");
            String recordsRes = Shell.run("ls -t " + colorFontsDir + "/*_colorFont_*.ttf 2>/dev/null").trim();
            if (recordsRes.isEmpty()) {
                log(cb, "[错误] colorFonts 目录下无历史记录文件！请先在主题商店生成一条 DIY 记录。");
                finish(cb, false, null);
                return;
            }
            String targetName = new File(recordsRes.split("\n")[0].trim()).getName();

            log(cb, "[4/4] 监听已就绪（约 " + WINDOW_SECONDS + " 秒）。");
            log(cb, "➡ 请打开『主题商店 → 历史 DIY 记录』，点你要的那条记录的【应用】。");
            log(cb, "   没成功可在这 " + WINDOW_SECONDS + " 秒内多点几次，命中即自动完成。");

            String script = buildScript(colorFontsDir, targetName, fontMd5);
            Shell.run("rm -f " + RESULT_FILE);
            Process scriptProc = Shell.process(
                    "cat > /data/local/tmp/run_app_watch.sh && chmod 755 /data/local/tmp/run_app_watch.sh && /data/local/tmp/run_app_watch.sh");
            try (FileOutputStream fos = (FileOutputStream) scriptProc.getOutputStream()) {
                fos.write(script.getBytes("UTF-8"));
                fos.flush();
            }

            String result = "";
            for (int i = 0; i < WINDOW_SECONDS / 2 + 10; i++) {
                Thread.sleep(2000);
                result = Shell.run("cat " + RESULT_FILE + " 2>/dev/null").trim();
                if (result.startsWith("SUCCESS") || result.startsWith("TIMEOUT")) break;
            }
            try { scriptProc.destroy(); } catch (Exception ignored) {}

            boolean success = result.startsWith("SUCCESS");
            if (success || result.startsWith("TIMEOUT")) {
                Shell.run("rm -f " + RESULT_FILE);
            }
            String verifyRes = Shell.run(
                    "ls -la " + FontLibrary.SYS_FONT + " && md5sum " + FontLibrary.SYS_FONT).trim();
            log(cb, "[结果] " + result);
            log(cb, verifyRes);
            log(cb, success ? "✅ 系统字体 MD5 已匹配：" + fontName
                            : "⚠️ 本轮未命中。请确认已在主题商店 DIY 记录页点了【应用】，然后再点一次「应用」重试。");
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
     * 纯监听脚本：不执行 input/keyevent/uiautomator、不做任何导航。
     * 约 120 秒内每轮：还原记录为正版 → 暂存自定义字体 → 挂 inotifyd →
     * 在「读满（>100 次读）后的第一个关闭」处 mv 换入 → 等系统字体 MD5 命中。
     */
    private static String buildScript(String colorFontsDir, String targetName, String wantMd5) {
        return "#!/system/bin/sh\n"
                + "COLOR_FONTS=\"" + colorFontsDir + "\"\n"
                + "TARGET=\"$COLOR_FONTS/" + targetName + "\"\n"
                + "NEW=/data/local/tmp/app_font_inject.ttf\n"
                + "ORIG=/data/local/tmp/.orig_genuine.ttf\n"
                + "SWAP=\"$COLOR_FONTS/.swap_ready.dat\"\n"
                + "SYS=" + FontLibrary.SYS_FONT + "\n"
                + "RESULT=" + RESULT_FILE + "\n"
                + "WANT=\"" + wantMd5 + "\"\n"
                + "echo RUNNING > \"$RESULT\"\n"
                + "pkill -f inotifyd 2>/dev/null\n"
                + "cp \"$TARGET\" \"$ORIG\" 2>/dev/null\n"
                + "END=$(( $(date +%s) + " + WINDOW_SECONDS + " ))\n"
                + "while [ \"$(date +%s)\" -lt \"$END\" ]; do\n"
                + "  cp \"$ORIG\" \"$TARGET\" 2>/dev/null\n"
                + "  cp \"$NEW\" \"$SWAP\"; chmod 666 \"$SWAP\" 2>/dev/null\n"
                + "  reads=0; DONE=\n"
                + "  /system/bin/inotifyd - \"$TARGET:aw0r\" | while read -r ev p r; do\n"
                + "    case \"$ev\" in\n"
                + "      *a*) reads=$((reads+1)) ;;\n"
                + "      *r*) reads=0 ;;\n"
                + "      *) if [ -z \"$DONE\" ] && [ \"${reads:-0}\" -gt 100 ]; then /system/bin/mv -f \"$SWAP\" \"$TARGET\"; DONE=1; exit 0; fi; reads=0 ;;\n"
                + "    esac\n"
                + "  done &\n"
                + "  WPID=$!\n"
                + "  j=0\n"
                + "  while [ \"$j\" -lt 15 ]; do\n"
                + "    sleep 1\n"
                + "    set -- $(md5sum \"$SYS\" 2>/dev/null); [ \"$1\" = \"$WANT\" ] && break\n"
                + "    j=$((j+1))\n"
                + "  done\n"
                + "  sleep 1\n"
                + "  kill \"$WPID\" 2>/dev/null; pkill -f inotifyd 2>/dev/null\n"
                + "  set -- $(md5sum \"$SYS\" 2>/dev/null)\n"
                + "  if [ \"$1\" = \"$WANT\" ]; then cp \"$ORIG\" \"$TARGET\" 2>/dev/null; rm -f \"$SWAP\"; echo \"SUCCESS $1\" > \"$RESULT\"; exit 0; fi\n"
                + "done\n"
                + "cp \"$ORIG\" \"$TARGET\" 2>/dev/null; rm -f \"$SWAP\"\n"
                + "set -- $(md5sum \"$SYS\" 2>/dev/null)\n"
                + "echo \"TIMEOUT $1\" > \"$RESULT\"\n";
    }

    private static void log(Callback cb, String line) {
        if (cb != null) MAIN.post(() -> cb.onLog(line));
    }

    private static void finish(Callback cb, boolean ok, String info) {
        if (cb != null) MAIN.post(() -> cb.onFinish(ok, info));
    }
}
