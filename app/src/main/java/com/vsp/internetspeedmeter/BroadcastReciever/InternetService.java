package com.vsp.internetspeedmeter.BroadcastReciever;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.TrafficStats;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import com.vsp.internetspeedmeter.NotificationService;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

import androidx.annotation.Nullable;

import static com.vsp.internetspeedmeter.MainActivity.TAG;

public class InternetService extends Service {
    private NotificationService notificationService;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isScreenOn = true;

    private long prevTotalRx = 0, prevTotalTx = 0;
    private long prevMobileRx = 0, prevMobileTx = 0;

    private long dailyMobileBytes = 0;
    private long dailyWifiBytes = 0;
    private String lastRecordedDate = "";

    private SharedPreferences prefs;
    private SimpleDateFormat dateFormat;

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                isScreenOn = false;
                handler.removeCallbacks(runnable);
            } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                isScreenOn = true;
                // Re-sync previous values to avoid a fake speed spike upon turning screen on
                syncTrafficStats();
                handler.removeCallbacks(runnable);
                handler.post(runnable);
            }
        }
    };

    private final Runnable runnable = new Runnable() {
        @Override
        public void run() {
            if (!isScreenOn) return;

            long curTotalRx = TrafficStats.getTotalRxBytes();
            long curTotalTx = TrafficStats.getTotalTxBytes();
            long curMobileRx = TrafficStats.getMobileRxBytes();
            long curMobileTx = TrafficStats.getMobileTxBytes();

            // Handle initial state or reboot where counter resets
            if (prevTotalRx == 0 || curTotalRx < prevTotalRx) {
                syncTrafficStats();
                handler.postDelayed(this, 1000);
                return;
            }

            long downSpeed = Math.max(0, curTotalRx - prevTotalRx);
            long upSpeed = Math.max(0, curTotalTx - prevTotalTx);

            long deltaMobileRx = Math.max(0, curMobileRx - prevMobileRx);
            long deltaMobileTx = Math.max(0, curMobileTx - prevMobileTx);
            long deltaMobile = deltaMobileRx + deltaMobileTx;

            long deltaTotal = downSpeed + upSpeed;
            long deltaWifi = Math.max(0, deltaTotal - deltaMobile);

            prevTotalRx = curTotalRx;
            prevTotalTx = curTotalTx;
            prevMobileRx = curMobileRx;
            prevMobileTx = curMobileTx;

            // Check if day changed
            checkDateRollover();

            dailyMobileBytes += deltaMobile;
            dailyWifiBytes += deltaWifi;

            // Persist daily counts
            prefs.edit()
                    .putLong("dailyMobileBytes", dailyMobileBytes)
                    .putLong("dailyWifiBytes", dailyWifiBytes)
                    .putString("lastRecordedDate", lastRecordedDate)
                    .apply();

            startForeground(
                    NotificationService.NOTIFICATION_ID,
                    notificationService.updateNotification(downSpeed, upSpeed, dailyMobileBytes, dailyWifiBytes).build()
            );

            handler.postDelayed(this, 1000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        dateFormat = new SimpleDateFormat("dd-MM-yyyy", Locale.getDefault());
        lastRecordedDate = dateFormat.format(Calendar.getInstance().getTime());

        prefs = getSharedPreferences("traffic_data", MODE_PRIVATE);
        String savedDate = prefs.getString("lastRecordedDate", "");
        if (savedDate.equals(lastRecordedDate)) {
            dailyMobileBytes = prefs.getLong("dailyMobileBytes", 0);
            dailyWifiBytes = prefs.getLong("dailyWifiBytes", 0);
        } else {
            dailyMobileBytes = 0;
            dailyWifiBytes = 0;
        }

        notificationService = new NotificationService(this);

        syncTrafficStats();

        // Register screen receiver to conserve battery when display is off
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenReceiver, filter);
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        startForeground(
                NotificationService.NOTIFICATION_ID,
                notificationService.updateNotification(0, 0, dailyMobileBytes, dailyWifiBytes).build()
        );

        handler.removeCallbacks(runnable);
        handler.post(runnable);

        return START_STICKY;
    }

    private void syncTrafficStats() {
        prevTotalRx = TrafficStats.getTotalRxBytes();
        prevTotalTx = TrafficStats.getTotalTxBytes();
        prevMobileRx = TrafficStats.getMobileRxBytes();
        prevMobileTx = TrafficStats.getMobileTxBytes();
    }

    private void checkDateRollover() {
        String today = dateFormat.format(Calendar.getInstance().getTime());
        if (!today.equals(lastRecordedDate)) {
            lastRecordedDate = today;
            dailyMobileBytes = 0;
            dailyWifiBytes = 0;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(screenReceiver);
        } catch (Exception ignored) {}
        handler.removeCallbacks(runnable);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}