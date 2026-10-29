package id.sinyalku.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;

/** Penyimpanan pengaturan sederhana. */
final class Prefs {
    static final int PORT = 8765;
    private final SharedPreferences sp;

    Prefs(Context c) { sp = c.getApplicationContext().getSharedPreferences("sinyalku", Context.MODE_PRIVATE); }

    String mode() { return sp.getString("mode", ""); }               // "A" = HP hotspot, "B" = HP pemantau
    void mode(String m) { sp.edit().putString("mode", m).apply(); }

    /** PIN pemasangan HP hotspot: dibuat sekali, 6 angka. */
    String pin() {
        String p = sp.getString("pin", "");
        if (p.length() != 6) {
            p = String.format("%06d", new SecureRandom().nextInt(1_000_000));
            sp.edit().putString("pin", p).apply();
        }
        return p;
    }
    void newPin() { sp.edit().remove("pin").apply(); }

    String targetIp() { return sp.getString("ip", ""); }
    void targetIp(String v) { sp.edit().putString("ip", v).apply(); }
    String targetPin() { return sp.getString("tpin", ""); }
    void targetPin(String v) { sp.edit().putString("tpin", v).apply(); }

    int intervalSec() { return sp.getInt("interval", 3); }
    void intervalSec(int v) { sp.edit().putInt("interval", Math.max(2, Math.min(60, v))).apply(); }

    Logic.Rules rules() {
        Logic.Rules r = new Logic.Rules();
        r.weakDbm = sp.getInt("weakDbm", -105);
        r.battPct = sp.getInt("battPct", 20);
        r.dataLimitMb = sp.getLong("dataMb", 0);
        r.notifyDown = sp.getBoolean("nDown", true);
        r.notifyWeak = sp.getBoolean("nWeak", true);
        r.notifyBatt = sp.getBoolean("nBatt", true);
        r.notifyData = sp.getBoolean("nData", true);
        r.notifyHotspot = sp.getBoolean("nHot", true);
        return r;
    }
    void rules(Logic.Rules r) {
        sp.edit().putInt("weakDbm", r.weakDbm).putInt("battPct", r.battPct).putLong("dataMb", r.dataLimitMb)
            .putBoolean("nDown", r.notifyDown).putBoolean("nWeak", r.notifyWeak).putBoolean("nBatt", r.notifyBatt)
            .putBoolean("nData", r.notifyData).putBoolean("nHot", r.notifyHotspot).apply();
    }

    // Hitungan pemakaian data seluler per hari (di HP hotspot).
    SharedPreferences raw() { return sp; }
}
