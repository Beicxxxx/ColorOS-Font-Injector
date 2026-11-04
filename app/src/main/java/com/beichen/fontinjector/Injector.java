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
 * TOCTOU 注入：主题商店应用 DIY 字体时会连读 3 次同一路径，
 * 在第 2 次 open 的毫秒窗口里 mv -f 换掉文件，让受 SELinux 保护的
 * /data/format_unclear/font/Customized-Regular.ttf 被写成自定义字体。
 */
final class Injector {

    interface Callback {
        void onLog(String line);

        void onFinish(boolean success, String systemInfo);
    }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Injector() {}

    static void apply(Context ctx, File font, String fontName, Callback cb) {
        EXECUTOR.execute(() -> run(ctx, font, fontName, cb));
    }

    private static void run(Context ctx, File font, String fontName, Callback cb) {
        String fontMd5 = TtfUtil.md5(font);
        StringBuilder sysInfo = new StringBuilder();
        try {
            // Step 1: 暂存字体到 /data/local/tmp
            log(cb, "[1/6] 暂存字体文件至设备运行环境...");
            String pushCmd = "cat > /data/local/tmp/app_font_inject.ttf && chmod 666 /data/local/tmp/app_font_inject.ttf";
            Process pushProc = Shell.process(pushCmd);
            try (FileInputStream fis = new FileInputStream(font);
                 FileOutputStream pos = (FileOutputStream) pushProc.getOutputStream()) {
                byte[] buf = new byte[65536];
                int len;
                while ((len = fis.read(buf)) != -1) pos.write(buf, 0, len);
                pos.flush();
            }
            pushProc.waitFor();

            // Step 2: 定位主题商店 DIY 目录
            log(cb, "[2/6] 定位主题商店 DIY 数据目录...");
            String diyDirRes = Shell.run("ls -d " + FontLibrary.THEME_TMP + "/*/colorFonts 2>/dev/null").trim();
            if (diyDirRes.isEmpty()) {
                log(cb, "[Error] 未找到主题商店 DIY 目录！请先在主题商店中下载或生成一款 DIY 字体。");
                finish(cb, false, null);
                return;
            }
            String colorFontsDir = diyDirRes.split("\n")[0].trim();
            String baseDiyDir = new File(colorFontsDir).getParent();
            log(cb, "[ThemeStore] 基础目录: " + baseDiyDir);

            // Step 3: 找当前有效的 DIY 记录模板
            log(cb, "[3/6] 查找当前有效的 DIY 记录模板...");
            String recordsRes = Shell.run("ls -t " + colorFontsDir + "/*_colorFont_*.ttf 2>/dev/null").trim();
            if (recordsRes.isEmpty()) {
                log(cb, "[Error] colorFonts 目录下无历史记录文件！");
                finish(cb, false, null);
                return;
            }
            String targetRecord = recordsRes.split("\n")[0].trim();
            String targetName = new File(targetRecord).getName();
            log(cb, "[Target] 匹配记录文件: " + targetName);

            // Step 4: 原子暂存 + inotifyd 监听脚本
            log(cb, "[4/6] 部署精确原子 Inode 竞态监听器...");
            String script = "#!/system/bin/sh\n"
                    + "DIR=\"" + baseDiyDir + "\"\n"
                    + "COLOR_FONTS=\"$DIR/colorFonts\"\n"
                    + "TARGET_NAME=\"" + targetName + "\"\n"
                    + "TARGET=\"$COLOR_FONTS/$TARGET_NAME\"\n"
                    + "ORIG=\"$DIR/.orig_backup_clean.ttf\"\n"
                    + "SWAP_TMP=\"$COLOR_FONTS/.swap_ready.dat\"\n"
                    + "input keyevent 4\n"
                    + "sleep 0.4\n"
                    + "pkill -f inotifyd 2>/dev/null\n"
                    + "if [ ! -f \"$ORIG\" ]; then cp \"$TARGET\" \"$ORIG\"; fi\n"
                    + "cp \"$ORIG\" \"$TARGET\"\n"
                    + "cp /data/local/tmp/app_font_inject.ttf \"$SWAP_TMP\"\n"
                    + "chmod 666 \"$TARGET\" \"$SWAP_TMP\"\n"
                    + "rm -f /data/local/tmp/app_swap.log\n"
                    + "/system/bin/inotifyd - \"$COLOR_FONTS:r\" | while read -r ev d f; do\n"
                    + "    if [ \"$f\" = \"$TARGET_NAME\" ]; then\n"
                    + "        COUNT=$((COUNT + 1))\n"
                    + "        if [ \"$COUNT\" -eq 2 ]; then\n"
                    + "            /system/bin/mv -f \"$SWAP_TMP\" \"$TARGET\"\n"
                    + "            echo \"SWAPPED\" > /data/local/tmp/app_swap.log\n"
                    + "        fi\n"
                    + "    fi\n"
                    + "done &\n"
                    + "PIPE_PID=$!\n"
                    + "sleep 0.3\n"
                    // shortcut: 主题商店「应用」按钮坐标写死，换机型/分辨率需改这里
                    + "input tap 1056 672\n"
                    + "sleep 3.5\n"
                    + "kill $PIPE_PID 2>/dev/null\n"
                    + "pkill -f inotifyd 2>/dev/null\n"
                    + "cp \"$ORIG\" \"$TARGET\"\n"
                    + "chmod 666 \"$TARGET\"\n"
                    + "rm -f \"$SWAP_TMP\"\n";

            // Step 5: 执行脚本
            log(cb, "[5/6] 触发无感替换流程...");
            Process scriptProc = Shell.process(
                    "cat > /data/local/tmp/run_app_inject.sh && chmod 755 /data/local/tmp/run_app_inject.sh && /data/local/tmp/run_app_inject.sh");
            try (FileOutputStream fos = (FileOutputStream) scriptProc.getOutputStream()) {
                fos.write(script.getBytes());
                fos.flush();
            }
            scriptProc.waitFor();

            // Step 6: 校验系统字体
            log(cb, "[6/6] 校验系统字体写入状态...");
            String verifyRes = Shell.run(
                    "ls -la " + FontLibrary.SYS_FONT + " && md5sum " + FontLibrary.SYS_FONT);
            log(cb, "[Result]\n" + verifyRes.trim());
            sysInfo.append(verifyRes.trim());

            boolean success = verifyRes.toLowerCase().contains(fontMd5.toLowerCase());
            log(cb, success ? "✅ 系统字体 MD5 已匹配：" + fontName : "⚠️ 系统字体 MD5 未匹配");
            finish(cb, success, sysInfo.toString());

            History.Entry e = new History.Entry();
            e.ts = History.now();
            e.action = "apply";
            e.font = fontName;
            e.upem = String.valueOf(TtfUtil.read(font).upem);
            e.ok = success;
            e.note = TtfUtil.size(font.length());
            History.add(ctx, e);
        } catch (Exception ex) {
            log(cb, "[Fatal Error] " + ex.getMessage());
            finish(cb, false, null);
        }
    }

    private static void log(Callback cb, String line) {
        if (cb != null) MAIN.post(() -> cb.onLog(line));
    }

    private static void finish(Callback cb, boolean ok, String info) {
        if (cb != null) MAIN.post(() -> cb.onFinish(ok, info));
    }
}
