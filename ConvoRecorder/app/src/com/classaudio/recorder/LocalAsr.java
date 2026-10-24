package com.classaudio.recorder;

import android.content.Context;
import android.net.ConnectivityManager;
import android.os.SystemClock;

import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig;
import com.k2fsa.sherpa.onnx.SileroVadModelConfig;
import com.k2fsa.sherpa.onnx.SpeechSegment;
import com.k2fsa.sherpa.onnx.Vad;
import com.k2fsa.sherpa.onnx.VadModelConfig;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * On-device English transcription; no audio leaves the phone. Silero VAD cuts one speaker's channel into
 * utterances and NVIDIA Parakeet TDT 0.6B v2 (int8, run by sherpa-onnx) transcribes each of them,
 * punctuation and capitalization included. The model (about 660 MB) is downloaded once.
 */
final class LocalAsr implements AutoCloseable {
    private static final String PARAKEET =
            "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/resolve/1ab9323565ddb038682214b292f588070a538ce2/";

    private static final class ModelFile {
        final String name, url, sha256;
        final long size;
        ModelFile(String name, String url, long size, String sha256) {
            this.name = name; this.url = url; this.size = size; this.sha256 = sha256;
        }
    }

    private static final ModelFile[] FILES = {
            new ModelFile("encoder.int8.onnx", PARAKEET + "encoder.int8.onnx", 652184296L,
                    "a32b12d17bbbc309d0686fbbcc2987b5e9b8333a7da83fa6b089f0a2acd651ab"),
            new ModelFile("decoder.int8.onnx", PARAKEET + "decoder.int8.onnx", 7257753L,
                    "b6bb64963457237b900e496ee9994b59294526439fbcc1fecf705b31a15c6b4e"),
            new ModelFile("joiner.int8.onnx", PARAKEET + "joiner.int8.onnx", 1739080L,
                    "7946164367946e7f9f29a122407c3252b680dbae9a51343eb2488d057c3c43d2"),
            new ModelFile("tokens.txt", PARAKEET + "tokens.txt", 9384L,
                    "ec182b70dd42113aff6c5372c75cac58c952443eb22322f57bbd7f53977d497d"),
            new ModelFile("silero_vad.onnx", "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
                    643854L, "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"),
    };
    static final long MODEL_BYTES;
    static {
        long sum = 0;
        for (ModelFile f : FILES) sum += f.size;
        MODEL_BYTES = sum;
    }

    static File dir(Context c) {
        File d = new File(c.getExternalFilesDir(null), "models/parakeet-tdt-0.6b-v2-int8");
        d.mkdirs();
        return d;
    }

    static boolean isReady(Context c) {
        File dir = dir(c);
        for (ModelFile f : FILES) {
            File file = new File(dir, f.name);
            if (!file.isFile() || file.length() != f.size) return false;
        }
        return true;
    }

    static boolean onMeteredNetwork(Context c) {
        return c.getSystemService(ConnectivityManager.class).isActiveNetworkMetered();
    }

    /** Downloads the missing model files, resuming partial ones, and checks each against its SHA-256. */
    static void download(Context c, Transcriber.Progress p) throws Exception {
        File dir = dir(c);
        long done = 0;
        for (ModelFile f : FILES) {
            File dest = new File(dir, f.name);
            if (dest.isFile() && dest.length() == f.size) { done += f.size; continue; }
            File part = new File(dir, f.name + ".part");
            for (int attempt = 1; ; attempt++) {
                try {
                    fetch(f, part, done, p);
                    break;
                } catch (IOException e) {
                    if (attempt >= 5) throw e;
                    p.update("connection lost, retrying");
                    Thread.sleep(3000L * attempt);
                }
            }
            p.update("checking " + f.name);
            if (!sha256(part).equals(f.sha256)) {
                part.delete();
                throw new IOException(f.name + " arrived corrupted; try again");
            }
            if (!part.renameTo(dest)) throw new IOException("could not save " + dest);
            done += f.size;
        }
    }

