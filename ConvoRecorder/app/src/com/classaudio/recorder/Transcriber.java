package com.classaudio.recorder;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Transcribes a recording, on the phone ({@link LocalAsr}, the default) or through an OpenAI-compatible
 * /audio/transcriptions endpoint. Each channel of the WAV is one speaker (left = mic = Speaker 1,
 * right = ChatGPT = Speaker 2), so speakers are known exactly instead of guessed; the timed segments of
 * both channels are merged into one transcript.
 */
final class Transcriber {
    interface Progress { void update(String message); }

    static final class Settings {
        boolean useApi;
        String baseUrl, apiKey, model, language;

        static Settings load(Context c) {
            SharedPreferences p = c.getSharedPreferences("settings", Context.MODE_PRIVATE);
            Settings s = new Settings();
            s.useApi = p.getBoolean("use_api", false);
            s.apiKey = p.getString("api_key", "").trim();
            s.baseUrl = p.getString("base_url", "https://api.openai.com/v1").trim();
            s.model = p.getString("model", "whisper-1").trim();
            s.language = p.getString("language", "").trim();
            return s;
        }
    }

    static final class Segment {
        final int speaker;
        final double start, end;
        final String text;
        Segment(int speaker, double start, double end, String text) {
            this.speaker = speaker; this.start = start; this.end = end; this.text = text;
        }
    }

    static final int RATE = 16000;                   // what speech models use; 48 kHz would only grow uploads
    private static final int MAX_CHUNK = RATE * 600; // 10 min = 19 MB WAV, under the 25 MB upload limit
    private static final int CUT_SEARCH = RATE * 30; // split long audio at the quietest point of the last 30 s
    private static final int WIN = RATE / 10;        // 100 ms analysis window

    static File transcriptFile(File wav) {
        return new File(wav.getParentFile(), wav.getName().replaceAll("\\.wav$", "") + ".txt");
    }

    static File transcribe(File wav, Context c, Settings s, Progress p) throws Exception {
        if (s.useApi && s.apiKey.isEmpty()) throw new IllegalStateException("enter an API key, or switch to on-device transcription");
        if (!s.useApi && !LocalAsr.isReady(c)) {
            if (LocalAsr.onMeteredNetwork(c)) throw new IllegalStateException("download the speech model first (on Wi-Fi)");
            LocalAsr.download(c, p);
        }
        p.update("preparing audio");
        File[] mono = { new File(c.getCacheDir(), "speaker1.raw"), new File(c.getCacheDir(), "speaker2.raw") };
        try {
            splitChannels(wav, mono);
            List<Segment> all = new ArrayList<>();
            if (s.useApi) {
                all.addAll(transcribeWithApi(mono[0], 1, s, p));
                all.addAll(transcribeWithApi(mono[1], 2, s, p));
            } else {
                p.update("loading speech model");
                try (LocalAsr asr = new LocalAsr(c)) {
                    all.addAll(asr.transcribe(mono[0], 1, p));
                    all.addAll(asr.transcribe(mono[1], 2, p));
                }
            }
            List<Segment> kept = dropEcho(all);
            Collections.sort(kept, (a, b) -> Double.compare(a.start, b.start));
            File out = transcriptFile(wav);
            try (OutputStream o = new FileOutputStream(out)) {
                o.write(format(wav, kept).getBytes(StandardCharsets.UTF_8));
            }
            return out;
        } finally {
            for (File f : mono) f.delete();
        }
    }

