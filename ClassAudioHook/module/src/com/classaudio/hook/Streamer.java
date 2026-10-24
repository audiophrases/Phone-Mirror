package com.classaudio.hook;

import android.media.AudioFormat;
import android.media.AudioTrack;

import java.io.DataOutputStream;
import java.io.BufferedOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * Serves captured PCM on 127.0.0.1:47800. Frame: int trackId, int sampleRate, byte channels,
 * int length, bytes (16-bit little-endian PCM). Reach it from the PC with: adb forward tcp:47800 tcp:47800
 */
final class Streamer {
    static final int PORT = 47800;
    private static final ArrayBlockingQueue<byte[]> QUEUE = new ArrayBlockingQueue<byte[]>(400);
    private static volatile boolean clientConnected;
    private static boolean started;

    static synchronized void start() {
        if (started) return;
        started = true;
        Thread t = new Thread(new Runnable() { public void run() { serve(); } }, "ClassAudio-server");
        t.setDaemon(true);
        t.start();
    }

    private static void serve() {
        while (true) {
            try (ServerSocket server = new ServerSocket(PORT, 1, InetAddress.getByName("127.0.0.1"))) {
                while (true) {
                    Socket s = server.accept();
                    s.setTcpNoDelay(true);
                    QUEUE.clear();
                    clientConnected = true;
                    try {
                        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 8192));
                        while (true) {
                            byte[] frame = QUEUE.take();
                            out.write(frame);
                            if (QUEUE.isEmpty()) out.flush();
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        clientConnected = false;
                        try { s.close(); } catch (Throwable ignored) { }
                    }
                }
            } catch (Throwable t) {
                try { Thread.sleep(2000); } catch (InterruptedException ignored) { }
            }
        }
    }

    static void capture(AudioTrack track, Object[] args) {
        if (!clientConnected || args == null || args.length < 3) return;
        if (track.getAudioFormat() != AudioFormat.ENCODING_PCM_16BIT) return;
        Object data = args[0];
        byte[] pcm;
        if (data instanceof byte[]) {
            int off = (Integer) args[1], len = (Integer) args[2];
            if (len <= 0) return;
            pcm = new byte[len];
            System.arraycopy((byte[]) data, off, pcm, 0, len);
        } else if (data instanceof short[]) {
            int off = (Integer) args[1], n = (Integer) args[2];
            if (n <= 0) return;
            short[] src = (short[]) data;
            pcm = new byte[n * 2];
            for (int i = 0; i < n; i++) {
                pcm[2 * i] = (byte) src[off + i];
                pcm[2 * i + 1] = (byte) (src[off + i] >> 8);
            }
        } else if (data instanceof ByteBuffer) {
            ByteBuffer bb = ((ByteBuffer) data).duplicate();
            int len = Math.min((Integer) args[1], bb.remaining());
            if (len <= 0) return;
            pcm = new byte[len];
            bb.get(pcm, 0, len);
        } else {
            return;
        }
        ByteBuffer f = ByteBuffer.allocate(13 + pcm.length);
        f.putInt(System.identityHashCode(track));
        f.putInt(track.getSampleRate());
        f.put((byte) track.getChannelCount());
        f.putInt(pcm.length);
        f.put(pcm);
        QUEUE.offer(f.array());
        muteOnPhone(data, args, pcm.length);
    }

    /** The PC is playing this audio, so blank the buffer the phone is about to play. */
    private static void muteOnPhone(Object data, Object[] args, int bytes) {
        try {
            if (data instanceof byte[]) {
                int off = (Integer) args[1];
                java.util.Arrays.fill((byte[]) data, off, off + bytes, (byte) 0);
            } else if (data instanceof short[]) {
                int off = (Integer) args[1];
                java.util.Arrays.fill((short[]) data, off, off + bytes / 2, (short) 0);
            } else if (data instanceof ByteBuffer) {
                ByteBuffer bb = ((ByteBuffer) data).duplicate();
                bb.put(new byte[bytes]);
            }
        } catch (Throwable ignored) { // read-only buffer: phone keeps playing, which is harmless
        }
    }
}
