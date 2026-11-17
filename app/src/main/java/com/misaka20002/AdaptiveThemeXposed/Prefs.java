package com.misaka20002.AdaptiveThemeXposed;

/** 模块 App 与 system_server 中的 hook 共用的配置键和默认值 */
final class Prefs {
    static final String PKG = "com.misaka20002.AdaptiveThemeXposed";
    static final String FILE = "config";
    /**
     * 设置页与 system_server（包名 android）之间的保存约定：
     *  1. 设置页发 ACTION_PREPARE（有序广播），等模块确认收到；
     *  2. 设置页写配置文件；
     *  3. 设置页发 ACTION_RELOAD，表示保存结束（无论成败）。
     * 这样模块在文件变化之前就已经知道"配置要变了"，不会在保存的瞬间按旧配置切换。
     */
    static final String ACTION_PREPARE = "com.misaka20002.AdaptiveThemeXposed.PREPARE_SAVE";
    /** ACTION_PREPARE 里携带的 Bundle：内含设置页进程的一个 Binder，模块用它得知设置页是否中途被杀 */
    static final String EXTRA_SAVE_TOKEN = "com.misaka20002.AdaptiveThemeXposed.extra.SAVE_TOKEN";
    static final String KEY_SAVE_TOKEN = "token";
    static final String ACTION_RELOAD = "com.misaka20002.AdaptiveThemeXposed.RELOAD";
    static final String SYSTEM_PKG = "android";
    /** 签名级权限：只有本模块 App（同一签名）能向 system_server 里的接收器发送重载广播 */
    static final String PERMISSION_RELOAD = "com.misaka20002.AdaptiveThemeXposed.permission.RELOAD";

    static final String ENABLED = "enabled";
    static final String SOURCE = "source";
    static final String SRC_BRIGHTNESS = "brightness";
    static final String SRC_LUX = "lux";

    // 亮度阈值以"亮度条位置"为单位（0-100），不是线性背光值
    static final String B_DARK = "slider_dark_below";
    static final String B_LIGHT = "slider_light_above";
    static final String L_DARK = "lux_dark_below";
    static final String L_LIGHT = "lux_light_above";

    static final String DELAY_SEC = "delay_sec";
    static final String REQUIRE_AUTO = "require_auto";
    static final String RESPECT_SYSTEM_AUTO = "respect_system_auto";
    static final String HOME_ONLY = "home_only";
    static final String DEBUG = "debug";

    static final float DEF_B_DARK = 15f, DEF_B_LIGHT = 35f;
    static final float DEF_L_DARK = 10f, DEF_L_LIGHT = 60f;
    static final float DEF_DELAY_SEC = 10f;

    static final float SLIDER_MAX = 100f;   // 换算后的亮度条位置最大就是 100
    static final float MAX_DELAY_SEC = 3600f;

    static boolean validDelay(float sec) {
        return sec >= 0f && sec <= MAX_DELAY_SEC;
    }

    /** 模块侧兜底：超过上限的按上限算（负数、NaN、无穷大已在 parse() 里换成默认值） */
    static float sanitizeDelay(float sec) {
        return Math.min(sec, MAX_DELAY_SEC);
    }

    /** 亮度阈值必须都能达到：0 <= 切深色 < 切浅色 <= 100 */
    static boolean validBrightness(float dark, float light) {
        return dark >= 0f && light > dark && light <= SLIDER_MAX;
    }

    static boolean validLux(float dark, float light) {
        return dark >= 0f && light > dark;
    }

    /** 模块侧兜底（旧版本可能存过越界值）：先把上限收进 100，仍不成立就用默认值 */
    static float[] sanitizeBrightness(float dark, float light) {
        light = Math.min(light, SLIDER_MAX);
        return validBrightness(dark, light) ? new float[]{dark, light} : new float[]{DEF_B_DARK, DEF_B_LIGHT};
    }

    static float[] sanitizeLux(float dark, float light) {
        return validLux(dark, light) ? new float[]{dark, light} : new float[]{DEF_L_DARK, DEF_L_LIGHT};
    }

    static float parse(String s, float def) {
        if (s == null) return def;
        try {
            float v = Float.parseFloat(s.trim());
            return (Float.isNaN(v) || Float.isInfinite(v) || v < 0) ? def : v;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private Prefs() {}
}