    /** 48 kHz stereo WAV -> one raw 16 kHz mono file per channel (3-sample average as the anti-alias filter). */
    private static void splitChannels(File wav, File[] out) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(wav), 1 << 16));
             OutputStream o1 = new BufferedOutputStream(new FileOutputStream(out[0]), 1 << 16);
             OutputStream o2 = new BufferedOutputStream(new FileOutputStream(out[1]), 1 << 16)) {
            long left = dataLength(in, wav.length());
            byte[] buf = new byte[12 * 4096], b1 = new byte[2 * 4096], b2 = new byte[2 * 4096];
            while (left >= 12) {
                int n = (int) Math.min(buf.length, left - left % 12);
                in.readFully(buf, 0, n);
                left -= n;
                int k = n / 12;
                for (int i = 0; i < k; i++) {
                    int b = 12 * i;
                    int l = (s16(buf, b) + s16(buf, b + 4) + s16(buf, b + 8)) / 3;
                    int r = (s16(buf, b + 2) + s16(buf, b + 6) + s16(buf, b + 10)) / 3;
                    b1[2 * i] = (byte) l; b1[2 * i + 1] = (byte) (l >> 8);
                    b2[2 * i] = (byte) r; b2[2 * i + 1] = (byte) (r >> 8);
                }
                o1.write(b1, 0, 2 * k);
                o2.write(b2, 0, 2 * k);
            }
        }
    }

    /** Walks the RIFF chunks to "data"; tolerates the size left at 0 by a recording that did not stop cleanly. */
    private static long dataLength(DataInputStream in, long fileLength) throws IOException {
        byte[] id = new byte[4];
        in.readFully(id);
        if (!"RIFF".equals(new String(id, StandardCharsets.US_ASCII))) throw new IOException("not a WAV file");
        in.skipBytes(4);
        in.readFully(id);
        long pos = 12;
        while (true) {
            in.readFully(id);
            long size = Integer.reverseBytes(in.readInt()) & 0xffffffffL;
            pos += 8;
            String name = new String(id, StandardCharsets.US_ASCII);
            if (name.equals("data")) return size == 0 || pos + size > fileLength ? fileLength - pos : size;
            byte[] body = new byte[(int) size];
            in.readFully(body);
            pos += size;
            if (name.equals("fmt ")) {
                ByteBuffer f = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
                int channels = f.getShort(2), rate = f.getInt(4), bits = f.getShort(14);
                if (channels != 2 || rate != Mixer.RATE || bits != 16) {
                    throw new IOException("expected a 48 kHz 16-bit stereo recording from this app");
                }
            }
        }
    }

    private static int s16(byte[] b, int i) { return (short) ((b[i] & 0xff) | (b[i + 1] << 8)); }

    private static List<Segment> transcribeWithApi(File raw, int speaker, Settings s, Progress p) throws Exception {
        List<Segment> segs = new ArrayList<>();
        String who = speaker == 1 ? "your microphone" : "ChatGPT";
        long total = raw.length() / 2;
        try (RandomAccessFile f = new RandomAccessFile(raw, "r")) {
            long start = 0;
            for (int part = 1; start < total; part++) {
                int n = (int) Math.min(MAX_CHUNK, total - start);
                short[] pcm = read(f, start, n);
                if (start + n < total) n = quietestCut(pcm, n);
                if (hasSound(pcm, n)) {
                    p.update(total > MAX_CHUNK ? "transcribing " + who + ", part " + part : "transcribing " + who);
                    JSONObject r = post(toWav(pcm, n), s);
                    double offset = start / (double) RATE;
                    JSONArray arr = r.optJSONArray("segments");
                    if (arr == null) {
                        String text = r.optString("text", "").trim();
                        if (!text.isEmpty()) segs.add(new Segment(speaker, offset, offset + n / (double) RATE, text));
                    } else {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject seg = arr.getJSONObject(i);
                            String text = seg.optString("text", "").trim();
                            // Whisper's own rule for "this was not speech", which catches most silence hallucinations.
                            if (text.isEmpty() || seg.optDouble("no_speech_prob", 0) > 0.6 && seg.optDouble("avg_logprob", 0) < -1) continue;
                            segs.add(new Segment(speaker, offset + seg.optDouble("start", 0), offset + seg.optDouble("end", 0), text));
                        }
                    }
                }
                start += n;
            }
        }
        return segs;
    }

    private static short[] read(RandomAccessFile f, long start, int n) throws IOException {
        byte[] b = new byte[n * 2];
        f.seek(start * 2);
        f.readFully(b);
        short[] s = new short[n];
        ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(s);
        return s;
    }

    private static double energy(short[] pcm, int from) {
        double sum = 0;
        for (int i = from; i < from + WIN; i++) sum += (double) pcm[i] * pcm[i];
        return sum / WIN;
    }

    private static int quietestCut(short[] pcm, int n) {
        int best = n;
        double min = Double.MAX_VALUE;
        for (int i = Math.max(0, n - CUT_SEARCH); i + WIN <= n; i += WIN / 4) {
            double e = energy(pcm, i);
            if (e < min) { min = e; best = i + WIN / 2; }
        }
        return best;
    }

    /** Skips uploads of silence: ChatGPT's channel is digital zero whenever it is not talking. */
    private static boolean hasSound(short[] pcm, int n) {
        for (int i = 0; i + WIN <= n; i += WIN) if (energy(pcm, i) > 300.0 * 300.0) return true;
        return false;
    }

    private static byte[] toWav(short[] pcm, int n) {
        ByteBuffer b = ByteBuffer.allocate(WavWriter.HEADER + n * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + n * 2)
                .put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16)
                .putShort((short) 1).putShort((short) 1).putInt(RATE).putInt(RATE * 2)
                .putShort((short) 2).putShort((short) 16)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(n * 2);
        for (int i = 0; i < n; i++) b.putShort(pcm[i]);
        return b.array();
    }

    private static JSONObject post(byte[] wav, Settings s) throws Exception {
        String boundary = "convo" + System.nanoTime();
        ByteArrayOutputStream body = new ByteArrayOutputStream(wav.length + 1024);
        field(body, boundary, "model", s.model);
        field(body, boundary, "response_format", "verbose_json");
        field(body, boundary, "timestamp_granularities[]", "segment");
        if (!s.language.isEmpty()) field(body, boundary, "language", s.language);
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n"
                + "Content-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(wav);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        for (int attempt = 1; ; attempt++) {
            int code;
            String text;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(s.baseUrl.replaceAll("/+$", "") + "/audio/transcriptions").openConnection();
                c.setConnectTimeout(30_000);
                c.setReadTimeout(600_000);
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(body.size());
                c.setRequestProperty("Authorization", "Bearer " + s.apiKey);
                c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                try (OutputStream o = c.getOutputStream()) { body.writeTo(o); }
                code = c.getResponseCode();
                text = readAll(code < 400 ? c.getInputStream() : c.getErrorStream());
                c.disconnect();
            } catch (IOException e) {
                if (attempt >= 4) throw e;
                Thread.sleep(2000L << attempt);
                continue;
            }
            if (code == 200) return new JSONObject(text);
            if ((code == 429 || code >= 500) && attempt < 4) { Thread.sleep(2000L << attempt); continue; }
            throw new IOException("HTTP " + code + ": " + errorMessage(text));
        }
    }

    private static void field(ByteArrayOutputStream body, String boundary, String name, String value) throws IOException {
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n")
                .getBytes(StandardCharsets.UTF_8));
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream is = in) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            for (int n; (n = is.read(b)) > 0; ) o.write(b, 0, n);
            return o.toString("UTF-8");
        }
    }

    private static String errorMessage(String body) {
        try { return new JSONObject(body).getJSONObject("error").getString("message"); }
        catch (Exception e) { return body.length() > 300 ? body.substring(0, 300) : body; }
    }

    /**
     * On the loudspeaker the mic also hears ChatGPT. Drops mic segments that mostly repeat words ChatGPT
     * was saying at that moment; with headphones nothing matches and nothing is dropped.
     */
    private static List<Segment> dropEcho(List<Segment> all) {
        List<Segment> kept = new ArrayList<>();
        for (Segment m : all) {
            boolean echo = false;
            Set<String> mw = words(m.text);
            if (m.speaker == 1 && mw.size() >= 2) {
                for (Segment g : all) {
                    if (g.speaker != 2 || g.start > m.end + 1 || m.start > g.end + 1) continue;
                    Set<String> gw = words(g.text);
                    int common = 0;
                    for (String w : mw) if (gw.contains(w)) common++;
                    if (common >= 0.6 * mw.size()) { echo = true; break; }
                }
            }
            if (!echo) kept.add(m);
        }
        return kept;
    }

    private static Set<String> words(String text) {
        Set<String> set = new HashSet<>();
        for (String w : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}']+")) if (!w.isEmpty()) set.add(w);
        return set;
    }

    /** Consecutive segments of one speaker within a few seconds become one turn. */
    private static String format(File wav, List<Segment> segs) {
        StringBuilder sb = new StringBuilder();
        sb.append("Conversation: ").append(wav.getName().replaceAll("\\.wav$", "")).append('\n');
        sb.append("Speaker 1 = me (microphone), Speaker 2 = ChatGPT\n");
        int speaker = 0;
        double lastEnd = 0;
        for (Segment s : segs) {
            if (s.speaker == speaker && s.start - lastEnd < 3) {
                sb.append(' ').append(s.text);
            } else {
                int t = (int) s.start;
                sb.append(String.format(Locale.US, "\n\n[%02d:%02d:%02d] Speaker %d: ", t / 3600, t / 60 % 60, t % 60, s.speaker));
                sb.append(s.text);
                speaker = s.speaker;
            }
            lastEnd = Math.max(lastEnd, s.end);
        }
        if (segs.isEmpty()) sb.append("\n\n(no speech found)");
        return sb.append('\n').toString();
    }
}
