package com.beichen.fontinjector;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 字体库：应用私有导入目录 + 主题商店 DIY 目录 + Download，统一成一个列表。 */
final class FontLibrary {

    static final String SYS_FONT = "/data/format_unclear/font/Customized-Regular.ttf";
    static final String THEME_TMP =
            "/sdcard/Android/data/com.heytap.themestore/files/Themes/.data/temp";
    static final String DOWNLOAD = "/sdcard/Download";

    static final class Item {
        String name;
        String source;      // 导入 / DIY / 下载
        String path;        // 本地绝对路径，或设备上的路径
        boolean local;
        long size;
        int upem;
        String family = "";
    }

    private FontLibrary() {}

    static File importDir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "fonts");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    static File backupDir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "backups");
        if (!d.exists()) d.mkdirs();
        return d;
    }


    static List<Item> list(Context ctx, boolean shellOk) {
        Map<String, Item> out = new LinkedHashMap<>();

        for (File f : importDir(ctx).listFiles((d, n) -> n.toLowerCase().endsWith(".ttf"))) {
            Item it = local(f, "导入");
            out.put(it.name, it);
        }
        if (!shellOk) return new ArrayList<>(out.values());

        for (String line : Shell.run("ls -l " + THEME_TMP + "/*/colorFonts/*.ttf 2>/dev/null").split("\n")) {
            Item it = remote(line, "DIY");
            if (it != null && !out.containsKey(it.name)) out.put(it.name, it);
        }
        for (String line : Shell.run("ls -l " + DOWNLOAD + "/*.ttf 2>/dev/null").split("\n")) {
            Item it = remote(line, "下载");
            if (it != null && !out.containsKey(it.name)) out.put(it.name, it);
        }
        return new ArrayList<>(out.values());
    }

    private static Item local(File f, String source) {
        Item it = new Item();
        it.name = f.getName();
        it.source = source;
        it.path = f.getAbsolutePath();
        it.local = true;
        it.size = f.length();
        TtfUtil.Meta m = TtfUtil.read(f);
        it.upem = m.upem;
        it.family = m.family;
        return it;
    }

    /** 解析 `ls -l` 一行：权限 链接 属主 属组 大小 日期 时间 名字 */
    private static Item remote(String line, String source) {
        String[] p = line.trim().split("\\s+");
        if (p.length < 8) return null;
        String path = p[p.length - 1];
        if (!path.toLowerCase().endsWith(".ttf")) return null;
        Item it = new Item();
        it.name = new File(path).getName();
        it.source = source;
        it.path = path;
        it.local = false;
        try {
            it.size = Long.parseLong(p[4]);
        } catch (Exception ignored) {
        }
        return it;
    }

    /** 远程条目先拉到 cache，注入/解析都只认本地文件。 */
    static File ensureLocal(Context ctx, Item it) {
        if (it.local) return new File(it.path);
        File dst = new File(ctx.getCacheDir(), "remote_" + it.name);
        if (!dst.exists() && !Shell.pull(it.path, dst)) return null;
        return dst.exists() ? dst : null;
    }

    static boolean exportToDownload(File font, String name) {
        String target = DOWNLOAD + "/" + name;
        return Shell.push(font, target) || Shell.run("cp '" + font.getAbsolutePath() + "' '" + target + "'").trim().isEmpty();
    }

    static boolean delete(Context ctx, Item it) {
        if (it.local) return new File(it.path).delete();
        Shell.run("rm -f '" + it.path + "'");
        return true;
    }

    /** 抓一份当前生效的系统字体（只有 shell 读得到），落进字体库，可随时再应用回去。 */
    static File backupSystemFont(Context ctx, String tag) {
        File dst = new File(importDir(ctx), "系统备份_" + tag + ".ttf");
        return Shell.pull(SYS_FONT, dst) ? dst : null;
    }

    static File savePicked(Context ctx, Uri uri) {
        String name = "imported_" + System.currentTimeMillis() + ".ttf";
        try (Cursor c = ctx.getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String picked = c.getString(idx);
                    if (picked != null && picked.toLowerCase().endsWith(".ttf")) name = picked;
                }
            }
        } catch (Exception ignored) {
        }
        File dst = new File(importDir(ctx), name);
        try (InputStream in = ctx.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int len;
            while ((len = in.read(buf)) != -1) out.write(buf, 0, len);
            return dst;
        } catch (Exception e) {
            return null;
        }
    }
}
