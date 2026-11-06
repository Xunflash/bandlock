package com.xunflash.bandlock;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class BandEngine {
    interface Ui {
        void onCurrent(String text);
        void onStatus(String text, int progress);
        void onBand(Band band);
        void onIdle(String text);
    }

    private static final String TAG = "BandLock";
    private static final String BIN = "/data/local/tmp/qcom-band-menu";

    private final Context context;
    private volatile Ui ui;
    private volatile boolean running;
    private volatile boolean abort;
    private volatile boolean abortToDefault;
    private int sim = 2;
    private int slot = 1;

    BandEngine(Context context) {
        this.context = context.getApplicationContext();
    }

    void setUi(Ui ui) {
        this.ui = ui;
    }

    boolean running() {
        return running;
    }

    void startScan() {
        if (running) return;
        abort = false;
        abortToDefault = false;
        running = true;
        new Thread(this::scan, "band-scan").start();
    }

    void stopToDefault() {
        abortToDefault = true;
        abort = true;
        if (!running) {
            running = true;
            new Thread(() -> {
                try {
                    prepare();
                    restoreDefault();
                    idle(readCurrentLine("已恢复硬件默认频段，网络模式为 5G/4G 自动"));
                } catch (Exception e) {
                    Log.e(TAG, "reset", e);
                    idle("恢复失败：" + e.getMessage());
                } finally {
                    running = false;
                }
            }, "band-reset").start();
        }
    }

    void lock(boolean nr, List<Band> picked) {
        if (running) return;
        if (picked.isEmpty()) {
            idle(nr ? "先勾选要锁定的 5G 频段" : "先勾选要锁定的 4G 频段");
            return;
        }
        running = true;
        abort = false;
        new Thread(() -> {
            try {
                prepare();
                StringBuilder nums = new StringBuilder();
                for (Band b : picked) {
                    if (nums.length() > 0) nums.append(',');
                    nums.append(b.number);
                }
                if (nr) {
                    status("锁定 5G " + nums, 50);
                    framework(Band.NR_BITS);
                    qmi("rat nr");
                    qmi("mode sa");
                    String msg = qmi("sa " + nums);
                    if (msg.contains("not sent")) throw new Exception(msg);
                } else {
                    status("锁定 4G " + nums, 50);
                    framework(Band.LTE_BITS);
                    qmi("rat lte");
                    String msg = qmi("lte " + nums);
                    if (msg.contains("not sent")) throw new Exception(msg);
                }
                sleep(4000);
                idle(readCurrentLine(nr ? "已锁定 5G " + nums : "已锁定 4G " + nums));
            } catch (Exception e) {
                Log.e(TAG, "lock", e);
                idle("锁定失败：" + e.getMessage());
            } finally {
                running = false;
            }
        }, "band-lock").start();
    }

    private void scan() {
        Saved saved = null;
        boolean changed = false;
        try {
            prepare();
            publishCurrent();
            saved = capture();
            changed = true;
            int total = Band.NR.length + Band.LTE.length;
            int done = 0;
            framework(Band.NR_BITS);
            qmi("rat nr");
            qmi("mode sa");
            for (int band : Band.NR) {
                if (abort) break;
                done++;
                status("测试 5G n" + band + "  " + done + "/" + total, done * 100 / total);
                Band row = probe(true, band);
                emit(row);
            }
            if (!abort) {
                framework(Band.LTE_BITS);
                qmi("rat lte");
                sleep(1500);
                for (int band : Band.LTE) {
                    if (abort) break;
                    done++;
                    status("测试 4G B" + band + "  " + done + "/" + total, done * 100 / total);
                    Band row = probe(false, band);
                    emit(row);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "scan", e);
            status("测试中断：" + e.getMessage(), 0);
        } finally {
            try {
                if (abortToDefault) restoreDefault();
                else if (changed && saved != null) restoreSaved(saved);
                sleep(2500);
                String tail = abortToDefault
                        ? "已恢复硬件默认频段，网络模式为 5G/4G 自动"
                        : "测试结束，已回到测试前的锁定";
                idle(readCurrentLine(tail));
            } catch (Exception e) {
                Log.e(TAG, "restore", e);
                idle("恢复测试前状态失败：" + e.getMessage());
            }
            running = false;
        }
    }

    private Band probe(boolean nr, int number) throws Exception {
        Band row = new Band(nr, number);
        row.state = "测试中";
        emit(row);
        String msg = qmi(nr ? "sa " + number : "lte " + number);
        if (msg.contains("not sent") || msg.contains("not supported") || msg.contains("Invalid")) {
            row.supported = false;
            row.state = "不支持";
            return row;
        }
        Air hit = null;
        long deadline = System.currentTimeMillis() + 12000;
        while (System.currentTimeMillis() < deadline) {
            Air air = Air.read();
            if (air.reg && air.mhz > 0 && (nr ? Air.nrContains(number, air.mhz) : air.band == number)) {
                sleep(1200);
                hit = Air.read();
                break;
            }
            sleep(2000);
        }
        if (hit == null || !hit.reg) {
            row.camped = false;
            row.state = "无信号";
            return row;
        }
        boolean match = nr ? Air.nrContains(number, hit.mhz) : hit.band == number;
        row.camped = match;
        row.mhz = hit.mhz > 0 ? hit.mhz : null;
        row.rsrp = hit.rsrp;
        row.sinr = hit.sinr;
        if (!match) {
            row.state = "驻留到了别的频段";
            return row;
        }
        row.rtt = ping();
        row.mbps = speed();
        row.state = "有信号";
        return row;
    }

    private void prepare() throws Exception {
        installBinary();
        detectSim();
        Root.Out id = Root.sh("id", 8000);
        if (!id.text.contains("uid=0")) throw new Exception("没有 root");
    }

    private void installBinary() throws Exception {
        File out = new File(context.getFilesDir(), "qcom-band-menu");
        if (!out.exists() || out.length() < 1000) {
            try (InputStream in = context.getAssets().open("qcom-band-menu");
                 FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) fos.write(buf, 0, n);
            }
        }
        Root.Out cp = Root.sh("cp '" + out.getAbsolutePath() + "' " + BIN + " && chmod 755 " + BIN, 8000);
        if (cp.code != 0) throw new Exception("无法安装锁频工具");
    }

    private void detectSim() throws Exception {
        Root.Out st = Root.sh("getprop gsm.sim.state", 5000);
        String[] parts = st.text.trim().split(",");
        int found = -1;
        for (int i = 0; i < parts.length; i++) {
            if ("LOADED".equals(parts[i].trim())) found = i;
        }
        if (found >= 0) {
            slot = found;
            sim = found + 1;
        }
        Log.i(TAG, "sim " + sim + " slot " + slot + " state " + st.text.trim());
    }

    private String qmi(String command) throws Exception {
        String script = "printf '%s\\n' 'sim " + sim + "' '" + command + "' 'exit' | " + BIN;
        Root.Out out = Root.sh(script, 20000);
        Log.i(TAG, "qmi " + command + " -> " + lastMessage(out.text));
        return out.text;
    }

    private static String lastMessage(String text) {
        String last = "";
        for (String line : text.split("\n")) {
            if (line.startsWith("Message:")) last = line.substring("Message:".length()).trim();
        }
        return last.isEmpty() ? text : last;
    }

    private void framework(String bits) throws Exception {
        Root.Out out = Root.sh(
                "cmd phone set-allowed-network-types-for-users -s " + slot + " " + bits, 8000);
        Log.i(TAG, "framework " + bits + " " + out.text.trim());
        if (!out.text.contains("completed") && out.code != 0) {
            throw new Exception("切换网络模式失败");
        }
    }

    private Saved capture() throws Exception {
        Saved s = new Saved();
        String text = qmi("refresh");
        s.rat = lastLine(text, "RAT:");
        s.sa = lastLine(text, "NR-SA:");
        Root.Out fw = Root.sh("cmd phone get-allowed-network-types-for-users -s " + slot, 8000);
        s.framework = fw.text.trim();
        Log.i(TAG, "saved rat=" + s.rat + " sa=" + s.sa + " fw=" + s.framework);
        return s;
    }

    private static String lastLine(String text, String prefix) {
        String last = "";
        for (String line : text.split("\n")) {
            if (line.startsWith(prefix)) last = line.substring(prefix.length()).trim();
        }
        return last;
    }

    private void restoreSaved(Saved saved) throws Exception {
        status("回到测试前的锁定", 99);
        qmi("reset");
        if (saved.rat.contains("NR") && !saved.rat.contains("LTE")) qmi("rat nr");
        else qmi("rat auto");
        qmi("mode both");
        List<Integer> sa = parseBands(saved.sa);
        if (!sa.isEmpty() && sa.size() <= 4) {
            StringBuilder nums = new StringBuilder();
            for (int n : sa) {
                if (nums.length() > 0) nums.append(',');
                nums.append(n);
            }
            qmi("mode sa");
            qmi("sa " + nums);
        }
        if (saved.framework.equals("NR")) framework(Band.NR_BITS);
        else if (saved.framework.equals("LTE")) framework(Band.LTE_BITS);
        else framework(Band.DEFAULT_BITS);
    }

    private void restoreDefault() throws Exception {
        status("恢复系统默认", 99);
        String msg = qmi("reset");
        qmi("rat auto");
        qmi("mode both");
        framework(Band.DEFAULT_BITS);
        if (msg.contains("failed") || msg.contains("not sent")) {
            throw new Exception(lastMessage(msg));
        }
    }

    private static List<Integer> parseBands(String saLine) {
        List<Integer> out = new ArrayList<>();
        if (saLine == null || saLine.isEmpty() || saLine.contains("none")) return out;
        for (String part : saLine.split(",")) {
            String t = part.trim();
            if (t.startsWith("n")) t = t.substring(1);
            if (t.isEmpty()) continue;
            try {
                out.add(Integer.parseInt(t));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    private Integer ping() {
        try {
            Root.Out out = Root.sh("ping -c 3 -W 1 223.5.5.5", 8000);
            Matcher m = Pattern.compile("time=([0-9.]+)").matcher(out.text);
            double sum = 0;
            int n = 0;
            while (m.find()) {
                sum += Double.parseDouble(m.group(1));
                n++;
            }
            return n == 0 ? null : (int) Math.round(sum / n);
        } catch (Exception e) {
            return null;
        }
    }

    private Double speed() {
        try {
            String cmd = "curl -4 -o /dev/null -s --max-time 6 -w '%{http_code} %{speed_download}' '"
                    + Band.SPEED_URL + "'";
            Root.Out out = Root.sh(cmd, 12000);
            String t = out.text.trim();
            String[] p = t.split("\\s+");
            if (p.length < 2) return null;
            if (!p[0].startsWith("2")) return null;
            double bytes = Double.parseDouble(p[p.length - 1]);
            return bytes * 8.0 / 1_000_000.0;
        } catch (Exception e) {
            return null;
        }
    }

    private void publishCurrent() {
        try {
            Ui u = ui;
            if (u != null) u.onCurrent(readCurrentLine(""));
        } catch (Exception ignored) {
        }
    }

    private String readCurrentLine(String prefix) throws Exception {
        Air air = Air.read();
        String cell;
        if (!air.reg) cell = "当前没有注册小区";
        else {
            String name = air.tech.contains("NR") ? "5G" : "4G";
            String band = air.band > 0 ? (air.tech.contains("NR") ? "n" : "B") + air.band : air.tech;
            cell = String.format(Locale.US, "当前 %s %s", name, band);
            if (air.mhz > 0) cell += String.format(Locale.US, "  %.0f MHz", air.mhz);
            if (air.rsrp != null) cell += "  " + air.rsrp + " dBm";
            if (air.sinr != null) cell += "  SINR " + air.sinr;
            if (!air.op.isEmpty()) cell += "  " + air.op;
        }
        if (prefix == null || prefix.isEmpty()) return cell;
        return prefix + "\n" + cell;
    }

    private void emit(Band band) {
        Ui u = ui;
        if (u != null) u.onBand(band);
    }

    private void status(String text, int progress) {
        Ui u = ui;
        if (u != null) u.onStatus(text, progress);
    }

    private void idle(String text) {
        Ui u = ui;
        if (u != null) u.onIdle(text);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class Saved {
        String rat = "";
        String sa = "";
        String framework = "NR";
    }
}
