package com.xunflash.bandlock;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

final class Root {
    static final class Out {
        final String text;
        final int code;
        Out(String text, int code) {
            this.text = text;
            this.code = code;
        }
    }

    static Out sh(String cmd, int timeoutMs) throws Exception {
        Process p = new ProcessBuilder("/system/bin/su", "-c", cmd)
                .redirectErrorStream(true)
                .start();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        Thread pump = new Thread(() -> {
            try (InputStream in = p.getInputStream()) {
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) >= 0) {
                    if (buf.size() < 1_500_000) buf.write(b, 0, n);
                }
            } catch (Exception ignored) {
            }
        });
        pump.start();
        boolean done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        if (!done) {
            p.destroyForcibly();
            pump.join(500);
            return new Out(buf.toString(StandardCharsets.UTF_8) + "\nTIMEOUT", 124);
        }
        pump.join(1000);
        return new Out(buf.toString(StandardCharsets.UTF_8), p.exitValue());
    }
}
