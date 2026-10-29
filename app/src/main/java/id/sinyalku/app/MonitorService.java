package id.sinyalku.app;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import org.json.JSONObject;

import java.util.List;

/** Layanan latar di HP pemantau: membaca status HP hotspot berkala dan mengirim peringatan. */
public final class MonitorService extends Service {
    private volatile boolean go;
    private Thread th;
    private PowerManager.WakeLock wl;
    private WifiManager.WifiLock wifiLock;

    static void start(Context c) {
        Intent i = new Intent(c, MonitorService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }
    static void stop(Context c) { c.stopService(new Intent(c, MonitorService.class)); }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Notif.channels(this);
        android.app.Notification n = Notif.service(this, "SinyalKu: memantau HP hotspot", "Menghubungkan…");
        if (Build.VERSION.SDK_INT >= 34) startForeground(Notif.ID_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(Notif.ID_SERVICE, n);
        if (th == null) {
            go = true; Monitor.running = true;
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sinyalku:monitor");
            wl.setReferenceCounted(false); wl.acquire();
            try {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "sinyalku:monitor");
                wifiLock.acquire();
            } catch (Throwable t) { /* abaikan */ }
            th = new Thread(this::loop, "sinyalku-monitor");
            th.start();
        }
        return START_STICKY;
    }

    private void loop() {
        Prefs prefs = new Prefs(this);
        Logic.AlertEngine engine = new Logic.AlertEngine();
        int alertId = Notif.ID_ALERT_BASE;
        while (go) {
            String ip = prefs.targetIp();
            if (!Logic.validIp(ip)) ip = MainActivity.gatewayIp(this);
            Monitor.Result r = Logic.validIp(ip) ? Monitor.call(ip, prefs.targetPin(), "/status", 2500, 3500) : null;
            try {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo wi = wm.getConnectionInfo();
                Monitor.wifiRssi = wi == null ? 0 : wi.getRssi();
            } catch (Throwable t) { Monitor.wifiRssi = 0; }

            JSONObject status = null;
            if (r == null) { Monitor.http = -1; Monitor.error = "Alamat HP hotspot belum diketahui. Sambungkan ke WiFi hotspot atau isi alamatnya."; }
            else {
                Monitor.http = r.code;
                if (r.code == 200) {
                    try { status = new JSONObject(r.body); Monitor.last = status; Monitor.lastOkMs = System.currentTimeMillis(); Monitor.latencyMs = r.ms; Monitor.error = ""; }
                    catch (Throwable t) { Monitor.error = "Jawaban tidak dikenali"; }
                } else if (r.code == 401) Monitor.error = "PIN salah. Lihat PIN di layar HP hotspot.";
                else if (r.code == 429) Monitor.error = "Terlalu banyak percobaan PIN. Tunggu 1 menit.";
                else { Monitor.error = "Tidak terjangkau. Pastikan terhubung ke hotspot HP A dan SinyalKu HP A aktif."; Monitor.latencyMs = -1; }
            }
            // PIN salah bukan "HP putus": jangan memicu peringatan putus.
            if (Monitor.http != 401 && Monitor.http != 429) {
                List<Logic.Alert> al = engine.update(Monitor.toReading(status), prefs.rules(), System.currentTimeMillis());
                for (Logic.Alert a : al) Notif.alert(this, alertId + (a.key.hashCode() & 0xff), a.title, a.text, a.urgent);
            }
            try {
                String text;
                if (status != null) {
                    JSONObject sig = status.optJSONObject("signal"), hot = status.optJSONObject("hotspot");
                    int dbm = sig == null ? -999 : sig.optInt("dbm", -999);
                    text = (dbm > -200 ? dbm + " dBm" : Logic.levelName(sig == null ? -1 : sig.optInt("level", -1)))
                        + " · " + (sig == null ? "?" : sig.optString("type", "?"))
                        + " · " + (hot != null && hot.optBoolean("on") ? "hotspot nyala" : "hotspot mati");
                } else text = Monitor.http == 401 ? "PIN salah" : engine.isDown() ? "HP hotspot tidak terjangkau" : "Menghubungkan…";
                ((android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(Notif.ID_SERVICE, Notif.service(this, "SinyalKu: memantau HP hotspot", text));
            } catch (Throwable t) { /* abaikan */ }
            try { Thread.sleep(Math.max(2, prefs.intervalSec()) * 1000L); } catch (InterruptedException e) { break; }
        }
    }

    @Override public void onDestroy() {
        go = false; Monitor.running = false;
        if (th != null) th.interrupt();
        try { if (wl != null && wl.isHeld()) wl.release(); } catch (Throwable t) { /* abaikan */ }
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Throwable t) { /* abaikan */ }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
