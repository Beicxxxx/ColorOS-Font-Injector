package com.beichen.fontinjector;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

public class MainActivity extends AppCompatActivity {

    private static final int SHIZUKU_REQ_CODE = 1001;

    private TextView tvShizukuStatus;
    private Button btnReqShizuku;
    private TextView tvSysFontInfo;
    private Button btnRefreshSysFont;
    private TextView tvSelectedFontInfo;
    private Button btnPickFont;
    private Button btnInjectApply;
    private TextView tvLogs;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private File cachedFontFile = null;
    private String selectedFontMd5 = "";
    private long selectedFontSize = 0;
    private int selectedFontUpem = 0;
    private String selectedFontName = "";

    private final Shizuku.OnRequestPermissionResultListener permissionListener = (requestCode, grantResult) -> {
        if (requestCode == SHIZUKU_REQ_CODE) {
            checkShizukuStatus();
        }
    };

    private final Shizuku.OnBinderReceivedListener binderReceivedListener = this::checkShizukuStatus;
    private final Shizuku.OnBinderDeadListener binderDeadListener = this::checkShizukuStatus;

    private final ActivityResultLauncher<String[]> filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> {
                if (uri != null) {
                    handlePickedFont(uri);
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        applyWindowInsets();

        tvShizukuStatus = findViewById(R.id.tv_shizuku_status);
        btnReqShizuku = findViewById(R.id.btn_req_shizuku);
        tvSysFontInfo = findViewById(R.id.tv_sysfont_info);
        btnRefreshSysFont = findViewById(R.id.btn_refresh_sysfont);
        tvSelectedFontInfo = findViewById(R.id.tv_selected_font_info);
        btnPickFont = findViewById(R.id.btn_pick_font);
        btnInjectApply = findViewById(R.id.btn_inject_apply);
        tvLogs = findViewById(R.id.tv_logs);
        tvLogs.setMovementMethod(new ScrollingMovementMethod());

        btnReqShizuku.setOnClickListener(v -> requestShizukuPermission());
        btnRefreshSysFont.setOnClickListener(v -> refreshSystemFontInfo());
        btnPickFont.setOnClickListener(v -> filePickerLauncher.launch(new String[]{"*/*"}));
        btnInjectApply.setOnClickListener(v -> executeInjection());

        Shizuku.addRequestPermissionResultListener(permissionListener);
        Shizuku.addBinderReceivedListener(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);

        checkShizukuStatus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Shizuku.removeRequestPermissionResultListener(permissionListener);
        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        Shizuku.removeBinderDeadListener(binderDeadListener);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // If an injection finished while this app was in the background (or was
        // killed by ColorOS and relaunched), surface its result now. The script
        // runs under shizuku_server and completes independently of this app.
        executor.execute(() -> {
            try {
                if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return;
                String r = runShizukuShell("cat /data/local/tmp/app_inject_result.txt 2>/dev/null").trim();
                if (r.startsWith("SUCCESS") || r.startsWith("FAILED")) {
                    runShizukuShell("rm -f /data/local/tmp/app_inject_result.txt");
                    final boolean ok = r.startsWith("SUCCESS");
                    mainHandler.post(() -> {
                        btnInjectApply.setEnabled(cachedFontFile != null);
                        refreshSystemFontInfo();
                        Toast.makeText(MainActivity.this, ok ? "注入已完成 ✅" : "注入未成功，请重试", Toast.LENGTH_LONG).show();
                    });
                }
            } catch (Exception ignored) {}
        });
    }

    private void log(String msg) {
        mainHandler.post(() -> {
            tvLogs.append(msg + "\n");
            final int scrollAmount = tvLogs.getLayout() != null ?
                    tvLogs.getLayout().getLineTop(tvLogs.getLineCount()) - tvLogs.getHeight() : 0;
            if (scrollAmount > 0) {
                tvLogs.scrollTo(0, scrollAmount);
            }
        });
    }

    // targetSdk 35+ 在 Android 15/16 强制 edge-to-edge，必须自己让开状态栏/导航栏
    private void applyWindowInsets() {
        View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), root);
        controller.setAppearanceLightStatusBars(!night);
        controller.setAppearanceLightNavigationBars(!night);
    }

    private void checkShizukuStatus() {
        if (!Shizuku.pingBinder()) {
            tvShizukuStatus.setText("❌ Shizuku 未运行，请先在 Shizuku 应用中启动服务");
            btnReqShizuku.setVisibility(View.GONE);
            btnInjectApply.setEnabled(false);
            return;
        }

        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            tvShizukuStatus.setText("✅ Shizuku 权限已获取 (Shell UID=2000)");
            btnReqShizuku.setVisibility(View.GONE);
            if (cachedFontFile != null) {
                btnInjectApply.setEnabled(true);
            }
            refreshSystemFontInfo();
        } else if (Shizuku.shouldShowRequestPermissionRationale()) {
            tvShizukuStatus.setText("⚠️ 未授予 Shizuku 权限");
            btnReqShizuku.setVisibility(View.VISIBLE);
            btnInjectApply.setEnabled(false);
        } else {
            tvShizukuStatus.setText("⚠️ 未授予 Shizuku 权限");
            btnReqShizuku.setVisibility(View.VISIBLE);
            btnInjectApply.setEnabled(false);
        }
    }

    private void requestShizukuPermission() {
        if (Shizuku.pingBinder()) {
            Shizuku.requestPermission(SHIZUKU_REQ_CODE);
        } else {
            Toast.makeText(this, "Shizuku 未运行，请启动 Shizuku", Toast.LENGTH_SHORT).show();
        }
    }

    private void refreshSystemFontInfo() {
        if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            tvSysFontInfo.setText("需 Shizuku 授权后读取");
            return;
        }

        executor.execute(() -> {
            String res = runShizukuShell("ls -la /data/format_unclear/font/Customized-Regular.ttf && md5sum /data/format_unclear/font/Customized-Regular.ttf");
            mainHandler.post(() -> {
                if (res.contains("Customized-Regular.ttf")) {
                    tvSysFontInfo.setText(res.trim());
                } else {
                    tvSysFontInfo.setText("未检测到生效字体 (可能为默认系统字体)");
                }
            });
        });
    }

    private void handlePickedFont(Uri uri) {
        executor.execute(() -> {
            try {
                String fileName = "selected.ttf";
                try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        if (idx >= 0) fileName = cursor.getString(idx);
                    }
                }
                selectedFontName = fileName;

                File cacheFile = new File(getCacheDir(), "picked_font.ttf");
                try (InputStream in = getContentResolver().openInputStream(uri);
                     FileOutputStream out = new FileOutputStream(cacheFile)) {
                    byte[] buf = new byte[65536];
                    int len;
                    while ((len = in.read(buf)) != -1) {
                        out.write(buf, 0, len);
                    }
                }
                cachedFontFile = cacheFile;
                selectedFontSize = cacheFile.length();
                selectedFontMd5 = calculateMd5(cacheFile);
                selectedFontUpem = parseTtfUpem(cacheFile);

                mainHandler.post(() -> {
                    String info = "名称: " + selectedFontName + "\n"
                            + "大小: " + String.format("%.2f MB", selectedFontSize / (1024.0 * 1024.0)) + " (" + selectedFontSize + " 字节)\n"
                            + "MD5: " + selectedFontMd5 + "\n"
                            + "unitsPerEm: " + selectedFontUpem + (selectedFontUpem == 256 ? " (标准)" : " (非 256)");
                    tvSelectedFontInfo.setText(info);
                    log("[File] 已加载字体: " + selectedFontName + ", UPEM=" + selectedFontUpem);

                    if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                        btnInjectApply.setEnabled(true);
                    }
                });

            } catch (Exception e) {
                log("[Error] 读取字体失败: " + e.getMessage());
            }
        });
    }

    private int parseTtfUpem(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] header = new byte[12];
            if (fis.read(header) < 12) return 0;
            ByteBuffer bb = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
            bb.getInt(); // sfntVersion
            int numTables = bb.getShort() & 0xFFFF;

            for (int i = 0; i < numTables; i++) {
                byte[] entry = new byte[16];
                if (fis.read(entry) < 16) break;
                String tag = new String(entry, 0, 4);
                if ("head".equals(tag)) {
                    ByteBuffer entryBb = ByteBuffer.wrap(entry).order(ByteOrder.BIG_ENDIAN);
                    entryBb.position(8);
                    int offset = entryBb.getInt();

                    // Read head table
                    try (FileInputStream headFis = new FileInputStream(file)) {
                        headFis.skip(offset + 18);
                        byte[] upemBuf = new byte[2];
                        if (headFis.read(upemBuf) == 2) {
                            return ByteBuffer.wrap(upemBuf).order(ByteOrder.BIG_ENDIAN).getShort() & 0xFFFF;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private String calculateMd5(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        try (InputStream is = new FileInputStream(file)) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = is.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        byte[] md5sum = digest.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : md5sum) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    private Process createShizukuProcess(String[] cmd) throws Exception {
        java.lang.reflect.Method method = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
        method.setAccessible(true);
        return (Process) method.invoke(null, cmd, null, null);
    }

    private String runShizukuShell(String cmd) {
        StringBuilder output = new StringBuilder();
        try {
            Process process = createShizukuProcess(new String[]{"sh", "-c", cmd});
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            process.waitFor();
        } catch (Exception e) {
            output.append("Error: ").append(e.getMessage());
        }
        return output.toString();
    }

    private void executeInjection() {
        if (cachedFontFile == null || !cachedFontFile.exists()) {
            Toast.makeText(this, "请先选择字体文件", Toast.LENGTH_SHORT).show();
            return;
        }

        btnInjectApply.setEnabled(false);
        log("\n========== 开始 TOCTOU 注入 ==========");

        executor.execute(() -> {
            try {
                // Step 1: Copy selected font to /data/local/tmp/app_font_inject.ttf
                log("[1/6] 暂存字体文件至设备运行环境...");
                String pushCmd = "cat > /data/local/tmp/app_font_inject.ttf && chmod 666 /data/local/tmp/app_font_inject.ttf";
                Process pushProc = createShizukuProcess(new String[]{"sh", "-c", pushCmd});
                try (FileInputStream fis = new FileInputStream(cachedFontFile);
                     FileOutputStream pos = (FileOutputStream) pushProc.getOutputStream()) {
                    byte[] buf = new byte[65536];
                    int len;
                    while ((len = fis.read(buf)) != -1) {
                        pos.write(buf, 0, len);
                    }
                    pos.flush();
                }
                pushProc.waitFor();

                // Step 2: Locate Theme Store DIY folder
                log("[2/6] 定位主题商店 DIY 数据目录...");
                String diyDirRes = runShizukuShell("ls -d /sdcard/Android/data/com.heytap.themestore/files/Themes/.data/temp/*/colorFonts 2>/dev/null").trim();
                if (diyDirRes.isEmpty()) {
                    log("[Error] 未找到主题商店 DIY 目录！请先在主题商店中下载或生成一款 DIY 字体。");
                    mainHandler.post(() -> btnInjectApply.setEnabled(true));
                    return;
                }
                String colorFontsDir = diyDirRes.split("\n")[0].trim();
                String baseDiyDir = new File(colorFontsDir).getParent();
                log("[ThemeStore] 基础目录: " + baseDiyDir);

                // Step 3: Find target record file
                log("[3/6] 查找当前有效的 DIY 记录模板...");
                String recordsRes = runShizukuShell("ls -t " + colorFontsDir + "/*_colorFont_*.ttf 2>/dev/null").trim();
                if (recordsRes.isEmpty()) {
                    log("[Error] colorFonts 目录下无历史记录文件！");
                    mainHandler.post(() -> btnInjectApply.setEnabled(true));
                    return;
                }
                String targetRecord = recordsRes.split("\n")[0].trim();
                String targetName = new File(targetRecord).getName();
                log("[Target] 匹配记录文件: " + targetName);

                // Step 4-5: run the ENTIRE TOCTOU orchestration as one self-
                // contained Shizuku shell script. It runs under shizuku_server
                // (a child of that process, NOT of this app), so it keeps running
                // and finishes even if ColorOS kills this app after it backgrounds
                // itself to drive the Theme Store. The script brings the Theme
                // Store DIY history page forward, dismisses a stale -15 dialog,
                // finds the "应用" button, arms the read-count swap, taps, and
                // retries up to 6 times, writing the outcome to a result file.
                // Earlier the orchestration lived in Java and pressed BACK from
                // this app's own activity, which exited the app to the launcher
                // (the "闪退"); doing it in the shell avoids that entirely.
                log("[4/5] 启动自包含注入脚本（由 Shizuku 托管，闪退也不影响）...");
                String want = selectedFontMd5.toLowerCase();
                String script = "#!/system/bin/sh\n"
                        + "COLOR_FONTS=\"" + colorFontsDir + "\"\n"
                        + "TARGET=\"$COLOR_FONTS/" + targetName + "\"\n"
                        + "NEW=/data/local/tmp/app_font_inject.ttf\n"
                        + "ORIG=/data/local/tmp/.orig_genuine.ttf\n"
                        + "SWAP=\"$COLOR_FONTS/.swap_ready.dat\"\n"
                        + "SYS=/data/format_unclear/font/Customized-Regular.ttf\n"
                        + "UIXML=/data/local/tmp/app_ui.xml\n"
                        + "RESULT=/data/local/tmp/app_inject_result.txt\n"
                        + "WANT=\"" + want + "\"\n"
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

                runShizukuShell("rm -f /data/local/tmp/app_inject_result.txt");
                Process scriptProc = createShizukuProcess(new String[]{"sh", "-c", "cat > /data/local/tmp/run_app_inject.sh && chmod 755 /data/local/tmp/run_app_inject.sh && /data/local/tmp/run_app_inject.sh"});
                try (FileOutputStream fos = (FileOutputStream) scriptProc.getOutputStream()) {
                    fos.write(script.getBytes());
                    fos.flush();
                }

                // Poll the result file. The script is independent of this app, so
                // even if ColorOS kills us while the Theme Store is foreground,
                // the script finishes and writes RESULT; onResume() surfaces it.
                log("[5/5] 等待注入结果（商店会被拉到前台，本应用可能退到后台）...");
                String result = "";
                for (int i = 0; i < 60; i++) {
                    Thread.sleep(2000);
                    result = runShizukuShell("cat /data/local/tmp/app_inject_result.txt 2>/dev/null").trim();
                    if (result.startsWith("SUCCESS") || result.startsWith("FAILED")) break;
                }
                try { scriptProc.destroy(); } catch (Exception ignored) {}

                final boolean success = result.startsWith("SUCCESS");
                if (success || result.startsWith("FAILED")) {
                    runShizukuShell("rm -f /data/local/tmp/app_inject_result.txt");
                }
                final String verifyRes = runShizukuShell("ls -la /data/format_unclear/font/Customized-Regular.ttf && md5sum /data/format_unclear/font/Customized-Regular.ttf");
                log("[Result] " + result + "\n" + verifyRes.trim());

                mainHandler.post(() -> {
                    btnInjectApply.setEnabled(true);
                    refreshSystemFontInfo();
                    if (success) {
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("🎉 注入成功！")
                                .setMessage("系统字体已成功替换为 " + selectedFontName + "！\n\nMD5: " + selectedFontMd5 + "\n大小: " + selectedFontSize + " 字节\n\n新字体已即刻生效，无需重启设备。")
                                .setPositiveButton("太棒了", null)
                                .show();
                    } else {
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("⚠️ 注入未完全对齐")
                                .setMessage("系统字体 MD5 尚未与所选字体一致。\n\n请先在主题商店打开到“历史 DIY 记录”界面，再回到本应用重试。")
                                .setPositiveButton("知道了", null)
                                .show();
                    }
                });

            } catch (Exception e) {
                log("[Fatal Error] " + e.getMessage());
                mainHandler.post(() -> btnInjectApply.setEnabled(true));
            }
        });
    }
}
