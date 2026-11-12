package com.example.tonecontrol;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.audiofx.DynamicsProcessing;
import android.media.audiofx.Equalizer;
import android.os.Build;
import android.os.IBinder;

public class ToneControlService extends Service {

    private static final String CHANNEL_ID = "tone_control_channel";
    private static final int NOTIFICATION_ID = 1001;

    private Equalizer equalizer;
    private DynamicsProcessing dynamicsProcessing;

    @Override
    public void onCreate() {
        super.onCreate();
        initAudioEffects();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE);
        int lowDb = MainActivity.clampDb(prefs.getInt(MainActivity.KEY_LOW_DB, 0));
        int midDb = MainActivity.clampDb(prefs.getInt(MainActivity.KEY_MID_DB, 0));
        int highDb = MainActivity.clampDb(prefs.getInt(MainActivity.KEY_HIGH_DB, 0));

        startForegroundSafely(lowDb, midDb, highDb);
        applyTone(lowDb, midDb, highDb);

        return START_STICKY;
    }

    private void initAudioEffects() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                DynamicsProcessing.Config.Builder builder = new DynamicsProcessing.Config.Builder(
                        DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                        1,
                        true, 3,
                        false, 0,
                        true, 3,
                        false
                );
                DynamicsProcessing.Config config = builder.build();
                dynamicsProcessing = new DynamicsProcessing(0, 0, config);
                dynamicsProcessing.setEnabled(true);
            } catch (Exception ignored) {
                dynamicsProcessing = null;
            }
        }

        try {
            equalizer = new Equalizer(0, 0);
            equalizer.setEnabled(true);
        } catch (Exception ignored) {
            equalizer = null;
        }
    }

    private void applyTone(int lowDb, int midDb, int highDb) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && dynamicsProcessing != null) {
            try {
                DynamicsProcessing.EqBand lowBandPre = new DynamicsProcessing.EqBand(true, 250.0f, lowDb * 0.5f);
                DynamicsProcessing.EqBand midBandPre = new DynamicsProcessing.EqBand(true, 2500.0f, midDb * 0.5f);
                DynamicsProcessing.EqBand highBandPre = new DynamicsProcessing.EqBand(true, 20000.0f, highDb * 0.5f);
                dynamicsProcessing.setPreEqBandAllChannelsTo(0, lowBandPre);
                dynamicsProcessing.setPreEqBandAllChannelsTo(1, midBandPre);
                dynamicsProcessing.setPreEqBandAllChannelsTo(2, highBandPre);

                DynamicsProcessing.EqBand lowBandPost = new DynamicsProcessing.EqBand(true, 250.0f, lowDb * 0.5f);
                DynamicsProcessing.EqBand midBandPost = new DynamicsProcessing.EqBand(true, 2500.0f, midDb * 0.5f);
                DynamicsProcessing.EqBand highBandPost = new DynamicsProcessing.EqBand(true, 20000.0f, highDb * 0.5f);
                dynamicsProcessing.setPostEqBandAllChannelsTo(0, lowBandPost);
                dynamicsProcessing.setPostEqBandAllChannelsTo(1, midBandPost);
                dynamicsProcessing.setPostEqBandAllChannelsTo(2, highBandPost);
            } catch (Exception ignored) {}
        }

        if (equalizer != null) {
            try {
                short bands = equalizer.getNumberOfBands();
                short[] range = equalizer.getBandLevelRange();
                if (bands > 0) {
                    short lowMb = (short) Math.max(range[0], Math.min(range[1], lowDb * 100));
                    equalizer.setBandLevel((short) 0, lowMb);
                }
                if (bands > 2) {
                    short midMb = (short) Math.max(range[0], Math.min(range[1], midDb * 100));
                    equalizer.setBandLevel((short) (bands / 2), midMb);
                }
                if (bands > 1) {
                    short highMb = (short) Math.max(range[0], Math.min(range[1], highDb * 100));
                    equalizer.setBandLevel((short) (bands - 1), highMb);
                }
            } catch (Exception ignored) {}
        }
    }

    private void startForegroundSafely(int lowDb, int midDb, int highDb) {
        Notification notification = buildNotification(lowDb, midDb, highDb);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Exception ignored) {}
    }

    private Notification buildNotification(int lowDb, int midDb, int highDb) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingFlags);

        String contentText = "Low: " + formatDb(lowDb) + " dB | Mid: " + formatDb(midDb) + " dB | High: " + formatDb(highDb) + " dB";

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Tone Control Background Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        builder.setContentTitle("Tone Control Active")
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_media_ff)
                .setContentIntent(pendingIntent)
                .setOngoing(true);

        return builder.build();
    }

    private String formatDb(int db) {
        return (db > 0 ? "+" : "") + db;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE);
        int lowDb = MainActivity.clampDb(prefs.getInt(MainActivity.KEY_LOW_DB, 0));
        int midDb = MainActivity.clampDb(prefs.getInt(MainActivity.KEY_MID_DB, 0));
        int highDb = MainActivity.clampDb(prefs.getInt(MainActivity.KEY_HIGH_DB, 0));
        applyTone(lowDb, midDb, highDb);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (dynamicsProcessing != null) {
            try {
                dynamicsProcessing.release();
            } catch (Exception ignored) {}
        }
        if (equalizer != null) {
            try {
                equalizer.release();
            } catch (Exception ignored) {}
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
