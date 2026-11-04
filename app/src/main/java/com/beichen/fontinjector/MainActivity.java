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

    // Center (x,y) of the newest DIY record's "应用" button. The newest
    // colorFont file (ls -t) is the top-most / left-most card, so return the
    // first "应用" button in reading order.
    private int[] findApplyButtonXY() {
        try {
            String xml = runShizukuShell("uiautomator dump /data/local/tmp/uidump.xml >/dev/null 2>&1; cat /data/local/tmp/uidump.xml");
            java.util.regex.Matcher nm = java.util.regex.Pattern.compile("<node\\b[^>]*?>").matcher(xml);
            java.util.regex.Pattern useId = java.util.regex.Pattern.compile("resource-id=\"[^\"]*/use\"");
            java.util.regex.Pattern bounds = java.util.regex.Pattern.compile("bounds=\"\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]\"");
            java.util.List<int[]> btns = new java.util.ArrayList<>();
            while (nm.find()) {
                String s = nm.group();
                if (s.contains("content-desc=\"应用\"") || useId.matcher(s).find()) {
                    java.util.regex.Matcher bm = bounds.matcher(s);
                    if (bm.find()) {
                        int x1 = Integer.parseInt(bm.group(1)), y1 = Integer.parseInt(bm.group(2));
                        int x2 = Integer.parseInt(bm.group(3)), y2 = Integer.parseInt(bm.group(4));
                        btns.add(new int[]{(x1 + x2) / 2, (y1 + y2) / 2});
                    }
                }
            }
            if (btns.isEmpty()) return null;
            btns.sort((a, b) -> a[1] != b[1] ? a[1] - b[1] : a[0] - b[0]);
            return btns.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    // Bring the "历史 DIY 记录" page to the foreground, dismissing a leftover
    // "-15 资源包已损坏" dialog or the apply-success H5 page.
    private void ensureHistoryPage() {
        for (int i = 0; i < 4; i++) {
            String xml = runShizukuShell("uiautomator dump /data/local/tmp/uidump.xml >/dev/null 2>&1; cat /data/local/tmp/uidump.xml");
            java.util.regex.Matcher nm = java.util.regex.Pattern.compile("<node\\b[^>]*?>").matcher(xml);
            java.util.regex.Pattern bounds = java.util.regex.Pattern.compile("bounds=\"\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]\"");
            while (nm.find()) {
                String s = nm.group();
                if (s.contains("text=\"知道了\"")) {
                    java.util.regex.Matcher bm = bounds.matcher(s);
                    if (bm.find()) {
                        int x1 = Integer.parseInt(bm.group(1)), y1 = Integer.parseInt(bm.group(2));
                        int x2 = Integer.parseInt(bm.group(3)), y2 = Integer.parseInt(bm.group(4));
                        runShizukuShell("input tap " + ((x1 + x2) / 2) + " " + ((y1 + y2) / 2));
                    }
                    break;
                }
            }
            String fg = runShizukuShell("dumpsys activity activities | grep topResumedActivity | head -1");
            if (fg.contains("DiyFontHistoryActivity")) return;
            runShizukuShell("input keyevent 4");
            try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
        }
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

                // Step 4-6: TOCTOU apply, retried until the system font matches.
                // Theme Store reads the whole record to validate its MD5 (many
                // read events), closes it, then re-opens it to copy it into the
                // system font path. Swap on the first close that follows a large
                // read burst: validation sees the genuine file, the copy reads
                // ours. Swapping on the "2nd open" is too late (that fd is
                // already bound to the old inode). The window is ~30ms so the
                // swap can lose the race; retry until it lands.
                log("[4/6] 部署读取计数竞态监听器并触发替换（最多重试 6 次）...");
                boolean applied = false;
                String verifyRes = "";
                for (int attempt = 1; attempt <= 6 && !applied; attempt++) {
                    log("  尝试 " + attempt + "/6 ...");
                    ensureHistoryPage();
                    int[] xy = findApplyButtonXY();
                    int tapX = (xy != null) ? xy[0] : 384;
                    int tapY = (xy != null) ? xy[1] : 672;

                    String script = "#!/system/bin/sh\n"
                            + "COLOR_FONTS=\"" + colorFontsDir + "\"\n"
                            + "TARGET_NAME=\"" + targetName + "\"\n"
                            + "TARGET=\"$COLOR_FONTS/$TARGET_NAME\"\n"
                            + "ORIG=\"/data/local/tmp/.orig_genuine.ttf\"\n"
                            + "SWAP_TMP=\"$COLOR_FONTS/.swap_ready.dat\"\n"
                            + "pkill -f inotifyd 2>/dev/null\n"
                            + "cp \"$TARGET\" \"$ORIG\"\n"
                            + "cp /data/local/tmp/app_font_inject.ttf \"$SWAP_TMP\"\n"
                            + "chmod 666 \"$SWAP_TMP\" 2>/dev/null\n"
                            + "reads=0\n"
                            + "/system/bin/inotifyd - \"$TARGET:aw0r\" | while read -r ev path rest; do\n"
                            + "    case \"$ev\" in\n"
                            + "        *a*) reads=$((reads+1)) ;;\n"
                            + "        *r*) reads=0 ;;\n"
                            + "        *)\n"
                            + "            if [ -z \"$DONE\" ] && [ \"${reads:-0}\" -gt 100 ]; then\n"
                            + "                /system/bin/mv -f \"$SWAP_TMP\" \"$TARGET\"\n"
                            + "                DONE=1\n"
                            + "            fi\n"
                            + "            reads=0 ;;\n"
                            + "    esac\n"
                            + "done &\n"
                            + "PIPE_PID=$!\n"
                            + "sleep 0.4\n"
                            + "input tap " + tapX + " " + tapY + "\n"
                            + "sleep 8\n"
                            + "kill $PIPE_PID 2>/dev/null\n"
                            + "pkill -f inotifyd 2>/dev/null\n"
                            + "cp \"$ORIG\" \"$TARGET\" 2>/dev/null\n"
                            + "rm -f \"$SWAP_TMP\"\n";

                    Process scriptProc = createShizukuProcess(new String[]{"sh", "-c", "cat > /data/local/tmp/run_app_inject.sh && chmod 755 /data/local/tmp/run_app_inject.sh && /data/local/tmp/run_app_inject.sh"});
                    try (FileOutputStream fos = (FileOutputStream) scriptProc.getOutputStream()) {
                        fos.write(script.getBytes());
                        fos.flush();
                    }
                    scriptProc.waitFor();

                    verifyRes = runShizukuShell("md5sum /data/format_unclear/font/Customized-Regular.ttf");
                    applied = verifyRes.toLowerCase().contains(selectedFontMd5.toLowerCase());
                    log("  系统字体: " + verifyRes.trim());
                }

                log("[6/6] 校验系统字体写入状态...");
                log("[Result]\n" + verifyRes.trim());

                final boolean success = applied;
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
                                .setMessage("系统字体 MD5 尚未与所选字体一致。\n\n提示：请确保主题商店已处于后台，或在主题商店“历史 DIY 记录”界面重试。")
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
