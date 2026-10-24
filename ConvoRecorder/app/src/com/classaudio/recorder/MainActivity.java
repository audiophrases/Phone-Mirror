package com.classaudio.recorder;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Start/stop recording, live levels for both speakers, transcription settings and the list of recordings. */
public class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private TextView status, levels, jobStatus, modelStatus;
    private Button recordButton, downloadButton;
    private EditText apiKey, baseUrl, model, language;
    private CheckBox useApi, auto;
    private LinearLayout list;
    private AlertDialog playerDialog;
    private int shownVersion = -1;
    private final Runnable tick = new Runnable() {
        @Override public void run() { refresh(); handler.postDelayed(this, 250); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        setContentView(buildUi());
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        shownVersion = -1;
        handler.post(tick);
    }

    @Override
    protected void onDestroy() {
        if (playerDialog != null) playerDialog.dismiss(); // releases the player
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
        saveSettings();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        status = text(22);
        levels = text(14);
        levels.setTypeface(Typeface.MONOSPACE);
        recordButton = new Button(this);
        recordButton.setOnClickListener(v -> toggleRecording());
        jobStatus = text(14);
        root.addView(status);
        root.addView(levels);
        root.addView(recordButton);
        root.addView(jobStatus);
        TextView help = text(13);
        help.setText("Needs the ClassAudioHook module enabled for ChatGPT in Vector. Start recording, then talk in "
                + "ChatGPT voice mode. Your microphone is Speaker 1 (left channel), ChatGPT is Speaker 2 (right channel).");
        root.addView(help);

        root.addView(heading("Transcription"));
        modelStatus = text(14);
        root.addView(modelStatus);
        downloadButton = new Button(this);
        downloadButton.setText(String.format(Locale.US, "Download speech model (%d MB, use Wi-Fi)", LocalAsr.MODEL_BYTES / 1_000_000));
        downloadButton.setOnClickListener(v -> startForegroundService(new Intent(this, RecorderService.class)
                .setAction(RecorderService.ACTION_DOWNLOAD_MODEL)));
        root.addView(downloadButton);
        useApi = new CheckBox(this);
        useApi.setText("Use an online API instead (OpenAI-compatible)");
        useApi.setChecked(prefs.getBoolean("use_api", false));
        root.addView(useApi);
        LinearLayout api = new LinearLayout(this);
        api.setOrientation(LinearLayout.VERTICAL);
        apiKey = edit(api, "API key", "api_key", "");
        apiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        baseUrl = edit(api, "API base URL", "base_url", "https://api.openai.com/v1");
        model = edit(api, "Model (must return timed segments, e.g. whisper-1)", "model", "whisper-1");
        language = edit(api, "Language code, blank = detect", "language", "en");
        root.addView(api);
        api.setVisibility(useApi.isChecked() ? View.VISIBLE : View.GONE);
        useApi.setOnCheckedChangeListener((b, checked) -> api.setVisibility(checked ? View.VISIBLE : View.GONE));
        auto = new CheckBox(this);
        auto.setText("Transcribe automatically when recording stops");
        auto.setChecked(prefs.getBoolean("auto", true));
        root.addView(auto);

        root.addView(heading("Recordings"));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                v.setPadding(i.left, i.top, i.right, i.bottom);
            } else {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        return scroll;
    }

    private void refresh() {
        boolean rec = RecorderService.recording;
        status.setTextColor(rec ? 0xFFFF5252 : levels.getCurrentTextColor());
        if (rec) {
            long s = (SystemClock.elapsedRealtime() - RecorderService.recordingStartedMs) / 1000;
            status.setText(String.format(Locale.US, "● Recording %d:%02d:%02d", s / 3600, s / 60 % 60, s % 60));
            Mixer m = RecorderService.mixer;
            levels.setText(RecorderService.tapConnected && m != null
                    ? "Me      " + meter(m.level(Mixer.MIC)) + "\nChatGPT " + meter(m.level(Mixer.CHATGPT))
                    : "Waiting for ChatGPT… open the app (force-stop and reopen it once after enabling the module)");
        } else {
            status.setText("Not recording");
            levels.setText("");
        }
        recordButton.setText(rec ? "Stop recording" : "Start recording");
        jobStatus.setText(RecorderService.jobStatus);
        if (shownVersion != RecorderService.filesVersion) {
            shownVersion = RecorderService.filesVersion;
            boolean ready = LocalAsr.isReady(this);
            modelStatus.setText(ready
                    ? "On-device: NVIDIA Parakeet TDT 0.6B v2 (English) is installed. Nothing leaves the phone."
                    : "On-device transcription needs a one-time speech model download. Without it, recordings are only transcribed if you choose the online API below.");
            downloadButton.setVisibility(ready ? View.GONE : View.VISIBLE);
            rebuildList();
        }
    }

    /** 20-cell meter over -60..0 dBFS. */
    private static String meter(float peak) {
        int cells = peak <= 0 ? 0 : (int) Math.max(0, Math.min(20, (20 * Math.log10(peak) + 60) / 3));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) sb.append(i < cells ? '█' : '░');
        return sb.toString();
    }

    private void toggleRecording() {
        saveSettings();
        startForegroundService(new Intent(this, RecorderService.class)
                .setAction(RecorderService.recording ? RecorderService.ACTION_STOP : RecorderService.ACTION_START));
    }

    private void rebuildList() {
        list.removeAllViews();
        File[] files = RecorderService.dir(this).listFiles((d, n) -> n.endsWith(".wav"));
        if (files == null || files.length == 0) {
            TextView none = text(14);
            none.setText("No recordings yet.");
            list.addView(none);
            return;
        }
        Arrays.sort(files, (a, b) -> b.getName().compareTo(a.getName()));
        for (File wav : files) {
            File txt = Transcriber.transcriptFile(wav);
            boolean live = wav.equals(RecorderService.currentFile);
            long secs = Math.max(0, wav.length() - WavWriter.HEADER) / (Mixer.RATE * 4);
            TextView title = text(15);
            title.setPadding(0, dp(12), 0, 0);
            title.setText(String.format(Locale.US, "%s\n%d:%02d · %.1f MB%s", wav.getName().replace(".wav", ""),
                    secs / 60, secs % 60, wav.length() / 1e6, live ? " · recording…" : txt.exists() ? " · transcribed" : ""));
            list.addView(title);
            if (live) continue;
            LinearLayout row = new LinearLayout(this);
            row.addView(button("Play", v -> showPlayer(wav, txt)));
            row.addView(button(txt.exists() ? "Redo" : "Transcribe", v -> transcribe(wav)));
            if (txt.exists()) row.addView(button("Read", v -> showTranscript(txt)));
            row.addView(button("Share", v -> share(wav, txt)));
            row.addView(button("Delete", v -> confirmDelete(wav, txt)));
            list.addView(row);
        }
    }

    private void transcribe(File wav) {
        saveSettings();
        if (useApi.isChecked() && apiKey.getText().toString().trim().isEmpty()) {
            Toast.makeText(this, "Enter an API key under Transcription first", Toast.LENGTH_LONG).show();
            return;
        }
        startForegroundService(new Intent(this, RecorderService.class)
                .setAction(RecorderService.ACTION_TRANSCRIBE).putExtra(RecorderService.EXTRA_FILE, wav.getPath()));
    }

    private void showTranscript(File txt) {
        String content;
        try {
            content = new String(Files.readAllBytes(txt.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        TextView tv = text(15);
        tv.setText(content);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(20), dp(8), dp(20), dp(8));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(tv);
        new AlertDialog.Builder(this).setTitle(txt.getName()).setView(scroll)
                .setNeutralButton("Copy", (d, w) -> getSystemService(ClipboardManager.class)
                        .setPrimaryClip(ClipData.newPlainText("transcript", content)))
                .setPositiveButton("Close", null)
                .show();
    }

    private static final Pattern TURN = Pattern.compile("^\\[(\\d+):(\\d\\d):(\\d\\d)\\] Speaker (\\d): (.*)$");
    private static final float[] SPEEDS = { 1f, 1.25f, 1.5f, 2f, 0.75f };

    /** Player with speaker solo, seek, speed, and the transcript: tap a line to jump there; the current line is highlighted. */
    private void showPlayer(File wav, File txt) {
        Player player;
        try {
            player = new Player(wav);
        } catch (IOException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        long duration = player.durationMs();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);

        TextView time = text(14);
        time.setTypeface(Typeface.MONOSPACE);
        box.addView(time);
        SeekBar seek = new SeekBar(this);
        seek.setMax((int) duration);
        boolean[] dragging = { false };
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) time.setText(clock(p) + " / " + clock(duration));
            }
            @Override public void onStartTrackingTouch(SeekBar s) { dragging[0] = true; }
            @Override public void onStopTrackingTouch(SeekBar s) { dragging[0] = false; player.seekMs(s.getProgress()); }
        });
        box.addView(seek);

        LinearLayout controls = new LinearLayout(this);
        controls.addView(button("−10 s", v -> player.seekMs(player.positionMs() - 10_000)));
        Button playPause = button("Pause", v -> { if (player.isPlaying()) player.pause(); else player.play(); });
        controls.addView(playPause);
        controls.addView(button("+10 s", v -> player.seekMs(player.positionMs() + 10_000)));
        int[] speed = { 0 };
        controls.addView(button("1×", v -> {
            speed[0] = (speed[0] + 1) % SPEEDS.length;
            player.setSpeed(SPEEDS[speed[0]]);
            ((Button) v).setText(String.format(Locale.US, "%s×", SPEEDS[speed[0]]).replace(".0×", "×"));
        }));
        box.addView(controls);

        RadioGroup voices = new RadioGroup(this);
        voices.setOrientation(LinearLayout.HORIZONTAL);
        String[] labels = { "Both", "Only me", "Only ChatGPT" };
        for (int i = 0; i < labels.length; i++) {
            RadioButton r = new RadioButton(this);
            r.setId(View.generateViewId());
            r.setText(labels[i]);
            r.setTag(i);
            voices.addView(r);
            if (i == Player.BOTH) voices.check(r.getId());
        }
        voices.setOnCheckedChangeListener((g, id) -> player.setMode((Integer) g.findViewById(id).getTag()));
        box.addView(voices);

        LinearLayout lines = new LinearLayout(this);
        lines.setOrientation(LinearLayout.VERTICAL);
        List<Long> starts = new ArrayList<>();
        List<TextView> turns = new ArrayList<>();
        try {
            for (String line : txt.exists() ? Files.readAllLines(txt.toPath(), StandardCharsets.UTF_8) : new ArrayList<String>()) {
                Matcher m = TURN.matcher(line);
                if (!m.matches()) continue;
                long ms = (Long.parseLong(m.group(1)) * 3600 + Long.parseLong(m.group(2)) * 60 + Long.parseLong(m.group(3))) * 1000;
                boolean me = m.group(4).equals("1");
                SpannableStringBuilder sb = new SpannableStringBuilder();
                sb.append(clock(ms)).append("  ").append(me ? "Me" : "ChatGPT");
                sb.setSpan(new StyleSpan(Typeface.BOLD), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (!me) sb.setSpan(new ForegroundColorSpan(0xFFFFB547), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.append("\n").append(m.group(5));
                TextView t = text(15);
                t.setText(sb);
                t.setPadding(dp(8), dp(8), dp(8), dp(8));
                t.setOnClickListener(v -> { player.seekMs(Math.max(0, ms - 300)); player.play(); });
                lines.addView(t);
                starts.add(ms);
                turns.add(t);
            }
        } catch (IOException ignored) { }
        if (turns.isEmpty()) {
            TextView none = text(14);
            none.setText(txt.exists() ? "No speech in the transcript." : "Not transcribed yet. The transcript will show here, and you can tap a line to jump to it.");
            lines.addView(none);
        }
        ScrollView transcript = new ScrollView(this);
        transcript.addView(lines);
        transcript.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                getResources().getDisplayMetrics().heightPixels * 2 / 5));
        box.addView(transcript);

        int[] current = { -1 };
        Runnable ticker = new Runnable() {
            @Override public void run() {
                long pos = player.positionMs();
                if (!dragging[0]) {
                    seek.setProgress((int) pos);
                    time.setText(clock(pos) + " / " + clock(duration));
                }
                playPause.setText(player.isPlaying() ? "Pause" : "Play");
                int idx = -1;
                for (int i = 0; i < starts.size() && starts.get(i) <= pos + 300; i++) idx = i;
                if (idx != current[0]) {
                    if (current[0] >= 0) turns.get(current[0]).setBackgroundColor(0);
                    if (idx >= 0) {
                        TextView t = turns.get(idx);
                        t.setBackgroundColor(0x3348A0FF);
                        if (player.isPlaying()) transcript.smoothScrollTo(0, Math.max(0, t.getTop() - dp(24)));
                    }
                    current[0] = idx;
                }
                handler.postDelayed(this, 200);
            }
        };
        playerDialog = new AlertDialog.Builder(this).setTitle(wav.getName().replace(".wav", ""))
                .setView(box).setPositiveButton("Close", null).create();
        playerDialog.setOnDismissListener(d -> {
            handler.removeCallbacks(ticker);
            player.release();
            playerDialog = null;
        });
        playerDialog.show();
        player.play();
        handler.post(ticker);
    }

    private static String clock(long ms) {
        long s = ms / 1000;
        return s >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private void share(File wav, File txt) {
        ArrayList<Uri> uris = new ArrayList<>();
        if (txt.exists()) uris.add(ShareProvider.uriFor(txt));
        uris.add(ShareProvider.uriFor(wav));
        Intent send = new Intent(Intent.ACTION_SEND_MULTIPLE)
                .setType(txt.exists() ? "*/*" : "audio/wav")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, "Share conversation"));
    }

    private void confirmDelete(File wav, File txt) {
        new AlertDialog.Builder(this).setMessage("Delete " + wav.getName() + (txt.exists() ? " and its transcript?" : "?"))
                .setPositiveButton("Delete", (d, w) -> {
                    wav.delete();
                    txt.delete();
                    RecorderService.filesVersion++;
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void saveSettings() {
        prefs.edit()
                .putBoolean("use_api", useApi.isChecked())
                .putString("api_key", apiKey.getText().toString().trim())
                .putString("base_url", baseUrl.getText().toString().trim())
                .putString("model", model.getText().toString().trim())
                .putString("language", language.getText().toString().trim())
                .putBoolean("auto", auto.isChecked())
                .apply();
    }

    private TextView text(int sp) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private TextView heading(String s) {
        TextView t = text(18);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(24), 0, dp(4));
        t.setText(s);
        return t;
    }

    private EditText edit(LinearLayout parent, String hint, String key, String def) {
        TextView label = text(13);
        label.setText(hint);
        parent.addView(label);
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setText(prefs.getString(key, def));
        parent.addView(e);
        return e;
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        return b;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
