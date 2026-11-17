package com.misaka20002.AdaptiveThemeXposed;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class HookEntry implements IXposedHookLoadPackage {
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        // 只作用于 system_server
        if (!"android".equals(lpparam.packageName) || !"android".equals(lpparam.processName)) return;
        try {
            ThemeController.init(lpparam.classLoader);
        } catch (Throwable t) {
            XposedBridge.log("[AdaptiveTheme] init failed");
            XposedBridge.log(t);
        }
    }
}
