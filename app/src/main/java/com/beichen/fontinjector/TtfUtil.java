package com.beichen.fontinjector;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.Locale;

/** 直接读 TTF 头（只取列表要显示的字段，不引第三方字体库）。 */
final class TtfUtil {

    static final class Meta {
        int upem;
        int weight;
        String family = "";
        boolean cff;
    }

    private TtfUtil() {}

    static Meta read(File file) {
        Meta m = new Meta();
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] head = new byte[12];
            if (fis.read(head) < 12) return m;
            ByteBuffer bb = ByteBuffer.wrap(head).order(ByteOrder.BIG_ENDIAN);
            bb.getInt();
            int numTables = bb.getShort() & 0xFFFF;
            int headOff = -1, os2Off = -1, nameOff = -1;
            for (int i = 0; i < numTables; i++) {
                byte[] rec = new byte[16];
                if (fis.read(rec) < 16) break;
                String tag = new String(rec, 0, 4);
                int off = ByteBuffer.wrap(rec).order(ByteOrder.BIG_ENDIAN).getInt(8);
                switch (tag) {
                    case "head": headOff = off; break;
                    case "OS/2": os2Off = off; break;
                    case "name": nameOff = off; break;
                    case "CFF ": m.cff = true; break;
                    default: break;
                }
            }
            if (headOff >= 0) {
                ByteBuffer b = readAt(fis, headOff, 20);
                if (b != null) m.upem = b.getShort(18) & 0xFFFF;
            }
            if (os2Off >= 0) {
                ByteBuffer b = readAt(fis, os2Off, 6);
                if (b != null) m.weight = b.getShort(4) & 0xFFFF;
            }
            if (nameOff >= 0) m.family = readFamily(fis, nameOff);
        } catch (Exception ignored) {
        }
        return m;
    }

    private static ByteBuffer readAt(FileInputStream fis, int offset, int len) {
        try {
            fis.getChannel().position(offset);
            byte[] buf = new byte[len];
            int read = fis.read(buf);
            if (read <= 0) return null;
            return ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN);
        } catch (Exception e) {
            return null;
        }
    }

    /** name 表里取 nameID=1（家族名），优先 Windows/Unicode 平台。 */
    private static String readFamily(FileInputStream fis, int nameOff) {
        try {
            ByteBuffer head = readAt(fis, nameOff, 6);
            if (head == null) return "";
            int count = head.getShort(2) & 0xFFFF;
            int stringOff = head.getShort(4) & 0xFFFF;
            String fallback = "";
            for (int i = 0; i < count; i++) {
                ByteBuffer rec = readAt(fis, nameOff + 6 + i * 12, 12);
                if (rec == null) break;
                int platformId = rec.getShort(0) & 0xFFFF;
                int nameId = rec.getShort(6) & 0xFFFF;
                int length = rec.getShort(8) & 0xFFFF;
                int offset = rec.getShort(10) & 0xFFFF;
                if (nameId != 1) continue;
                ByteBuffer raw = readAt(fis, nameOff + stringOff + offset, length);
                if (raw == null) continue;
                String value;
                if (platformId == 0 || platformId == 3) {
                    value = new String(raw.array(), java.nio.charset.StandardCharsets.UTF_16BE);
                } else {
                    value = new String(raw.array(), java.nio.charset.StandardCharsets.ISO_8859_1);
                }
                value = value.replace("\u0000", "").trim();
                if (value.isEmpty()) continue;
                if (platformId == 3) return value;
                fallback = value;
            }
            return fallback;
        } catch (Exception e) {
            return "";
        }
    }

    static String md5(File file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            try (InputStream is = new FileInputStream(file)) {
                byte[] buffer = new byte[65536];
                int read;
                while ((read = is.read(buffer)) > 0) digest.update(buffer, 0, read);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest()) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    static String size(long bytes) {
        if (bytes >= 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
        if (bytes >= 1024) return String.format(Locale.US, "%d KB", bytes / 1024);
        return bytes + " B";
    }
}
