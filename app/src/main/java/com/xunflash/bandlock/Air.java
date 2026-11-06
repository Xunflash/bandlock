package com.xunflash.bandlock;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One reading of the registered cell, parsed from dumpsys. */
final class Air {
    static final int NONE = 2147483647;
    boolean reg;
    String tech = "";
    int chan = -1;
    int band = -1;
    double mhz = -1;
    Integer rsrp;
    Integer sinr;
    String op = "";

    static Air read() throws Exception {
        Root.Out out = Root.sh("dumpsys telephony.registry", 12000);
        return parse(out.text);
    }

    static Air parse(String text) {
        Air a = new Air();
        int cut = indexOfHistory(text);
        String live = cut > 0 ? text.substring(0, cut) : text;
        int at = live.lastIndexOf("mDataRegState=0(IN_SERVICE)");
        if (at < 0) return a;
        String chunk = live.substring(at, Math.min(live.length(), at + 7000));
        a.reg = true;
        a.tech = group(chunk, "getRilDataRadioTechnology=\\d+\\(([^)]+)\\)");
        String chan = group(chunk, "mChannelNumber=(-?\\d+)");
        if (chan != null) {
            int c = Integer.parseInt(chan);
            if (c > 0 && c < NONE) a.chan = c;
        }
        a.op = group(chunk, "mOperatorAlphaLong=([^,\\n]*)");
        if (a.op == null) a.op = "";
        boolean nr = a.tech != null && a.tech.contains("NR");
        if (nr && a.chan > 0) {
            a.mhz = nrMhz(a.chan);
            a.band = nrBand(a.mhz);
            int[] s = lastGood(chunk, "ssRsrp = (-?\\d+) ssRsrq = (-?\\d+) ssSinr = (-?\\d+)");
            if (s != null) {
                a.rsrp = s[0];
                a.sinr = s[2];
            }
        } else if (a.chan > 0) {
            a.band = lteBand(a.chan);
            a.mhz = lteMhz(a.band, a.chan);
            String r = group(chunk, "CellSignalStrengthLte: rssi=-?\\d+ rsrp=(-?\\d+)");
            String n = group(chunk, "rssnr=(-?\\d+)");
            if (r != null) {
                int v = Integer.parseInt(r);
                if (v > -200 && v < 0) a.rsrp = v;
            }
            if (n != null) {
                int v = Integer.parseInt(n);
                if (v > -50 && v < 50) a.sinr = v;
            }
        }
        if (a.tech == null) a.tech = "";
        return a;
    }

    private static int indexOfHistory(String text) {
        Matcher m = Pattern.compile("\n\\s+20\\d\\d-").matcher(text);
        return m.find() ? m.start() : -1;
    }

    private static String group(String text, String pat) {
        Matcher m = Pattern.compile(pat).matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static int[] lastGood(String text, String pat) {
        Matcher m = Pattern.compile(pat).matcher(text);
        int[] last = null;
        while (m.find()) {
            int r = Integer.parseInt(m.group(1));
            int q = Integer.parseInt(m.group(2));
            int s = Integer.parseInt(m.group(3));
            if (r > -200 && r < 0) last = new int[] {r, q, s};
        }
        return last;
    }

    static double nrMhz(int arfcn) {
        if (arfcn < 600000) return arfcn * 0.005;
        if (arfcn < 2016667) return 3000 + (arfcn - 600000) * 0.015;
        return -1;
    }

    static int nrBand(double mhz) {
        if (mhz < 0) return -1;
        if (mhz >= 758 && mhz <= 803) return 28;
        if (mhz >= 869 && mhz <= 894) return 5;
        if (mhz >= 925 && mhz <= 960) return 8;
        if (mhz >= 1805 && mhz <= 1880) return 3;
        if (mhz >= 2110 && mhz <= 2170) return 1;
        if (mhz >= 2300 && mhz <= 2400) return 40;
        if (mhz >= 2496 && mhz <= 2690) return 41;
        if (mhz >= 4400 && mhz <= 5000) return 79;
        if (mhz >= 3300 && mhz <= 3800) return 78;
        if (mhz >= 3300 && mhz <= 4200) return 77;
        return -1;
    }

    static boolean nrContains(int band, double mhz) {
        if (mhz < 0) return false;
        switch (band) {
            case 1: return mhz >= 2110 && mhz <= 2170;
            case 3: return mhz >= 1805 && mhz <= 1880;
            case 5: return mhz >= 869 && mhz <= 894;
            case 8: return mhz >= 925 && mhz <= 960;
            case 28: return mhz >= 758 && mhz <= 803;
            case 40: return mhz >= 2300 && mhz <= 2400;
            case 41: return mhz >= 2496 && mhz <= 2690;
            case 77: return mhz >= 3300 && mhz <= 4200;
            case 78: return mhz >= 3300 && mhz <= 3800;
            case 79: return mhz >= 4400 && mhz <= 5000;
            default: return nrBand(mhz) == band;
        }
    }

    static int lteBand(int earfcn) {
        if (earfcn >= 0 && earfcn <= 599) return 1;
        if (earfcn >= 1200 && earfcn <= 1949) return 3;
        if (earfcn >= 2400 && earfcn <= 2649) return 5;
        if (earfcn >= 3450 && earfcn <= 3799) return 8;
        if (earfcn >= 9210 && earfcn <= 9659) return 28;
        if (earfcn >= 36200 && earfcn <= 36349) return 34;
        if (earfcn >= 37750 && earfcn <= 38249) return 38;
        if (earfcn >= 38250 && earfcn <= 38649) return 39;
        if (earfcn >= 38650 && earfcn <= 39649) return 40;
        if (earfcn >= 39650 && earfcn <= 41589) return 41;
        return -1;
    }

    static double lteMhz(int band, int earfcn) {
        switch (band) {
            case 1: return 2110 + 0.1 * earfcn;
            case 3: return 1805 + 0.1 * (earfcn - 1200);
            case 5: return 869 + 0.1 * (earfcn - 2400);
            case 8: return 925 + 0.1 * (earfcn - 3450);
            case 28: return 758 + 0.1 * (earfcn - 9210);
            case 34: return 2010 + 0.1 * (earfcn - 36200);
            case 38: return 2570 + 0.1 * (earfcn - 37750);
            case 39: return 1880 + 0.1 * (earfcn - 38250);
            case 40: return 2300 + 0.1 * (earfcn - 38650);
            case 41: return 2496 + 0.1 * (earfcn - 39650);
            default: return -1;
        }
    }
}
