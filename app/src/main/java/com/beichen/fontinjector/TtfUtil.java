package com.beichen.fontinjector;

import android.util.Base64;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** 直接读 TTF 头（只取列表要显示的字段，不引第三方字体库）。 */
final class TtfUtil {

    static final class Meta {
        int upem;
        int weight;
        String family = "";
        String display = "";   // 字体内部名：优先全名(nameID=4)，退家族名(nameID=1[+2])
        boolean cff;
    }

    private TtfUtil() {}

    /** 字节源：本地文件随机读，或设备远程文件按段读（dd+base64）。 */
    private interface Src {
        byte[] read(int off, int len);
    }

    private static final class LocalSrc implements Src {
        private final File f;
        LocalSrc(File f) { this.f = f; }
        public byte[] read(int off, int len) {
            if (len <= 0 || off < 0) return len == 0 ? new byte[0] : null;
            try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                long total = raf.length();
                if (off >= total) return null;
                int n = (int) Math.min(len, total - off);
                raf.seek(off);
                byte[] buf = new byte[n];
                raf.readFully(buf);
                return buf;
            } catch (Exception e) {
                return null;
            }
        }
    }

    private static final class RemoteSrc implements Src {
        private final String path;
        RemoteSrc(String path) { this.path = path; }
        public byte[] read(int off, int len) {
            if (len <= 0 || off < 0) return len == 0 ? new byte[0] : null;
            String b64 = Shell.run("dd if='" + path + "' bs=1 skip=" + off
                    + " count=" + len + " 2>/dev/null | base64 -w0").trim();
            if (b64.isEmpty()) return null;
            try {
                return Base64.decode(b64, Base64.DEFAULT);
            } catch (Exception e) {
                return null;
            }
        }
    }

    static Meta read(File file) {
        return parse(new LocalSrc(file));
    }

    /** 远程字体（DIY/下载）：不整文件拉取，只按偏移读 header/目录/name 表三段。 */
    static Meta readRemote(String path) {
        return parse(new RemoteSrc(path));
    }

    private static Meta parse(Src s) {
        Meta m = new Meta();
        byte[] head = s.read(0, 12);
        if (head == null || head.length < 12) return m;
        int numTables = u16(head, 4);
        if (numTables <= 0 || numTables > 4096) return m;
        byte[] dir = s.read(12, numTables * 16);
        if (dir == null) return m;
        int headOff = -1, os2Off = -1, nameOff = -1, nameLen = 0;
        for (int i = 0; i < numTables; i++) {
            int b = i * 16;
            if (b + 16 > dir.length) break;
            String tag = new String(dir, b, 4, StandardCharsets.ISO_8859_1);
            int off = i32(dir, b + 8);
            int len = i32(dir, b + 12);
            switch (tag) {
                case "head": headOff = off; break;
                case "OS/2": os2Off = off; break;
                case "name": nameOff = off; nameLen = len; break;
                case "CFF ": m.cff = true; break;
                default: break;
            }
        }
        if (headOff >= 0) {
            byte[] b = s.read(headOff, 20);
            if (b != null && b.length >= 20) m.upem = u16(b, 18);
        }
        if (os2Off >= 0) {
            byte[] b = s.read(os2Off, 6);
            if (b != null && b.length >= 6) m.weight = u16(b, 4);
        }
        if (nameOff >= 0) parseName(s, nameOff, nameLen, m);
        return m;
    }

    /** 解析 name 表：按平台/语言打分，取最合适的 全名(4) / 家族(1) / 子族(2)。 */
    private static void parseName(Src s, int nameOff, int nameLen, Meta m) {
        byte[] nt = s.read(nameOff, nameLen);
        if (nt == null || nt.length < 6) return;
        int count = u16(nt, 2);
        int strOff = u16(nt, 4);
        int[] bestRank = new int[5];   // 下标用 nameID：1,2,4
        String[] bestVal = new String[5];
        for (int i = 0; i < count; i++) {
            int b = 6 + i * 12;
            if (b + 12 > nt.length) break;
            int platform = u16(nt, b);
            int lang = u16(nt, b + 4);
            int nameId = u16(nt, b + 6);
            int length = u16(nt, b + 8);
            int offset = u16(nt, b + 10);
            if (nameId != 1 && nameId != 2 && nameId != 4) continue;
            int sOff = strOff + offset;
            if (sOff < 0 || sOff + length > nt.length) continue;
            String val = decode(platform, nt, sOff, length).replace("\u0000", "").trim();
            if (val.isEmpty()) continue;
            int r = rank(platform, lang);
            if (r > bestRank[nameId]) {
                bestRank[nameId] = r;
                bestVal[nameId] = val;
            }
        }
        String full = bestVal[4], fam = bestVal[1], sub = bestVal[2];
        m.family = fam != null ? fam : "";
        if (full != null) {
            m.display = full;
        } else if (fam != null) {
            m.display = (sub != null && !sub.equalsIgnoreCase("regular")) ? fam + " " + sub : fam;
        } else {
            m.display = "";
        }
    }

    private static String decode(int platform, byte[] buf, int off, int len) {
        try {
            if (platform == 0 || platform == 3) {
                return new String(buf, off, len, StandardCharsets.UTF_16BE);
            }
            return new String(buf, off, len, StandardCharsets.ISO_8859_1);
        } catch (Exception e) {
            return "";
        }
    }

    /** 平台/语言偏好：Windows 英文 > Windows 其他 > Unicode > Mac 英文 > 其余。 */
    private static int rank(int platform, int lang) {
        if (platform == 3 && lang == 0x409) return 5;
        if (platform == 3) return 4;
        if (platform == 0) return 3;
        if (platform == 1 && lang == 0) return 2;
        return 1;
    }

    private static int u16(byte[] b, int i) {
        return ((b[i] & 0xff) << 8) | (b[i + 1] & 0xff);
    }

    private static int i32(byte[] b, int i) {
        return ((b[i] & 0xff) << 24) | ((b[i + 1] & 0xff) << 16)
                | ((b[i + 2] & 0xff) << 8) | (b[i + 3] & 0xff);
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
