package com.sbclock.xposed;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/**
 * 纯 framework 实现的 HyperOS / MIUI 设置页视觉。
 *
 * 结构要点（照 HyperOS 系统设置页）：
 * - 分组标题写在卡片**外面**，小字灰色，与卡片内文字左对齐；
 * - 卡片是整宽、圆角 16dp、几乎无阴影的白/深色块，卡片之间留 12dp；
 * - 行内左右各 16dp，行之间用一条左缩进的分隔线隔开；
 * - 强调色跟随壁纸（Monet accent1），用于开关、滑块、主按钮。
 *
 * 配色策略（重要）：中性色不读系统 system_neutral1_* —— Android 的该资源编号方向
 * 与 Material tone 相反（0 = 最浅/白，1000 = 最深/黑），极易把 surface 与 onSurface 取反。
 */
public final class UiKit {

    /** 卡片圆角。HyperOS 是 16dp，不要再放大到 22dp 那种 Material 大圆角。 */
    private static final float RADIUS_DP = 16f;
    /** 行内容左右内边距，分隔线也按这个值缩进。 */
    private static final int ROW_PAD_H = 16;
    /** 单行上下内边距。 */
    private static final int ROW_PAD_V = 13;
    /** 卡片之间的间距。 */
    private static final int CARD_GAP_DP = 12;

    public static final class Palette {
        public int bg;
        public int card;
        public int text;
        public int text2;
        public int accent;
        public int onAccent;
        public int accentSoft;
        public int outline;
        public int control;
        public int ok;
        public int warn;
        public int err;
        public boolean dark;
    }

    private UiKit() {
    }

