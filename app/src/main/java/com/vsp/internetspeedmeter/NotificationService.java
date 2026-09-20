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
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.SystemClock;

import com.vsp.internetspeedmeter.Room.Usage;
import com.vsp.internetspeedmeter.Room.UsageRepository;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/**
 * Builds the status-bar notification in the style of "Internet Speed Meter Lite":
 * the status-bar icon is a two-line bitmap (download speed on top, unit below),
 * the collapsed row shows download and upload speed, and the expanded row shows
 * today's usage split into mobile and Wi-Fi.
 */
public class NotificationService {
    public static final String CHANNEL_ID = "speed_meter_channel";
    public static final String CHANNEL_NAME = "Internet Speed Meter";
    public static final String CHANNEL_DESC = "Displays real-time network speed and daily usage";
    public static final String TAG = "internetspeed";
    public static final int NOTIFICATION_ID = 1;

    /** Icon canvas is square; the status bar scales it down to the icon slot. */
    private static final int ICON_SIZE = 96;
    /** Room stores one row per day, so writing it every tick is pure wear for no gain. */
    private static final long DB_WRITE_INTERVAL_MS = 10_000L;

    private static final long KB = 1024L;
    private static final long MB = KB * 1024L;
    private static final long GB = MB * 1024L;

    private Notification.Builder mBuilder;
    private NotificationManager mNotifyMgr;
    private PendingIntent pendingIntent;
    private final Context context;
    private Paint valuePaint, unitPaint;
    private UsageRepository usageRepository;
    public String myDate;
    private SimpleDateFormat df;

    private long lastDbWriteMs = 0L;
    private String lastMobileStr = "", lastWifiStr = "", lastTotalStr = "";

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

        mBuilder.setSmallIcon(getIcon("0", "B/s"))
                .setContentTitle(speedLine(0, 0))
                .setContentText(usageLine(0, 0))
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(pendingIntent)
                .setOnlyAlertOnce(true);

