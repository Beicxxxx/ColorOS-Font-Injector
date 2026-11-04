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
 * 半自动注入：App 只负责「挂监听」，真正的「应用」由用户在主题商店里手动点真·按钮。
 *
 * 不自动点：自动点依赖屏幕坐标（点的是位置不是控件），换机型/DPI 会点偏；脚本又跑在
 * shizuku_server 下、独立于 App，一旦自动点+返回+重试就会和用户抢屏幕、杀 App 也停不掉。
 * 所以脚本全程不执行 input tap / keyevent / uiautomator、不做任何导航，只用 inotifyd
 * 监听记录文件，在校验读满后的关闭瞬间把暂存字体 mv 换上。
 *
 * 健壮性要点：
 * - trap 保证任何退出路径（正常/被 TERM/HUP/INT）都把记录原子还原成正版、清掉暂存，
 *   避免记录变脏导致后续恒 -15。
 * - 每轮 inotifyd 用 timeout 包住，杜绝「杀父后孤儿监听还在、日后劫持一次正常应用」。
 * - 还原记录用「同目录临时文件 + mv」原子完成，避免和用户点击撞上读到半截文件。
 * - Java 侧不主动 destroy() 脚本（SIGKILL 不可被 trap 捕获，会绕过还原）；只轮询结果，
 *   让脚本自己在约 120 秒窗口后收尾。
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
            // 按文件名里的时间戳选最新记录，而不是按 mtime（ls -t）：我们自己的
            // 交换/还原会不断改旧记录的 mtime，用 ls -t 会一直选错文件、永远错过。
            // 文件名 ..._colorFont_<epochms>.ttf，取 <epochms> 最大的一条。
            String recordsRes = Shell.run(
                    "for f in \"" + colorFontsDir + "\"/*_colorFont_*.ttf; do "
                  + "[ -e \"$f\" ] || continue; n=${f##*_}; n=${n%.ttf}; "
                  + "printf '%s %s\\n' \"$n\" \"$f\"; done | sort -rn | cut -d' ' -f2-").trim();
            if (recordsRes.isEmpty()) {
                log(cb, "[错误] colorFonts 目录下无历史记录文件！请先在主题商店生成一条 DIY 记录。");
                finish(cb, false, null);
                return;
            }
            String targetName = new File(recordsRes.split("\n")[0].trim()).getName();

            log(cb, "[4/4] 监听已就绪（约 " + WINDOW_SECONDS + " 秒）。");
            log(cb, "➡ 请打开『主题商店 → 历史 DIY 记录』，点你要的那条记录的【应用】。");
            log(cb, "   点一次后稍等十几秒看结果；没成功再点一次，命中即自动完成。");

            String script = buildScript(colorFontsDir, targetName, fontMd5);
            Shell.run("rm -f " + RESULT_FILE);
            Process scriptProc = Shell.process(
                    "cat > /data/local/tmp/run_app_watch.sh && chmod 755 /data/local/tmp/run_app_watch.sh && /data/local/tmp/run_app_watch.sh");
            try (FileOutputStream fos = (FileOutputStream) scriptProc.getOutputStream()) {
                fos.write(script.getBytes("UTF-8"));
                fos.flush();
            }

            // 只轮询结果，不主动 destroy 脚本（让它自己收尾并经 trap 还原）。
            // 预算比脚本窗口宽裕；若本 App 被切后台杀掉，脚本仍会写完结果，onResume 补结算。
            String result = "";
            for (int i = 0; i < WINDOW_SECONDS / 2 + 25; i++) {
                Thread.sleep(2000);
                result = Shell.run("cat " + RESULT_FILE + " 2>/dev/null").trim();
                if (result.startsWith("SUCCESS") || result.startsWith("TIMEOUT")
                        || result.startsWith("DIRTY")) break;
            }

            boolean success = result.startsWith("SUCCESS");
            boolean dirty = result.startsWith("DIRTY");
            String verifyRes = Shell.run(
                    "ls -la " + FontLibrary.SYS_FONT + " && md5sum " + FontLibrary.SYS_FONT).trim();
            log(cb, "[结果] " + result);
            log(cb, verifyRes);
            log(cb, success ? "✅ 系统字体 MD5 已匹配：" + fontName
                            : dirty ? "⚠️ 这条 DIY 记录已被弄脏（不是正版模板），注入会恒失败。请到主题商店重新生成一条 DIY 字体记录后再试。"
                            : "⚠️ 本轮未命中。确认已在主题商店 DIY 记录页点了【应用】；若窗口已过，再点一次「应用」重挂监听。");
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
     * trap 保证任何退出都原子还原记录并清暂存；每轮 inotifyd 由 timeout 包住防孤儿；
     * 还原走「同目录临时文件 + mv」原子完成。
     */
    private static String buildScript(String colorFontsDir, String targetName, String wantMd5) {
        return "#!/system/bin/sh\n"
                + "COLOR_FONTS=\"" + colorFontsDir + "\"\n"
                + "TARGET=\"$COLOR_FONTS/" + targetName + "\"\n"
                + "NEW=/data/local/tmp/app_font_inject.ttf\n"
                + "ORIG=/data/local/tmp/.orig_genuine.ttf\n"
                + "SWAP=\"$COLOR_FONTS/.swap_ready.dat\"\n"
                + "RTMP=\"$COLOR_FONTS/.restore.tmp\"\n"
                + "SYS=" + FontLibrary.SYS_FONT + "\n"
                + "RESULT=" + RESULT_FILE + "\n"
                + "WANT=\"" + wantMd5 + "\"\n"
                + "echo RUNNING > \"$RESULT\"\n"
                + "cp \"$TARGET\" \"$ORIG\" 2>/dev/null\n"              // capture genuine once
                // 若这条记录已被上一轮崩溃弄脏（内容=待注入字体），它不是正版基线，
                // 继续只会恒失败：直接报 DIRTY，让用户到主题商店重新生成一条 DIY 记录。
                + "set -- $(md5sum \"$ORIG\" 2>/dev/null)\n"
                + "if [ \"$1\" = \"$WANT\" ]; then echo \"DIRTY $1\" > \"$RESULT\"; rm -f \"$NEW\"; exit 0; fi\n"
                + "restore() { cp \"$ORIG\" \"$RTMP\" 2>/dev/null && /system/bin/mv -f \"$RTMP\" \"$TARGET\" 2>/dev/null; }\n"
                + "trap 'restore; rm -f \"$SWAP\" \"$RTMP\" \"$NEW\"' EXIT INT TERM HUP\n"
                + "END=$(( $(date +%s) + " + WINDOW_SECONDS + " ))\n"
                + "while [ \"$(date +%s)\" -lt \"$END\" ]; do\n"
                + "  restore\n"                                          // atomic: genuine in place before each arm
                + "  cp \"$NEW\" \"$SWAP\"; chmod 666 \"$SWAP\" 2>/dev/null\n"
                + "  reads=0; DONE=\n"
                + "  timeout 16 /system/bin/inotifyd - \"$TARGET:aw0r\" | while read -r ev p r; do\n"
                + "    case \"$ev\" in\n"
                + "      *a*) reads=$((reads+1)) ;;\n"
                + "      *r*) reads=0 ;;\n"
                + "      *) if [ -z \"$DONE\" ] && [ \"${reads:-0}\" -gt 100 ]; then /system/bin/mv -f \"$SWAP\" \"$TARGET\"; DONE=1; fi; reads=0 ;;\n"
                + "    esac\n"
                + "  done\n"
                + "  set -- $(md5sum \"$SYS\" 2>/dev/null)\n"
                + "  if [ \"$1\" = \"$WANT\" ]; then echo \"SUCCESS $1\" > \"$RESULT\"; exit 0; fi\n"
                + "done\n"
                + "set -- $(md5sum \"$SYS\" 2>/dev/null)\n"
                + "echo \"TIMEOUT $1\" > \"$RESULT\"\n"
                + "exit 0\n";
    }

    private static void log(Callback cb, String line) {
        if (cb != null) MAIN.post(() -> cb.onLog(line));
    }

    private static void finish(Callback cb, boolean ok, String info) {
        if (cb != null) MAIN.post(() -> cb.onFinish(ok, info));
    }
}
