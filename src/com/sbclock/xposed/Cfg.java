package com.sbclock.xposed;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;

/**
 * 模块配置。以纯文本形式放在 /data/system/sbclock.conf，
 * 这样 SystemUI（system uid）和模块 App（通过 su）都能读写。
 *
 * 字段对应界面「时间格式」分组（参照 MIUI/HyperOS 时钟设置）：
 *   hour24        24 小时制
 *   seconds       显示秒
 *   showPeriod    显示时段（AM/PM），仅 12 小时制下生效
 *   hourPad       小时补 0（09 还是 9）
 *   fixed_width   固定宽度（dp，0 = 默认不固定）
 *   custom_enabled 开启自定义格式
 *   custom        自定义 pattern
 *   compat        兼容模式（改写 DateFormat#getTimeFormat）
 */
public final class Cfg {

    public static final String PATH = "/data/system/sbclock.conf";

    /** 总开关。关掉后模块不再改动任何时钟文本，各分项配置原样保留。 */
    public boolean enabled = true;
    public boolean hour24 = true;
    public boolean seconds = false;
    public boolean showPeriod = false;
    public boolean hourPad = true;
    public int fixedWidth = 0;
    public boolean customEnabled = false;
    public String custom = "";
    public boolean compat = false;

    /** 生成 SimpleDateFormat pattern。自定义开启且非空时优先。
     *  时段不写进 pattern：它要 12 / 24 小时制都显示，且中文要按 MIUI 分段，
     *  统一由 {@link #format(java.util.Date, java.text.SimpleDateFormat)} 在前面拼接。 */
    public String pattern() {
        if (customEnabled) {
            String c = custom == null ? "" : custom.trim();
            if (c.length() > 0) {
                return c;
            }
        }
        StringBuilder sb = new StringBuilder();
        if (hour24) {
            sb.append(hourPad ? "HH" : "H");
        } else {
            sb.append(hourPad ? "hh" : "h");
        }
        sb.append(":mm");
        if (seconds) {
            sb.append(":ss");
        }
        return sb.toString();
    }

    /**
     * MIUI 的分时段文案。边界来自 MIUITime（net.hestudio.miuitime）模块反编译结果
     * `SystemUiClockHook#dayPeriod(int hour)`：
     * <pre>
     *   hour == 0        → 半夜
     *   1  ≤ hour &lt; 6   → 凌晨
     *   6  ≤ hour &lt; 12  → 上午
     *   hour == 12       → 中午
     *   13 ≤ hour &lt; 18  → 下午
     *   hour == 18       → 傍晚
     *   19 ≤ hour &lt; 24  → 晚上
     * </pre>
     */
    public static String dayPeriod(int hour) {
        if (hour == 0) {
            return "半夜";
        }
        if (hour < 6) {
            return "凌晨";
        }
        if (hour < 12) {
            return "上午";
        }
        if (hour == 12) {
            return "中午";
        }
        if (hour < 18) {
            return "下午";
        }
        if (hour == 18) {
            return "傍晚";
        }
        return "晚上";
    }

