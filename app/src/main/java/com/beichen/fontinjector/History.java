package com.beichen.fontinjector;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 本地历史记录：files/history.jsonl，一行一条，最新的排前面。 */
final class History {

    static final class Entry {
        String ts;
        String action;   // apply / backup / export
        String font;
        String upem;
        boolean ok;
        String note;
        String path;

        JSONObject toJson() {
            try {
                return new JSONObject()
                        .put("ts", ts).put("action", action).put("font", font)
                        .put("upem", upem).put("ok", ok).put("note", note == null ? "" : note)
                        .put("path", path == null ? "" : path);
            } catch (Exception e) {
                return new JSONObject();
            }
        }

        static Entry from(String line) {
            try {
                JSONObject o = new JSONObject(line);
                Entry e = new Entry();
                e.ts = o.optString("ts");
                e.action = o.optString("action");
                e.font = o.optString("font");
                e.upem = o.optString("upem");
                e.ok = o.optBoolean("ok");
                e.note = o.optString("note");
                e.path = o.optString("path");
                return e;
            } catch (Exception e) {
                return null;
            }
        }

        String actionLabel() {
            switch (action) {
                case "backup": return "备份系统字体";
                case "export": return "导出到 Download";
                default: return ok ? "应用成功" : "应用失败";
            }
        }
    }

    private History() {}

    private static File file(Context ctx) {
        return new File(ctx.getFilesDir(), "history.jsonl");
    }

    static void add(Context ctx, Entry e) {
        try (FileOutputStream out = new FileOutputStream(file(ctx), true)) {
            out.write((e.toJson().toString() + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    static List<Entry> list(Context ctx) {
        List<Entry> out = new ArrayList<>();
        File f = file(ctx);
        if (!f.exists()) return out;
        try {
            for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                if (line.trim().isEmpty()) continue;
                Entry e = Entry.from(line);
                if (e != null) out.add(e);
            }
        } catch (Exception ignored) {
        }
        Collections.reverse(out);
        return out;
    }

    static void clear(Context ctx) {
        file(ctx).delete();
    }

    static String now() {
        return new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
                .format(new java.util.Date());
    }
}
