package io.lslegacy;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.os.Build;

/** Notification builder that works from Android 2.3 (no Notification.Builder) upward. */
final class Notifs {
    private Notifs() {}

    static Notification build(Context ctx, String title, String text, PendingIntent pi, boolean ongoing,
                              boolean alert, int progressMax, int progress) {
        if (Build.VERSION.SDK_INT >= 11) return modern(ctx, title, text, pi, ongoing, alert, progressMax, progress);
        Notification n = new Notification(R.drawable.ic_stat, alert ? title : null, System.currentTimeMillis());
        if (ongoing) n.flags |= Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR;
        else n.flags |= Notification.FLAG_AUTO_CANCEL;
        if (alert) n.defaults |= Notification.DEFAULT_ALL;
        n.contentIntent = pi;
        try {
            // Removed from the SDK in API 23 but present on the devices that take this path.
            Notification.class.getMethod("setLatestEventInfo", Context.class, CharSequence.class, CharSequence.class,
                    PendingIntent.class).invoke(n, ctx, title, text, pi);
        } catch (Exception ignored) {
        }
        return n;
    }

    @TargetApi(16)
    @SuppressWarnings("deprecation")
    private static Notification modern(Context ctx, String title, String text, PendingIntent pi, boolean ongoing,
                                       boolean alert, int progressMax, int progress) {
        Notification.Builder b = new Notification.Builder(ctx)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(ongoing)
                .setAutoCancel(!ongoing);
        if (alert) b.setDefaults(Notification.DEFAULT_ALL).setTicker(title);
        if (progressMax > 0 && Build.VERSION.SDK_INT >= 14) b.setProgress(progressMax, progress, false);
        return Build.VERSION.SDK_INT >= 16 ? b.build() : b.getNotification();
    }
}