    /** 时段文字：中文系统按 MIUI 分段，其它语言回退 AM / PM。 */
    public static String periodText(java.util.Date when) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTime(when);
        java.util.Locale loc = java.util.Locale.getDefault();
        if ("zh".equals(loc.getLanguage())) {
            return dayPeriod(cal.get(java.util.Calendar.HOUR_OF_DAY));
        }
        return cal.get(java.util.Calendar.AM_PM) == java.util.Calendar.AM ? "AM" : "PM";
    }

    /**
     * 输出「时段 + 空格 + 时间」。界面预览与 SystemUI 侧共用这一套规则，
     * 避免两边显示的格式不一致。自定义格式交给用户自己的 pattern，不再追加时段。
     */
    public String format(java.util.Date when, java.text.SimpleDateFormat base) {
        String t = base.format(when);
        if (showPeriod && !customEnabled) {
            t = periodText(when) + " " + t;
        }
        return t;
    }

    /** 当前配置下、以此刻为例的完整输出，用于界面上的「当前格式」与预览。 */
    public String formatSample() {
        java.util.Date now = new java.util.Date();
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(pattern(), java.util.Locale.getDefault());
        return format(now, f);
    }

    private static volatile Cfg sCache;
    private static volatile long sCacheAt;

    /** 带 2 秒缓存的读取：SystemUI 侧每次格式化都会走到这里。 */
    public static Cfg loadCached() {
        long now = System.currentTimeMillis();
        Cfg c = sCache;
        if (c != null && now - sCacheAt < 2000) {
            return c;
        }
        c = load();
        sCache = c;
        sCacheAt = now;
        return c;
    }

    public Cfg copy() {
        Cfg c = new Cfg();
        c.enabled = enabled;
        c.hour24 = hour24;
        c.seconds = seconds;
        c.showPeriod = showPeriod;
        c.hourPad = hourPad;
        c.fixedWidth = fixedWidth;
        c.customEnabled = customEnabled;
        c.custom = custom;
        c.compat = compat;
        return c;
    }

    public String serialize() {
        return "enabled=" + (enabled ? 1 : 0)
                + "\nhour24=" + (hour24 ? 1 : 0)
                + "\nseconds=" + (seconds ? 1 : 0)
                + "\nshow_period=" + (showPeriod ? 1 : 0)
                + "\nhour_pad=" + (hourPad ? 1 : 0)
                + "\nfixed_width=" + fixedWidth
                + "\ncustom_enabled=" + (customEnabled ? 1 : 0)
                + "\ncustom=" + (custom == null ? "" : custom.replace("\n", " "))
                + "\ncompat=" + (compat ? 1 : 0)
                + "\n";
    }

    public static Cfg parse(String text) {
        Cfg c = new Cfg();
        if (text == null) {
            return c;
        }
        boolean sawCustomEnabled = false;
        String[] lines = text.split("\n");
        for (String raw : lines) {
            String line = raw.trim();
            int i = line.indexOf('=');
            if (i <= 0) {
                continue;
            }
            String key = line.substring(0, i).trim();
            String val = line.substring(i + 1).trim();
            if ("enabled".equals(key)) {
                c.enabled = "1".equals(val);
            } else if ("hour24".equals(key)) {
                c.hour24 = "1".equals(val);
            } else if ("seconds".equals(key)) {
                c.seconds = "1".equals(val);
            } else if ("show_period".equals(key)) {
                c.showPeriod = "1".equals(val);
            } else if ("hour_pad".equals(key)) {
                c.hourPad = "1".equals(val);
            } else if ("fixed_width".equals(key)) {
                c.fixedWidth = parseInt(val, 0);
            } else if ("custom_enabled".equals(key)) {
                c.customEnabled = "1".equals(val);
                sawCustomEnabled = true;
            } else if ("custom".equals(key)) {
                c.custom = val;
            } else if ("compat".equals(key)) {
                c.compat = "1".equals(val);
            }
        }
        // 兼容 v2.0 的配置文件（没有 custom_enabled，但 custom 有值即视为启用）
        if (!sawCustomEnabled && c.custom != null && c.custom.trim().length() > 0) {
            c.customEnabled = true;
        }
        return c;
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    /** 从 /data/system 读取当前生效配置；不存在时返回默认值。 */
    public static Cfg load() {
        File f = new File(PATH);
        if (!f.exists() || !f.canRead()) {
            return new Cfg();
        }
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[512];
            int n;
            while ((n = in.read(b)) > 0) {
                buf.write(b, 0, n);
                if (buf.size() > 4096) {
                    break;
                }
            }
            return parse(buf.toString("UTF-8"));
        } catch (Throwable t) {
            return new Cfg();
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
