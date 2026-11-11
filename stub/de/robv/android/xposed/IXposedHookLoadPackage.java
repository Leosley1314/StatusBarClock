package de.robv.android.xposed;

import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * 编译期 stub：真实实现由 Xposed / LSPosed 框架在运行时提供。
 * 该类不会被打包进 APK。
 */
public interface IXposedHookLoadPackage {
    void handleLoadPackage(LoadPackageParam lpparam) throws Throwable;
}
