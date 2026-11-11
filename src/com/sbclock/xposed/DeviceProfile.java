package com.sbclock.xposed;

import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 识别当前 ROM 与版本，并给出对应的适配策略。
 *
 * 适配范围（可用版本）：
 *   MIUI     12 / 12.5 / 13 / 14
 *   HyperOS  1.x / 2.x / 3.x
 *   类原生   全部（标准 Clock 视图恒定存在）
 *
 * 识别依据：
 *   HyperOS -> ro.mi.os.version.name（如 OS2.0.1.0.VNBCNXM）、ro.mi.os.version.code
 *   MIUI    -> ro.miui.ui.version.name（V14、V125 等）
 */
public final class DeviceProfile {

    private static final Pattern NUM = Pattern.compile("(\\d+)\\.(\\d+)");

    public final String rom;
    public final String rawVersion;
    public final int major;
    public final int minor;
    public final String androidVer;
    public final boolean supported;

    private DeviceProfile(String rom, String rawVersion, int major, int minor, String androidVer) {
        this.rom = rom;
        this.rawVersion = rawVersion;
        this.major = major;
        this.minor = minor;
        this.androidVer = androidVer;
        this.supported = computeSupported(rom, major);
    }

    private static boolean computeSupported(String rom, int major) {
        if ("HyperOS".equals(rom)) {
            return major >= 1 && major <= 3;
        }
        if ("MIUI".equals(rom)) {
            return major >= 12 && major <= 14;
        }
        return true;
    }

    public static DeviceProfile read() {
        String androidVer = Build.VERSION.RELEASE == null ? "?" : Build.VERSION.RELEASE;

        String hyperName = prop("ro.mi.os.version.name");
        String hyperCode = prop("ro.mi.os.version.code");
        String miuiName = prop("ro.miui.ui.version.name");

        if (hyperName.length() > 0 || hyperCode.length() > 0) {
            int major = 0;
            int minor = 0;
            Matcher m = NUM.matcher(hyperName);
            if (m.find()) {
                major = parseInt(m.group(1));
                minor = parseInt(m.group(2));
            } else if (hyperCode.length() > 0) {
                major = parseInt(hyperCode);
            }
            return new DeviceProfile("HyperOS", hyperName, major, minor, androidVer);
        }

        if (miuiName.length() > 0) {
            String v = miuiName;
            if (v.startsWith("V") || v.startsWith("v")) {
                v = v.substring(1);
            }
            int major;
            int minor = 0;
            if (v.length() >= 3) {
                major = parseInt(v.substring(0, 2));
                minor = parseInt(v.substring(2));
            } else {
                major = parseInt(v);
            }
            return new DeviceProfile("MIUI", miuiName, major, minor, androidVer);
        }

        return new DeviceProfile("AOSP", "", 0, 0, androidVer);
    }

    /**
     * 按版本决定时钟类注入顺序。
     *
     * HyperOS 的 MIUI 版 SystemUI 与 AOSP 差异很大，Redmi K30 Pro / HyperOS 1.0 上实测：
     *   statusbar.policy.Clock            只剩 <init>、run（改文本的方法不在这里）
     *   statusbar.policy.MiuiClock        不存在（真名是 statusbar.views.MiuiClock）
     *   statusbar.policy.VariableDateView 只剩 <clinit>
     * 真实承载时钟的是 views.MiuiClock / views.MiuiStatusBarClock 与
     * policy.MiuiStatusBarClockController、policy.SplitClockView。
     */
    public String[] clockCandidates() {
        if ("HyperOS".equals(rom)) {
            return new String[]{
                    "com.android.systemui.statusbar.views.MiuiClock",
                    "com.android.systemui.statusbar.views.MiuiStatusBarClock",
                    "com.android.systemui.statusbar.policy.MiuiStatusBarClockController",
                    "com.android.systemui.statusbar.policy.SplitClockView",
                    "com.android.systemui.statusbar.policy.VariableDateViewController",
                    "com.android.systemui.statusbar.policy.VariableDateView",
                    "com.android.systemui.shared.clocks.AnimatableClockView",
                    "com.android.systemui.statusbar.policy.Clock"
            };
        }
        if ("MIUI".equals(rom)) {
            return new String[]{
                    "com.android.systemui.statusbar.policy.MiuiClock",
                    "com.android.systemui.statusbar.views.MiuiClock",
                    "com.android.systemui.statusbar.views.MiuiStatusBarClock",
                    "com.android.systemui.statusbar.policy.Clock"
            };
        }
        return new String[]{
                "com.android.systemui.statusbar.policy.Clock",
                "android.widget.TextClock"
        };
    }

    /** 界面/日志里展示的一行描述。 */
    public String display() {
        if ("AOSP".equals(rom)) {
            return "类原生系统 · Android " + androidVer;
        }
        String ver = major + (minor > 0 ? "." + minor : "");
        return rom + " " + ver + " · Android " + androidVer;
    }

    /** 适配结论。 */
    public String supportText() {
        return supported ? "已适配" : "未适配";
    }

    /** 未适配/需要额外手段时的提示。 */
    public String supportHint() {
        if (!supported) {
            return "可用版本为 MIUI 12/12.5/13/14、HyperOS 1.x/2.x/3.x；"
                    + "当前版本不在列表内，若格式没有变化请开启「兼容模式」";
        }
        if ("HyperOS".equals(rom)) {
            return "按 HyperOS " + major + ".x 策略：优先注入 MiuiClock / MiuiStatusBarClock，TextView 兜底";
        }
        if ("MIUI".equals(rom)) {
            return "按 MIUI " + major + (minor > 0 ? "." + minor : "") + " 策略：注入标准 Clock 视图";
        }
        return "类原生策略：注入标准 Clock 视图";
    }

    // ------------------------------------------------------------ 属性读取

    private static String prop(String key) {
        String v = sysProp(key);
        if (v.length() == 0) {
            v = execProp(key);
        }
        return v;
    }

    /** 系统进程（SystemUI）可用；普通应用进程可能受 hidden API 限制。 */
    private static String sysProp(String key) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            Object v = c.getMethod("get", String.class).invoke(null, key);
            return v == null ? "" : v.toString().trim();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 兜底：直接调 getprop，不需要 root。 */
    private static String execProp(String key) {
        Process p = null;
        try {
            p = new ProcessBuilder("/system/bin/getprop", key).redirectErrorStream(true).start();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            InputStream in = p.getInputStream();
            byte[] b = new byte[256];
            int n;
            while ((n = in.read(b)) > 0) {
                buf.write(b, 0, n);
                if (buf.size() > 2048) {
                    break;
                }
            }
            p.waitFor();
            return buf.toString("UTF-8").trim();
        } catch (Throwable t) {
            return "";
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Throwable t) {
            return 0;
        }
    }
}
