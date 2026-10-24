package com.classaudio.hook;

import android.media.AudioTrack;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Copies PCM written to AudioTrack inside the ChatGPT app and hands it to {@link Streamer}. */
public class Main implements IXposedHookLoadPackage {
    private static final String TARGET = "com.openai.chatgpt";
    // AudioTrack.write overloads call each other; only the outermost call carries new audio.
    private static final ThreadLocal<int[]> DEPTH = new ThreadLocal<int[]>() {
        @Override protected int[] initialValue() { return new int[1]; }
    };

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET.equals(lpparam.packageName) || !TARGET.equals(lpparam.processName)) return;
        Streamer.start();
        XposedBridge.hookAllMethods(AudioTrack.class, "write", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                int[] d = DEPTH.get();
                if (d[0]++ == 0) {
                    try { Streamer.capture((AudioTrack) param.thisObject, param.args); }
                    catch (Throwable ignored) { }
                }
            }
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                DEPTH.get()[0]--;
            }
        });
        XposedBridge.log("ClassAudio: hooked AudioTrack.write in " + TARGET);
    }
}
