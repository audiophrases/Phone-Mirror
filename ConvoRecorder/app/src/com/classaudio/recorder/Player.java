package com.classaudio.recorder;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.SystemClock;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Plays a recording through AudioTrack so a speaker can be soloed: the file keeps you on the left channel
 * and ChatGPT on the right, and playback puts the chosen voice(s) in both ears. Supports pause, seek and
 * speed. Control methods are called from the UI thread; audio is fed from a worker thread.
 */
final class Player {
    static final int BOTH = 0, ME = 1, CHATGPT = 2;
    private static final int CHUNK = Mixer.RATE / 50; // 20 ms of frames per write

    private final RandomAccessFile file;
    private final long frames;
    private final AudioTrack track;
    private final Thread thread;
    private final Object lock = new Object();
    // Guarded by lock
    private boolean playing, released;
    private long seekTo = -1;
    // Position bookkeeping: frame `base` was at playback head `headBase`
    private volatile long base, headBase;
    private volatile int mode = BOTH;

    Player(File wav) throws IOException {
        file = new RandomAccessFile(wav, "r");
        frames = Math.max(0, file.length() - WavWriter.HEADER) / 4;
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(Mixer.RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(Mixer.RATE / 5 * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        thread = new Thread(this::feed, "player");
        thread.start();
    }

    long durationMs() { return frames * 1000 / Mixer.RATE; }

    long positionMs() {
        synchronized (lock) {
            if (seekTo >= 0) return seekTo * 1000 / Mixer.RATE;
        }
        long played = (track.getPlaybackHeadPosition() & 0xffffffffL) - headBase;
        return Math.min(frames, base + Math.max(0, played)) * 1000 / Mixer.RATE;
    }

    boolean isPlaying() { synchronized (lock) { return playing; } }

    void play() {
        synchronized (lock) {
            if (released || playing) return;
            if (positionMs() >= durationMs()) seekTo = 0; // replay from the start once finished
            playing = true;
            track.play();
            lock.notifyAll();
        }
    }

    void pause() {
        synchronized (lock) {
            playing = false;
            track.pause();
        }
    }

    void seekMs(long ms) {
        synchronized (lock) {
            seekTo = Math.max(0, Math.min(frames, ms * Mixer.RATE / 1000));
            lock.notifyAll();
        }
    }

    void setMode(int mode) { this.mode = mode; }

    void setSpeed(float speed) {
        try {
            track.setPlaybackParams(track.getPlaybackParams().setSpeed(speed));
        } catch (RuntimeException ignored) { // the output path cannot change speed: keep 1x
        }
    }

    void release() {
        synchronized (lock) {
            released = true;
            playing = false;
            lock.notifyAll();
        }
        try { thread.join(1000); } catch (InterruptedException ignored) { }
        track.release();
        try { file.close(); } catch (IOException ignored) { }
    }

    private void feed() {
        byte[] in = new byte[CHUNK * 4], out = new byte[CHUNK * 4];
        long next = 0; // next frame to read from the file
        try {
            while (true) {
                synchronized (lock) {
                    while (!released && !playing && seekTo < 0) lock.wait();
                    if (released) return;
                    if (seekTo >= 0) {
                        // Drop what is queued in the track and continue from the new spot.
                        track.pause();
                        track.flush();
                        next = seekTo;
                        seekTo = -1;
                        base = next;
                        headBase = track.getPlaybackHeadPosition() & 0xffffffffL;
                        if (playing) track.play();
                    }
                    if (!playing) continue;
                }
                int n = (int) Math.min(CHUNK, frames - next);
                if (n <= 0) {
                    // Let the queued audio finish, then stop at the end.
                    if (positionMs() >= durationMs() - 50) synchronized (lock) { if (seekTo < 0) { playing = false; track.pause(); } }
                    else SystemClock.sleep(20);
                    continue;
                }
                file.seek(WavWriter.HEADER + next * 4);
                file.readFully(in, 0, n * 4);
                mix(in, out, n);
                int off = 0;
                while (off < n * 4) {
                    synchronized (lock) {
                        while (!released && !playing && seekTo < 0) lock.wait(); // paused mid-chunk: finish it on resume
                        if (released || seekTo >= 0) break;                      // handled at the top of the loop
                    }
                    int w = track.write(out, off, n * 4 - off, AudioTrack.WRITE_NON_BLOCKING);
                    if (w < 0) return;
                    off += w;
                    if (off < n * 4) SystemClock.sleep(10);
                }
                if (off == n * 4) next += n;
            }
        } catch (InterruptedException | IOException ignored) {
        }
    }

    /** Stereo file (L = me, R = ChatGPT) -> stereo output with the selected voices in both ears. */
    private void mix(byte[] in, byte[] out, int n) {
        int m = mode;
        for (int i = 0; i < n; i++) {
            int p = 4 * i;
            int l = (short) ((in[p] & 0xff) | (in[p + 1] << 8));
            int r = (short) ((in[p + 2] & 0xff) | (in[p + 3] << 8));
            int v = m == ME ? l : m == CHATGPT ? r : Math.max(-32768, Math.min(32767, l + r));
            out[p] = out[p + 2] = (byte) v;
            out[p + 1] = out[p + 3] = (byte) (v >> 8);
        }
    }
}
