package de.robv.android.xposed;
public final class XposedBridge {
    public static void log(String text) {}
    public static void log(Throwable t) {}
    public static java.util.Set<Object> hookAllMethods(Class<?> hookClass, String methodName, XC_MethodHook callback) { return null; }
}
