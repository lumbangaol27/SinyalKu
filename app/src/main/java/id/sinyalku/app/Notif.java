package id.sinyalku.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;

/** Saluran dan pembuat notifikasi. */
final class Notif {
    static final String CH_SERVICE = "layanan", CH_ALERT = "peringatan";
    static final int ID_SERVICE = 1, ID_ALERT_BASE = 100, ID_SETTINGS = 50;

    private Notif() {}

    static void channels(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel s = new NotificationChannel(CH_SERVICE, "Layanan berjalan", NotificationManager.IMPORTANCE_LOW);
        s.setDescription("Menunjukkan SinyalKu sedang aktif di latar belakang");
        nm.createNotificationChannel(s);
        NotificationChannel a = new NotificationChannel(CH_ALERT, "Peringatan", NotificationManager.IMPORTANCE_HIGH);
        a.setDescription("Peringatan sinyal, hotspot, baterai, dan data");
        nm.createNotificationChannel(a);
    }

    static PendingIntent openApp(Context c) {
        Intent i = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static Notification service(Context c, String title, String text) {
        return new Notification.Builder(c, CH_SERVICE).setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(title).setContentText(text).setOngoing(true).setContentIntent(openApp(c)).build();
    }

    static void alert(Context c, int id, String title, String text, boolean urgent) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder b = new Notification.Builder(c, CH_ALERT).setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true).setContentIntent(openApp(c));
        if (urgent) b.setCategory(Notification.CATEGORY_ALARM);
        nm.notify(id, b.build());
    }

    /** Dipakai HP hotspot saat HP pemantau meminta pengaturan hotspot dibuka (Android melarang membukanya dari latar). */
    static void openHotspotSettings(Context c) {
        Intent i = new Intent();
        i.setClassName("com.android.settings", "com.android.settings.TetherSettings");
        if (i.resolveActivity(c.getPackageManager()) == null) i = new Intent(Settings.ACTION_WIRELESS_SETTINGS);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(c, 1, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        Notification n = new Notification.Builder(c, CH_ALERT).setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Diminta membuka pengaturan hotspot")
            .setContentText("Ketuk di sini untuk membuka pengaturan hotspot, lalu nyalakan atau matikan.")
            .setAutoCancel(true).setContentIntent(pi).setCategory(Notification.CATEGORY_CALL).build();
        nm.notify(ID_SETTINGS, n);
    }
}
