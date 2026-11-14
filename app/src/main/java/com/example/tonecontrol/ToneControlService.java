package com.example.tonecontrol;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.DynamicsProcessing;
import android.media.audiofx.Equalizer;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.SparseArray;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ToneControlService extends Service {

    private static final String CHANNEL_ID = "tone_control_engine_channel";
    private static final int NOTIFICATION_ID = 1001;
    public static final String EXTRA_FORWARDED_ACTION = "extra_forwarded_action";
    public static final String ACTION_SESSIONS_UPDATED = "com.example.tonecontrol.ACTION_SESSIONS_UPDATED";

    private static final SparseArray<DynamicsProcessing> dpSessions = new SparseArray<>();
    private static final SparseArray<Equalizer> eqSessions = new SparseArray<>();

    private static final ExecutorService scannerExecutor = Executors.newSingleThreadExecutor();
    private static volatile boolean rootChecked = false;
    private static volatile boolean rootAvailable = false;
    private static volatile String lastDetectionMode = "Broadcast Only";

    // 1. Pola dumpsys audio / logcat umum: sessionId:123, session=123, Session Id: 123, sessionId(123)
    private static final Pattern PATTERN_SESSION_KV =
            Pattern.compile("(?:sessionId|session\\s*id|audioSession|session)\\s*(?:[:=]|\\()\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

    // 2. Pola tabel Tracks di dumpsys media.audio_flinger:
    // Menangkap track normal ("  54 yes ..."), FastTrack ("F 54 yes ..."), Offload ("O 54 yes ..."), Direct ("D 54 yes ...")
    private static final Pattern PATTERN_FLINGER_TRACK_ROW =
            Pattern.compile("^\\s*[A-Za-z]*\\s*\\d+\\s+(?:yes|no)\\s+\\S+\\s+(\\d+)\\s+", Pattern.MULTILINE);

    // 3. Pola Effect Chain di dumpsys media.audio_flinger: "Effect chain ... session 123"
    private static final Pattern PATTERN_FLINGER_EFFECT_CHAIN =
            Pattern.compile("Effect\\s+chain.*session\\s+(\\d+)", Pattern.CASE_INSENSITIVE);

    private BroadcastReceiver dynamicAudioReceiver;
    private AudioManager.AudioPlaybackCallback playbackCallback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Runnable periodicScanRunnable = new Runnable() {
        @Override
        public void run() {
            triggerActiveSessionScan(ToneControlService.this);
            // Polling setiap 2.5 detik untuk menangkap aplikasi (TikTok/YouTube/Game) yang ganti AudioTrack diam-diam
            mainHandler.postDelayed(this, 2500);
        }
    };

    public static void startOrUpdateService(Context context, Intent sourceIntent) {
        Intent serviceIntent = new Intent(context.getApplicationContext(), ToneControlService.class);
        if (sourceIntent != null) {
            serviceIntent.putExtra(EXTRA_FORWARDED_ACTION, sourceIntent.getAction());
            if (sourceIntent.hasExtra(AudioEffect.EXTRA_AUDIO_SESSION)) {
                serviceIntent.putExtra(
                        AudioEffect.EXTRA_AUDIO_SESSION,
                        sourceIntent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0)
                );
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.getApplicationContext().startForegroundService(serviceIntent);
            } else {
                context.getApplicationContext().startService(serviceIntent);
            }
        } catch (Exception ignored) {
            try {
                context.getApplicationContext().startService(serviceIntent);
            } catch (Exception ignoredFallback) {}
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundSilent();
        registerDynamicAudioSessionReceiver();
        registerPlaybackCallback();
        ensureSessionInitialized(this, 0);
        applyFromPreferences(this);
        mainHandler.post(periodicScanRunnable);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundSilent();
        if (intent != null) {
            String action = intent.getStringExtra(EXTRA_FORWARDED_ACTION);
            int sessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0);
            if (AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION.equals(action) && sessionId > 0) {
                ensureSessionInitialized(this, sessionId);
            } else if (AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION.equals(action) && sessionId > 0) {
                releaseSession(sessionId);
            }
        }
        applyFromPreferences(this);
        triggerActiveSessionScan(this);
        return START_STICKY;
    }

    private void startForegroundSilent() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        "Tone Control Audio Engine",
                        NotificationManager.IMPORTANCE_MIN
                );
                channel.setSound(null, null);
                channel.enableVibration(false);
                channel.enableLights(false);
                channel.setShowBadge(false);
                channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
                nm.createNotificationChannel(channel);
            }

            Intent launchIntent = new Intent(this, MainActivity.class);
            int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                piFlags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent contentIntent = PendingIntent.getActivity(this, 0, launchIntent, piFlags);

            Notification.Builder builder = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ? new Notification.Builder(this, CHANNEL_ID)
                    : new Notification.Builder(this);

            builder.setSmallIcon(android.R.drawable.ic_media_ff)
                    .setContentTitle(getString(R.string.app_name))
                    .setContentText("EQ Active (" + getAttachedSessionCount() + " sessions)")
                    .setContentIntent(contentIntent)
                    .setOngoing(true);

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                builder.setPriority(Notification.PRIORITY_MIN);
            }

            Notification notification = builder.build();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                );
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Exception ignored) {}
    }

    private void registerDynamicAudioSessionReceiver() {
        if (dynamicAudioReceiver != null) {
            return;
        }
        dynamicAudioReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                String action = intent.getAction();
                int sessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0);
                if (AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION.equals(action) && sessionId > 0) {
                    ensureSessionInitialized(context, sessionId);
                    applyFromPreferences(context);
                    notifySessionsUpdated(context);
                } else if (AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION.equals(action) && sessionId > 0) {
                    releaseSession(sessionId);
                    notifySessionsUpdated(context);
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
        filter.addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(dynamicAudioReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(dynamicAudioReceiver, filter);
        }
    }

    private void registerPlaybackCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || playbackCallback != null) {
            return;
        }
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;

        playbackCallback = new AudioManager.AudioPlaybackCallback() {
            @Override
            public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                super.onPlaybackConfigChanged(configs);
                // Burst scan bertahap karena banyak aplikasi baru mendaftarkan AudioTrack 100-800ms setelah event
                triggerActiveSessionScan(ToneControlService.this);
                mainHandler.postDelayed(() -> triggerActiveSessionScan(ToneControlService.this), 200);
                mainHandler.postDelayed(() -> triggerActiveSessionScan(ToneControlService.this), 700);
                mainHandler.postDelayed(() -> triggerActiveSessionScan(ToneControlService.this), 1500);
            }
        };
        am.registerAudioPlaybackCallback(playbackCallback, mainHandler);
    }

    public static void triggerActiveSessionScan(Context context) {
        final Context appCtx = context.getApplicationContext();
        scannerExecutor.execute(() -> scanAndAttachSessionsInternal(appCtx));
    }

    private static void scanAndAttachSessionsInternal(Context context) {
        initRootAndAudioPropertiesIfAvailable(context);
        Set<Integer> discovered = new HashSet<>();
        StringBuilder modeBuilder = new StringBuilder();

        // JALUR 1: Jika Root (su) tersedia, selalu jalankan dumpsys media.audio_flinger & dumpsys audio lewat su
        // (Jangan dilewati meskipun izin DUMP aktif, karena SELinux memblokir media.audio_flinger untuk non-root!)
        if (rootAvailable) {
            parseSessionsFromCommand(
                    new String[]{"su", "-c", "dumpsys media.audio_flinger; dumpsys audio"},
                    discovered
            );
            modeBuilder.append("Root (AudioFlinger+Audio)");
        }

        // JALUR 2: Binder dump langsung ke service "audio" & "media.audio_flinger" (tidak diblokir SELinux binary exec)
        boolean hasDumpPerm = context.checkSelfPermission(android.Manifest.permission.DUMP)
                == PackageManager.PERMISSION_GRANTED;
        if (hasDumpPerm) {
            dumpServiceViaBinder("audio", discovered);
            dumpServiceViaBinder("media.audio_flinger", discovered);
            parseSessionsFromCommand(new String[]{"/system/bin/dumpsys", "audio"}, discovered);
            parseSessionsFromCommand(new String[]{"/system/bin/dumpsys", "media.audio_flinger"}, discovered);
            if (modeBuilder.length() == 0) {
                modeBuilder.append("ADB DUMP");
            }
        }

        // JALUR 3: Baca logcat untuk menangkap sessionId aplikasi seperti YouTube/Game jika izin READ_LOGS aktif
        boolean hasLogsPerm = context.checkSelfPermission(android.Manifest.permission.READ_LOGS)
                == PackageManager.PERMISSION_GRANTED;
        if (hasLogsPerm) {
            parseSessionsFromCommand(
                    new String[]{"/system/bin/logcat", "-d", "-t", "250", "-b", "main,system,events"},
                    discovered
            );
            if (modeBuilder.length() > 0) {
                modeBuilder.append(" + READ_LOGS");
            } else {
                modeBuilder.append("ADB READ_LOGS");
            }
        }

        if (modeBuilder.length() == 0) {
            modeBuilder.append("Broadcast Only (Berikan izin DUMP/Root)");
        }
        lastDetectionMode = modeBuilder.toString();

        boolean addedNew = false;
        if (!discovered.isEmpty()) {
            for (Integer sid : discovered) {
                if (sid != null && sid > 0) {
                    if (isNewSession(sid)) {
                        addedNew = true;
                    }
                    ensureSessionInitialized(context, sid);
                }
            }
            applyFromPreferences(context);
        }
        if (addedNew) {
            notifySessionsUpdated(context);
        }
    }

    private static synchronized boolean isNewSession(int sessionId) {
        return dpSessions.get(sessionId) == null && eqSessions.get(sessionId) == null;
    }

    private static void initRootAndAudioPropertiesIfAvailable(Context context) {
        if (rootChecked) return;
        rootChecked = true;
        Process p = null;
        try {
            String pkg = context.getPackageName();
            // Jika perangkat memiliki root:
            // 1. Berikan izin DUMP & READ_LOGS
            // 2. Nonaktifkan AAudio MMAP (aaudio.mmap_policy=1 / AAUDIO_POLICY_NEVER) & Offload
            //    supaya semua game dan pemutar media melewati mixer standar AudioFlinger dan terkena efek EQ
            String cmd = "pm grant " + pkg + " android.permission.DUMP; "
                    + "pm grant " + pkg + " android.permission.READ_LOGS; "
                    + "setprop aaudio.mmap_policy 1; "
                    + "setprop aaudiostream.mmap_policy 1; "
                    + "setprop audio.offload.disable 1; "
                    + "setprop vendor.audio.offload.track.enable false; "
                    + "echo ROOT_OK";
            p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("ROOT_OK")) {
                        rootAvailable = true;
                    }
                }
            }
            p.waitFor();
        } catch (Throwable ignored) {
            rootAvailable = false;
        } finally {
            if (p != null) {
                p.destroy();
            }
        }
    }

    private static void dumpServiceViaBinder(String serviceName, Set<Integer> outSessions) {
        ParcelFileDescriptor[] pipe = null;
        try {
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            Method getService = smClass.getDeclaredMethod("getService", String.class);
            IBinder binder = (IBinder) getService.invoke(null, serviceName);
            if (binder == null) return;

            pipe = ParcelFileDescriptor.createPipe();
            final ParcelFileDescriptor readSide = pipe[0];
            final ParcelFileDescriptor writeSide = pipe[1];

            Thread dumpThread = new Thread(() -> {
                try {
                    binder.dump(writeSide.getFileDescriptor(), new String[0]);
                } catch (Throwable ignored) {
                } finally {
                    try {
                        writeSide.close();
                    } catch (Throwable ignoredClose) {}
                }
            });
            dumpThread.start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(readSide.getFileDescriptor())))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    extractSessionsFromLine(line, outSessions);
                }
            }
            dumpThread.join(1500);
        } catch (Throwable ignored) {
        } finally {
            if (pipe != null) {
                try { pipe[0].close(); } catch (Throwable ignored) {}
                try { pipe[1].close(); } catch (Throwable ignored) {}
            }
        }
    }

    private static void parseSessionsFromCommand(String[] cmd, Set<Integer> outSessions) {
        Process process = null;
        try {
            process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    extractSessionsFromLine(line, outSessions);
                }
            }
            process.waitFor();
        } catch (Throwable ignored) {
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static void extractSessionsFromLine(String line, Set<Integer> outSessions) {
        if (line == null || line.isEmpty()) return;

        Matcher m1 = PATTERN_SESSION_KV.matcher(line);
        while (m1.find()) {
            addValidSessionId(m1.group(1), outSessions);
        }

        Matcher m2 = PATTERN_FLINGER_TRACK_ROW.matcher(line);
        if (m2.find()) {
            addValidSessionId(m2.group(1), outSessions);
        }

        Matcher m3 = PATTERN_FLINGER_EFFECT_CHAIN.matcher(line);
        if (m3.find()) {
            addValidSessionId(m3.group(1), outSessions);
        }
    }

    private static void addValidSessionId(String raw, Set<Integer> outSessions) {
        try {
            int sid = Integer.parseInt(raw);
            if (sid > 0) {
                outSessions.add(sid);
            }
        } catch (NumberFormatException ignored) {}
    }

    public static synchronized void ensureSessionInitialized(Context context, int sessionId) {
        final Context appCtx = (context != null) ? context.getApplicationContext() : null;
        DynamicsProcessing existingDp = dpSessions.get(sessionId);
        Equalizer existingEq = eqSessions.get(sessionId);
        if (existingDp != null || existingEq != null) {
            return;
        }

        DynamicsProcessing dp = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                // Gunakan hanya Pre-EQ (tanpa duplikasi Post-EQ) dan frame duration 10ms agar kompatibel dengan track low-latency
                DynamicsProcessing.Config.Builder builder = new DynamicsProcessing.Config.Builder(
                        DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                        1,
                        true, MainActivity.BAND_COUNT,
                        false, 0,
                        false, 0,
                        false
                );
                builder.setPreferredFrameDuration(10.0f);
                DynamicsProcessing.Config config = builder.build();
                dp = new DynamicsProcessing(Integer.MAX_VALUE, sessionId, config);

                final int targetSid = sessionId;
                dp.setEnableStatusListener((effect, enabled) -> {
                    if (!enabled) {
                        try {
                            effect.setEnabled(true);
                            if (appCtx != null) applyFromPreferences(appCtx);
                        } catch (Exception ignored) {}
                    }
                });
                dp.setControlStatusListener((effect, controlGranted) -> {
                    if (!controlGranted) {
                        // Jika aplikasi lain mengambil alih efek, buat ulang dengan prioritas tertinggi
                        releaseSession(targetSid);
                        if (appCtx != null) {
                            ensureSessionInitialized(appCtx, targetSid);
                            applyFromPreferences(appCtx);
                        }
                    }
                });

                dp.setEnabled(true);
                dpSessions.put(sessionId, dp);
            } catch (Exception ignored) {
                dp = null;
            }
        }

        // Pasang Equalizer hanya jika DynamicsProcessing gagal dibuat ATAU pada session 0
        // (Memasang keduanya sekaligus pada session yang sama dapat menyebabkan konflik/cancel di beberapa HAL)
        if (dp == null || sessionId == 0) {
            try {
                Equalizer eq = new Equalizer(Integer.MAX_VALUE, sessionId);
                eq.setEnableStatusListener((effect, enabled) -> {
                    if (!enabled) {
                        try {
                            effect.setEnabled(true);
                            if (appCtx != null) applyFromPreferences(appCtx);
                        } catch (Exception ignored) {}
                    }
                });
                eq.setEnabled(true);
                eqSessions.put(sessionId, eq);
            } catch (Exception ignored) {}
        }
    }

    public static synchronized void releaseSession(int sessionId) {
        if (sessionId == 0) {
            return;
        }
        DynamicsProcessing dp = dpSessions.get(sessionId);
        if (dp != null) {
            try {
                dp.release();
            } catch (Exception ignored) {}
            dpSessions.remove(sessionId);
        }
        Equalizer eq = eqSessions.get(sessionId);
        if (eq != null) {
            try {
                eq.release();
            } catch (Exception ignored) {}
            eqSessions.remove(sessionId);
        }
    }

    public static synchronized void applyFromPreferences(Context context) {
        ensureSessionInitialized(context, 0);
        SharedPreferences prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE);
        int[] gains = new int[MainActivity.BAND_COUNT];
        for (int i = 0; i < MainActivity.BAND_COUNT; i++) {
            gains[i] = MainActivity.clampDb(prefs.getInt(MainActivity.BAND_KEYS[i], 0));
        }
        applyToneToAllSessions(gains);
    }

    private static void applyToneToAllSessions(int[] gains) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            for (int s = 0; s < dpSessions.size(); s++) {
                DynamicsProcessing dp = dpSessions.valueAt(s);
                if (dp != null) {
                    try {
                        if (!dp.getEnabled()) {
                            dp.setEnabled(true);
                        }
                        for (int i = 0; i < MainActivity.BAND_COUNT; i++) {
                            float cutoff = MainActivity.BAND_CUTOFF_HZ[i];
                            float clampedGain = (float) MainActivity.clampDb(gains[i]);
                            DynamicsProcessing.EqBand preBand = new DynamicsProcessing.EqBand(true, cutoff, clampedGain);
                            dp.setPreEqBandAllChannelsTo(i, preBand);
                        }
                    } catch (Exception ignored) {}
                }
            }
        }

        for (int s = 0; s < eqSessions.size(); s++) {
            Equalizer eq = eqSessions.valueAt(s);
            if (eq != null) {
                try {
                    if (!eq.getEnabled()) {
                        eq.setEnabled(true);
                    }
                    short numBands = eq.getNumberOfBands();
                    short[] range = eq.getBandLevelRange();
                    for (short b = 0; b < numBands; b++) {
                        int centerFreqMilliHz = eq.getCenterFreq(b);
                        float centerHz = centerFreqMilliHz / 1000.0f;
                        int mappedIdx = mapFreqToBandIndex(centerHz);
                        int clampedDb = MainActivity.clampDb(gains[mappedIdx]);
                        short targetMb = (short) Math.max(range[0], Math.min(range[1], clampedDb * 100));
                        eq.setBandLevel(b, targetMb);
                    }
                } catch (Exception ignored) {}
            }
        }
    }

    private static int mapFreqToBandIndex(float hz) {
        for (int i = 0; i < MainActivity.BAND_COUNT; i++) {
            if (hz <= MainActivity.BAND_CUTOFF_HZ[i]) {
                return i;
            }
        }
        return MainActivity.BAND_COUNT - 1;
    }

    public static synchronized int getAttachedSessionCount() {
        Set<Integer> all = new HashSet<>();
        for (int i = 0; i < dpSessions.size(); i++) all.add(dpSessions.keyAt(i));
        for (int i = 0; i < eqSessions.size(); i++) all.add(eqSessions.keyAt(i));
        return all.size();
    }

    public static synchronized String getDiagnosticStatus(Context context) {
        List<Integer> ids = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < dpSessions.size(); i++) {
            int k = dpSessions.keyAt(i);
            if (seen.add(k)) ids.add(k);
        }
        for (int i = 0; i < eqSessions.size(); i++) {
            int k = eqSessions.keyAt(i);
            if (seen.add(k)) ids.add(k);
        }
        Collections.sort(ids);
        return "Mode: " + lastDetectionMode + "\nActive Sessions (" + ids.size() + "): " + ids.toString();
    }

    private static void notifySessionsUpdated(Context context) {
        if (context == null) return;
        try {
            Intent i = new Intent(ACTION_SESSIONS_UPDATED);
            i.setPackage(context.getPackageName());
            context.sendBroadcast(i);
        } catch (Exception ignored) {}
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        startForegroundSilent();
        applyFromPreferences(this);
        scheduleRestart();
    }

    @Override
    public void onDestroy() {
        mainHandler.removeCallbacks(periodicScanRunnable);
        if (dynamicAudioReceiver != null) {
            try {
                unregisterReceiver(dynamicAudioReceiver);
            } catch (Exception ignored) {}
            dynamicAudioReceiver = null;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && playbackCallback != null) {
            try {
                AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                if (am != null) {
                    am.unregisterAudioPlaybackCallback(playbackCallback);
                }
            } catch (Exception ignored) {}
            playbackCallback = null;
        }
        scheduleRestart();
        super.onDestroy();
    }

    private void scheduleRestart() {
        try {
            Intent restartIntent = new Intent(getApplicationContext(), ToneControlService.class);
            restartIntent.setPackage(getPackageName());
            int flags = PendingIntent.FLAG_ONE_SHOT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent restartPendingIntent;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                restartPendingIntent = PendingIntent.getForegroundService(
                        getApplicationContext(), 1, restartIntent, flags);
            } else {
                restartPendingIntent = PendingIntent.getService(
                        getApplicationContext(), 1, restartIntent, flags);
            }
            AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (alarmManager != null) {
                alarmManager.set(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + 500,
                        restartPendingIntent
                );
            }
        } catch (Exception ignored) {}
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
