package id.sinyalku.app;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.TrafficStats;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.telephony.CellSignalStrength;
import android.telephony.PhoneStateListener;
import android.telephony.SignalStrength;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;

/** Mengumpulkan status HP hotspot: sinyal, baterai, hotspot, perangkat tersambung, dan data. */
final class Collector {
    private static Collector inst;
    private final Context ctx;
    private final Prefs prefs;
    private volatile int sigLevel = -1, sigDbm = -999;
    private boolean listening;
    private long lastT, lastRx = -1, lastTx = -1, lastHsRx = -1, lastHsTx = -1;
    private double rxBps = -1, txBps = -1, hsRxBps = -1, hsTxBps = -1;
    private long sessionStartRx = -1, sessionStartTx = -1;

    static synchronized Collector get(Context c) {
        if (inst == null) inst = new Collector(c.getApplicationContext());
        return inst;
    }

    private Collector(Context c) { ctx = c; prefs = new Prefs(c); }

    // ------------------------------------------------------------ sinyal seluler

    private void applySignal(SignalStrength s) {
        if (s == null) return;
        int dbm = -999, level = -1;
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                int best = Integer.MIN_VALUE;
                for (CellSignalStrength c : s.getCellSignalStrengths()) {
                    int d = c.getDbm();
                    if (d < 0 && d > -200 && d > best) best = d;
                }
                if (best != Integer.MIN_VALUE) dbm = best;
            }
            level = s.getLevel();
        } catch (Throwable t) { /* biarkan tidak diketahui */ }
        sigLevel = level; sigDbm = dbm;
    }

    synchronized void startListening() {
        if (listening) return;
        listening = true;
        final TelephonyManager tm = (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
        if (tm == null) return;
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                if (Build.VERSION.SDK_INT >= 31) {
                    Executor ex = ctx.getMainExecutor();
                    tm.registerTelephonyCallback(ex, new SigCb());
                } else {
                    tm.listen(new PhoneStateListener() {
                        @Override public void onSignalStrengthsChanged(SignalStrength s) { applySignal(s); }
                    }, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS);
                }
            } catch (Throwable t) { listening = false; }
        });
        try { if (Build.VERSION.SDK_INT >= 28) applySignal(tm.getSignalStrength()); } catch (Throwable t) { /* abaikan */ }
    }

    private final class SigCb extends TelephonyCallback implements TelephonyCallback.SignalStrengthsListener {
        @Override public void onSignalStrengthsChanged(SignalStrength s) { applySignal(s); }
    }

    private String netType(TelephonyManager tm) {
        int t = TelephonyManager.NETWORK_TYPE_UNKNOWN;
        try { t = Build.VERSION.SDK_INT >= 30 ? tm.getDataNetworkType() : tm.getNetworkType(); } catch (Throwable e) { /* tanpa izin */ }
        switch (t) {
            case 20: return "5G";
            case TelephonyManager.NETWORK_TYPE_LTE: return "4G";
            case TelephonyManager.NETWORK_TYPE_HSPAP: case TelephonyManager.NETWORK_TYPE_HSDPA: case TelephonyManager.NETWORK_TYPE_HSUPA:
            case TelephonyManager.NETWORK_TYPE_HSPA: case TelephonyManager.NETWORK_TYPE_UMTS: case TelephonyManager.NETWORK_TYPE_EVDO_0:
            case TelephonyManager.NETWORK_TYPE_EVDO_A: case TelephonyManager.NETWORK_TYPE_EVDO_B: return "3G";
            case TelephonyManager.NETWORK_TYPE_EDGE: case TelephonyManager.NETWORK_TYPE_GPRS: case TelephonyManager.NETWORK_TYPE_CDMA:
            case TelephonyManager.NETWORK_TYPE_1xRTT: return "2G";
            default: return "?";
        }
    }

    // ------------------------------------------------------------ hotspot dan perangkat

    /** Mencari antarmuka hotspot yang aktif. Mengembalikan {nama, ip} atau null. */
    String[] hotspotIface() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || !Logic.isHotspotIface(ni.getName())) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) return new String[]{ni.getName(), a.getHostAddress()};
                }
            }
        } catch (Throwable t) { /* abaikan */ }
        return null;
    }

    private static String readAll(BufferedReader r) throws Exception {
        StringBuilder sb = new StringBuilder(); String l;
        while ((l = r.readLine()) != null) sb.append(l).append('\n');
        return sb.toString();
    }

    private List<Logic.Client> clients(String iface) {
        List<Logic.Client> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/net/arp"))) { out.addAll(Logic.parseArp(readAll(r))); } catch (Throwable t) { /* tidak terbaca */ }
        if (out.isEmpty()) {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"ip", "neigh", "show"});
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) { out.addAll(Logic.parseIpNeigh(readAll(r))); }
                p.destroy();
            } catch (Throwable t) { /* tidak tersedia */ }
        }
        List<Logic.Client> f = new ArrayList<>();
        for (Logic.Client c : out) if (iface == null || c.dev.equals(iface)) f.add(c);
        return f;
    }

    private static long sys(String iface, String file) {
        try (BufferedReader r = new BufferedReader(new FileReader(new File("/sys/class/net/" + iface + "/statistics/" + file)))) {
            return Long.parseLong(r.readLine().trim());
        } catch (Throwable t) { return -1; }
    }

    // ------------------------------------------------------------ data harian

    private long todayBytes(long totalNow) {
        String day = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
        android.content.SharedPreferences sp = prefs.raw();
        String savedDay = sp.getString("dataDay", "");
        long today = sp.getLong("dataToday", 0), last = sp.getLong("dataLast", -1);
        if (!day.equals(savedDay)) { today = 0; last = -1; }
        if (totalNow >= 0) {
            if (last >= 0) today += totalNow >= last ? totalNow - last : totalNow; // HP dinyalakan ulang: hitungan mulai dari nol lagi
            last = totalNow;
        }
        sp.edit().putString("dataDay", day).putLong("dataToday", today).putLong("dataLast", last).apply();
        return today;
    }

    // ------------------------------------------------------------ gambaran lengkap

    synchronized JSONObject snapshot() {
        JSONObject o = new JSONObject();
        try {
            o.put("app", "SinyalKu").put("v", 1).put("ts", System.currentTimeMillis()).put("device", Build.MODEL);

            Intent b = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            JSONObject bat = new JSONObject();
            if (b != null) {
                int lv = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), sc = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                int st = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                bat.put("pct", lv < 0 ? -1 : Math.round(lv * 100f / sc));
                bat.put("charging", st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL);
                bat.put("tempC", b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0);
            }
            o.put("battery", bat);

            TelephonyManager tm = (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
            JSONObject sig = new JSONObject();
            sig.put("level", sigLevel).put("dbm", sigDbm);
            if (tm != null) { sig.put("type", netType(tm)); sig.put("operator", String.valueOf(tm.getNetworkOperatorName())); }
            o.put("signal", sig);

            String[] hs = hotspotIface();
            JSONObject hot = new JSONObject();
            hot.put("on", hs != null);
            JSONArray arr = new JSONArray();
            boolean known = false;
            if (hs != null) {
                hot.put("iface", hs[0]).put("ip", hs[1]);
                List<Logic.Client> cl = clients(hs[0]);
                for (Logic.Client c : cl) arr.put(new JSONObject().put("ip", c.ip).put("mac", c.mac));
                known = !cl.isEmpty();
                long hrx = sys(hs[0], "rx_bytes"), htx = sys(hs[0], "tx_bytes");
                hot.put("rxBytes", hrx).put("txBytes", htx);
            }
            hot.put("clients", arr).put("clientsKnown", known);
            o.put("hotspot", hot);

            long now = System.currentTimeMillis();
            long rx = TrafficStats.getMobileRxBytes(), tx = TrafficStats.getMobileTxBytes();
            if (sessionStartRx < 0 && rx >= 0) { sessionStartRx = rx; sessionStartTx = tx; }
            if (lastT > 0 && now - lastT > 500) {
                double dt = (now - lastT) / 1000.0;
                if (rx >= 0 && lastRx >= 0 && rx >= lastRx) rxBps = (rx - lastRx) / dt;
                if (tx >= 0 && lastTx >= 0 && tx >= lastTx) txBps = (tx - lastTx) / dt;
            }
            if (now - lastT > 500) { lastT = now; lastRx = rx; lastTx = tx; }
            JSONObject data = new JSONObject();
            long total = (rx >= 0 && tx >= 0) ? rx + tx : -1;
            data.put("todayBytes", todayBytes(total));
            data.put("sessionBytes", (rx >= 0 && sessionStartRx >= 0) ? (rx - sessionStartRx) + (tx - sessionStartTx) : -1);
            data.put("rxBps", rxBps).put("txBps", txBps);
            o.put("data", data);
        } catch (Throwable t) {
            try { o.put("error", String.valueOf(t)); } catch (Throwable ignore) { /* abaikan */ }
        }
        return o;
    }
}
