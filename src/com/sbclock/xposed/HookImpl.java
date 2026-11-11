package com.sbclock.xposed;

import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 运行在 com.android.systemui 进程内。
 * 策略：先精确 hook 时钟类，再对 TextView#setText 兜底，最后可选 DateFormat 兼容模式。
 */
public final class HookImpl {

    private static final String[] UPDATE_METHODS = {
            "updateClock", "updateText", "updateTime", "onTimeChanged"
    };

    private static final Map<TextView, Boolean> TICKING =
            Collections.synchronizedMap(new WeakHashMap<TextView, Boolean>());
    /** 见过的时钟视图，用于总开关切换时一次性把全部视图重绘 / 还原。 */
    private static final Map<TextView, Boolean> KNOWN =
            Collections.synchronizedMap(new WeakHashMap<TextView, Boolean>());
    /** 剥过时段尾巴的日期视图，总开关从关变开时要再剥一次。 */
    private static final Map<TextView, Boolean> KNOWN_DATE =
            Collections.synchronizedMap(new WeakHashMap<TextView, Boolean>());
    private static final Map<Class<?>, Boolean> CLS_CACHE =
            new ConcurrentHashMap<Class<?>, Boolean>();
    private static final ThreadLocal<Boolean> IN_HOOK = new ThreadLocal<Boolean>();

    private static volatile Cfg sCfg;
    private static volatile long sCfgAt;
    private static volatile String sFmtKey;
    private static volatile SimpleDateFormat sFmt;
    /** 精确时钟类是否注入成功；为 true 时 TextView 兜底不再负责改文本。 */
    private static volatile boolean sPrecise;

    private HookImpl() {
    }

    public static void install(ClassLoader cl) {
        // 本模块不写任何日志：没有日志文件，也不往框架日志吐东西。
        // 判断「是否真的注入」看 Settings.Global 的 sbclock_hook_at 是否随进程重启更新即可。
        DeviceProfile dp = DeviceProfile.read();
        boolean any = false;
        for (String name : dp.clockCandidates()) {
            any |= hookClockClass(cl, name);
        }
        // 常驻：精确 hook 命中时它只用于捕获时钟视图以挂秒表；
        // 未命中时它才是真正的改文本兜底。回调内按 sPrecise/seconds 走快速返回。
        sPrecise = any;
        hookTextViewFallback();
        // 注：不要再 hook SimpleDateFormat#format。锁屏时钟的第二行是个空的文本视图，
        // 一旦从格式化入口塞进时间，它就会和大字那行同时显示，出现两条重叠的时间
        // （v3.2 / v3.3 的「晚上 晚上 21:24」就是这么来的）。
        hookDateFormat();
        if (!any) {
            XposedBridge.log("SBClock: 精确时钟类未命中，由 TextView 兜底接管");
        }
        publishState(cl, dp, any);
        watchEnabled();
    }

    private static volatile Boolean sLastEnabled;
    private static volatile boolean sWatching;

    /**
     * 盯着总开关。开关一变就把「见过的那些时钟视图」全部重绘一次：
     * 关掉时立刻换回系统原生时间，打开时立刻换回模块格式。
     *
     * 为什么需要它：状态栏在没开秒的情况下只在分钟边界刷新一次，
     * 光等系统下一次 setText，用户点完开关要干等将近一分钟才看到效果。
     */
    private static void watchEnabled() {
        if (sWatching) {
            return;
        }
        sWatching = true;
        final Handler h = new Handler(Looper.getMainLooper());
        h.post(new Runnable() {
            @Override
            public void run() {
                try {
                    boolean en = cfg().enabled;
                    Boolean last = sLastEnabled;
                    if (last != null && last.booleanValue() != en) {
                        TextView[] list;
                        synchronized (KNOWN) {
                            list = KNOWN.keySet().toArray(new TextView[0]);
                        }
                        for (TextView tv : list) {
                            if (tv != null && tv.isAttachedToWindow()) {
                                applyText(tv);
                            }
                        }
                        if (en) {
                            // 停用期间系统把「11月11日 周三晚上」原样写回来了，重新接管时再剥一次
                            reStripDates();
                        }
                    }
                    sLastEnabled = Boolean.valueOf(en);
                } catch (Throwable ignored) {
                }
                h.postDelayed(this, 1500);
            }
        });
    }

