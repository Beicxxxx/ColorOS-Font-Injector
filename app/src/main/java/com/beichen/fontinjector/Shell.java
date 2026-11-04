package com.beichen.fontinjector;

import java.io.File;
import java.io.FileOutputStream;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;

import rikka.shizuku.Shizuku;

/** 通过 Shizuku 拿 shell（uid 2000）跑命令/传文件。所有调用前请确认 Shizuku 已授权。 */
final class Shell {

    private Shell() {}

    static Process process(String cmd) throws Exception {
        java.lang.reflect.Method m = Shizuku.class.getDeclaredMethod(
                "newProcess", String[].class, String[].class, String.class);
        m.setAccessible(true);
        return (Process) m.invoke(null, new String[]{"sh", "-c", cmd}, null, null);
    }

    static String run(String cmd) {
        StringBuilder sb = new StringBuilder();
        try {
            Process p = process(cmd);
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream()))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            p.waitFor();
        } catch (Exception e) {
            sb.append("Error: ").append(e.getMessage());
        }
        return sb.toString();
    }

    /** 本机文件 -> 设备路径（cat > path）。 */
    static boolean push(File src, String remote) {
        try {
            Process p = process("cat > '" + remote + "'");
            try (InputStream in = new java.io.FileInputStream(src);
                 OutputStream out = p.getOutputStream()) {
                byte[] buf = new byte[65536];
                int len;
                while ((len = in.read(buf)) != -1) out.write(buf, 0, len);
                out.flush();
            }
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** 设备路径 -> 本机文件（cat path）。 */
    static boolean pull(String remote, File dst) {
        try {
            File parent = dst.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            Process p = process("cat '" + remote + "'");
            try (InputStream in = p.getInputStream();
                 FileOutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[65536];
                int len;
                while ((len = in.read(buf)) != -1) out.write(buf, 0, len);
            }
            p.waitFor();
            return dst.length() > 0;
        } catch (Exception e) {
            return false;
        }
    }
}
