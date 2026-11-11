package com.sbclock.xposed;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final String PREFS = "cfg";
    private static final String ALIAS = ".Launcher";
    /** 界面上「关于」卡里的两个地址；改仓库名时只改这里。 */
    private static final String URL_REPO = "https://github.com/Leosley1314/StatusBarClock";
    private static final String URL_MIUITIME = "https://github.com/hestudio-community/MIUITime";

    private UiKit.Palette p;
    private SharedPreferences sp;
    private final Cfg cfg = new Cfg();
    private String customPattern = "";

    private Switch swEnabled;
    /** 由程序回写开关状态时置 true，避免触发监听器又去写一遍配置。 */
    private boolean suppressToggle;
    private Switch swSeconds;
    private Switch swHour24;
    private Switch swPeriod;
    private Switch swHourPad;
    private Switch swCustom;
    private Switch swHide;
    private Switch swCompat;
    private UiKit.SliderRow sliderWidth;
    private TextView tvFormatValue;
    private TextView tvPatternNow;

    private TextView btnApply;
    private TextView btnRestart;
    private TextView btnRefresh;
    private View statusDot;
    private TextView statusLabel;
    private TextView statusRom;
    private TextView statusSub;
    private DeviceProfile lastDp;
    private boolean lastHasRoot;
    private Activation lastAct = new Activation();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        p = UiKit.palette(this);
        applyWindow();
        setContentView(buildUi());
        loadState();
        refreshStatus();
    }

    // ---------------------------------------------------------------- 窗口

    /** 「应用」卡片里的当前格式跟着时间走，和状态栏看到的是同一套输出。
     *  刷新间隔取 3 秒：留足界面空闲窗口，不打断 uiautomator / 无障碍的 idle 判定。 */
    private final android.os.Handler liveTick =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable liveTask = new Runnable() {
        @Override
        public void run() {
            if (tvPatternNow != null) {
                Cfg c = collectCfg();
                tvPatternNow.setText(c.enabled ? c.formatSample() : "已停用");
            }
            liveTick.postDelayed(this, 3000);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        liveTick.removeCallbacks(liveTask);
        liveTick.post(liveTask);
    }

    @Override
    protected void onPause() {
        super.onPause();
        liveTick.removeCallbacks(liveTask);
    }

    /** 从 PackageManager 读，避免每次发版都要回来改页脚。 */
    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    private void applyWindow() {
        Window w = getWindow();
        w.setBackgroundDrawable(new ColorDrawable(p.bg));
        w.setStatusBarColor(p.bg);
        w.setNavigationBarColor(p.bg);
        int vis = p.dark ? 0
                : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        w.getDecorView().setSystemUiVisibility(vis);
    }

    // ---------------------------------------------------------------- 界面

    private View buildUi() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(p.bg);
        sv.setFillViewport(true);

        LinearLayout root = UiKit.column(this);
        int side = UiKit.dp(this, 16);
        root.setPadding(side, UiKit.dp(this, 22), side, UiKit.dp(this, 32));
        sv.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(UiKit.text(this, "状态栏时钟", 26, p.text, true), matchWrap());
        root.addView(UiKit.text(this, "HyperOS / MIUI · LSPosed 模块", 13, p.text2, false),
                UiKit.gaps(this, 6, 0));

        root.addView(statusCard(), UiKit.gaps(this, 18, 0));

        root.addView(UiKit.groupTitle(this, p, "时间格式"), UiKit.groupGap(this));
        root.addView(formatCard());

        root.addView(UiKit.groupTitle(this, p, "应用"), UiKit.groupGap(this));
        root.addView(applyCard());

        root.addView(UiKit.groupTitle(this, p, "其他"), UiKit.groupGap(this));
        root.addView(otherCard());

        root.addView(UiKit.groupTitle(this, p, "关于"), UiKit.groupGap(this));
        root.addView(aboutCard());

        TextView footer = UiKit.text(this, "v" + versionName() + " · 仅供个人设备使用", 12, p.text2, false);
        footer.setGravity(Gravity.CENTER);
        root.addView(footer, UiKit.gaps(this, 24, 0));

        return sv;
    }

    /** 状态卡：HyperOS 里就是一整块卡片，右上角挂一个文字按钮，不用整宽的次级按钮。 */
    private View statusCard() {
        LinearLayout card = UiKit.paddedCard(this, p);

        LinearLayout row = UiKit.rowLinear(this);
        statusDot = new View(this);
        statusDot.setBackground(UiKit.circle(p.warn));
        row.addView(statusDot, new LinearLayout.LayoutParams(
                UiKit.dp(this, 9), UiKit.dp(this, 9)));

        statusLabel = UiKit.text(this, "检测中…", 17, p.text, true);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        labelLp.leftMargin = UiKit.dp(this, 9);
        row.addView(statusLabel, labelLp);

        btnRefresh = UiKit.linkButton(this, p, "刷新");
        btnRefresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshStatus();
            }
        });
        row.addView(btnRefresh);
        card.addView(row, matchWrap());

        statusRom = UiKit.text(this, "正在识别系统版本…", 12, p.text2, false);
        LinearLayout.LayoutParams romLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        romLp.topMargin = UiKit.dp(this, 10);
        card.addView(statusRom, romLp);

        statusSub = UiKit.text(this, "正在检查 root 授权与激活状态", 12, p.text2, false);
        statusSub.setLineSpacing(UiKit.dp(this, 2), 1f);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = UiKit.dp(this, 5);
        card.addView(statusSub, subLp);

        // 总开关：放在状态卡里，因为它决定「模块到底管不管时钟」，
        // 和上面那行激活状态是同一件事的两面。改一下立即落盘，不用点「保存并应用」。
        card.addView(UiKit.divider(this, p));
        swEnabled = UiKit.toggle(this, p);
        card.addView(UiKit.switchRow(this, p, "启用模块",
                "关闭后回到系统原生时钟，下面的设置全部保留", swEnabled), matchWrap());
        return card;
    }

    /** 按 HyperOS「设置 → 时间格式」的行式条目组织，分组标题在卡片外。 */
    private View formatCard() {
        LinearLayout card = UiKit.card(this, p);

        // 显示秒
        swSeconds = UiKit.toggle(this, p);
        card.addView(UiKit.switchRow(this, p, "显示秒",
                "状态栏、通知面板、锁屏的时间都每秒刷新", swSeconds), matchWrap());
        card.addView(UiKit.divider(this, p));

        // 固定宽度
        sliderWidth = new UiKit.SliderRow(this, p, "固定宽度",
                "给时钟留出固定宽度，避免秒位变化导致状态栏抖动", 120, "dp");
        card.addView(sliderWidth.root);
        card.addView(UiKit.divider(this, p));

        // 24 小时制
        swHour24 = UiKit.toggle(this, p);
        card.addView(UiKit.switchRow(this, p, "24 小时制", null, swHour24), matchWrap());
        card.addView(UiKit.divider(this, p));

        // 显示时段
        swPeriod = UiKit.toggle(this, p);
        card.addView(UiKit.switchRow(this, p, "显示时段",
                "在时间左侧显示 MIUI 分时段", swPeriod), matchWrap());
        card.addView(UiKit.divider(this, p));

        // 小时补 0
        swHourPad = UiKit.toggle(this, p);
        card.addView(UiKit.switchRow(this, p, "小时补 0",
                "开启显示 09:05，关闭显示 9:05", swHourPad), matchWrap());
        card.addView(UiKit.divider(this, p));

        // 自定义格式
        swCustom = UiKit.toggle(this, p);
        card.addView(UiKit.switchRow(this, p, "开启自定义格式",
                "忽略上面的开关，直接按格式输出", swCustom), matchWrap());
        card.addView(UiKit.divider(this, p));

        // 设置格式
        tvFormatValue = UiKit.text(this, "未设置", 13, p.text2, false);
        LinearLayout fmtRow = UiKit.actionRow(this, p, "设置格式",
                "java.text.SimpleDateFormat，例如 HH:mm:ss", tvFormatValue);
        fmtRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showFormatDialog();
            }
        });
        card.addView(fmtRow);

        return card;
    }

    private View applyCard() {
        LinearLayout card = UiKit.card(this, p);

        tvPatternNow = UiKit.text(this, "HH:mm", 13, p.accent, true);
        tvPatternNow.setGravity(Gravity.END);
        tvPatternNow.setMinWidth(UiKit.dp(this, 112));
        card.addView(UiKit.switchRow(this, p, "当前格式",
                "保存后几秒内自动更新，无需重启", tvPatternNow), matchWrap());
        card.addView(UiKit.divider(this, p));

        LinearLayout btnBox = UiKit.column(this);
        int padH = UiKit.dp(this, 16);
        btnBox.setPadding(padH, UiKit.dp(this, 14), padH, UiKit.dp(this, 16));

        btnApply = UiKit.button(this, p, "保存并应用", true);
        btnApply.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });
        btnBox.addView(btnApply);

        btnRestart = UiKit.button(this, p, "重启 SystemUI", false);
        btnRestart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                restartSystemUI();
            }
        });
        btnBox.addView(btnRestart);
        // 保住按钮自身的高度参数，只补一个上边距
        LinearLayout.LayoutParams rlp = (LinearLayout.LayoutParams) btnRestart.getLayoutParams();
        rlp.topMargin = UiKit.dp(this, 8);

        card.addView(btnBox);
        return card;
    }

    private View otherCard() {
        LinearLayout card = UiKit.card(this, p);

        swCompat = UiKit.toggle(this, p);
        card.addView(UiKit.switchRow(this, p, "兼容模式",
                "注入无效时开启，改写所有时间显示", swCompat), matchWrap());
        card.addView(UiKit.divider(this, p));

        swHide = UiKit.toggle(this, p);
        card.addView(UiKit.switchRow(this, p, "隐藏桌面图标",
                "隐藏后可从 LSPosed 管理器进入本模块", swHide), matchWrap());
        return card;
    }

    /** 关于卡：HyperOS「关于手机」式信息头 + 两条可点的说明行。 */
    private View aboutCard() {
        LinearLayout card = UiKit.card(this, p);

        int padH = UiKit.dp(this, 16);
        LinearLayout head = UiKit.rowLinear(this);
        head.setPadding(padH, UiKit.dp(this, 18), padH, UiKit.dp(this, 18));
        head.setGravity(Gravity.CENTER_VERTICAL);

        ImageView icon = new ImageView(this);
        try {
            icon.setImageDrawable(getPackageManager().getApplicationIcon(getPackageName()));
        } catch (Throwable ignored) {
        }
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                UiKit.dp(this, 40), UiKit.dp(this, 40));
        head.addView(icon, iconLp);

        LinearLayout texts = UiKit.column(this);
        LinearLayout.LayoutParams textsLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        textsLp.leftMargin = UiKit.dp(this, 14);
        texts.addView(UiKit.text(this, "状态栏时钟", 16, p.text, true));
        texts.addView(UiKit.text(this, "HyperOS / MIUI · LSPosed 模块", 12, p.text2, false),
                UiKit.gaps(this, 4, 0));
        head.addView(texts, textsLp);
        card.addView(head, matchWrap());

        card.addView(UiKit.divider(this, p));

        card.addView(linkRow("项目仓库", stripScheme(URL_REPO), URL_REPO));
        card.addView(UiKit.divider(this, p));
        card.addView(linkRow("MIUITime", stripScheme(URL_MIUITIME), URL_MIUITIME));
        return card;
    }

    /** 副标题只显示 host + path，不带协议头：两行口径一致，也不会因为 "https://" 挤掉可见字符。 */
    private static String stripScheme(String url) {
        int i = url.indexOf("://");
        return i < 0 ? url : url.substring(i + 3);
    }

    /** 关于卡里的一行：标题 + 灰色说明，点整行打开浏览器。 */
    private View linkRow(String title, String sub, final String url) {
        LinearLayout row = UiKit.actionRow(this, p, title, sub, null);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openUrl(url);
            }
        });
        return row;
    }

    private void openUrl(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            toast("没有可用的浏览器");
        }
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    // ---------------------------------------------------------------- 自定义格式

    private void showFormatDialog() {
        LinearLayout col = UiKit.column(this);
        int pad = UiKit.dp(this, 20);
        col.setPadding(pad, UiKit.dp(this, 6), pad, 0);

        final EditText et = new EditText(this);
        et.setTextSize(15);
        et.setTextColor(p.text);
        et.setHintTextColor(p.text2);
        et.setHint("例如 HH:mm:ss 、a h:mm");
        et.setSingleLine(true);
        et.setText(customPattern);
        et.setBackground(UiKit.round(p.accentSoft, UiKit.dp(this, 14)));
        et.setPadding(UiKit.dp(this, 14), UiKit.dp(this, 12), UiKit.dp(this, 14), UiKit.dp(this, 12));
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        col.addView(et, matchWrap());

        final TextView preview = UiKit.text(this, "预览：—", 13, p.text2, false);
        col.addView(preview, UiKit.gaps(this, 10, 0));

        LinearLayout chips = UiKit.rowLinear(this);
        String[] quick = {"HH:mm", "HH:mm:ss", "h:mm", "a h:mm"};
        for (int i = 0; i < quick.length; i++) {
            final String q = quick[i];
            TextView chip = UiKit.text(this, q, 12, p.accent, true);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(UiKit.dp(this, 10), UiKit.dp(this, 7),
                    UiKit.dp(this, 10), UiKit.dp(this, 7));
            chip.setBackground(UiKit.round(p.accentSoft, UiKit.dp(this, 12)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) {
                lp.leftMargin = UiKit.dp(this, 6);
            }
            chip.setLayoutParams(lp);
            chip.setClickable(true);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    et.setText(q);
                    et.setSelection(q.length());
                }
            });
            chips.addView(chip);
        }
        col.addView(chips, UiKit.gaps(this, 18, 0));

        updatePreview(preview, et.getText().toString());
        et.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                updatePreview(preview, s == null ? "" : s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        new AlertDialog.Builder(this)
                .setTitle("设置格式")
                .setView(col)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String v = et.getText().toString().trim();
                        if (v.length() > 0) {
                            try {
                                new SimpleDateFormat(v, Locale.getDefault());
                            } catch (Throwable t) {
                                toast("格式不合法：" + t.getMessage());
                                return;
                            }
                        }
                        customPattern = v;
                        swCustom.setChecked(v.length() > 0);
                        refreshFormatValue();
                        persistPrefs();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void updatePreview(TextView preview, String pattern) {
        String ptn = pattern == null ? "" : pattern.trim();
        if (ptn.length() == 0) {
            preview.setText("预览：—");
            return;
        }
        try {
            preview.setText("预览：" + new SimpleDateFormat(ptn, Locale.getDefault())
                    .format(new Date()));
        } catch (Throwable t) {
            preview.setText("预览：格式不合法（" + t.getMessage() + "）");
        }
    }

    private void refreshFormatValue() {
        boolean on = customPattern != null && customPattern.trim().length() > 0;
        tvFormatValue.setText(on ? customPattern : "未设置");
        tvFormatValue.setTextColor(on ? p.accent : p.text2);
        if (tvPatternNow != null) {
            tvPatternNow.setText(collectCfg().formatSample());
        }
    }

    // ---------------------------------------------------------------- 状态

    private void loadState() {
        sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        cfg.enabled = sp.getBoolean("enabled", true);
        cfg.hour24 = sp.getBoolean("hour24", true);
        cfg.seconds = sp.getBoolean("seconds", false);
        cfg.showPeriod = sp.getBoolean("showPeriod", false);
        cfg.hourPad = sp.getBoolean("hourPad", true);
        cfg.fixedWidth = sp.getInt("fixedWidth", 0);
        cfg.customEnabled = sp.getBoolean("customEnabled", false);
        customPattern = sp.getString("custom", "");
        cfg.custom = customPattern;
        cfg.compat = sp.getBoolean("compat", false);

        swHour24.setChecked(cfg.hour24);
        swEnabled.setChecked(cfg.enabled);
        swSeconds.setChecked(cfg.seconds);
        swPeriod.setChecked(cfg.showPeriod);
        swHourPad.setChecked(cfg.hourPad);
        swCustom.setChecked(cfg.customEnabled);
        swCompat.setChecked(cfg.compat);
        sliderWidth.setProgress(cfg.fixedWidth);
        swHide.setChecked(isLauncherHidden());

        // 「显示时段」在任何小时制下都保持可点，12 / 24 小时制都会显示
        swHour24.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                refreshFormatValue();
            }
        });
        android.widget.CompoundButton.OnCheckedChangeListener refresh =
                new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(android.widget.CompoundButton b, boolean v) {
                        refreshFormatValue();
                    }
                };
        swSeconds.setOnCheckedChangeListener(refresh);
        swPeriod.setOnCheckedChangeListener(refresh);
        swHourPad.setOnCheckedChangeListener(refresh);
        swCustom.setOnCheckedChangeListener(refresh);

        swHide.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                setLauncherHidden(isChecked);
            }
        });

        // 总开关不走「保存并应用」：拨一下就该立刻生效，而且不必因为自定义格式没填而被拦。
        swEnabled.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                if (suppressToggle) {
                    return;
                }
                applyEnabled(isChecked);
            }
        });

        refreshFormatValue();
    }

    /** 总开关立即落盘。配置的其它项原样带过去，不做校验（哪怕自定义格式为空也不拦）。 */
    private void applyEnabled(final boolean on) {
        Cfg c = collectCfg();
        c.enabled = on;
        cfg.enabled = on;
        persistPrefs();
        // 必须先把缓存里的配置态改成刚写入的值：否则 updateStatus 的「开关跟配置文件对齐」
        // 会拿上一次探测的旧值把开关拨回去，界面看起来像没反应。
        if (lastAct != null) {
            lastAct.cfgEnabled = Boolean.valueOf(on);
        }
        updateStatus(lastDp, lastHasRoot, lastAct);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final RootUtil.Result r = RootUtil.apply(c);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!r.success()) {
                            // 写不进去就别让开关骗人：拨回去，状态卡跟着重算
                            suppressToggle = true;
                            swEnabled.setChecked(!on);
                            suppressToggle = false;
                            if (lastAct != null) {
                                lastAct.cfgEnabled = Boolean.valueOf(!on);
                            }
                            updateStatus(lastDp, lastHasRoot, lastAct);
                            persistPrefs();
                        }
                        toast(r.success()
                                ? (on ? "已启用，几秒内恢复成模块的格式"
                                      : "已停用，几秒内回到系统原生时钟")
                                : "写入失败：" + brief(r.output));
                    }
                });
            }
        }).start();
    }

    private Cfg collectCfg() {
        Cfg c = new Cfg();
        c.enabled = swEnabled.isChecked();
        c.hour24 = swHour24.isChecked();
        c.seconds = swSeconds.isChecked();
        c.showPeriod = swPeriod.isChecked();
        c.hourPad = swHourPad.isChecked();
        c.fixedWidth = sliderWidth.getProgress();
        c.customEnabled = swCustom.isChecked();
        c.custom = customPattern == null ? "" : customPattern.trim();
        c.compat = swCompat.isChecked();
        return c;
    }

    private void persistPrefs() {
        Cfg c = collectCfg();
        sp.edit()
                .putBoolean("enabled", c.enabled)
                .putBoolean("hour24", c.hour24)
                .putBoolean("seconds", c.seconds)
                .putBoolean("showPeriod", c.showPeriod)
                .putBoolean("hourPad", c.hourPad)
                .putInt("fixedWidth", c.fixedWidth)
                .putBoolean("customEnabled", c.customEnabled)
                .putString("custom", c.custom)
                .putBoolean("compat", c.compat)
                .apply();
        if (tvPatternNow != null) {
            tvPatternNow.setText(c.enabled ? c.formatSample() : "已停用");
        }
    }

    private void refreshStatus() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final DeviceProfile dp = DeviceProfile.read();
                final RootUtil.Result root = RootUtil.checkRoot();
                final boolean hasRoot = root.ok && root.output.contains("uid=0");
                final Activation act = hasRoot ? readActivation() : new Activation();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        updateStatus(dp, hasRoot, act);
                    }
                });
            }
        }).start();
    }

    /** 激活判定的两个事实：SystemUI 写的注入时间戳，以及当前 SystemUI 进程的启动时刻。 */
    private static final class Activation {
        long hookAt;
        long systemUiStart;
        /** 配置文件里的总开关值；null 表示没读到（此时退回界面上开关的状态）。 */
        Boolean cfgEnabled;

        /**
         * 只看时间戳是否大于 0 会在 SystemUI 重启后误报：旧时间戳还留着，新进程其实没被注入。
         * 所以要求「注入时间戳落在当前 SystemUI 进程启动之后」。留 90 秒容差，
         * 抵消 `/proc` 时钟滴答与 `date` 秒级取整的误差。
         */
        boolean isActive() {
            if (hookAt <= 0) {
                return false;
            }
            if (systemUiStart <= 0) {
                return true;
            }
            return hookAt >= systemUiStart - 90000L;
        }

        String atText() {
            if (hookAt <= 0) {
                return "";
            }
            try {
                return new SimpleDateFormat("HH:mm", Locale.getDefault())
                        .format(new Date(hookAt));
            } catch (Throwable t) {
                return "";
            }
        }
    }

    /**
     * 一次 su 取回激活判定所需的全部事实。
     *
     * HyperOS 上普通应用直接读 `Settings.Global` 会拿到 0（读取被拦），所以注入时间戳用
     * `settings` 命令读；SystemUI 的启动时刻由 `/proc/<pid>/stat` 第 22 字段（开机以来的
     * 时钟滴答，CLK_TCK=100）配合 `/proc/uptime` 反推。
     */
    private Activation readActivation() {
        Activation a = new Activation();
        try {
            RootUtil.Result r = RootUtil.activationProbe();
            for (String line : r.output.split("\n")) {
                String s = line.trim();
                if (s.startsWith("SBC_HOOK")) {
                    String v = s.substring("SBC_HOOK".length()).trim();
                    if (v.length() > 0 && !"null".equals(v)) {
                        try {
                            a.hookAt = Long.parseLong(v);
                        } catch (Throwable ignored) {
                        }
                    }
                } else if (s.startsWith("SBC_EN")) {
                    String v = s.substring("SBC_EN".length()).trim();
                    if (v.startsWith("enabled=")) {
                        a.cfgEnabled = Boolean.valueOf("1".equals(v.substring("enabled=".length()).trim()));
                    }
                } else if (s.startsWith("SBC_ACT")) {
                    String[] parts = s.substring("SBC_ACT".length()).trim().split("\\s+");
                    if (parts.length >= 4) {
                        try {
                            long st = Long.parseLong(parts[1]);
                            double up = Double.parseDouble(parts[2]);
                            long nowSec = Long.parseLong(parts[3]);
                            if (st > 0) {
                                // 启动绝对毫秒 = 现在 − (开机秒数 − 进程已运行秒数)
                                a.systemUiStart = nowSec * 1000L
                                        - (long) (up * 1000 - st * 10);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return a;
    }

    private void updateStatus(DeviceProfile dp, boolean hasRoot, Activation act) {
        lastDp = dp;
        lastHasRoot = hasRoot;
        lastAct = act;
        if (dp == null) {
            return;
        }
        statusRom.setText(dp.display() + " · " + dp.supportText());
        statusRom.setTextColor(dp.supported ? p.text2 : p.warn);

        // 界面开关一律跟配置文件对齐：写失败或外部改过配置时，界面不会自己骗自己
        if (act.cfgEnabled != null && act.cfgEnabled.booleanValue() != swEnabled.isChecked()) {
            suppressToggle = true;
            swEnabled.setChecked(act.cfgEnabled.booleanValue());
            suppressToggle = false;
        }

        if (!hasRoot) {
            statusDot.setBackground(UiKit.circle(p.err));
            statusLabel.setText("未激活");
            statusSub.setText("缺少 root：无法写入配置，也无法重启 SystemUI");
            return;
        }
        if (act.isActive()) {
            boolean en = act.cfgEnabled != null ? act.cfgEnabled.booleanValue() : swEnabled.isChecked();
            if (!en) {
                // 注入正常但总开关关着：既不是故障，也不是在管时钟，单独给一态，免得「已激活」让人误以为在生效
                statusDot.setBackground(UiKit.circle(p.text2));
                statusLabel.setText("已停用");
                statusSub.setText("模块已注入，总开关关闭 · 时钟保持系统原生");
                return;
            }
            statusDot.setBackground(UiKit.circle(p.ok));
            String when = act.atText();
            statusLabel.setText("已激活");
            statusSub.setText("模块已在系统界面中运行" + (when.length() > 0 ? " · " + when : ""));
        } else {
            statusDot.setBackground(UiKit.circle(p.warn));
            statusLabel.setText("未激活");
            statusSub.setText("在 LSPosed 中启用本模块并勾选「系统界面」，然后重启 SystemUI");
        }
    }

    // ---------------------------------------------------------------- 动作

    private void save() {
        Cfg c = collectCfg();
        if (c.customEnabled && c.custom.length() == 0) {
            toast("已开启自定义格式，请先点「设置格式」填入 pattern");
            return;
        }
        if (c.customEnabled) {
            try {
                new SimpleDateFormat(c.custom, Locale.getDefault());
            } catch (Throwable t) {
                toast("自定义格式不合法：" + t.getMessage());
                return;
            }
        }

        cfg.enabled = c.enabled;
        cfg.hour24 = c.hour24;
        cfg.seconds = c.seconds;
        cfg.showPeriod = c.showPeriod;
        cfg.hourPad = c.hourPad;
        cfg.fixedWidth = c.fixedWidth;
        cfg.customEnabled = c.customEnabled;
        cfg.custom = c.custom;
        cfg.compat = c.compat;

        persistPrefs();

        if (tvPatternNow != null) {
            tvPatternNow.setText(c.enabled ? c.formatSample() : "已停用");
        }

        setBusy(true);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Cfg target = c;
                final RootUtil.Result r = RootUtil.apply(target);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setBusy(false);
                        toast(r.success() ? "已保存，状态栏将在几秒内更新"
                                : "写入失败：" + brief(r.output));
                        if (r.success()) {
                            refreshStatus();
                        }
                    }
                });
            }
        }).start();
    }

    private String brief(String s) {
        if (s == null) {
            return "未知错误";
        }
        String t = s.trim();
        return t.length() > 120 ? t.substring(0, 120) + "…" : t;
    }

    private void restartSystemUI() {
        toast("正在重启 SystemUI…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final RootUtil.Result r = RootUtil.restartSystemUI();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        toast(r.success() ? "已重启 SystemUI" : "重启失败：" + brief(r.output));
                    }
                });
            }
        }).start();
    }

    private void setBusy(boolean busy) {
        btnApply.setEnabled(!busy);
        btnApply.setAlpha(busy ? 0.6f : 1.0f);
        btnApply.setText(busy ? "正在写入…" : "保存并应用");
    }

    private boolean isLauncherHidden() {
        ComponentName cn = new ComponentName(this, getPackageName() + ALIAS);
        return getPackageManager().getComponentEnabledSetting(cn)
                == PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
    }

    private void setLauncherHidden(boolean hidden) {
        ComponentName cn = new ComponentName(this, getPackageName() + ALIAS);
        int state = hidden ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                : PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        try {
            getPackageManager().setComponentEnabledSetting(cn, state, PackageManager.DONT_KILL_APP);
            toast(hidden ? "桌面图标已隐藏" : "桌面图标已恢复");
        } catch (Throwable t) {
            toast("切换失败：" + t.getMessage());
        }
    }

    private void showDialog(String title, String msg) {
        ScrollView sv = new ScrollView(this);
        TextView t = UiKit.text(this, msg, 13, p.text, false);
        t.setTextIsSelectable(true);
        int pad = UiKit.dp(this, 18);
        t.setPadding(pad, UiKit.dp(this, 8), pad, pad);
        sv.addView(t);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(sv)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
