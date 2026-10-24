package com.classaudio.recorder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Records from the ClassAudioHook tap (127.0.0.1:47810 inside the ChatGPT process) and runs transcriptions,
 * staying in the foreground while either is in progress.
 */
public class RecorderService extends Service {
    static final String ACTION_START = "start", ACTION_STOP = "stop", ACTION_TRANSCRIBE = "transcribe", ACTION_DOWNLOAD_MODEL = "download";
    static final String EXTRA_FILE = "file";
    static final int TAP_PORT = 47810;
    private static final String CHANNEL = "convo";

    // Read by MainActivity's refresh loop.
    static volatile boolean recording, tapConnected;
    static volatile long recordingStartedMs;
    static volatile File currentFile;
    static volatile Mixer mixer;
    static volatile String jobStatus = "";
    static volatile int filesVersion;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService jobs = Executors.newSingleThreadExecutor();
    private int pendingJobs; // main thread only
    private Thread tapThread;
    private volatile Socket tapSocket;
    private PowerManager.WakeLock wakeLock, jobWakeLock;

    static File dir(Context c) {
        File d = new File(c.getExternalFilesDir(null), "conversations");
        d.mkdirs();
        return d;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground();
        String action = intent == null ? null : intent.getAction();
        if (ACTION_START.equals(action)) startRecording();
        else if (ACTION_STOP.equals(action)) stopRecording();
        else if (ACTION_TRANSCRIBE.equals(action)) enqueue(new File(intent.getStringExtra(EXTRA_FILE)));
        else if (ACTION_DOWNLOAD_MODEL.equals(action)) enqueue("Speech model", p -> {
            LocalAsr.download(this, p);
            return "Speech model ready. Transcription now runs on the phone.";
        });
        stopIfIdle();
        return START_NOT_STICKY;
    }

    private void startRecording() {
        if (recording) return;
        File file = new File(dir(this), "convo_" + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date()) + ".wav");
        Mixer m;
        try {
            m = new Mixer(file);
        } catch (IOException e) {
            jobStatus = "Could not create " + file.getName() + ": " + e.getMessage();
            return;
        }
        currentFile = file;
        mixer = m;
        recording = true;
        recordingStartedMs = SystemClock.elapsedRealtime();
        wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ConvoRecorder:record");
        wakeLock.acquire();
        tapThread = new Thread(() -> tapLoop(m), "tap");
        tapThread.start();
        filesVersion++;
        updateNotification();
    }

    private void tapLoop(Mixer m) {
        while (recording) {
            try (Socket s = new Socket()) {
                tapSocket = s;
                s.connect(new InetSocketAddress("127.0.0.1", TAP_PORT), 1000);
                tapConnected = true;
                DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 65536));
                while (recording) {
                    int source = in.readByte(), track = in.readInt();
                    long nanos = in.readLong();
                    int rate = in.readInt(), channels = in.readByte(), len = in.readInt();
                    if (len < 0 || len > 1 << 20 || channels < 1 || rate <= 0) throw new IOException("bad frame");
                    byte[] pcm = new byte[len];
                    in.readFully(pcm);
                    m.add(source, track, nanos, rate, channels, pcm);
                }
            } catch (IOException e) {
                tapConnected = false;
                if (recording) SystemClock.sleep(1000);
            }
        }
        tapConnected = false;
    }

    private void stopRecording() {
        if (!recording) return;
        recording = false;
        try { Socket s = tapSocket; if (s != null) s.close(); } catch (IOException ignored) { }
        try { tapThread.join(3000); } catch (InterruptedException ignored) { }
        File file = currentFile;
        try {
            mixer.close();
        } catch (IOException e) {
            jobStatus = "Error finishing " + file.getName() + ": " + e.getMessage();
        }
        mixer = null;
        currentFile = null;
        wakeLock.release();
        filesVersion++;
        if (file.length() <= WavWriter.HEADER) {
            file.delete();
            jobStatus = "Nothing was captured. Was ChatGPT voice mode running, with the module enabled?";
        } else if (getSharedPreferences("settings", MODE_PRIVATE).getBoolean("auto", true)) {
            enqueue(file);
        }
        updateNotification();
    }

    private interface Job { String run(Transcriber.Progress p) throws Exception; }

    private void enqueue(File wav) {
        Transcriber.Settings settings = Transcriber.Settings.load(this);
        enqueue(wav.getName(), p -> "Transcript ready: " + Transcriber.transcribe(wav, this, settings, p).getName());
    }

    /** Jobs run one at a time, in order, holding the CPU awake so they finish with the screen off. */
    private void enqueue(String label, Job job) {
        if (pendingJobs++ == 0) {
            jobWakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ConvoRecorder:job");
            jobWakeLock.acquire();
        }
        jobStatus = label + ": queued";
        jobs.execute(() -> {
            String result;
            try {
                result = job.run(msg -> {
                    jobStatus = label + ": " + msg + "…";
                    main.post(this::updateNotification);
                });
            } catch (Exception e) {
                result = label + " failed: " + e.getMessage();
            }
            String r = result;
            main.post(() -> {
                jobStatus = r;
                filesVersion++;
                if (--pendingJobs == 0) jobWakeLock.release();
                updateNotification();
                stopIfIdle();
            });
        });
    }

    private void stopIfIdle() {
        if (!recording && pendingJobs == 0) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private void goForeground() {
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "Recording and transcription", NotificationManager.IMPORTANCE_LOW));
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, notification());
    }

    private void updateNotification() {
        if (recording || pendingJobs > 0) getSystemService(NotificationManager.class).notify(1, notification());
    }

    private Notification notification() {
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(recording ? "Recording ChatGPT conversation" : "Working")
                .setContentText(recording ? "Speaker 1 = you, Speaker 2 = ChatGPT" : jobStatus)
                .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE))
                .setOngoing(true);
        if (recording) {
            PendingIntent stop = PendingIntent.getService(this, 1,
                    new Intent(this, RecorderService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
            b.addAction(new Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_media_pause), "Stop", stop).build());
        }
        return b.build();
    }

    @Override
    public void onDestroy() {
        if (recording) stopRecording();
        jobs.shutdown();
        super.onDestroy();
    }
}
