package com.xunflash.bandlock;

import java.util.Locale;

final class Band {
    static final int[] NR = {1, 78, 77, 5, 8, 3, 28, 41};
    static final int[] LTE = {1, 3, 5, 8, 34, 38, 39, 40, 41};
    static final String NR_BITS = "10000000000000000000";
    static final String LTE_BITS = "01000001000000000000";
    /** 5G/4G/3G/2G mask this phone has used as its ordinary user setting. */
    static final String DEFAULT_BITS = "11011111101111111111";
    static final String SPEED_URL =
            "http://mirrors.huaweicloud.com/nodejs/v20.18.1/node-v20.18.1-linux-x64.tar.xz";

    final boolean nr;
    final int number;
    boolean checked;
    String state = "等待";
    Integer rsrp;
    Integer sinr;
    Double mhz;
    Double mbps;
    Integer rtt;
    boolean supported = true;
    boolean camped;

    Band(boolean nr, int number) {
        this.nr = nr;
        this.number = number;
    }

    String key() {
        return (nr ? "n" : "b") + number;
    }

    String title() {
        return (nr ? "n" : "B") + number;
    }

    String detail() {
        if ("等待".equals(state) || "测试中".equals(state)) return state;
        if (!supported) return "这台机器没有这个频段";
        if (!camped) return "没有驻留";
        StringBuilder s = new StringBuilder();
        if (mhz != null && mhz > 0) s.append(String.format(Locale.US, "%.0f MHz", mhz)).append("  ");
        if (rsrp != null) s.append(rsrp).append(" dBm  ");
        if (sinr != null) s.append("SINR ").append(sinr).append("  ");
        if (mbps != null) s.append(String.format(Locale.US, "%.1f Mbps", mbps)).append("  ");
        if (rtt != null) s.append(rtt).append(" ms");
        String t = s.toString().trim();
        return t.isEmpty() ? state : t;
    }
}
