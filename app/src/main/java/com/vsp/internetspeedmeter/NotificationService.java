package com.vsp.internetspeedmeter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.util.Log;

import com.vsp.internetspeedmeter.Room.Usage;
import com.vsp.internetspeedmeter.Room.UsageRepository;

import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

public class NotificationService {
    public static final String CHANNEL_ID = "speed_meter_channel";
    public static final String CHANNEL_NAME = "Internet Speed Meter";
    public static final String CHANNEL_DESC = "Displays real-time network speed and daily usage";
    public static final String TAG = "internetspeed";
    public static final int NOTIFICATION_ID = 1;

    private Notification.Builder mBuilder;
    private NotificationManager mNotifyMgr;
    private PendingIntent pendingIntent;
    private Context context;
    private Bitmap bitmap;
    private Canvas canvas;
    private Paint paint, unitsPaint;
    private Icon icon;
    private UsageRepository usageRepository;
    public String myDate;
    private SimpleDateFormat df;
    private final DecimalFormat decimalFormat = new DecimalFormat("#.0");

    public NotificationService(Context context) {
        this.context = context;
        usageRepository = new UsageRepository(context);
        df = new SimpleDateFormat("dd-MM-yyyy", Locale.getDefault());
        myDate = df.format(Calendar.getInstance().getTime());
        createNotification();
    }

    public void createNotification() {
        createNotificationChannel();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mBuilder = new Notification.Builder(context, CHANNEL_ID);
        } else {
            mBuilder = new Notification.Builder(context);
        }

        setupIcon();

        Intent notifiIntent = new Intent(context, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        pendingIntent = PendingIntent.getActivity(context, 100, notifiIntent, flags);

        icon = getIcon("0", "K");
        mBuilder.setSmallIcon(icon)
                .setContentTitle("Down: 0 KB/s   Up: 0 KB/s")
                .setContentText("Mobile: 0 MB   WiFi: 0 MB")
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(pendingIntent)
                .setOnlyAlertOnce(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mBuilder.setVisibility(Notification.VISIBILITY_PUBLIC);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mBuilder.setBadgeIconType(Notification.BADGE_ICON_NONE);
        }

        mNotifyMgr = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    public Notification.Builder updateNotification(long downSpeedBytes, long upSpeedBytes, long mobileBytes, long wifiBytes) {
        // Check date rollover
        checkDateRollover();

        // Speed formatting
        String downStr = formatSpeed(downSpeedBytes);
        String upStr = formatSpeed(upSpeedBytes);

        // Status bar icon: show total current speed
        long totalSpeedBytes = downSpeedBytes + upSpeedBytes;
        SpeedUnit iconSpeed = formatSpeedForIcon(totalSpeedBytes);
        icon = getIcon(iconSpeed.value, iconSpeed.unit);

        // Data usage formatting
        String mobileStr = formatData(mobileBytes);
        String wifiStr = formatData(wifiBytes);
        String totalStr = formatData(mobileBytes + wifiBytes);

        mBuilder.setSmallIcon(icon);
        mBuilder.setContentTitle("Down: " + downStr + "   Up: " + upStr);
        mBuilder.setContentText("Mobile: " + mobileStr + "   WiFi: " + wifiStr);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            mBuilder.setSubText("Total: " + totalStr);
        }

        // Update database
        usageRepository.update(new Usage(myDate, mobileStr, wifiStr, totalStr));

        return mBuilder;
    }

    private void checkDateRollover() {
        String currentDate = df.format(Calendar.getInstance().getTime());
        if (!currentDate.equals(myDate)) {
            myDate = currentDate;
            usageRepository.insert(new Usage(myDate, "0 MB", "0 MB", "0 MB"));
        }
    }

    private void setupIcon() {
        paint = new Paint();
        paint.setAntiAlias(true);
        paint.setTextSize(52);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        paint.setColor(Color.WHITE);

        unitsPaint = new Paint();
        unitsPaint.setAntiAlias(true);
        unitsPaint.setTextSize(36);
        unitsPaint.setTextAlign(Paint.Align.CENTER);
        unitsPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        unitsPaint.setColor(Color.WHITE);

        bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
        canvas = new Canvas(bitmap);
    }

    public Icon getIcon(String speed, String units) {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        canvas.drawText(speed, 48, 50, paint);
        canvas.drawText(units, 48, 88, unitsPaint);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Icon.createWithBitmap(bitmap);
        }
        return null;
    }

    private String formatSpeed(long bytesPerSec) {
        if (bytesPerSec >= 1000000000L) {
            return decimalFormat.format((double) bytesPerSec / 1000000000L) + " GB/s";
        } else if (bytesPerSec >= 1000000L) {
            return decimalFormat.format((double) bytesPerSec / 1000000L) + " MB/s";
        } else if (bytesPerSec >= 1000L) {
            return (bytesPerSec / 1000L) + " KB/s";
        } else {
            return bytesPerSec + " B/s";
        }
    }

    private static class SpeedUnit {
        String value;
        String unit;
        SpeedUnit(String value, String unit) {
            this.value = value;
            this.unit = unit;
        }
    }

    private SpeedUnit formatSpeedForIcon(long bytesPerSec) {
        if (bytesPerSec >= 1000000000L) {
            return new SpeedUnit(decimalFormat.format((double) bytesPerSec / 1000000000L), "GB");
        } else if (bytesPerSec >= 1000000L) {
            return new SpeedUnit(decimalFormat.format((double) bytesPerSec / 1000000L), "MB");
        } else if (bytesPerSec >= 1000L) {
            long kb = bytesPerSec / 1000L;
            if (kb > 999) kb = 999;
            return new SpeedUnit(String.valueOf(kb), "KB");
        } else {
            return new SpeedUnit("0", "KB");
        }
    }

    private String formatData(long bytes) {
        if (bytes >= 1073741824L) {
            return decimalFormat.format((double) bytes / 1073741824L) + " GB";
        } else if (bytes >= 1048576L) {
            return decimalFormat.format((double) bytes / 1048576L) + " MB";
        } else if (bytes >= 1024L) {
            return (bytes / 1024L) + " KB";
        } else {
            return bytes + " B";
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription(CHANNEL_DESC);
            channel.setShowBadge(false);
            channel.enableVibration(false);
            channel.setSound(null, null);

            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
}