        mBuilder.setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_SERVICE);
        // Phones below Oreo have no channels, so the priority has to be set here to
        // keep the row silent and parked at the bottom of the shade.
        mBuilder.setPriority(Notification.PRIORITY_LOW);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mBuilder.setBadgeIconType(Notification.BADGE_ICON_NONE);
        }

        mNotifyMgr = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    public Notification.Builder updateNotification(long downSpeedBytes, long upSpeedBytes, long mobileBytes, long wifiBytes) {
        checkDateRollover();

        // Internet Speed Meter Lite puts the download speed alone in the status bar;
        // upload only appears in the expanded row.
        String[] icon = splitSpeed(downSpeedBytes);
        mBuilder.setSmallIcon(getIcon(icon[0], icon[1]));

        String mobileStr = formatData(mobileBytes);
        String wifiStr = formatData(wifiBytes);
        String totalStr = formatData(mobileBytes + wifiBytes);

        mBuilder.setContentTitle(speedLine(downSpeedBytes, upSpeedBytes));
        mBuilder.setContentText(usageLine(mobileBytes, wifiBytes));
        mBuilder.setSubText(totalStr + " today");
        mBuilder.setStyle(new Notification.BigTextStyle().bigText(
                "Today: " + totalStr
                        + "\nMobile: " + mobileStr
                        + "\nWi-Fi: " + wifiStr));

        persistUsage(mobileStr, wifiStr, totalStr);

        return mBuilder;
    }

    /**
     * Refreshes the already-running foreground notification. Re-posting through
     * NotificationManager once a second is far cheaper than routing every tick
     * through startForeground(), which round-trips via ActivityManager.
     */
    public void postUpdate(long downSpeedBytes, long upSpeedBytes, long mobileBytes, long wifiBytes) {
        Notification notification = updateNotification(downSpeedBytes, upSpeedBytes, mobileBytes, wifiBytes).build();
        if (mNotifyMgr != null) {
            mNotifyMgr.notify(NOTIFICATION_ID, notification);
        }
    }

    /** Collapsed row, e.g. "↓ 124 kB/s    ↑ 12 kB/s". */
    private String speedLine(long downBytes, long upBytes) {
        return "↓ " + formatSpeed(downBytes) + "    ↑ " + formatSpeed(upBytes);
    }

    private String usageLine(long mobileBytes, long wifiBytes) {
        return "Mobile " + formatData(mobileBytes) + "  •  Wi-Fi " + formatData(wifiBytes);
    }

    /**
     * The daily row only ever changes at MB granularity, so it is written at most
     * once every {@link #DB_WRITE_INTERVAL_MS} and only when a value actually moved —
     * the update loop itself ticks once a second.
     */
    private void persistUsage(String mobileStr, String wifiStr, String totalStr) {
        boolean changed = !mobileStr.equals(lastMobileStr)
                || !wifiStr.equals(lastWifiStr)
                || !totalStr.equals(lastTotalStr);
        long now = SystemClock.elapsedRealtime();
        if (!changed || now - lastDbWriteMs < DB_WRITE_INTERVAL_MS) {
            return;
        }
        lastMobileStr = mobileStr;
        lastWifiStr = wifiStr;
        lastTotalStr = totalStr;
        lastDbWriteMs = now;
        // Insert, not update: @Update is a no-op when today's row does not exist yet,
        // which is exactly the case on the first launch of a new day, so a whole day of
        // usage used to be dropped. The date is the primary key and the DAO replaces on
        // conflict, so this is an upsert.
        usageRepository.insert(new Usage(myDate, mobileStr, wifiStr, totalStr));
    }

    private void checkDateRollover() {
        String currentDate = df.format(Calendar.getInstance().getTime());
        if (!currentDate.equals(myDate)) {
            myDate = currentDate;
            lastMobileStr = lastWifiStr = lastTotalStr = "";
            lastDbWriteMs = 0L;
            usageRepository.insert(new Usage(myDate, "0 B", "0 B", "0 B"));
        }
    }

    private void setupIcon() {
        valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        valuePaint.setTextAlign(Paint.Align.CENTER);
        valuePaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        valuePaint.setColor(Color.WHITE);

        unitPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        unitPaint.setTextAlign(Paint.Align.CENTER);
        unitPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        unitPaint.setColor(Color.WHITE);
    }

    /**
     * Draws the two-line status-bar icon. A fresh bitmap is allocated per update on
     * purpose: the system reads the icon's bitmap asynchronously after the
     * notification is posted, so redrawing one shared bitmap in place makes the
     * status bar flicker between the old and new value.
     */
    public Icon getIcon(String speed, String units) {
        Bitmap bitmap = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        fitText(valuePaint, speed, 54f, ICON_SIZE - 4f);
        fitText(unitPaint, units, 38f, ICON_SIZE - 4f);

        // Two stacked lines, each vertically centred inside its own half.
        canvas.drawText(speed, ICON_SIZE / 2f, baselineIn(valuePaint, 0f, ICON_SIZE / 2f), valuePaint);
        canvas.drawText(units, ICON_SIZE / 2f, baselineIn(unitPaint, ICON_SIZE / 2f, ICON_SIZE), unitPaint);

        return Icon.createWithBitmap(bitmap);
    }

    /** Shrinks the text size until the string fits {@code maxWidth}. */
    private void fitText(Paint paint, String text, float startSize, float maxWidth) {
        float size = startSize;
        paint.setTextSize(size);
        while (size > 10f && paint.measureText(text) > maxWidth) {
            size -= 2f;
            paint.setTextSize(size);
        }
    }

    private float baselineIn(Paint paint, float top, float bottom) {
        Paint.FontMetrics fm = paint.getFontMetrics();
        return (top + bottom) / 2f - (fm.ascent + fm.descent) / 2f;
    }

    /** Speed for the notification text, e.g. "0 B/s", "124 kB/s", "1.2 MB/s". */
    private String formatSpeed(long bytesPerSec) {
        String[] parts = splitSpeed(bytesPerSec);
        return parts[0] + " " + parts[1];
    }

    /** Same numbers as {@link #formatSpeed}, split into value and unit for the icon. */
    private String[] splitSpeed(long bytesPerSec) {
        if (bytesPerSec >= GB) {
            return new String[]{scaled(bytesPerSec, GB), "GB/s"};
        } else if (bytesPerSec >= MB) {
            return new String[]{scaled(bytesPerSec, MB), "MB/s"};
        } else if (bytesPerSec >= KB) {
            return new String[]{scaled(bytesPerSec, KB), "kB/s"};
        }
        return new String[]{String.valueOf(bytesPerSec), "B/s"};
    }

    /** One decimal below 10 (1.2), whole numbers above it (124) — as ISM Lite shows them. */
    private String scaled(long bytes, long unit) {
        double value = (double) bytes / unit;
        if (value < 10d) {
            return String.format(Locale.US, "%.1f", value);
        }
        return String.valueOf(Math.round(value));
    }

    private String formatData(long bytes) {
        if (bytes >= GB) {
            return scaled(bytes, GB) + " GB";
        } else if (bytes >= MB) {
            return scaled(bytes, MB) + " MB";
        } else if (bytes >= KB) {
            return scaled(bytes, KB) + " kB";
        }
        return bytes + " B";
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
            channel.enableLights(false);
            channel.setSound(null, null);

            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
}
