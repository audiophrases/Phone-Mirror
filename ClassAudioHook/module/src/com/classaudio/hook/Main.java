package com.classaudio.hook;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.os.SystemClock;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Taps the ChatGPT app's audio. Port 47800: ChatGPT's voice for the PC receiver (adb forward), muted on
 * the phone while the PC listens. Port 47810: both sides of the conversation (mic + ChatGPT) for the
 * on-phone ConvoRecorder app; it never mutes anything.
 */
public class Main implements IXposedHookLoadPackage {
    private static final String TARGET = "com.openai.chatgpt";
    static final byte SRC_MIC = 0, SRC_CHATGPT = 1;
    static final Streamer MIRROR = new Streamer(47800, 400);
    static final Streamer RECORDER = new Streamer(47810, 3000);

    /** write/read overloads call each other; only the outermost call carries new audio. */
    private abstract static class OuterCallHook extends XC_MethodHook {
        private final ThreadLocal<int[]> depth = new ThreadLocal<int[]>() {
            @Override protected int[] initialValue() { return new int[1]; }
        };
        void before(MethodHookParam param) { }
        void after(MethodHookParam param) { }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (depth.get()[0]++ == 0) {
                try { before(param); } catch (Throwable ignored) { }
            }
        }
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (--depth.get()[0] == 0) {
                try { after(param); } catch (Throwable ignored) { }
            }
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET.equals(lpparam.packageName) || !TARGET.equals(lpparam.processName)) return;
        MIRROR.start();
        RECORDER.start();
        XposedBridge.hookAllMethods(AudioTrack.class, "write", new OuterCallHook() {
            @Override void before(MethodHookParam param) { onPlayback((AudioTrack) param.thisObject, param.args); }
        });
        XposedBridge.hookAllMethods(AudioRecord.class, "read", new OuterCallHook() {
            @Override void after(MethodHookParam param) {
                onRecord((AudioRecord) param.thisObject, param.args, param.getResult());
            }
        });
        XposedBridge.log("ClassAudio: hooked AudioTrack.write and AudioRecord.read in " + TARGET);
    }

    private static void onPlayback(AudioTrack track, Object[] args) {
        boolean mirror = MIRROR.connected(), rec = RECORDER.connected();
        if (!mirror && !rec) return;
        if (track.getAudioFormat() != AudioFormat.ENCODING_PCM_16BIT) return;
        long now = SystemClock.elapsedRealtimeNanos();
        byte[] pcm = Pcm.fromWrite(args);
        if (pcm == null) return;
        int id = System.identityHashCode(track), rate = track.getSampleRate(), ch = track.getChannelCount();
        if (rec) RECORDER.offer(Pcm.recorderFrame(SRC_CHATGPT, id, now, rate, ch, pcm));
        if (mirror) {
            MIRROR.offer(Pcm.mirrorFrame(id, rate, ch, pcm));
            Pcm.zeroWrite(args, pcm.length);
        }
    }

    private static void onRecord(AudioRecord record, Object[] args, Object result) {
        if (!RECORDER.connected() || !(result instanceof Integer)) return;
        if (record.getAudioFormat() != AudioFormat.ENCODING_PCM_16BIT) return;
        byte[] pcm = Pcm.fromRead(args, (Integer) result);
        if (pcm == null) return;
        RECORDER.offer(Pcm.recorderFrame(SRC_MIC, System.identityHashCode(record), SystemClock.elapsedRealtimeNanos(),
                record.getSampleRate(), record.getChannelCount(), pcm));
    }
}
