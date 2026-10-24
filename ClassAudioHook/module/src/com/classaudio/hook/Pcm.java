package com.classaudio.hook;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** Copies 16-bit PCM out of AudioTrack.write / AudioRecord.read arguments and builds wire frames. */
final class Pcm {
    private Pcm() { }

    /** AudioTrack.write(data, offset|size, size, ...): the data is in args before the call. */
    static byte[] fromWrite(Object[] args) {
        if (args == null || args.length < 2) return null;
        Object data = args[0];
        if (data instanceof byte[] && args.length >= 3) {
            int off = (Integer) args[1], len = (Integer) args[2];
            return len <= 0 ? null : Arrays.copyOfRange((byte[]) data, off, off + len);
        } else if (data instanceof short[] && args.length >= 3) {
            int n = (Integer) args[2];
            return n <= 0 ? null : toBytes((short[]) data, (Integer) args[1], n);
        } else if (data instanceof ByteBuffer) {
            ByteBuffer bb = ((ByteBuffer) data).duplicate();
            int len = Math.min((Integer) args[1], bb.remaining());
            if (len <= 0) return null;
            byte[] pcm = new byte[len];
            bb.get(pcm, 0, len);
            return pcm;
        }
        return null;
    }

    /** AudioRecord.read(data, offset|size, ...): the data is in args after the call; count is its return value. */
    static byte[] fromRead(Object[] args, int count) {
        if (args == null || args.length < 2 || count <= 0) return null;
        Object data = args[0];
        if (data instanceof byte[]) {
            int off = (Integer) args[1];
            return Arrays.copyOfRange((byte[]) data, off, off + count);
        } else if (data instanceof short[]) {
            return toBytes((short[]) data, (Integer) args[1], count);
        } else if (data instanceof ByteBuffer) {
            // The native read fills the buffer from index 0 regardless of position (WebRTC relies on this too).
            ByteBuffer bb = ((ByteBuffer) data).duplicate();
            bb.clear();
            int len = Math.min(count, bb.remaining());
            byte[] pcm = new byte[len];
            bb.get(pcm, 0, len);
            return pcm;
        }
        return null;
    }

    private static byte[] toBytes(short[] src, int off, int n) {
        byte[] pcm = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            pcm[2 * i] = (byte) src[off + i];
            pcm[2 * i + 1] = (byte) (src[off + i] >> 8);
        }
        return pcm;
    }

    /** Port 47800 frame: int trackId, int sampleRate, byte channels, int length, 16-bit LE PCM. */
    static byte[] mirrorFrame(int trackId, int rate, int channels, byte[] pcm) {
        ByteBuffer f = ByteBuffer.allocate(13 + pcm.length);
        f.putInt(trackId).putInt(rate).put((byte) channels).putInt(pcm.length).put(pcm);
        return f.array();
    }

    /**
     * Port 47810 frame: byte source (0 = mic, 1 = ChatGPT), int trackId, long elapsedRealtimeNanos,
     * int sampleRate, byte channels, int length, 16-bit LE PCM. Mic timestamps mark the end of the
     * buffer (taken after read returns), playback timestamps its start (taken before write).
     */
    static byte[] recorderFrame(byte source, int trackId, long nanos, int rate, int channels, byte[] pcm) {
        ByteBuffer f = ByteBuffer.allocate(22 + pcm.length);
        f.put(source).putInt(trackId).putLong(nanos).putInt(rate).put((byte) channels).putInt(pcm.length).put(pcm);
        return f.array();
    }

    /** The PC is playing this audio, so blank the buffer the phone is about to play. */
    static void zeroWrite(Object[] args, int bytes) {
        try {
            Object data = args[0];
            if (data instanceof byte[]) {
                int off = (Integer) args[1];
                Arrays.fill((byte[]) data, off, off + bytes, (byte) 0);
            } else if (data instanceof short[]) {
                int off = (Integer) args[1];
                Arrays.fill((short[]) data, off, off + bytes / 2, (short) 0);
            } else if (data instanceof ByteBuffer) {
                ((ByteBuffer) data).duplicate().put(new byte[bytes]);
            }
        } catch (Throwable ignored) { // read-only buffer: phone keeps playing, which is harmless
        }
    }
}