    private static volatile DeviceProfile sDp;
    private static volatile boolean sPublished;

    /**
     * 用 hook 到的视图自己的 Context 写注入标记。
     *
     * 为什么不靠反射拿 ActivityThread：HyperOS 上 currentApplication 这条链不一定通，
     * 失败又只能落在写不进文件的日志里，等于什么都没发生。视图的 Context 一定有效。
     */
    private static void publishFromView(android.view.View v) {
        if (sPublished || v == null) {
            return;
        }
        try {
            android.content.Context ctx = v.getContext();
            if (ctx == null) {
                return;
            }
            android.content.ContentResolver cr = ctx.getContentResolver();
            android.provider.Settings.Global.putLong(cr, "sbclock_hook_at",
                    System.currentTimeMillis());
            DeviceProfile dp = sDp;
            android.provider.Settings.Global.putString(cr, "sbclock_hook_state",
                    (sPrecise ? "precise" : "fallback") + "|" + (dp == null ? "?" : dp.display()));
            sPublished = true;
        } catch (Throwable t) {
            // 写不进去就下次再试，别把 hook 弄崩
        }
    }

    /**
     * 把「本进程确实注入过」这件事写到 Settings.Global，供模块界面读取。
     *
     * 为什么不靠日志文件：SystemUI 是 system_app，对 /data/system 只有写已有文件的权限，
     * 一旦文件被删（历史版本「日志超过 32KB 就 delete」）就再也创建不回来，日志永远为空，
     * 界面于是永远显示「等待生效」，而状态栏其实是好的。Settings.Global 没有这个坑。
     */
    private static void publishState(ClassLoader cl, DeviceProfile dp, boolean precise) {
        sDp = dp;
        try {
            Class<?> at = XposedHelpers.findClass("android.app.ActivityThread", cl);
            Object app = XposedHelpers.callStaticMethod(at, "currentApplication");
            if (app == null) {
                return;
            }
            android.content.ContentResolver cr =
                    ((android.content.Context) app).getContentResolver();
            android.provider.Settings.Global.putLong(cr, "sbclock_hook_at",
                    System.currentTimeMillis());
            android.provider.Settings.Global.putString(cr, "sbclock_hook_state",
                    (precise ? "precise" : "fallback") + "|" + dp.display());
            sPublished = true;
        } catch (Throwable t) {
            XposedBridge.log("SBClock: 写激活标记失败 " + t);
        }
    }

    private static boolean hookClockClass(ClassLoader cl, String name) {
        Class<?> clazz;
        try {
            clazz = XposedHelpers.findClass(name, cl);
        } catch (Throwable t) {
            return false;
        }
        if (clazz == null) {
            return false;
        }
        boolean hit = false;
        try {
            XposedBridge.hookAllMethods(clazz, "getSmallTime", new GetSmallTimeHook());
            hit = true;
        } catch (Throwable ignored) {
        }
        for (String m : UPDATE_METHODS) {
            try {
                XposedBridge.hookAllMethods(clazz, m, new UpdateHook());
                hit = true;
            } catch (Throwable ignored) {
            }
        }
        return hit;
    }

    private static void hookTextViewFallback() {
        try {
            XposedBridge.hookAllMethods(TextView.class, "setText", new SetTextHook());
        } catch (Throwable t) {
            XposedBridge.log("SBClock: TextView 兜底注入失败 " + t);
        }
    }

    private static void hookDateFormat() {
        try {
            XposedBridge.hookAllMethods(DateFormat.class, "getTimeFormat", new DateFormatHook());
        } catch (Throwable t) {
            XposedBridge.log("SBClock: DateFormat 注入失败 " + t);
        }
    }

    // ---------- hooks ----------

