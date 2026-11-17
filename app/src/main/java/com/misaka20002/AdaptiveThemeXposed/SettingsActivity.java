package com.misaka20002.AdaptiveThemeXposed;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class SettingsActivity extends Activity {
    private SharedPreferences sp;
    private Switch swEnabled, swRequireAuto, swRespectAuto, swHomeOnly, swDebug;
    private RadioButton rbBrightness, rbLux;
    private EditText bDark, bLight, lDark, lLight, delay;

    private LinearLayout cardSource, cardBrightness, cardLux, cardBehavior;
    private TextView brightnessTag, luxTag, brightnessSummary, luxSummary, delaySummary;
    private RangeBar brightnessBar, luxBar;
    private Button saveButton;

    /** 上次成功写入的配置，用于判断表单是否有未保存的修改 */
    private String savedKey;

    private Palette c;

    @SuppressWarnings("deprecation")
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        c = new Palette(this);
        try {
            // Vector/LSPosed 在声明 xposedsharedprefs 时允许 MODE_WORLD_READABLE
            sp = getSharedPreferences(Prefs.FILE, Context.MODE_WORLD_READABLE);
        } catch (SecurityException e) {
            sp = null;
        }

        setupWindow();

        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(c.bg);

        ScrollView sv = new ScrollView(this);
        sv.setId(R.id.scroll);
        sv.setClipToPadding(false);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(24), dp(16), dp(16));
        sv.addView(content);
        outer.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bottomBar = null;
        if (sp == null) {
            buildHeader(content, false);
            LinearLayout card = card(content, null);
            body(card, "请在 Vector / LSPosed 中启用本模块，作用域勾选「系统框架」，然后重启手机。", c.text);
        } else {
            buildHeader(content, true);
            buildForm(content);
            bottomBar = buildBottomBar(outer);
        }

        applyInsets(outer, bottomBar != null ? bottomBar : content);
        setContentView(outer);

        if (sp != null) {
            savedKey = key(readForm());
            updateState();
        }
    }

    @Override
    protected void onRestoreInstanceState(Bundle state) {
        super.onRestoreInstanceState(state);
        // 系统恢复表单内容时监听器会逐个触发，这里再统一刷新一次
        if (sp != null) updateState();
    }

    // ---------- 布局 ----------

    private void buildHeader(LinearLayout parent, boolean active) {
        TextView title = new TextView(this);
        title.setText("Adaptive Theme");
        title.setTextSize(28);
        title.setTextColor(c.text);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        parent.addView(title);

        TextView sub = new TextView(this);
        sub.setText("根据环境亮度自动切换深色 / 浅色模式");
        sub.setTextSize(14);
        sub.setTextColor(c.textSecondary);
        sub.setPadding(0, dp(4), 0, dp(12));
        parent.addView(sub);

        TextView chip = new TextView(this);
        chip.setText(active ? "●  模块已激活" : "●  模块未激活");
        chip.setTextSize(13);
        chip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        chip.setTextColor(active ? c.onAccentContainer : c.onErrorContainer);
        chip.setPadding(dp(12), dp(6), dp(14), dp(6));
        chip.setBackground(rounded(active ? c.accentContainer : c.errorContainer, dp(16)));
        LinearLayout.LayoutParams lp = wrap();
        lp.bottomMargin = dp(20);
        parent.addView(chip, lp);
    }

    private void buildForm(LinearLayout content) {
        // 总开关单独一张卡片
        LinearLayout master = card(content, null);
        swEnabled = switchRow(master, R.id.sw_enabled, "启用自动切换",
                "关闭后模块不做任何事", sp.getBoolean(Prefs.ENABLED, true));

        // 判断依据
        cardSource = card(content, "判断依据");
        RadioGroup rg = new RadioGroup(this);
        rbBrightness = radio(rg, R.id.rb_brightness, "自动亮度输出",
                "按系统自动亮度算出的亮度条位置判断");
        rbLux = radio(rg, R.id.rb_lux, "环境光传感器",
                "直接读 lux，不受手动拖动亮度条影响");
        boolean lux = Prefs.SRC_LUX.equals(sp.getString(Prefs.SOURCE, Prefs.SRC_BRIGHTNESS));
        rg.check(lux ? R.id.rb_lux : R.id.rb_brightness);
        rg.setOnCheckedChangeListener((g, id) -> updateState());
        cardSource.addView(rg);

        // 亮度阈值
        cardBrightness = card(content, "亮度阈值");
        brightnessTag = cardTag(cardBrightness);
        hint(cardBrightness, "≈ 亮度条位置，0–100");
        LinearLayout row = pairRow(cardBrightness);
        bDark = field(row, R.id.et_b_dark, "切深色 ≤", sp.getString(Prefs.B_DARK, fmt(Prefs.DEF_B_DARK)), "%");
        gap(row);
        bLight = field(row, R.id.et_b_light, "切浅色 ≥", sp.getString(Prefs.B_LIGHT, fmt(Prefs.DEF_B_LIGHT)), "%");
        brightnessBar = rangeBar(cardBrightness, false);
        brightnessSummary = summary(cardBrightness);

        // 环境光阈值
        cardLux = card(content, "环境光阈值");
        luxTag = cardTag(cardLux);
        hint(cardLux, "单位 lux。室内一般 50–500，夜间关灯接近 0");
        row = pairRow(cardLux);
        lDark = field(row, R.id.et_l_dark, "切深色 ≤", sp.getString(Prefs.L_DARK, fmt(Prefs.DEF_L_DARK)), "lx");
        gap(row);
        lLight = field(row, R.id.et_l_light, "切浅色 ≥", sp.getString(Prefs.L_LIGHT, fmt(Prefs.DEF_L_LIGHT)), "lx");
        luxBar = rangeBar(cardLux, true);
        luxSummary = summary(cardLux);

        // 行为
        cardBehavior = card(content, "行为");
        row = pairRow(cardBehavior);
        delay = field(row, R.id.et_delay, "持续时间", sp.getString(Prefs.DELAY_SEC, fmt(Prefs.DEF_DELAY_SEC)), "秒");
        View spacer = new View(this);
        row.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        delaySummary = summary(cardBehavior);
        divider(cardBehavior);
        swRequireAuto = switchRow(cardBehavior, R.id.sw_require_auto, "仅在自动亮度开启时切换",
                "部分系统上此项始终生效", sp.getBoolean(Prefs.REQUIRE_AUTO, true));
        swRespectAuto = switchRow(cardBehavior, R.id.sw_respect_auto, "尊重系统自动深色模式",
                "系统深色模式为自动 / 定时时不干预", sp.getBoolean(Prefs.RESPECT_SYSTEM_AUTO, true));
        swHomeOnly = switchRow(cardBehavior, R.id.sw_home_only, "仅在桌面切换",
                "在桌面或最近任务界面时才切换，避免应用内界面重建", sp.getBoolean(Prefs.HOME_ONLY, true));
        swDebug = switchRow(cardBehavior, R.id.sw_debug, "调试日志",
                "每 5 秒记录亮度与 lux，用于校准阈值", sp.getBoolean(Prefs.DEBUG, false));

        buildHelp(content);
    }

    private void buildHelp(LinearLayout content) {
        LinearLayout card = card(content, null);
        TextView header = new TextView(this);
        header.setTextSize(16);
        header.setTextColor(c.text);
        header.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.setPadding(0, dp(14), 0, dp(14));
        header.setBackground(selectable());
        card.addView(header, matchWrap());

        TextView body = new TextView(this);
        body.setTextSize(14);
        body.setTextColor(c.textSecondary);
        body.setLineSpacing(dp(3), 1f);
        body.setPadding(0, 0, 0, dp(14));
        body.setText("• 两个阈值之间是滞回区间，处于其中时保持当前主题不变。\n\n"
                + "• 每次环境从亮变暗（或从暗变亮）只切换一次。之后你手动改主题不会被改回去，"
                + "直到环境再次变化。\n\n"
                + "• 开启「仅在桌面切换」后，如果条件满足时你正在使用应用，模块会等你回到桌面"
                + "再切换；等待期间环境又变回去则不切。如果系统无法确认前台是不是桌面，"
                + "模块宁可不切，并在日志里说明原因。\n\n"
                + "• 数据来自系统的自动亮度模块。关闭自动亮度后系统通常会停用光线传感器，"
                + "此时模块收不到数据，不会切换。\n\n"
                + "• 校准：打开调试日志并保存，分别在暗处和正常室内使用几分钟，然后在 Vector "
                + "管理器的「日志」里搜索 AdaptiveTheme，按实际读数设置阈值。\n\n"
                + "• 保存后立即通知系统重新读取配置，无需重启；已在倒计时中的切换也会按新配置重新判断。");
        body.setVisibility(View.GONE);
        card.addView(body, matchWrap());

        Runnable sync = () -> header.setText(
                (body.getVisibility() == View.VISIBLE ? "▾  " : "▸  ") + "使用说明");
        sync.run();
        header.setOnClickListener(v -> {
            body.setVisibility(body.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            sync.run();
        });
    }

    private LinearLayout buildBottomBar(LinearLayout outer) {
        LinearLayout bar = new LinearLayout(this);
        bar.setBackgroundColor(c.bg);
        bar.setPadding(dp(16), dp(8), dp(16), dp(12));
        saveButton = new Button(this);
        saveButton.setAllCaps(false);
        saveButton.setTextSize(16);
        saveButton.setTextColor(c.onAccent);
        saveButton.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        saveButton.setStateListAnimator(null);
        saveButton.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF),
                rounded(c.accent, dp(28)), null));
        saveButton.setOnClickListener(v -> save());
        bar.addView(saveButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        outer.addView(bar, matchWrap());
        return bar;
    }

    // ---------- 状态刷新 ----------

    /** 表单任意变化后调用：刷新摘要、色条、卡片状态和保存按钮 */
    private void updateState() {
        if (saveButton == null || delaySummary == null) return; // 构建过程中
        Snapshot s = readForm();
        boolean on = s.enabled;
        boolean lux = s.lux;

        float bd = Prefs.parse(s.bDark, -1f), bl = Prefs.parse(s.bLight, -1f);
        boolean bValid = Prefs.validBrightness(bd, bl);
        brightnessBar.set(bd, bl, bValid);
        setSummary(brightnessSummary, bValid,
                "≤ " + fmt(bd) + " 切深色 · " + fmt(bd) + "–" + fmt(bl) + " 保持 · ≥ " + fmt(bl) + " 切浅色",
                "需满足 0 ≤ 切深色 < 切浅色 ≤ 100");

        float ld = Prefs.parse(s.lDark, -1f), ll = Prefs.parse(s.lLight, -1f);
        boolean lValid = Prefs.validLux(ld, ll);
        luxBar.set(ld, ll, lValid);
        setSummary(luxSummary, lValid,
                "≤ " + fmt(ld) + " lx 切深色 · 之间保持 · ≥ " + fmt(ll) + " lx 切浅色",
                "需满足 0 ≤ 切深色 < 切浅色");

        float d = Prefs.parse(s.delay, -1f);
        boolean dValid = Prefs.validDelay(d);
        setSummary(delaySummary, dValid,
                d == 0f ? "条件满足后立即切换" : "环境需连续 " + fmt(d) + " 秒满足条件才切换",
                "需在 0 到 3600 秒之间");

        // 当前未使用的那组阈值淡化显示，但仍可编辑
        styleTag(brightnessTag, !lux);
        styleTag(luxTag, lux);
        float off = 0.45f;
        cardSource.setAlpha(on ? 1f : off);
        cardBehavior.setAlpha(on ? 1f : off);
        cardBrightness.setAlpha(on && !lux ? 1f : off);
        cardLux.setAlpha(on && lux ? 1f : off);

        boolean dirty = !key(s).equals(savedKey);
        boolean enabled = dirty && !saving;
        saveButton.setEnabled(enabled);
        saveButton.setAlpha(enabled ? 1f : 0.4f);
        saveButton.setText(saving ? "正在保存…" : dirty ? "保存更改" : "已保存");
    }

    private void setSummary(TextView tv, boolean valid, String ok, String err) {
        tv.setText(valid ? ok : "⚠ " + err);
        tv.setTextColor(valid ? c.textSecondary : c.error);
    }

    private void styleTag(TextView tag, boolean active) {
        tag.setText(active ? "使用中" : "未使用");
        tag.setTextColor(active ? c.onAccentContainer : c.textSecondary);
        tag.setBackground(rounded(active ? c.accentContainer : c.outlineVariant, dp(10)));
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (sp != null && !saving && !key(readForm()).equals(savedKey)) {
            new AlertDialog.Builder(this)
                    .setTitle("有未保存的更改")
                    .setMessage("离开前要保存吗？")
                    .setPositiveButton("保存", (d, w) -> save())
                    .setNegativeButton("放弃", (d, w) -> finish())
                    .setNeutralButton("取消", null)
                    .show();
            return;
        }
        super.onBackPressed();
    }

    // ---------- 保存 ----------

    private boolean saving;
    /** 交给模块的存活凭证：本进程一死，模块立刻知道这次保存不会完成 */
    private final Binder saveToken = new Binder();

    /** 点击「保存」那一刻的表单内容。校验和写入用的是同一份，之后再改表单不影响这次保存 */
    private static final class Snapshot {
        boolean enabled, lux, requireAuto, respectAuto, homeOnly, debug;
        String bDark, bLight, lDark, lLight, delay;
    }

    private Snapshot readForm() {
        Snapshot s = new Snapshot();
        s.enabled = swEnabled.isChecked();
        s.lux = rbLux.isChecked();
        s.requireAuto = swRequireAuto.isChecked();
        s.respectAuto = swRespectAuto.isChecked();
        s.homeOnly = swHomeOnly.isChecked();
        s.debug = swDebug.isChecked();
        s.bDark = bDark.getText().toString().trim();
        s.bLight = bLight.getText().toString().trim();
        s.lDark = lDark.getText().toString().trim();
        s.lLight = lLight.getText().toString().trim();
        s.delay = delay.getText().toString().trim();
        return s;
    }

    private static String key(Snapshot s) {
        return s.enabled + "|" + s.lux + "|" + s.requireAuto + "|" + s.respectAuto + "|" + s.homeOnly
                + "|" + s.debug + "|" + s.bDark + "|" + s.bLight + "|" + s.lDark + "|" + s.lLight
                + "|" + s.delay;
    }

    /** @return 出错的输入框；null 表示全部有效。错误信息直接标在输入框上 */
    private EditText validate(Snapshot s) {
        // 切浅色的值必须大于切深色的值（否则没有滞回区间）。亮度阈值还不能超过 100：
        // 换算后的亮度条位置最大就是 100，填 120 会导致进了深色再也切不回来
        if (!Prefs.validBrightness(Prefs.parse(s.bDark, -1f), Prefs.parse(s.bLight, -1f))) {
            return markError(Prefs.parse(s.bDark, -1f) < 0f ? bDark : bLight,
                    "需满足 0 ≤ 切深色 < 切浅色 ≤ 100");
        }
        if (!Prefs.validLux(Prefs.parse(s.lDark, -1f), Prefs.parse(s.lLight, -1f))) {
            return markError(Prefs.parse(s.lDark, -1f) < 0f ? lDark : lLight,
                    "需满足 0 ≤ 切深色 < 切浅色");
        }
        if (!Prefs.validDelay(Prefs.parse(s.delay, -1f))) {
            return markError(delay, "需在 0 到 3600 秒之间");
        }
        return null;
    }

    private EditText markError(EditText e, String msg) {
        e.setError(msg);
        return e;
    }

    private void save() {
        if (saving) return;
        final Snapshot snapshot = readForm();
        EditText bad = validate(snapshot);
        if (bad != null) {
            bad.requestFocus();
            Toast.makeText(this, "有设置无效，请检查标出的输入框", Toast.LENGTH_SHORT).show();
            return;
        }
        saving = true;
        updateState();
        // 第 1 步：先告诉 system_server 里的模块"要保存了"。用有序广播：最终回调一定在模块的接收器
        // 处理完之后才执行，所以写文件时模块已经知道配置要变，不会在这一瞬间按旧配置切换。
        // 模块没激活时没有接收器，回调会立刻执行。
        Bundle token = new Bundle();
        token.putBinder(Prefs.KEY_SAVE_TOKEN, saveToken);
        Intent prepare = new Intent(Prefs.ACTION_PREPARE).setPackage(Prefs.SYSTEM_PKG)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                .putExtra(Prefs.EXTRA_SAVE_TOKEN, token);
        sendOrderedBroadcast(prepare, null, new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent i) {
                commitAndNotify(snapshot);
            }
        }, null, Activity.RESULT_OK, null, null);
    }

    private void commitAndNotify(Snapshot s) {
        saving = false;
        // 第 2 步：写文件。写的是点击那一刻校验过的快照，不再读表单
        boolean written = sp.edit()
                .putBoolean(Prefs.ENABLED, s.enabled)
                .putString(Prefs.SOURCE, s.lux ? Prefs.SRC_LUX : Prefs.SRC_BRIGHTNESS)
                .putString(Prefs.B_DARK, s.bDark)
                .putString(Prefs.B_LIGHT, s.bLight)
                .putString(Prefs.L_DARK, s.lDark)
                .putString(Prefs.L_LIGHT, s.lLight)
                .putString(Prefs.DELAY_SEC, s.delay)
                .putBoolean(Prefs.REQUIRE_AUTO, s.requireAuto)
                .putBoolean(Prefs.RESPECT_SYSTEM_AUTO, s.respectAuto)
                .putBoolean(Prefs.HOME_ONLY, s.homeOnly)
                .putBoolean(Prefs.DEBUG, s.debug)
                .commit();
        // 第 3 步：通知"保存结束"。写入失败也要发，否则模块会一直等下去；
        // 失败时文件没变，模块重读后不会有任何变化
        sendBroadcast(new Intent(Prefs.ACTION_RELOAD).setPackage(Prefs.SYSTEM_PKG)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND));
        if (!written) {
            // 没写进去就不能说"已保存"：system_server 仍在使用之前的配置
            updateState();
            Toast.makeText(this, "保存失败：配置没有写入，系统仍在使用之前的设置", Toast.LENGTH_LONG).show();
            return;
        }
        savedKey = key(s);
        updateState();
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
    }

    // ---------- 窗口与边衬区 ----------

    @SuppressWarnings("deprecation")
    private void setupWindow() {
        Window w = getWindow();
        w.getDecorView().setBackgroundColor(c.bg);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // 内容延伸到系统栏后面，边距由 applyInsets 处理（Android 15 起强制如此）
            w.setDecorFitsSystemWindows(false);
            w.setStatusBarColor(Color.TRANSPARENT);
            w.setNavigationBarColor(Color.TRANSPARENT);
            WindowInsetsController ic = w.getInsetsController();
            if (ic != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                ic.setSystemBarsAppearance(c.night ? 0 : mask, mask);
            }
        } else {
            w.setStatusBarColor(c.bg);
            w.setNavigationBarColor(c.bg);
            View decor = w.getDecorView();
            int flags = decor.getSystemUiVisibility();
            int light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            decor.setSystemUiVisibility(c.night ? flags & ~light : flags | light);
        }
    }

    @SuppressWarnings("deprecation")
    private void applyInsets(View outer, View bottom) {
        final int bottomBase = bottom.getPaddingBottom();
        outer.setOnApplyWindowInsetsListener((v, insets) -> {
            int l, t, r, b;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout());
                Insets ime = insets.getInsets(WindowInsets.Type.ime());
                l = bars.left; t = bars.top; r = bars.right; b = Math.max(bars.bottom, ime.bottom);
            } else {
                l = insets.getSystemWindowInsetLeft();
                t = insets.getSystemWindowInsetTop();
                r = insets.getSystemWindowInsetRight();
                b = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(l, t, r, 0);
            bottom.setPadding(bottom.getPaddingLeft(), bottom.getPaddingTop(),
                    bottom.getPaddingRight(), bottomBase + b);
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    ? WindowInsets.CONSUMED : insets.consumeSystemWindowInsets();
        });
    }

    // ---------- UI 构件 ----------

    private LinearLayout card(LinearLayout parent, String title) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(c.card, dp(20)));
        card.setPadding(dp(16), title == null ? dp(4) : dp(16), dp(16), dp(4));
        if (title != null) {
            LinearLayout head = new LinearLayout(this);
            head.setGravity(Gravity.CENTER_VERTICAL);
            TextView tv = new TextView(this);
            tv.setText(title);
            tv.setTextSize(14);
            tv.setTextColor(c.accent);
            tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            head.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            card.addView(head, matchWrap());
        }
        LinearLayout.LayoutParams lp = matchWrap();
        lp.bottomMargin = dp(12);
        parent.addView(card, lp);
        return card;
    }

    /** 卡片标题右侧的小标签（使用中 / 未使用） */
    private TextView cardTag(LinearLayout card) {
        LinearLayout head = (LinearLayout) card.getChildAt(0);
        TextView tag = new TextView(this);
        tag.setTextSize(12);
        tag.setPadding(dp(8), dp(2), dp(8), dp(2));
        head.addView(tag, wrap());
        return tag;
    }

    private void hint(LinearLayout parent, String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(13);
        tv.setTextColor(c.textSecondary);
        tv.setPadding(0, dp(2), 0, dp(4));
        parent.addView(tv);
    }

    private TextView summary(LinearLayout parent) {
        TextView tv = new TextView(this);
        tv.setTextSize(13);
        tv.setPadding(0, dp(6), 0, dp(12));
        parent.addView(tv);
        return tv;
    }

    private void body(LinearLayout parent, String s, int color) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(15);
        tv.setTextColor(color);
        tv.setLineSpacing(dp(2), 1f);
        tv.setPadding(0, dp(12), 0, dp(12));
        parent.addView(tv);
    }

    private void divider(LinearLayout parent) {
        View v = new View(this);
        v.setBackgroundColor(c.outlineVariant);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.bottomMargin = dp(4);
        parent.addView(v, lp);
    }

    private LinearLayout pairRow(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, dp(8), 0, 0);
        parent.addView(row, matchWrap());
        return row;
    }

    private void gap(LinearLayout row) {
        row.addView(new View(this), new LinearLayout.LayoutParams(dp(12), 1));
    }

    private EditText field(LinearLayout row, int id, String label, String value, String suffix) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        TextView lb = new TextView(this);
        lb.setText(label);
        lb.setTextSize(13);
        lb.setTextColor(c.textSecondary);
        lb.setPadding(dp(4), 0, 0, dp(4));
        col.addView(lb);

        LinearLayout box = new LinearLayout(this);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(14), 0, dp(14), 0);
        GradientDrawable bg = rounded(Color.TRANSPARENT, dp(12));
        bg.setStroke(dp(1), c.outline);
        box.setBackground(bg);

        EditText e = new EditText(this);
        e.setId(id);
        e.setBackground(null);
        e.setPadding(0, dp(12), 0, dp(12));
        e.setTextSize(17);
        e.setTextColor(c.text);
        e.setSingleLine(true);
        e.setSelectAllOnFocus(true);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setText(value);
        e.setOnFocusChangeListener((v, focused) ->
                bg.setStroke(dp(focused ? 2 : 1), focused ? c.accent : c.outline));
        e.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int d) {}
            @Override public void afterTextChanged(Editable s) {
                e.setError(null);
                updateState();
            }
        });
        box.addView(e, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView sx = new TextView(this);
        sx.setText(suffix);
        sx.setTextSize(15);
        sx.setTextColor(c.textSecondary);
        box.addView(sx);

        col.addView(box, matchWrap());
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return e;
    }

    private RangeBar rangeBar(LinearLayout parent, boolean log) {
        RangeBar bar = new RangeBar(this, log, c);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10));
        lp.topMargin = dp(14);
        parent.addView(bar, lp);
        return bar;
    }

    private Switch switchRow(LinearLayout parent, int id, String title, String summary, boolean checked) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));
        row.setBackground(selectable());

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(0, 0, dp(16), 0);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(16);
        t.setTextColor(c.text);
        texts.addView(t);
        TextView s = new TextView(this);
        s.setText(summary);
        s.setTextSize(13);
        s.setTextColor(c.textSecondary);
        s.setPadding(0, dp(2), 0, 0);
        texts.addView(s);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch sw = new Switch(this);
        sw.setId(id);
        sw.setChecked(checked);
        int[][] states = {{android.R.attr.state_checked}, {}};
        sw.setThumbTintList(new ColorStateList(states, new int[]{c.accent, c.thumbOff}));
        sw.setTrackTintList(new ColorStateList(states, new int[]{c.accent, c.outline}));
        sw.setOnCheckedChangeListener((b, v) -> updateState());
        row.addView(sw, wrap());
        row.setOnClickListener(v -> sw.toggle());

        parent.addView(row, matchWrap());
        return sw;
    }

    private RadioButton radio(RadioGroup group, int id, String title, String desc) {
        RadioButton rb = new RadioButton(this);
        rb.setId(id);
        SpannableStringBuilder sb = new SpannableStringBuilder(title);
        sb.setSpan(new ForegroundColorSpan(c.text), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        int start = sb.length();
        sb.append("\n").append(desc);
        sb.setSpan(new RelativeSizeSpan(0.82f), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new ForegroundColorSpan(c.textSecondary), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        rb.setText(sb);
        rb.setTextSize(16);
        rb.setLineSpacing(dp(2), 1f);
        rb.setPadding(dp(8), dp(10), 0, dp(10));
        rb.setButtonTintList(new ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}}, new int[]{c.accent, c.textSecondary}));
        group.addView(rb, new RadioGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return rb;
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private android.graphics.drawable.Drawable selectable() {
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        return getDrawable(tv.resourceId);
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private static String fmt(float f) {
        if (f < 0f) return "?";
        return f == (int) f ? String.valueOf((int) f) : String.valueOf(f);
    }

    // ---------- 配色：Android 12+ 跟随系统动态取色，旧版本用固定色 ----------

    private static final class Palette {
        final boolean night;
        final int bg, card, text, textSecondary, accent, onAccent, accentContainer, onAccentContainer,
                outline, outlineVariant, thumbOff, error, errorContainer, onErrorContainer,
                segDark, segLight;

        Palette(Context ctx) {
            night = (ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
            boolean dyn = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
            if (night) {
                bg = pick(ctx, dyn, android.R.color.system_neutral1_900, 0xFF121316);
                card = pick(ctx, dyn, android.R.color.system_neutral1_800, 0xFF1F2024);
                text = pick(ctx, dyn, android.R.color.system_neutral1_50, 0xFFE4E2E6);
                textSecondary = pick(ctx, dyn, android.R.color.system_neutral2_200, 0xFFC4C6D0);
                accent = pick(ctx, dyn, android.R.color.system_accent1_200, 0xFFA8C7FA);
                onAccent = pick(ctx, dyn, android.R.color.system_accent1_800, 0xFF062E6F);
                accentContainer = pick(ctx, dyn, android.R.color.system_accent1_700, 0xFF0B4A9E);
                onAccentContainer = pick(ctx, dyn, android.R.color.system_accent1_100, 0xFFD3E3FD);
                outline = pick(ctx, dyn, android.R.color.system_neutral2_500, 0xFF8E9099);
                outlineVariant = pick(ctx, dyn, android.R.color.system_neutral2_700, 0xFF44474F);
                thumbOff = pick(ctx, dyn, android.R.color.system_neutral2_300, 0xFFA9ABB4);
                error = 0xFFF2B8B5;
                errorContainer = 0xFF8C1D18;
                onErrorContainer = 0xFFF9DEDC;
                segDark = 0xFF050608;
                segLight = 0xFFF6E7B0;
            } else {
                bg = pick(ctx, dyn, android.R.color.system_neutral1_50, 0xFFF1F1F5);
                card = pick(ctx, dyn, android.R.color.system_neutral1_10, 0xFFFFFFFF);
                text = pick(ctx, dyn, android.R.color.system_neutral1_900, 0xFF1B1B1F);
                textSecondary = pick(ctx, dyn, android.R.color.system_neutral2_700, 0xFF44474F);
                accent = pick(ctx, dyn, android.R.color.system_accent1_600, 0xFF0B57D0);
                onAccent = 0xFFFFFFFF;
                accentContainer = pick(ctx, dyn, android.R.color.system_accent1_100, 0xFFD3E3FD);
                onAccentContainer = pick(ctx, dyn, android.R.color.system_accent1_900, 0xFF041E49);
                outline = pick(ctx, dyn, android.R.color.system_neutral2_400, 0xFF747775);
                outlineVariant = pick(ctx, dyn, android.R.color.system_neutral2_100, 0xFFE1E3E1);
                thumbOff = pick(ctx, dyn, android.R.color.system_neutral2_500, 0xFF747775);
                error = 0xFFB3261E;
                errorContainer = 0xFFF9DEDC;
                onErrorContainer = 0xFF410E0B;
                segDark = 0xFF2E3038;
                segLight = 0xFFFFD970;
            }
        }

        private static int pick(Context ctx, boolean dyn, int res, int fallback) {
            return dyn ? ctx.getColor(res) : fallback;
        }
    }

    // ---------- 阈值示意条：深色区 | 滞回区 | 浅色区 ----------

    private static final class RangeBar extends View {
        private final boolean log;
        private final Palette c;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();
        private final RectF rect = new RectF();
        private float dark, light;
        private boolean valid;

        RangeBar(Context ctx, boolean log, Palette c) {
            super(ctx);
            this.log = log;
            this.c = c;
        }

        void set(float dark, float light, boolean valid) {
            this.dark = dark;
            this.light = light;
            this.valid = valid;
            invalidate();
        }

        /** 亮度按 0–100 线性；lux 跨度大，用对数刻度 */
        private float pos(float v) {
            if (!log) return Math.max(0f, Math.min(1f, v / Prefs.SLIDER_MAX));
            float max = Math.max(1000f, light * 3f);
            return (float) (Math.log10(1 + v) / Math.log10(1 + max));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            clip.reset();
            rect.set(0, 0, w, h);
            clip.addRoundRect(rect, h / 2f, h / 2f, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clip);
            if (!valid) {
                paint.setColor(c.error);
                paint.setAlpha(0x55);
                canvas.drawRect(rect, paint);
            } else {
                float x1 = pos(dark) * w, x2 = pos(light) * w;
                paint.setColor(c.segDark);
                canvas.drawRect(0, 0, x1, h, paint);
                paint.setColor(c.outlineVariant);
                canvas.drawRect(x1, 0, x2, h, paint);
                paint.setColor(c.segLight);
                canvas.drawRect(x2, 0, w, h, paint);
            }
            canvas.restore();
        }
    }
}
