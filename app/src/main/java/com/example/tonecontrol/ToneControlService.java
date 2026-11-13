package com.example.tonecontrol;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.audiofx.DynamicsProcessing;
import android.media.audiofx.Equalizer;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

public class ToneControlService extends Service {

    private static Equalizer equalizer;
    private static DynamicsProcessing dynamicsProcessing;
    private static boolean initialized = false;

    @Override
    public void onCreate() {
        super.onCreate();
        ensureAudioEffectsInitialized();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        applyFromPreferences(this);
        return START_STICKY;
    }

    public static synchronized void ensureAudioEffectsInitialized() {
        if (initialized && (dynamicsProcessing != null || equalizer != null)) {
            return;
        }
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
                dynamicsProcessing = new DynamicsProcessing(0, 0, config);
                dynamicsProcessing.setEnabled(true);
            } catch (Exception ignored) {
                dynamicsProcessing = null;
            }
        }

        if (dynamicsProcessing == null) {
            try {
                equalizer = new Equalizer(0, 0);
                equalizer.setEnabled(true);
            } catch (Exception ignored) {
                equalizer = null;
            }
        }
        initialized = true;
    }

    public static synchronized void applyFromPreferences(Context context) {
        ensureAudioEffectsInitialized();
        SharedPreferences prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE);
        int[] gains = new int[MainActivity.BAND_COUNT];
        for (int i = 0; i < MainActivity.BAND_COUNT; i++) {
            gains[i] = MainActivity.clampDb(prefs.getInt(MainActivity.BAND_KEYS[i], 0));
        }
        applyTone(gains);
    }

    private static void applyTone(int[] gains) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && dynamicsProcessing != null) {
            try {
                for (int i = 0; i < MainActivity.BAND_COUNT; i++) {
                    float cutoff = MainActivity.BAND_CUTOFF_HZ[i];
                    float clampedGain = (float) MainActivity.clampDb(gains[i]);
                    DynamicsProcessing.EqBand preBand = new DynamicsProcessing.EqBand(true, cutoff, clampedGain);
                    dynamicsProcessing.setPreEqBandAllChannelsTo(i, preBand);
                }
                return;
            } catch (Exception ignored) {}
        }

        if (equalizer != null) {
            try {
                short numBands = equalizer.getNumberOfBands();
                short[] range = equalizer.getBandLevelRange();
                for (short b = 0; b < numBands; b++) {
                    int centerFreqMilliHz = equalizer.getCenterFreq(b);
                    float centerHz = centerFreqMilliHz / 1000.0f;
                    int mappedIdx = mapFreqToBandIndex(centerHz);
                    int clampedDb = MainActivity.clampDb(gains[mappedIdx]);
                    short targetMb = (short) Math.max(range[0], Math.min(range[1], clampedDb * 100));
                    equalizer.setBandLevel(b, targetMb);
                }
            } catch (Exception ignored) {}
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
        applyFromPreferences(this);
        try {
            Intent restartIntent = new Intent(getApplicationContext(), ToneControlService.class);
            restartIntent.setPackage(getPackageName());
            int flags = PendingIntent.FLAG_ONE_SHOT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent restartPendingIntent = PendingIntent.getService(
                    getApplicationContext(), 1, restartIntent, flags);
            AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (alarmManager != null) {
                alarmManager.set(
                        AlarmManager.ELAPSED_REALTIME,
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
