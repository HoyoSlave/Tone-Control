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
import android.content.pm.ServiceInfo;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.DynamicsProcessing;
import android.media.audiofx.Equalizer;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.SparseArray;

public class ToneControlService extends Service {

    private static final String CHANNEL_ID = "tone_control_engine_channel";
    private static final int NOTIFICATION_ID = 1001;
    public static final String EXTRA_FORWARDED_ACTION = "extra_forwarded_action";

    private static final SparseArray<DynamicsProcessing> dpSessions = new SparseArray<>();
    private static final SparseArray<Equalizer> eqSessions = new SparseArray<>();

    private BroadcastReceiver dynamicAudioReceiver;

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
        ensureSessionInitialized(0);
        applyFromPreferences(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundSilent();
        if (intent != null) {
            String action = intent.getStringExtra(EXTRA_FORWARDED_ACTION);
            int sessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0);
            if (AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION.equals(action) && sessionId != 0) {
                ensureSessionInitialized(sessionId);
            } else if (AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION.equals(action) && sessionId != 0) {
                releaseSession(sessionId);
            }
        }
        applyFromPreferences(this);
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
                    .setContentText("EQ Active")
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
                if (AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION.equals(action) && sessionId != 0) {
                    ensureSessionInitialized(sessionId);
                    applyFromPreferences(context);
                } else if (AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION.equals(action) && sessionId != 0) {
                    releaseSession(sessionId);
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

    public static synchronized void ensureSessionInitialized(int sessionId) {
        if (dpSessions.get(sessionId) != null || eqSessions.get(sessionId) != null) {
            return;
        }
        DynamicsProcessing dp = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                DynamicsProcessing.Config.Builder builder = new DynamicsProcessing.Config.Builder(
                        DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                        1,
                        true, MainActivity.BAND_COUNT,
                        false, 0,
                        false, 0,
                        false
                );
                DynamicsProcessing.Config config = builder.build();
                dp = new DynamicsProcessing(Integer.MAX_VALUE, sessionId, config);
                dp.setEnabled(true);
                dpSessions.put(sessionId, dp);
            } catch (Exception ignored) {
                dp = null;
            }
        }

        if (dp == null) {
            try {
                Equalizer eq = new Equalizer(Integer.MAX_VALUE, sessionId);
                eq.setEnabled(true);
                eqSessions.put(sessionId, eq);
            } catch (Exception ignored) {}
        }
    }

    public static synchronized void releaseSession(int sessionId) {
        if (sessionId == 0) {
            return; // Never release global mix session 0
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
        ensureSessionInitialized(0);
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
                        dp.setEnabled(true);
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
                    eq.setEnabled(true);
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

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        startForegroundSilent();
        applyFromPreferences(this);
        scheduleRestart();
    }

    @Override
    public void onDestroy() {
        if (dynamicAudioReceiver != null) {
            try {
                unregisterReceiver(dynamicAudioReceiver);
            } catch (Exception ignored) {}
            dynamicAudioReceiver = null;
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
