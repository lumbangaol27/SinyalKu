package id.sinyalku.app;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import org.json.JSONObject;

/** Layanan latar di HP hotspot: menjalankan server status dan menjaga HP tetap aktif. */
public final class HotspotService extends Service {
    static volatile boolean running;
    private StatusServer server;
    private PowerManager.WakeLock wl;
    private WifiManager.WifiLock wifiLock;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            try {
                JSONObject s = Collector.get(HotspotService.this).snapshot();
                JSONObject sig = s.optJSONObject("signal"), hs = s.optJSONObject("hotspot");
                String t = (hs != null && hs.optBoolean("on") ? "Hotspot menyala" : "Hotspot mati")
                    + " · sinyal " + (sig != null && sig.optInt("dbm", -999) > -200 ? sig.optInt("dbm") + " dBm" : Logic.levelName(sig == null ? -1 : sig.optInt("level", -1)))
                    + " · PIN " + new Prefs(HotspotService.this).pin();
                ((android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(Notif.ID_SERVICE, Notif.service(HotspotService.this, "SinyalKu: HP hotspot aktif", t));
            } catch (Throwable e) { /* abaikan */ }
            h.postDelayed(this, 30_000);
        }
    };

    static void start(Context c) {
        Intent i = new Intent(c, HotspotService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }
    static void stop(Context c) { c.stopService(new Intent(c, HotspotService.class)); }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Notif.channels(this);
        android.app.Notification n = Notif.service(this, "SinyalKu: HP hotspot aktif", "Menyiapkan…");
        if (Build.VERSION.SDK_INT >= 34) startForeground(Notif.ID_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(Notif.ID_SERVICE, n);
        if (server == null) {
            server = new StatusServer(this);
            running = server.start();
            Collector.get(this).startListening();
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sinyalku:hotspot");
            wl.setReferenceCounted(false);
            wl.acquire();
            try {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "sinyalku:hotspot");
                wifiLock.acquire();
            } catch (Throwable t) { /* abaikan */ }
            h.post(tick);
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        running = false;
        h.removeCallbacksAndMessages(null);
        if (server != null) server.stop();
        try { if (wl != null && wl.isHeld()) wl.release(); } catch (Throwable t) { /* abaikan */ }
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Throwable t) { /* abaikan */ }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
