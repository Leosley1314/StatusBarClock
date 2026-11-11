package de.robv.android.xposed;

/**
 * 编译期 stub。
 *
 * 注意：这里的签名必须与 LSPosed / Xposed 真实 API 完全一致。
 * 方法是按「名称 + 参数类型 + 返回类型」三者组装成 dex 里的描述符去查找的，
 * 只要返回类型写错（例如把 Set 写成 Unhook），编译期照样通过，
 * 运行时却会抛 NoSuchMethodError —— 表现为 hook 全部「注入失败」。
 */
public final class XposedBridge {

    private XposedBridge() {
    }

    public static void log(String text) {
    }

    public static void log(Throwable t) {
    }

    public static java.util.Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> hookClass,
                                                                    String methodName,
                                                                    XC_MethodHook callback) {
        return null;
    }

    public static java.util.Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> hookClass,
                                                                    String methodName,
                                                                    XC_MethodHook callback,
                                                                    int priority) {
        return null;
    }
}
