package id.sinyalku.app;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;

/** Keadaan terakhir pantauan (dibaca layar) dan pemanggilan ke HP hotspot. */
final class Monitor {
    private Monitor() {}

    static volatile boolean running;
    static volatile JSONObject last;       // status terakhir yang berhasil dibaca
    static volatile long lastOkMs;
    static volatile int latencyMs = -1;
    static volatile int http = 0;           // 0 = belum, 200 = ok, 401 = PIN salah, 429 = diblokir sementara, -1 = tidak terjangkau
    static volatile String error = "";
    static volatile int wifiRssi = 0;

    static final class Result { int code; String body = ""; int ms; }

    static Result call(String ip, String pin, String path, int connectMs, int readMs) {
        Result r = new Result();
        long t0 = System.currentTimeMillis();
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("http://" + ip + ":" + Prefs.PORT + path).openConnection();
            c.setConnectTimeout(connectMs); c.setReadTimeout(readMs);
            c.setRequestProperty("X-Pin", pin == null ? "" : pin);
            c.setUseCaches(false);
            r.code = c.getResponseCode();
            InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in != null) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[2048]; int n;
                while ((n = in.read(buf)) > 0 && bo.size() < 65536) bo.write(buf, 0, n);
                r.body = new String(bo.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (Throwable t) {
            r.code = -1; r.body = String.valueOf(t.getMessage());
        } finally { if (c != null) c.disconnect(); }
        r.ms = (int) (System.currentTimeMillis() - t0);
        return r;
    }

    /** Mengubah status JSON menjadi pembacaan untuk mesin peringatan. */
    static Logic.Reading toReading(JSONObject s) {
        Logic.Reading r = new Logic.Reading();
        if (s == null) return r;
        r.reachable = true;
        JSONObject sig = s.optJSONObject("signal"), bat = s.optJSONObject("battery"), hot = s.optJSONObject("hotspot"), data = s.optJSONObject("data");
        if (sig != null) { r.level = sig.optInt("level", -1); r.dbm = sig.optInt("dbm", -999); }
        if (bat != null) { r.battPct = bat.optInt("pct", -1); r.charging = bat.optBoolean("charging"); }
        if (hot != null) r.hotspotOn = hot.optBoolean("on", true);
        if (data != null) r.dataTodayBytes = Math.max(0, data.optLong("todayBytes", 0));
        return r;
    }
}
