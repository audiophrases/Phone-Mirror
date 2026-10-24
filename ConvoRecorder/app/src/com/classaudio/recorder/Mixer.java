package com.classaudio.recorder;

import android.os.SystemClock;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Lays both sources on one 48 kHz timeline and streams it to a stereo WAV: left = mic (Speaker 1),
 * right = ChatGPT (Speaker 2). Every source track keeps a sample cursor that follows its frame
 * timestamps, so pauses become silence and the two sides stay aligned.
 */
final class Mixer {
    static final int RATE = 48000, MIC = 0, CHATGPT = 1;
    private static final int WINDOW = RATE * 8;  // in-memory span for overlapping and late writes
    private static final int SLACK = RATE;       // keep this much unflushed behind the newest sample
    private static final int RESYNC = RATE / 5;  // a track this far behind its timestamps jumps ahead (a pause)

    private final WavWriter wav;
    private final int[][] ring = new int[2][WINDOW];
    private final Map<Long, long[]> cursors = new HashMap<>(); // per (source, track): next sample index
    private long t0 = Long.MIN_VALUE, flushed, end;
    private final byte[] out = new byte[RATE / 10 * 4];
    // Read by the UI without locking; a stale value only means a stale meter.
    private final float[] peak = new float[2];
    private final long[] peakAt = new long[2];

    Mixer(File file) throws IOException { wav = new WavWriter(file, RATE, 2); }

    synchronized void add(int source, int trackId, long nanos, int rate, int channels, byte[] pcm) throws IOException {
        if (source != MIC && source != CHATGPT) return;
        int frames = pcm.length / (2 * channels);
        if (frames == 0) return;
        short[] mono = new short[frames];
        int max = 0;
        for (int i = 0; i < frames; i++) {
            int sum = 0;
            for (int c = 0; c < channels; c++) {
                int p = (i * channels + c) * 2;
                sum += (short) ((pcm[p] & 0xff) | (pcm[p + 1] << 8));
            }
            mono[i] = (short) (sum / channels);
            max = Math.max(max, Math.abs(mono[i]));
        }
        peak[source] = max / 32768f;
        peakAt[source] = SystemClock.uptimeMillis();

        // Mic timestamps mark the end of the buffer, playback timestamps its start (see the hook's Pcm).
        long start = source == MIC ? nanos - frames * 1_000_000_000L / rate : nanos;
        if (t0 == Long.MIN_VALUE) t0 = start;
        long expected = (start - t0) * RATE / 1_000_000_000L;
        long key = ((long) source << 32) | (trackId & 0xffffffffL);
        long[] cursor = cursors.get(key);
        if (cursor == null) cursors.put(key, cursor = new long[] { -1 });
        // Only a lagging track is resynced; one running ahead is buffered playback that will be heard later.
        if (cursor[0] < 0 || expected - cursor[0] > RESYNC) cursor[0] = Math.max(expected, flushed);

        short[] s = rate == RATE ? mono : resample(mono, rate);
        long pos = cursor[0];
        cursor[0] += s.length;
        flushTo(pos + s.length - WINDOW);
        int[] ch = ring[source];
        for (int i = 0; i < s.length; i++) {
            long p = pos + i;
            if (p >= flushed) ch[(int) (p % WINDOW)] += s[i];
        }
        end = Math.max(end, pos + s.length);
        if (end - SLACK - flushed >= RATE / 10) flushTo(end - SLACK);
    }

    /** Recent peak of a channel, 0..1, or 0 when it has been quiet. */
    float level(int source) {
        return SystemClock.uptimeMillis() - peakAt[source] < 300 ? peak[source] : 0;
    }

    private void flushTo(long upTo) throws IOException {
        while (flushed < upTo) {
            int n = (int) Math.min(upTo - flushed, out.length / 4);
            for (int i = 0; i < n; i++) {
                int idx = (int) ((flushed + i) % WINDOW);
                int l = clamp(ring[MIC][idx]), r = clamp(ring[CHATGPT][idx]);
                ring[MIC][idx] = ring[CHATGPT][idx] = 0;
                out[4 * i] = (byte) l;
                out[4 * i + 1] = (byte) (l >> 8);
                out[4 * i + 2] = (byte) r;
                out[4 * i + 3] = (byte) (r >> 8);
            }
            wav.write(out, 0, n * 4);
            flushed += n;
        }
    }

    private static int clamp(int v) { return Math.max(-32768, Math.min(32767, v)); }

    private static short[] resample(short[] in, int rate) {
        int n = (int) ((long) in.length * RATE / rate);
        short[] res = new short[n];
        for (int i = 0; i < n; i++) {
            double pos = (double) i * rate / RATE;
            int i0 = (int) pos, i1 = Math.min(i0 + 1, in.length - 1);
            double f = pos - i0;
            res[i] = (short) (in[i0] * (1 - f) + in[i1] * f);
        }
        return res;
    }

    synchronized void close() throws IOException {
        flushTo(end);
        wav.close();
    }
}
