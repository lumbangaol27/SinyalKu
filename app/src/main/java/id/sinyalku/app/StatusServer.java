package id.sinyalku.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Server HTTP kecil di HP hotspot. Semua jalur selain /hello butuh PIN di header X-Pin. */
final class StatusServer {
    private final Context ctx;
    private final Prefs prefs;
    private ServerSocket ss;
    private volatile boolean run;
    private ExecutorService pool;
    private final Map<String, int[]> fails = new HashMap<>(); // ip -> {jumlah gagal, waktu blokir selesai (detik)}
    private Ringtone ringing;
    private final Handler main = new Handler(Looper.getMainLooper());

    StatusServer(Context c) { ctx = c.getApplicationContext(); prefs = new Prefs(c); }

    synchronized boolean start() {
        if (run) return true;
        try {
            ss = new ServerSocket(Prefs.PORT, 8, null);
            ss.setReuseAddress(true);
        } catch (Throwable t) { return false; }
        run = true;
        pool = Executors.newFixedThreadPool(4);
        Thread th = new Thread(() -> {
            while (run) {
                try { final Socket s = ss.accept(); pool.execute(() -> handle(s)); } catch (Throwable t) { if (!run) break; }
            }
        }, "sinyalku-server");
        th.setDaemon(true);
        th.start();
        return true;
    }

    synchronized void stop() {
        run = false;
        try { if (ss != null) ss.close(); } catch (Throwable t) { /* abaikan */ }
        if (pool != null) pool.shutdownNow();
        stopRing();
    }

    private static boolean same(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private boolean blocked(String ip) {
        synchronized (fails) {
            int[] f = fails.get(ip);
            return f != null && f[1] > System.currentTimeMillis() / 1000;
        }
    }
    private void failed(String ip) {
        synchronized (fails) {
            int[] f = fails.get(ip);
            if (f == null) { f = new int[]{0, 0}; fails.put(ip, f); }
            if (++f[0] >= 5) { f[0] = 0; f[1] = (int) (System.currentTimeMillis() / 1000) + 60; }
        }
    }

    private void handle(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(3000);
            InputStream in = sock.getInputStream();
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int b, state = 0;
            while (head.size() < 8192 && (b = in.read()) >= 0) {
                head.write(b);
                state = (b == '\r' && (state == 0 || state == 2)) ? state + 1 : (b == '\n' && (state == 1 || state == 3)) ? state + 1 : 0;
                if (state == 4) break;
            }
            String[] lines = new String(head.toByteArray(), StandardCharsets.UTF_8).split("\r\n");
            String[] rl = lines.length > 0 ? lines[0].split(" ") : new String[0];
            if (rl.length < 2) { reply(sock.getOutputStream(), 400, "{\"error\":\"bad request\"}"); return; }
            String path = rl[1], pin = "";
            for (String l : lines) if (l.toLowerCase(Locale.ROOT).startsWith("x-pin:")) pin = l.substring(6).trim();
            String q = "";
            int qi = path.indexOf('?');
            if (qi >= 0) { q = path.substring(qi + 1); path = path.substring(0, qi); }
            InetAddress ra = sock.getInetAddress();
            String rip = ra == null ? "?" : ra.getHostAddress();

            if (path.equals("/hello")) { reply(sock.getOutputStream(), 200, new JSONObject().put("app", "SinyalKu").put("role", "hotspot").put("v", 1).toString()); return; }
            if (blocked(rip)) { reply(sock.getOutputStream(), 429, "{\"error\":\"terlalu banyak percobaan PIN, tunggu 1 menit\"}"); return; }
            if (!same(pin, prefs.pin())) { failed(rip); reply(sock.getOutputStream(), 401, "{\"error\":\"PIN salah\"}"); return; }
            synchronized (fails) { fails.remove(rip); }

            if (path.equals("/status")) {
                reply(sock.getOutputStream(), 200, Collector.get(ctx).snapshot().toString());
            } else if (path.equals("/cmd")) {
                String name = q.startsWith("name=") ? q.substring(5) : "";
                if (name.equals("ring")) { ring(); reply(sock.getOutputStream(), 200, "{\"ok\":true,\"msg\":\"HP A berbunyi 15 detik\"}"); }
                else if (name.equals("stopring")) { stopRing(); reply(sock.getOutputStream(), 200, "{\"ok\":true,\"msg\":\"Bunyi dihentikan\"}"); }
                else if (name.equals("settings")) { Notif.openHotspotSettings(ctx); reply(sock.getOutputStream(), 200, "{\"ok\":true,\"msg\":\"Notifikasi dikirim ke HP A. Minta ketuk notifikasinya.\"}"); }
                else reply(sock.getOutputStream(), 404, "{\"error\":\"perintah tidak dikenal\"}");
            } else reply(sock.getOutputStream(), 404, "{\"error\":\"tidak ada\"}");
        } catch (Throwable t) { /* koneksi putus: abaikan */ }
    }

    private static void reply(OutputStream out, int code, String json) throws Exception {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        String reason = code == 200 ? "OK" : code == 401 ? "Unauthorized" : code == 404 ? "Not Found" : code == 429 ? "Too Many Requests" : "Bad Request";
        String h = "HTTP/1.1 " + code + " " + reason + "\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: " + body.length
            + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private synchronized void ring() {
        main.post(() -> {
            try {
                stopRing();
                Uri u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
                if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
                ringing = RingtoneManager.getRingtone(ctx, u);
                if (ringing == null) return;
                ringing.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
                ringing.setLooping(true);
                ringing.play();
                main.postDelayed(this::stopRing, 15_000);
            } catch (Throwable t) { /* abaikan */ }
        });
    }

    private void stopRing() {
        main.post(() -> { try { if (ringing != null) { ringing.stop(); ringing = null; } } catch (Throwable t) { /* abaikan */ } });
    }
}