    private static final class GetSmallTimeHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
            if (!cfg().enabled) {
                return;
            }
            if (param.getResult() instanceof String) {
                param.setResult(formatNow());
            }
        }
    }

    private static final class UpdateHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
            Object obj = param.thisObject;
            if (obj instanceof TextView) {
                TextView tv = (TextView) obj;
                // 只改「本来就是时间」的时钟视图：类名含 Clock 且不含 date，
                // 并且当前文本已经整段是时间。这样锁屏/通知面板的日期行（"11月11日 周三"）
                // 即使挂在同一个更新链路上也不会被写成时间。
                if (isClockView(tv.getClass()) && looksLikeTime(tv.getText())) {
                    applyText(tv);
                }
            }
        }
    }

    private static final class SetTextHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
            if (isInHook()) {
                return;
            }
            // 精确 hook 已负责格式，且没开秒表 → 不必再看任何文本，直接走开。
            // （总开关关着时不短路，还要靠下面的 publishFromView 上报状态）
            if (sPrecise && !cfg().seconds && cfg().enabled) {
                return;
            }
            Object obj = param.thisObject;
            if (!(obj instanceof TextView)) {
                return;
            }
            if (param.args == null || param.args.length != 1) {
                return;
            }
            Object arg = param.args[0];
            if (!(arg instanceof CharSequence)) {
                return;
            }
            TextView tv = (TextView) obj;
            // 只要有任何一个时钟视图被碰到，就说明注入真的跑起来了，顺手把状态写出去。
            publishFromView(tv);
            // 总开关关掉：注入仍在（所以上面照常上报状态），但一个字符都不动。
            if (!cfg().enabled) {
                return;
            }
            // 日期行尾巴：系统在 12 小时制下会把时段拼在日期后面（「11月11日 周三晚上」），去掉
            java.util.regex.Matcher dm = DATE_WITH_PERIOD.matcher(arg.toString());
            if (dm.matches()) {
                stripDatePeriod(tv, dm.group(1).trim());
                return;
            }
            // 类名含 Clock，或者字号很大的时间文本（面板 / 锁屏大字时钟常是普通 TextView）
            if (!isClockView(tv.getClass()) && !isBigText(tv)) {
                return;
            }
            if (!looksLikeTime((CharSequence) arg)) {
                return;
            }
            // 不再按位置排除：锁屏与通知面板的时钟同样要接管。
            // 日期行由 isClockView（排除类名含 date）与 looksLikeTime（整段必须是时间）双重挡掉。
            applyText(tv);
        }
    }

    private static final class DateFormatHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
            if (!cfg().enabled || !cfg().compat) {
                return;
            }
            if (param.args == null || param.args.length < 1) {
                return;
            }
            if (!(param.args[0] instanceof android.content.Context)) {
                return;
            }
            try {
                param.setResult(new SimpleDateFormat(cfg().pattern(), Locale.getDefault()));
            } catch (Throwable ignored) {
            }
        }
    }

    // ---------- 应用与刷新 ----------

    private static void applyText(TextView tv) {
        if (isInHook()) {
            return;
        }
        KNOWN.put(tv, Boolean.TRUE);
        Cfg c = cfg();
        if (!c.enabled) {
            // 总开关关掉：一个字都不改，把视图还给系统
            restoreNative(tv);
            applyWidth(tv, c);
            return;
        }
        IN_HOOK.set(Boolean.TRUE);
        try {
            // 锁屏 / 通知面板 / 状态栏统一走同一套格式：时段在左，是否带秒看开关。
            // 大字位置字体很大，带秒后放不下时由 fitBigText 自动缩字号。
            String s = c.format(new Date(), formatterFor(c));
            tv.setText(s);
            if (isBigText(tv)) {
                fitBigText(tv, s);
            }
            if (c.seconds) {
                startTicker(tv);
            }
        } catch (Throwable ignored) {
        } finally {
            IN_HOOK.remove();
        }
    }

    /**
     * 总开关关掉后把视图还给系统：内容换成系统自己的时间样式（24h 用 HH:mm，12h 用 h:mm，
     * 和 MIUI 状态栏一致，不带上午/下午），大字字号回到接管前的原始值。
     * 这样不必等系统下一次刷新（状态栏无秒时可能要等一分钟）才生效。
     */
    private static void restoreNative(TextView tv) {
        if (isInHook()) {
            return;
        }
        IN_HOOK.set(Boolean.TRUE);
        try {
            CharSequence cur = tv.getText();
            if (cur != null && looksLikeTime(cur)) {
                boolean h24 = android.text.format.DateFormat.is24HourFormat(tv.getContext());
                SimpleDateFormat df = new SimpleDateFormat(h24 ? "HH:mm" : "h:mm", Locale.getDefault());
                tv.setText(df.format(new Date()));
            }
            Float base = BASE_SIZE.get(tv);
            if (base != null) {
                tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, base);
                BASE_SIZE.remove(tv);
            }
        } catch (Throwable ignored) {
        } finally {
            IN_HOOK.remove();
        }
    }

    /** 「固定宽度」：给时钟视图设 minWidth，避免秒位变化时状态栏左右抖动。 */
    private static void applyWidth(TextView tv, Cfg c) {
        try {
            int px = 0;
            if (c.fixedWidth > 0) {
                px = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                        c.fixedWidth, tv.getResources().getDisplayMetrics());
            }
            if (tv.getMinWidth() != px) {
                tv.setMinWidth(px);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 秒显示需要自己每秒推一次，否则系统只会每分钟刷新。 */
    private static void startTicker(final TextView tv) {
        if (TICKING.containsKey(tv)) {
            return;
        }
        TICKING.put(tv, Boolean.TRUE);
        final Handler h = new Handler(Looper.getMainLooper());
        h.post(new Runnable() {
            @Override
            public void run() {
                if (!tv.isAttachedToWindow()) {
                    TICKING.remove(tv);
                    return;
                }
                Cfg c = cfg();
                if (!c.enabled) {
                    // 关闭总开关后 ticker 只负责把这一帧还回系统，然后自己退场
                    restoreNative(tv);
                    TICKING.remove(tv);
                    return;
                }
                if (!c.seconds) {
                    TICKING.remove(tv);
                    return;
                }
                if (!isInHook()) {
                    IN_HOOK.set(Boolean.TRUE);
                    try {
                        tv.setText(formatNow(c));
                    } catch (Throwable ignored) {
                    } finally {
                        IN_HOOK.remove();
                    }
                }
                h.postDelayed(this, 1000 - (System.currentTimeMillis() % 1000) + 30);
            }
        });
    }

    // ---------- 工具 ----------

    private static boolean isInHook() {
        Boolean b = IN_HOOK.get();
        return b != null && b.booleanValue();
    }

    private static boolean isClockView(Class<?> c) {
        Boolean cached = CLS_CACHE.get(c);
        if (cached != null) {
            return cached.booleanValue();
        }
        String n = c.getName();
        // 只认「时钟」，并且明确排除日期视图：
        // VariableDateView / VariableDateViewController 承载的是日期（"11月11日 星期二"），
        // 早期把它们也当成时钟改成了时间，表现为通知面板的日期消失。
        String low = n.toLowerCase(Locale.US);
        boolean yes = n.contains("Clock") && !low.contains("date");
        CLS_CACHE.put(c, Boolean.valueOf(yes));
        return yes;
    }

    /** 字号很大的时间视图 = 锁屏 / 通知面板的大字时钟。getTextSize() 单位是 px。 */
    private static boolean isBigText(TextView tv) {
        try {
            return tv.getTextSize() >= 80f;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 记录大字视图的原始字号，缩字之后每次都要从原始字号重新计算，才能随文本长短来回伸缩。 */
    private static final Map<TextView, Float> BASE_SIZE =
            Collections.synchronizedMap(new WeakHashMap<TextView, Float>());

    /** 大字时钟带秒后比「晚上 HH:mm」宽得多，按当前可用宽度把字号缩到放得下为止。 */
    private static void fitBigText(TextView tv, String s) {
        try {
            Float saved = BASE_SIZE.get(tv);
            float base;
            if (saved == null) {
                base = tv.getTextSize();
                BASE_SIZE.put(tv, base);
            } else {
                base = saved;
            }
            int avail = tv.getWidth() - tv.getPaddingLeft() - tv.getPaddingRight();
            if (avail <= 0) {
                return;
            }
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, base);
            float need = tv.getPaint().measureText(s);
            if (need > avail) {
                float size = base * avail / need * 0.96f;
                if (size < base * 0.3f) {
                    size = base * 0.3f;
                }
                tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
            }
        } catch (Throwable ignored) {
        }
    }

    // 说明：v3.2 / v3.3 曾额外 hook SimpleDateFormat#format，想从格式化入口接管锁屏大字时钟。
    // 结果是锁屏那颗「第二行时间视图」（平时文本为空、只在自绘时钟切换时才用）被塞进了时间，
    // 于是锁屏上同时出现两条时间、通知面板显示成「晚上 晚上 晚 上 21:23」。
    // 现已移除：大字时钟交给下面的 SetTextHook（isBigText 判定）就够了。

    // 注：v2.9 曾用「按位置排除通知面板 / 锁屏」的办法防止日期行被改，
    // 但锁屏与通知面板的时钟本就在接管范围内。现在改为按类名（isClockView）
    // 与整段文本（looksLikeTime）双重判定，位置不再参与，日期行照样安全。

    /**
     * 只有「整段文本就是时间」才接管，避免误伤其它视图。
     * 之前用「包含冒号」这种宽松判定，结果把通知中心里带日期的视图也改成了时间
     * （日期消失）。这里要求从头到尾都是 时间 或 时段+时间。
     */
    private static final java.util.regex.Pattern TIME_ONLY = java.util.regex.Pattern.compile(
            "^\\s*(?:(?:AM|PM|上午|下午|凌晨|中午|傍晚|晚上|半夜)\\s*)?(\\d{1,2}):(\\d{2})(?::(\\d{2}))?"
                    + "\\s*(?:(?:AM|PM|上午|下午|凌晨|中午|傍晚|晚上|半夜))?\\s*$",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /** 「11月11日 周三晚上」这种：日期行被系统 12 小时制在末尾拼了时段，把尾巴剥掉。 */
    private static final java.util.regex.Pattern DATE_WITH_PERIOD = java.util.regex.Pattern.compile(
            "^(\\s*\\d{1,2}\\s*月\\s*\\d{1,2}\\s*日\\s*周\\S*)\\s*(AM|PM|上午|下午|凌晨|中午|傍晚|晚上|半夜)\\s*$",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /** 只去掉日期行末尾的时段，其余内容原样保留。 */
    private static void stripDatePeriod(TextView tv, String cleaned) {
        if (isInHook()) {
            return;
        }
        KNOWN_DATE.put(tv, Boolean.TRUE);
        IN_HOOK.set(Boolean.TRUE);
        try {
            tv.setText(cleaned);
        } catch (Throwable ignored) {
        } finally {
            IN_HOOK.remove();
        }
    }

    /** 重新接管时，把停用期间被系统写回尾巴的日期行再剥一次。 */
    private static void reStripDates() {
        TextView[] list;
        synchronized (KNOWN_DATE) {
            list = KNOWN_DATE.keySet().toArray(new TextView[0]);
        }
        for (TextView tv : list) {
            if (tv == null || !tv.isAttachedToWindow()) {
                continue;
            }
            try {
                CharSequence cur = tv.getText();
                if (cur == null) {
                    continue;
                }
                java.util.regex.Matcher m = DATE_WITH_PERIOD.matcher(cur.toString());
                if (m.matches()) {
                    stripDatePeriod(tv, m.group(1).trim());
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean looksLikeTime(CharSequence s) {
        if (s == null) {
            return false;
        }
        return TIME_ONLY.matcher(s.toString()).matches();
    }

    private static Cfg cfg() {
        long now = System.currentTimeMillis();
        Cfg c = sCfg;
        if (c == null || now - sCfgAt > 2000L) {
            c = Cfg.load();
            sCfg = c;
            sCfgAt = now;
        }
        return c;
    }

    private static String formatNow() {
        return formatNow(cfg());
    }

    private static String formatNow(Cfg c) {
        return c.format(new Date(), formatterFor(c));
    }

    private static SimpleDateFormat formatterFor(Cfg c) {
        String key = c.pattern();
        SimpleDateFormat f = sFmt;
        if (f == null || !key.equals(sFmtKey)) {
            try {
                f = new SimpleDateFormat(key, Locale.getDefault());
            } catch (Throwable t) {
                f = new SimpleDateFormat("HH:mm", Locale.getDefault());
            }
            sFmt = f;
            sFmtKey = key;
        }
        return f;
    }
}
