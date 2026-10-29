package id.sinyalku.app;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Logika murni (tanpa Android) supaya bisa diuji di komputer. */
public final class Logic {
    private Logic() {}

    public static final class Client {
        public final String ip, mac, dev;
        public Client(String ip, String mac, String dev) { this.ip = ip; this.mac = mac; this.dev = dev; }
    }

    private static boolean okMac(String m) {
        return m != null && m.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}") && !m.equals("00:00:00:00:00:00");
    }

    /** Isi /proc/net/arp. Hanya baris lengkap (flags 0x2) dengan MAC sah. */
    public static List<Client> parseArp(String text) {
        List<Client> out = new ArrayList<>();
        if (text == null) return out;
        for (String line : text.split("\n")) {
            String[] p = line.trim().split("\\s+");
            if (p.length < 6 || !p[0].matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) continue;
            if (!"0x2".equals(p[2]) || !okMac(p[3])) continue;
            out.add(new Client(p[0], p[3].toLowerCase(Locale.ROOT), p[5]));
        }
        return out;
    }

    /** Keluaran `ip neigh`. Baris FAILED/INCOMPLETE dilewati. */
    public static List<Client> parseIpNeigh(String text) {
        List<Client> out = new ArrayList<>();
        if (text == null) return out;
        for (String line : text.split("\n")) {
            String[] p = line.trim().split("\\s+");
            if (p.length < 4 || !p[0].matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) continue;
            String dev = "", mac = null, state = p[p.length - 1];
            for (int i = 1; i < p.length - 1; i++) {
                if ("dev".equals(p[i])) dev = p[i + 1];
                if ("lladdr".equals(p[i])) mac = p[i + 1];
            }
            if (!okMac(mac) || "FAILED".equals(state) || "INCOMPLETE".equals(state)) continue;
            out.add(new Client(p[0], mac.toLowerCase(Locale.ROOT), dev));
        }
        return out;
    }

    /** Nama antarmuka yang biasanya dipakai hotspot atau tethering. */
    public static boolean isHotspotIface(String name) {
        if (name == null) return false;
        return name.matches("(ap|swlan|softap|wlan)[0-9]+") && !name.equals("wlan0")
            || name.matches("(rndis|usb|ncm|bt-pan|eth)[0-9]*");
    }

    /** Kekuatan sinyal seluler 0 (nol) sampai 4 dari dBm; -1 kalau tidak diketahui. */
    public static int levelFromDbm(int dbm) {
        if (dbm >= 0 || dbm <= -200) return -1;
        if (dbm >= -85) return 4;
        if (dbm >= -95) return 3;
        if (dbm >= -105) return 2;
        if (dbm >= -115) return 1;
        return 0;
    }

    public static String levelName(int level) {
        switch (level) {
            case 4: return "Sangat baik";
            case 3: return "Baik";
            case 2: return "Sedang";
            case 1: return "Lemah";
            case 0: return "Sangat lemah";
            default: return "Tidak diketahui";
        }
    }

    public static String bars(int level) {
        String[] b = {"▂", "▄", "▆", "█"};
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 4; i++) s.append(level > i ? b[i] : "·");
        return s.toString();
    }

    /** Kualitas sinyal WiFi dari RSSI. */
    public static String wifiQuality(int rssi) {
        if (rssi >= 0 || rssi < -127) return "Tidak diketahui";
        if (rssi >= -55) return "Sangat baik";
        if (rssi >= -67) return "Baik";
        if (rssi >= -75) return "Sedang";
        return "Lemah";
    }

    public static String fmtBytes(long b) {
        if (b < 0) return "-";
        if (b < 1024) return b + " B";
        double v = b / 1024.0;
        if (v < 1024) return String.format(Locale.US, "%.0f KB", v);
        v /= 1024;
        if (v < 1024) return String.format(Locale.US, "%.1f MB", v);
        return String.format(Locale.US, "%.2f GB", v / 1024);
    }

    public static String fmtRate(double bytesPerSec) {
        if (bytesPerSec < 0) return "-";
        double bits = bytesPerSec * 8;
        if (bits < 1000) return String.format(Locale.US, "%.0f bps", bits);
        if (bits < 1_000_000) return String.format(Locale.US, "%.0f kbps", bits / 1000);
        return String.format(Locale.US, "%.1f Mbps", bits / 1_000_000);
    }

    /** Alamat IPv4 dari bilangan bulat urutan little-endian (format DhcpInfo Android). */
    public static String ipFromInt(int v) {
        return (v & 0xff) + "." + ((v >> 8) & 0xff) + "." + ((v >> 16) & 0xff) + "." + ((v >> 24) & 0xff);
    }

    public static boolean validIp(String s) {
        if (s == null || !s.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return false;
        for (String p : s.split("\\.")) if (Integer.parseInt(p) > 255) return false;
        return true;
    }

    // ---------------------------------------------------------------- peringatan

    /** Satu pembacaan status HP hotspot. reachable=false berarti HP tidak menjawab. */
    public static final class Reading {
        public boolean reachable;
        public int level = -1, dbm = -999, battPct = -1;
        public boolean charging, hotspotOn = true;
        public long dataTodayBytes;
    }

    public static final class Rules {
        public int weakDbm = -105, battPct = 20;
        public long dataLimitMb = 0; // 0 = mati
        public boolean notifyDown = true, notifyWeak = true, notifyBatt = true, notifyData = true, notifyHotspot = true;
    }

    public static final class Alert {
        public final String key, title, text;
        public final boolean urgent;
        public Alert(String key, String title, String text, boolean urgent) { this.key = key; this.title = title; this.text = text; this.urgent = urgent; }
    }

    /** Menyaring pembacaan menjadi peringatan: tidak berisik (butuh 3 kali berturut-turut, ada jeda ulang). */
    public static final class AlertEngine {
        private int fails, weak, hsOff;
        private boolean down;
        private final Map<String, Long> last = new HashMap<>();

        private boolean cool(String key, long now, long ms) {
            Long t = last.get(key);
            if (t != null && now - t < ms) return false;
            last.put(key, now);
            return true;
        }

        public boolean isDown() { return down; }

        public List<Alert> update(Reading r, Rules rules, long now) {
            List<Alert> out = new ArrayList<>();
            if (r == null || !r.reachable) {
                fails++; weak = 0; hsOff = 0;
                if (fails >= 3 && !down) {
                    down = true;
                    if (rules.notifyDown) out.add(new Alert("down", "HP hotspot tidak terjangkau", "HP A tidak menjawab 3 kali berturut-turut. Hotspot mungkin mati, HP kehabisan baterai, atau di luar jangkauan.", true));
                }
                return out;
            }
            fails = 0;
            if (down) {
                down = false;
                if (rules.notifyDown) out.add(new Alert("up", "HP hotspot terhubung lagi", "HP A sudah bisa dijangkau kembali.", false));
            }
            boolean weakNow = (r.dbm > -200 && r.dbm <= rules.weakDbm) || (r.dbm <= -200 && r.level >= 0 && r.level <= 1);
            weak = weakNow ? weak + 1 : 0;
            if (rules.notifyWeak && weak >= 3 && cool("weak", now, 10 * 60_000L)) {
                String v = r.dbm > -200 ? r.dbm + " dBm" : levelName(r.level);
                out.add(new Alert("weak", "Sinyal seluler HP hotspot lemah", "Sinyal sekarang " + v + ". Koneksi internet hotspot bisa lambat atau putus.", false));
            }
            hsOff = r.hotspotOn ? 0 : hsOff + 1;
            if (rules.notifyHotspot && hsOff >= 3 && cool("hs", now, 10 * 60_000L)) {
                out.add(new Alert("hs", "Hotspot di HP A mati", "HP A terjangkau, tetapi hotspotnya tidak menyala.", true));
            }
            if (rules.notifyBatt && !r.charging && r.battPct >= 0 && r.battPct <= rules.battPct && cool("batt", now, 15 * 60_000L)) {
                out.add(new Alert("batt", "Baterai HP hotspot hampir habis", "Baterai HP A tinggal " + r.battPct + "% dan tidak sedang diisi.", true));
            }
            if (rules.notifyData && rules.dataLimitMb > 0 && r.dataTodayBytes >= rules.dataLimitMb * 1024L * 1024L && cool("data", now, 6 * 3600_000L)) {
                out.add(new Alert("data", "Batas data harian tercapai", "Pemakaian data seluler HP A hari ini " + fmtBytes(r.dataTodayBytes) + " (batas " + rules.dataLimitMb + " MB).", false));
            }
            return out;
        }
    }
}
