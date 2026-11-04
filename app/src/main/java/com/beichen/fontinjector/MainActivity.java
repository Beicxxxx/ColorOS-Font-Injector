package com.beichen.fontinjector;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

public class MainActivity extends AppCompatActivity {

    private static final int SHIZUKU_REQ_CODE = 1001;

    private TextView tvShizukuStatus;
    private TextView tvSysFontInfo;
    private TextView tvLogs;
    private TextView tvCount;
    private TextView tvFontEmpty;
    private MaterialButton btnReqShizuku;
    private NestedScrollView logScroll;
    private RecyclerView recycler;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<FontLibrary.Item> items = new ArrayList<>();
    private FontAdapter adapter;
    private boolean busy;

    private final Shizuku.OnRequestPermissionResultListener permissionListener =
            (requestCode, grantResult) -> {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    log("[Shizuku] 权限已授予");
                } else {
                    log("[Shizuku] 权限被拒绝");
                }
                checkShizukuStatus();
            };

    private final Shizuku.OnBinderReceivedListener binderReceivedListener = this::checkShizukuStatus;
    private final Shizuku.OnBinderDeadListener binderDeadListener = this::checkShizukuStatus;

    private final ActivityResultLauncher<String[]> filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) importFont(uri);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvShizukuStatus = findViewById(R.id.tv_shizuku_status);
        tvSysFontInfo = findViewById(R.id.tv_sys_font_info);
        tvLogs = findViewById(R.id.tv_logs);
        tvCount = findViewById(R.id.tv_count);
        tvFontEmpty = findViewById(R.id.tv_font_empty);
        btnReqShizuku = findViewById(R.id.btn_req_shizuku);
        logScroll = findViewById(R.id.log_scroll);
        recycler = findViewById(R.id.recycler_fonts);

        adapter = new FontAdapter();
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(adapter);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.inflateMenu(R.menu.menu_main);
        toolbar.setOnMenuItemClickListener(this::onMenuItem);

        btnReqShizuku.setOnClickListener(v -> requestShizukuPermission());
        findViewById(R.id.btn_refresh_sys).setOnClickListener(v -> refreshSystemFontInfo());
        findViewById(R.id.btn_backup).setOnClickListener(v -> backupSystemFont());
        findViewById(R.id.fab_import).setOnClickListener(v -> filePickerLauncher.launch(
                new String[]{"font/ttf", "font/otf", "application/octet-stream", "*/*"}));

        applyWindowInsets();

        Shizuku.addRequestPermissionResultListener(permissionListener);
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);
        checkShizukuStatus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Shizuku.removeRequestPermissionResultListener(permissionListener);
        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        Shizuku.removeBinderDeadListener(binderDeadListener);
        executor.shutdownNow();
    }

    // ---------- 窗口 ----------

    private void applyWindowInsets() {
        View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), root);
        controller.setAppearanceLightStatusBars(!night);
        controller.setAppearanceLightNavigationBars(!night);
    }

    // ---------- Shizuku ----------

    private boolean shellReady() {
        return Shizuku.pingBinder()
                && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
    }

    private void checkShizukuStatus() {
        if (!Shizuku.pingBinder()) {
            tvShizukuStatus.setText("❌ Shizuku 未运行，请先在 Shizuku 应用中启动服务");
            btnReqShizuku.setVisibility(View.GONE);
            tvSysFontInfo.setText("需 Shizuku 授权后读取系统字体现状");
            reloadFonts();
            return;
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            tvShizukuStatus.setText("✅ Shizuku 权限已获取 (Shell UID=2000)");
            btnReqShizuku.setVisibility(View.GONE);
            refreshSystemFontInfo();
        } else {
            tvShizukuStatus.setText("⚠️ 未授予 Shizuku 权限");
            btnReqShizuku.setVisibility(View.VISIBLE);
        }
        reloadFonts();
    }

    private void requestShizukuPermission() {
        if (Shizuku.pingBinder()) {
            Shizuku.requestPermission(SHIZUKU_REQ_CODE);
        } else {
            Toast.makeText(this, "Shizuku 未运行，请先启动 Shizuku", Toast.LENGTH_SHORT).show();
        }
    }

    private void refreshSystemFontInfo() {
        if (!shellReady()) {
            tvSysFontInfo.setText("需 Shizuku 授权后读取系统字体现状");
            return;
        }
        executor.execute(() -> {
            String res = Shell.run("ls -la " + FontLibrary.SYS_FONT
                    + " && md5sum " + FontLibrary.SYS_FONT).trim();
            mainHandler.post(() -> tvSysFontInfo.setText(
                    res.contains("Customized-Regular.ttf") ? res : "未检测到生效字体（可能为系统默认字体）"));
        });
    }

    /** 用当前生效的系统字体做一份备份，落进字体库，可随时再应用回去。 */
    private void backupSystemFont() {
        log("[Backup] 开始读取系统字体…");
        if (!shellReady()) {
            Toast.makeText(this, "需要先授予 Shizuku 权限", Toast.LENGTH_SHORT).show();
            return;
        }
        executor.execute(() -> {
            File file = FontLibrary.backupSystemFont(this,
                    History.now().replace(" ", "_").replace(":", ""));
            mainHandler.post(() -> {
                if (file == null) {
                    log("[Error] 备份失败：读不到系统字体");
                    Toast.makeText(this, "备份失败", Toast.LENGTH_SHORT).show();
                    return;
                }
                History.Entry e = new History.Entry();
                e.ts = History.now();
                e.action = "backup";
                e.font = file.getName();
                e.ok = true;
                e.note = TtfUtil.size(file.length());
                e.path = file.getAbsolutePath();
                History.add(this, e);
                log("[Backup] 已备份当前系统字体 → " + file.getName());
                Toast.makeText(this, "已备份到字体库", Toast.LENGTH_SHORT).show();
                reloadFonts();
            });
        });
    }

    // ---------- 字体库 ----------

    private void reloadFonts() {
        boolean shellOk = shellReady();
        executor.execute(() -> {
            List<FontLibrary.Item> list = FontLibrary.list(this, shellOk);
            mainHandler.post(() -> {
                items.clear();
                items.addAll(list);
                adapter.notifyDataSetChanged();
                tvCount.setText(items.size() + " 个");
                tvFontEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
            });
        });
    }

    private void importFont(Uri uri) {
        executor.execute(() -> {
            File saved = FontLibrary.savePicked(this, uri);
            mainHandler.post(() -> {
                if (saved == null) {
                    log("[Error] 导入失败");
                    Toast.makeText(this, "导入失败", Toast.LENGTH_SHORT).show();
                    return;
                }
                log("[Import] 已导入 " + saved.getName() + " (" + TtfUtil.size(saved.length()) + ")");
                reloadFonts();
            });
        });
    }

    private void applyFont(FontLibrary.Item item) {
        if (busy) return;
        if (!shellReady()) {
            Toast.makeText(this, "需要先授予 Shizuku 权限", Toast.LENGTH_SHORT).show();
            return;
        }
        busy = true;
        log("\n========== 开始注入：" + item.name + " ==========");
        executor.execute(() -> {
            File file = FontLibrary.ensureLocal(this, item);
            if (file == null) {
                mainHandler.post(() -> {
                    busy = false;
                    log("[Error] 读不到文件：" + item.path);
                });
                return;
            }
            Injector.apply(this, file, item.name, new Injector.Callback() {
                @Override
                public void onLog(String line) {
                    log(line);
                }

                @Override
                public void onFinish(boolean success, String systemInfo) {
                    busy = false;
                    refreshSystemFontInfo();
                    new MaterialAlertDialogBuilder(MainActivity.this)
                            .setTitle(success ? "🎉 注入成功" : "⚠️ 注入未对齐")
                            .setMessage(success
                                    ? "系统字体已替换为 " + item.name + "，无需重启。\n\n" + systemInfo
                                    : "系统字体 MD5 与所选字体不一致。\n\n请确认主题商店已有 DIY 字体记录，"
                                      + "并确保执行时主题商店处在该 DIY 字体页面上后重试。")
                            .setPositiveButton("好", null)
                            .show();
                }
            });
        });
    }

    private void showFontActions(FontLibrary.Item item) {
        String meta = "来源：" + item.source
                + "\n大小：" + TtfUtil.size(item.size)
                + (item.upem > 0 ? "\nUPM：" + item.upem : "")
                + (item.family.isEmpty() ? "" : "\n家族名：" + item.family)
                + "\n路径：" + item.path;
        // AlertDialog 不支持 message + setItems 同时展示，三个动作直接用三个按钮
        new MaterialAlertDialogBuilder(this)
                .setTitle(item.name)
                .setMessage(meta)
                .setPositiveButton("应用", (d, w) -> applyFont(item))
                .setNeutralButton("保存", (d, w) -> exportFont(item))
                .setNegativeButton("删除", (d, w) -> deleteFont(item))
                .show();
    }

    private void exportFont(FontLibrary.Item item) {
        executor.execute(() -> {
            File file = FontLibrary.ensureLocal(this, item);
            boolean ok = file != null && FontLibrary.exportToDownload(file, item.name);
            mainHandler.post(() -> {
                if (ok) {
                    History.Entry e = new History.Entry();
                    e.ts = History.now();
                    e.action = "export";
                    e.font = item.name;
                    e.ok = true;
                    History.add(this, e);
                    log("[Export] 已保存到 /sdcard/Download/" + item.name);
                } else {
                    log("[Error] 导出失败：" + item.name);
                }
                Toast.makeText(this, ok ? "已保存到 Download" : "导出失败", Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void deleteFont(FontLibrary.Item item) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("删除字体")
                .setMessage("确定删除 " + item.name + " ？")
                .setPositiveButton("删除", (d, w) -> executor.execute(() -> {
                    boolean ok = FontLibrary.delete(this, item);
                    mainHandler.post(() -> {
                        log(ok ? "[Delete] 已删除 " + item.name : "[Error] 删除失败");
                        reloadFonts();
                    });
                }))
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------- 菜单 ----------

    private boolean onMenuItem(MenuItem mi) {
        int id = mi.getItemId();
        if (id == R.id.action_diy) {
            launch("com.heytap.themestore", "未安装主题商店");
        } else if (id == R.id.action_shizuku) {
            launch("moe.shizuku.privileged.api", "未安装 Shizuku");
        } else if (id == R.id.action_history) {
            startActivity(new Intent(this, HistoryActivity.class));
        } else if (id == R.id.action_about) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("使用说明")
                    .setMessage("1. 装好 Shizuku 并授权本应用（uid 2000 shell）。\n"
                            + "2. 在主题商店「字体」里做一款 DIY 字体，确保目录 "
                            + "Themes/.data/temp/<id>/colorFonts/ 下已有 *_colorFont_*.ttf 记录。\n"
                            + "3. 回到本应用，导入或从字体库选字体 → 应用；注入期间会自动点主题商店的"
                            + "「应用」按钮（坐标按机型写死）。\n"
                            + "4. 改 UPEM / 缩放 / 假粗 / CFF 转 TTF 请先用 fontctl 处理好再导入。\n\n"
                            + "提示：注入前记得先备份系统字体，出问题可在历史里回滚。")
                    .setPositiveButton("知道了", null)
                    .show();
        }
        return true;
    }

    private void launch(String pkg, String error) {
        Intent intent = getPackageManager().getLaunchIntentForPackage(pkg);
        if (intent == null) {
            Toast.makeText(this, error, Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(intent);
    }

    // ---------- 日志 ----------

    private void log(String msg) {
        mainHandler.post(() -> {
            tvLogs.append(msg + "\n");
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    // ---------- 列表 ----------

    private class FontAdapter extends RecyclerView.Adapter<FontAdapter.VH> {

        class VH extends RecyclerView.ViewHolder {
            final TextView name;
            final TextView meta;
            final MaterialButton apply;

            VH(View v) {
                super(v);
                name = v.findViewById(R.id.tv_name);
                meta = v.findViewById(R.id.tv_meta);
                apply = v.findViewById(R.id.btn_apply);
            }
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_font, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            FontLibrary.Item item = items.get(position);
            holder.name.setText(item.name);
            String meta = item.source + " · " + TtfUtil.size(item.size)
                    + (item.upem > 0 ? String.format(Locale.US, " · UPM %d", item.upem) : "");
            holder.meta.setText(meta);
            holder.apply.setOnClickListener(v -> applyFont(item));
            holder.itemView.setOnClickListener(v -> showFontActions(item));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }
}