    private static int dyn(String name, int fallback) {
        if (Build.VERSION.SDK_INT < 31) {
            return fallback;
        }
        try {
            int id = Resources.getSystem().getIdentifier(name, "color", "android");
            if (id != 0) {
                return Resources.getSystem().getColor(id, null);
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    public static Palette palette(Context ctx) {
        Palette p = new Palette();
        boolean dark = (ctx.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        p.dark = dark;

        if (dark) {
            // HyperOS 深色：纯黑底 + 近黑卡片
            p.bg = 0xFF000000;
            p.card = 0xFF17181A;
            p.text = 0xFFE8E8E8;
            p.text2 = 0xFF8C8C8C;
            p.outline = 0x1FFFFFFF;
            p.control = 0xFF232427;
            p.ok = 0xFF6DD58C;
            p.warn = 0xFFFDD663;
            p.err = 0xFFF28B82;
        } else {
            // 浅色：HyperOS 的浅灰底 + 纯白卡片
            p.bg = 0xFFF2F3F5;
            p.card = 0xFFFFFFFF;
            p.text = 0xFF0D0D0D;
            p.text2 = 0xFF818181;
            p.outline = 0x14000000;
            p.control = 0xFFF2F3F5;
            p.ok = 0xFF1E8E3E;
            p.warn = 0xFFB06000;
            p.err = 0xFFC5221F;
        }

        int accent = dyn("system_accent1_600", 0xFF0B57D0);
        p.accent = accent;
        p.onAccent = 0xFFFFFFFF;
        p.accentSoft = 0x1F000000 | (accent & 0x00FFFFFF);
        return p;
    }

    public static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    public static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(radiusPx);
        return d;
    }

    public static GradientDrawable circle(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    public static Drawable ripple(int bgColor, int rippleColor, float radiusPx) {
        GradientDrawable base = round(bgColor, radiusPx);
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), base, null);
    }

    public static TextView text(Context c, String s, float sp, int color, boolean medium) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setTypeface(medium ? Typeface.create("sans-serif-medium", Typeface.NORMAL)
                : Typeface.create("sans-serif", Typeface.NORMAL));
        return t;
    }

    public static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout rowLinear(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    /**
     * 分组标题：写在卡片外面，小字灰色，左边缩进与卡片内文字对齐。
     * HyperOS 的「时间格式」「应用」这类小标题就是这个样式。
     */
    public static TextView groupTitle(Context c, Palette p, String title) {
        TextView t = text(c, title, 14, p.text2, false);
        t.setPadding(dp(c, ROW_PAD_H), 0, dp(c, ROW_PAD_H), 0);
        return t;
    }

    /** 卡片外壳：整宽、圆角 16dp、无内边距（内边距交给每一行自己）。 */
    public static LinearLayout card(Context c, Palette p) {
        LinearLayout l = column(c);
        l.setBackground(round(p.card, dp(c, RADIUS_DP)));
        l.setClipToOutline(false);
        return l;
    }

    /** 需要在卡片内部自己排版（状态卡）时用：带 16dp 四周内边距。 */
    public static LinearLayout paddedCard(Context c, Palette p) {
        LinearLayout l = card(c, p);
        int pad = dp(c, ROW_PAD_H);
        l.setPadding(pad, pad, pad, pad);
        return l;
    }

    /** 行之间的分隔线，左侧缩进到与行文字对齐（HyperOS 的 inset divider）。 */
    public static View divider(Context c, Palette p) {
        View v = new View(c);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 0.6f)));
        lp.leftMargin = dp(c, ROW_PAD_H);
        v.setLayoutParams(lp);
        v.setBackgroundColor(p.outline);
        return v;
    }

    public static Switch toggle(Context c, Palette p) {
        Switch s = new Switch(c);
        s.setText("");
        int[][] states = {{android.R.attr.state_checked}, {}};
        s.setThumbTintList(new ColorStateList(states,
                new int[]{p.accent, p.dark ? 0xFF6E7276 : 0xFFC6C9CE}));
        s.setTrackTintList(new ColorStateList(states,
                new int[]{p.accentSoft, p.outline}));
        return s;
    }

    /** 一行「标题 + 说明 + 控件」。 */
    public static LinearLayout switchRow(Context c, Palette p, String title, String sub,
                                         View control) {
        LinearLayout row = rowLinear(c);
        row.setPadding(dp(c, ROW_PAD_H), dp(c, ROW_PAD_V), dp(c, ROW_PAD_H), dp(c, ROW_PAD_V));
        row.setMinimumHeight(dp(c, 54));

        LinearLayout texts = column(c);
        texts.addView(text(c, title, 16, p.text, true));
        if (sub != null && sub.length() > 0) {
            TextView s = text(c, sub, 12, p.text2, false);
            s.setLineSpacing(dp(c, 2), 1f);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            sp.topMargin = dp(c, 4);
            s.setLayoutParams(sp);
            texts.addView(s);
        }
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tp.rightMargin = dp(c, 12);
        row.addView(texts, tp);

        if (control != null) {
            row.addView(control);
        }
        return row;
    }

    /** 可点击的列表行，右侧可带值文本与箭头（HyperOS 的「设置格式 ›」那种）。 */
    public static LinearLayout actionRow(Context c, Palette p, String title, String sub,
                                         TextView value) {
        LinearLayout row = rowLinear(c);
        row.setPadding(dp(c, ROW_PAD_H), dp(c, ROW_PAD_V), dp(c, ROW_PAD_H), dp(c, ROW_PAD_V));
        row.setMinimumHeight(dp(c, 54));
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(p.accentSoft),
                null, null));

        LinearLayout texts = column(c);
        texts.addView(text(c, title, 16, p.text, true));
        if (sub != null && sub.length() > 0) {
            TextView s = text(c, sub, 12, p.text2, false);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            sp.topMargin = dp(c, 4);
            s.setLayoutParams(sp);
            texts.addView(s);
        }
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tp.rightMargin = dp(c, 8);
        row.addView(texts, tp);

        if (value != null) {
            row.addView(value);
        }
        TextView chevron = text(c, "›", 16, p.text2, false);
        LinearLayout.LayoutParams chp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        chp.leftMargin = dp(c, 6);
        chevron.setLayoutParams(chp);
        row.addView(chevron);
        return row;
    }

    /** 行内的文字按钮（如状态卡右上角的「查看日志」），HyperOS 里是主题色文字。 */
    public static TextView linkButton(Context c, Palette p, String label) {
        TextView t = text(c, label, 13, p.accent, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 10), dp(c, 6), dp(c, 10), dp(c, 6));
        t.setClickable(true);
        t.setFocusable(true);
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(p.accentSoft), null, null));
        return t;
    }

    public static TextView button(Context c, Palette p, String label, boolean primary) {
        TextView t = new TextView(c);
        t.setText(label);
        t.setTextSize(15);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setClickable(true);
        t.setFocusable(true);
        int h = dp(c, 46);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, h);
        t.setLayoutParams(lp);
        float radius = dp(c, 12);
        if (primary) {
            t.setTextColor(p.onAccent);
            t.setBackground(ripple(p.accent, 0x33FFFFFF, radius));
        } else {
            t.setTextColor(p.text);
            t.setBackground(new RippleDrawable(ColorStateList.valueOf(p.accentSoft),
                    round(p.control, radius), null));
        }
        return t;
    }

    public static LinearLayout.LayoutParams gaps(Context c, float topDp, float bottomDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, topDp);
        lp.bottomMargin = dp(c, bottomDp);
        return lp;
    }

    /** 卡片之间统一的间距。 */
    public static LinearLayout.LayoutParams cardGap(Context c) {
        return gaps(c, CARD_GAP_DP, 0);
    }

    /** 分组标题的间距：上方留白比下方大，和 HyperOS 的分组节奏一致。 */
    public static LinearLayout.LayoutParams groupGap(Context c) {
        return gaps(c, 20, 8);
    }

    /** 标题 + 当前值 + SeekBar 的滑块行。 */
    public static final class SliderRow {
        public final LinearLayout root;
        public final SeekBar bar;
        private final TextView value;
        private final String unit;

        public SliderRow(Context c, Palette p, String title, String sub, int max, String unit) {
            this.unit = unit;
            root = column(c);
            root.setPadding(dp(c, ROW_PAD_H), dp(c, 10), dp(c, ROW_PAD_H), dp(c, ROW_PAD_V));

            LinearLayout head = rowLinear(c);
            head.addView(text(c, title, 16, p.text, true),
                    new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            value = text(c, "", 13, p.text2, false);
            head.addView(value);
            root.addView(head);

            if (sub != null && sub.length() > 0) {
                TextView s = text(c, sub, 12, p.text2, false);
                root.addView(s, gaps(c, 4, 0));
            }

            bar = new SeekBar(c);
            bar.setMax(max);
            bar.setProgressTintList(ColorStateList.valueOf(p.accent));
            bar.setThumbTintList(ColorStateList.valueOf(p.accent));
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    setLabel(progress);
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                }
            });
            root.addView(bar, gaps(c, 8, 0));
            setLabel(0);
        }

        private void setLabel(int v) {
            value.setText(v <= 0 ? "默认" : v + " " + unit);
        }

        public int getProgress() {
            return bar.getProgress();
        }

        public void setProgress(int v) {
            bar.setProgress(v);
            setLabel(v);
        }
    }
}
