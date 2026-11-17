package com.misaka20002.AdaptiveThemeXposed;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
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
    private LinearLayout root;
    private Switch swEnabled, swRequireAuto, swRespectAuto, swHomeOnly, swDebug;
    private RadioButton rbBrightness, rbLux;
    private EditText bDark, bLight, lDark, lLight, delay;

    @SuppressWarnings("deprecation")
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            // Vector/LSPosed 在声明 xposedsharedprefs 时允许 MODE_WORLD_READABLE
            sp = getSharedPreferences(Prefs.FILE, Context.MODE_WORLD_READABLE);
        } catch (SecurityException e) {
            sp = null;
        }

        ScrollView sv = new ScrollView(this);
        sv.setFitsSystemWindows(true);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        root.setPadding(p, p, p, p);
        sv.addView(root);
        setContentView(sv);

        title("Adaptive Theme");
        if (sp == null) {
            text("模块未激活：请在 Vector 中启用本模块，作用域勾选「系统框架」，然后重启手机。");
            return;
        }

        swEnabled = sw("启用自动切换", sp.getBoolean(Prefs.ENABLED, true));

        section("判断依据");
        RadioGroup rg = new RadioGroup(this);
        rbBrightness = new RadioButton(this);
        rbBrightness.setText("自动亮度输出（换算为亮度条位置）");
        rbBrightness.setId(View.generateViewId());
        rbLux = new RadioButton(this);
        rbLux.setText("环境光传感器（lux，不受手动拖动亮度条影响）");
        rbLux.setId(View.generateViewId());
        rg.addView(rbBrightness);
        rg.addView(rbLux);
        boolean lux = Prefs.SRC_LUX.equals(sp.getString(Prefs.SOURCE, Prefs.SRC_BRIGHTNESS));
        rg.check(lux ? rbLux.getId() : rbBrightness.getId());
        root.addView(rg);

        section("亮度阈值（≈亮度条位置，0–100）");
        bDark = num("低于等于此值切深色", sp.getString(Prefs.B_DARK, fmt(Prefs.DEF_B_DARK)));
        bLight = num("高于等于此值切浅色", sp.getString(Prefs.B_LIGHT, fmt(Prefs.DEF_B_LIGHT)));

        section("环境光阈值（lux）");
        lDark = num("低于等于此值切深色", sp.getString(Prefs.L_DARK, fmt(Prefs.DEF_L_DARK)));
        lLight = num("高于等于此值切浅色", sp.getString(Prefs.L_LIGHT, fmt(Prefs.DEF_L_LIGHT)));

        section("行为");
        delay = num("需持续满足条件的时间（秒，0–3600）", sp.getString(Prefs.DELAY_SEC, fmt(Prefs.DEF_DELAY_SEC)));
        swRequireAuto = sw("仅在开启自动亮度时切换（部分系统上此项始终生效）", sp.getBoolean(Prefs.REQUIRE_AUTO, true));
        swRespectAuto = sw("系统深色模式为自动/定时时不干预",
                sp.getBoolean(Prefs.RESPECT_SYSTEM_AUTO, true));
        swHomeOnly = sw("仅在桌面或最近任务界面时切换（避免应用内界面重建）",
                sp.getBoolean(Prefs.HOME_ONLY, true));
        swDebug = sw("调试日志（每 5 秒记录亮度与 lux，用于校准）", sp.getBoolean(Prefs.DEBUG, false));

        Button save = new Button(this);
        save.setText("保存");
        save.setOnClickListener(v -> save());
        root.addView(save);

        text("说明：\n"
                + "• 两个阈值之间是滞回区间，处于其中时保持当前主题不变。\n"
                + "• 每次环境从亮变暗（或从暗变亮）只切换一次。之后你手动改主题不会被改回去，"
                + "直到环境再次变化。\n"
                + "• 开启「仅在桌面切换」后，如果条件满足时你正在使用应用，模块会等你回到桌面"
                + "再切换；等待期间环境又变回去则不切。如果系统无法确认前台是不是桌面，"
                + "模块宁可不切，并在日志里说明原因。\n"
                + "• 数据来自系统的自动亮度模块。关闭自动亮度后系统通常会停用光线传感器，"
                + "此时模块收不到数据，不会切换。\n"
                + "• 校准：打开调试日志并保存，分别在暗处和正常室内使用几分钟，然后在 Vector "
                + "管理器的「日志」里搜索 AdaptiveTheme，按实际读数设置阈值。\n"
                + "• 保存后立即通知系统重新读取配置，无需重启；已在倒计时中的切换也会按新配置重新判断。");
    }

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

    /** @return 错误提示；null 表示全部有效 */
    private static String validate(Snapshot s) {
        // 切浅色的值必须大于切深色的值（否则没有滞回区间）。亮度阈值还不能超过 100：
        // 换算后的亮度条位置最大就是 100，填 120 会导致进了深色再也切不回来
        if (!Prefs.validBrightness(Prefs.parse(s.bDark, -1f), Prefs.parse(s.bLight, -1f))) {
            return "亮度阈值无效：需满足 0 ≤ 切深色 < 切浅色 ≤ 100";
        }
        if (!Prefs.validLux(Prefs.parse(s.lDark, -1f), Prefs.parse(s.lLight, -1f))) {
            return "环境光阈值无效：需满足 0 ≤ 切深色 < 切浅色";
        }
        if (!Prefs.validDelay(Prefs.parse(s.delay, -1f))) {
            return "延迟无效：需在 0 到 3600 秒之间";
        }
        return null;
    }

    private void save() {
        if (saving) return;
        final Snapshot snapshot = readForm();
        String error = validate(snapshot);
        if (error != null) {
            Toast.makeText(this, error, Toast.LENGTH_LONG).show();
            return;
        }
        saving = true;
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
            public void onReceive(Context c, Intent i) {
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
            Toast.makeText(this, "保存失败：配置没有写入，系统仍在使用之前的设置", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
    }

    // ---------- 简易 UI 辅助 ----------
    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private static String fmt(float f) {
        return f == (int) f ? String.valueOf((int) f) : String.valueOf(f);
    }

    private void title(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(22);
        tv.setPadding(0, 0, 0, dp(12));
        root.addView(tv);
    }

    private void section(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(16);
        tv.setPadding(0, dp(16), 0, dp(4));
        root.addView(tv);
    }

    private void text(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(14);
        tv.setPadding(0, dp(12), 0, 0);
        root.addView(tv);
    }

    private Switch sw(String label, boolean checked) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setChecked(checked);
        s.setPadding(0, dp(6), 0, dp(6));
        root.addView(s);
        return s;
    }

    private EditText num(String hint, String value) {
        TextView label = new TextView(this);
        label.setText(hint);
        root.addView(label);
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setText(value);
        root.addView(e);
        return e;
    }
}
