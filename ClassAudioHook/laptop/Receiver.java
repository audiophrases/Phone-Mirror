import javax.sound.sampled.*;
import java.io.*;
import java.net.Socket;
import java.util.*;

/**
 * Receives PCM frames from the phone (adb forward tcp:47800) and plays them on the default output.
 * Frame: int trackId, int sampleRate, byte channels, int length, 16-bit LE PCM.
 * Run: java Receiver.java [port]
 */
public class Receiver {
    static final int OUT_RATE = 48000, BLOCK = 480;           // 10 ms blocks
    static final int PREBUFFER = OUT_RATE / 20;                // 50 ms jitter buffer
    static final Map<Integer, Track> tracks = new HashMap<>();

    static class Track {
        short[] buf = new short[OUT_RATE * 2];
        int head, size;
        boolean primed;
        long lastData = System.nanoTime();
        double phase; // resample position
        short last;

        synchronized void push(short[] s, int n) {
            for (int i = 0; i < n; i++) {
                if (size == buf.length) { head = (head + 1) % buf.length; size--; } // drop oldest
                buf[(head + size) % buf.length] = s[i];
                size++;
            }
            lastData = System.nanoTime();
        }

        synchronized void pull(int[] mix, int n) {
            if (!primed) { if (size < PREBUFFER) return; primed = true; }
            for (int i = 0; i < n; i++) {
                if (size == 0) { primed = false; return; }
                mix[i] += buf[head];
                head = (head + 1) % buf.length;
                size--;
            }
        }
    }

    public static void main(String[] a) throws Exception {
        int port = a.length > 0 ? Integer.parseInt(a[0]) : 47800;
        AudioFormat fmt = new AudioFormat(OUT_RATE, 16, 2, true, false);
        SourceDataLine line = AudioSystem.getSourceDataLine(fmt);
        line.open(fmt, OUT_RATE * 4 / 5); // 200 ms hardware buffer
        line.start();

        Thread mixer = new Thread(() -> {
            int[] mix = new int[BLOCK];
            byte[] out = new byte[BLOCK * 4];
            while (true) {
                Arrays.fill(mix, 0);
                synchronized (tracks) {
                    long now = System.nanoTime();
                    tracks.values().removeIf(t -> now - t.lastData > 5_000_000_000L);
                    for (Track t : tracks.values()) t.pull(mix, BLOCK);
                }
                for (int i = 0; i < BLOCK; i++) {
                    int v = Math.max(-32768, Math.min(32767, mix[i]));
                    out[4 * i] = out[4 * i + 2] = (byte) v;
                    out[4 * i + 1] = out[4 * i + 3] = (byte) (v >> 8);
                }
                line.write(out, 0, out.length);
            }
        }, "mixer");
        mixer.setDaemon(true);
        mixer.start();

        while (true) {
            try (Socket s = new Socket("127.0.0.1", port)) {
                DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 65536));
                System.out.println("Connected to phone audio stream.");
                while (true) {
                    int id = in.readInt(), rate = in.readInt(), ch = in.readByte(), len = in.readInt();
                    if (len < 0 || len > 1 << 20 || ch < 1) throw new IOException("bad frame");
                    byte[] raw = new byte[len];
                    in.readFully(raw);
                    int frames = len / (2 * ch);
                    short[] mono = new short[frames];
                    for (int i = 0; i < frames; i++) {
                        int sum = 0;
                        for (int c = 0; c < ch; c++) {
                            int p = (i * ch + c) * 2;
                            sum += (short) ((raw[p] & 0xff) | (raw[p + 1] << 8));
                        }
                        mono[i] = (short) (sum / ch);
                    }
                    short[] res = rate == OUT_RATE ? mono : resample(mono, rate);
                    Track t;
                    synchronized (tracks) { t = tracks.computeIfAbsent(id, k -> new Track()); }
                    t.push(res, res.length);
                }
            } catch (IOException e) {
                synchronized (tracks) { tracks.clear(); }
                Thread.sleep(1000);
            }
        }
    }

    static short[] resample(short[] in, int rate) {
        int n = (int) ((long) in.length * OUT_RATE / rate);
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            double pos = (double) i * rate / OUT_RATE;
            int i0 = (int) pos, i1 = Math.min(i0 + 1, in.length - 1);
            double f = pos - i0;
            out[i] = (short) (in[i0] * (1 - f) + in[i1] * f);
        }
        return out;
    }
}