    private static void fetch(ModelFile f, File part, long before, Transcriber.Progress p) throws IOException {
        long have = part.isFile() ? part.length() : 0;
        if (have > f.size) { part.delete(); have = 0; }
        if (have == f.size) return;
        HttpURLConnection c = (HttpURLConnection) new URL(f.url).openConnection();
        c.setConnectTimeout(30_000);
        c.setReadTimeout(60_000);
        if (have > 0) c.setRequestProperty("Range", "bytes=" + have + "-");
        try {
            int code = c.getResponseCode();
            if (code == 200) have = 0; // the server ignored the range: start over
            else if (code != 206) throw new IOException("HTTP " + code + " downloading " + f.name);
            try (InputStream in = c.getInputStream(); FileOutputStream o = new FileOutputStream(part, have > 0)) {
                byte[] b = new byte[1 << 16];
                long shown = 0;
                for (int n; (n = in.read(b)) > 0; ) {
                    o.write(b, 0, n);
                    have += n;
                    long now = SystemClock.uptimeMillis();
                    if (now - shown > 500) {
                        shown = now;
                        p.update(String.format(Locale.US, "downloading speech model, %d / %d MB",
                                (before + have) / 1_000_000, MODEL_BYTES / 1_000_000));
                    }
                }
            }
        } finally {
            c.disconnect();
        }
        if (have != f.size) throw new IOException("download of " + f.name + " was cut short");
    }

    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[1 << 16];
            for (int n; (n = in.read(b)) > 0; ) md.update(b, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte x : md.digest()) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private final File dir;
    private final OfflineRecognizer recognizer;

    LocalAsr(Context c) {
        dir = dir(c);
        OfflineTransducerModelConfig t = new OfflineTransducerModelConfig();
        t.setEncoder(path("encoder.int8.onnx"));
        t.setDecoder(path("decoder.int8.onnx"));
        t.setJoiner(path("joiner.int8.onnx"));
        OfflineModelConfig m = new OfflineModelConfig();
        m.setTransducer(t);
        m.setTokens(path("tokens.txt"));
        m.setModelType("nemo_transducer");
        m.setNumThreads(4);
        OfflineRecognizerConfig rc = new OfflineRecognizerConfig();
        rc.setModelConfig(m);
        rc.setDecodingMethod("greedy_search");
        recognizer = new OfflineRecognizer(null, rc);
    }

    private String path(String name) { return new File(dir, name).getAbsolutePath(); }

    /** One speaker's channel (raw 16 kHz mono 16-bit LE) -> timed utterances. */
    List<Transcriber.Segment> transcribe(File raw, int speaker, Transcriber.Progress p) throws IOException {
        SileroVadModelConfig sc = new SileroVadModelConfig();
        sc.setModel(path("silero_vad.onnx"));
        sc.setThreshold(0.5f);
        sc.setMinSilenceDuration(0.5f); // a shorter pause stays inside the utterance
        sc.setMinSpeechDuration(0.25f);
        sc.setMaxSpeechDuration(30f);
        sc.setWindowSize(512);
        VadModelConfig vc = new VadModelConfig();
        vc.setSileroVadModelConfig(sc);
        vc.setSampleRate(Transcriber.RATE);
        vc.setNumThreads(1);
        Vad vad = new Vad(null, vc);

        List<Transcriber.Segment> segs = new ArrayList<>();
        String who = speaker == 1 ? "your microphone" : "ChatGPT";
        long total = Math.max(1, raw.length() / 2), read = 0;
        try (InputStream in = new BufferedInputStream(new FileInputStream(raw), 1 << 16)) {
            byte[] b = new byte[Transcriber.RATE * 2 * 10]; // 10 s at a time
            while (true) {
                int n = 0;
                for (int r; n < b.length && (r = in.read(b, n, b.length - n)) > 0; ) n += r;
                if (n < 2) break;
                float[] f = new float[n / 2];
                for (int i = 0; i < f.length; i++) f[i] = (short) ((b[2 * i] & 0xff) | (b[2 * i + 1] << 8)) / 32768f;
                vad.acceptWaveform(f);
                drain(vad, speaker, segs);
                read += f.length;
                p.update(String.format(Locale.US, "transcribing %s, %d%%", who, read * 100 / total));
            }
            vad.flush();
            drain(vad, speaker, segs);
        } finally {
            vad.release();
        }
        return segs;
    }

    private void drain(Vad vad, int speaker, List<Transcriber.Segment> segs) {
        while (!vad.empty()) {
            SpeechSegment s = vad.front();
            float[] samples = s.getSamples();
            String text = recognize(samples);
            double start = s.getStart() / (double) Transcriber.RATE;
            if (!text.isEmpty()) segs.add(new Transcriber.Segment(speaker, start, start + samples.length / (double) Transcriber.RATE, text));
            vad.pop();
        }
    }

    private String recognize(float[] samples) {
        // A little trailing silence keeps the model from dropping the last word of a tightly cut utterance.
        float[] padded = new float[samples.length + Transcriber.RATE / 4];
        System.arraycopy(samples, 0, padded, 0, samples.length);
        OfflineStream s = recognizer.createStream();
        try {
            s.acceptWaveform(padded, Transcriber.RATE);
            recognizer.decode(s);
            return recognizer.getResult(s).getText().trim();
        } finally {
            s.release();
        }
    }

    @Override
    public void close() { recognizer.release(); }
}
