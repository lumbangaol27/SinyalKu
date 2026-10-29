package id.sinyalku.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.wifi.DhcpInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Layar tunggal dengan tiga tampilan: pilih peran, HP hotspot (A), dan HP pemantau (B). Dibuat lewat kode agar tanpa pustaka tambahan. */
public final class MainActivity extends Activity {
    private static final int C_BG = 0xFFF1F5F3, C_TEXT = 0xFF14201C, C_MUTED = 0xFF5A6A65, C_PRIMARY = 0xFF0F6E5A,
        C_OK = 0xFF1B7F3B, C_BAD = 0xFFB3261E, C_WARN = 0xFFA85F00;

    private Prefs prefs;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private ScrollView scroll;
    private LinearLayout root;
    private Runnable ticker;

    // tampilan HP hotspot
    private TextView aSvc, aLive, aAddr;
    private Button aToggle;
    private volatile String aLiveText = "";
    private volatile boolean aBusy;
    // tampilan HP pemantau
    private TextView bConn, bSig, bBat, bHot, bData, bWifi;
    private EditText bIp, bPin;
    private Button bToggle;

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        Notif.channels(this);
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(C_BG);
        scroll.setFillViewport(true);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(20), dp(16), dp(28));
        scroll.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
        show();
    }

    @Override protected void onResume() { super.onResume(); startTicker(); }
    @Override protected void onPause() { super.onPause(); if (ticker != null) h.removeCallbacks(ticker); }

    @Override protected void onDestroy() { super.onDestroy(); bg.shutdownNow(); }

    private void startTicker() {
        if (ticker != null) h.removeCallbacks(ticker);
        ticker = new Runnable() {
            @Override public void run() {
                try { tick(); } catch (Throwable t) { /* abaikan */ }
                h.postDelayed(this, 1000);
            }
        };
        h.post(ticker);
    }

    // ------------------------------------------------------------------ pembantu tampilan

    private TextView tv(String t, int sp, boolean bold, int color) {
        TextView v = new TextView(this);
        v.setText(t); v.setTextSize(sp); v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    private LinearLayout card(String title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(14));
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.WHITE); g.setCornerRadius(dp(14)); g.setStroke(dp(1), 0xFFD5DFDB);
        c.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(10));
        root.addView(c, lp);
        if (title != null) { TextView t = tv(title.toUpperCase(), 12, true, C_MUTED); t.setLetterSpacing(0.06f); c.addView(t); }
        return c;
    }

    private void gap(LinearLayout c, int d) { View v = new View(this); c.addView(v, new LinearLayout.LayoutParams(1, dp(d))); }

    private Button btn(String text, boolean primary, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text); b.setAllCaps(false); b.setTextSize(15); b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(primary ? Color.WHITE : C_PRIMARY);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(12));
        if (primary) g.setColor(C_PRIMARY); else { g.setColor(Color.WHITE); g.setStroke(dp(2), C_PRIMARY); }
        b.setBackground(g);
        b.setMinHeight(dp(50)); b.setMinimumHeight(dp(50));
        b.setOnClickListener(l);
        return b;
    }

    private void addBtn(LinearLayout c, Button b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(8), 0, 0);
        c.addView(b, lp);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    // ------------------------------------------------------------------ navigasi

    private void show() {
        root.removeAllViews();
        aSvc = aLive = aAddr = bConn = bSig = bBat = bHot = bData = bWifi = null;
        String m = prefs.mode();
        if ("A".equals(m)) buildA(); else if ("B".equals(m)) buildB(); else buildPicker();
        scroll.scrollTo(0, 0);
    }

    private void askPerms() {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.POST_NOTIFICATIONS);
        if ("A".equals(prefs.mode()) && checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.READ_PHONE_STATE);
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), 1);
    }

    private void buildPicker() {
        root.addView(tv("SinyalKu", 30, true, C_PRIMARY));
        root.addView(tv("Pantau sinyal dan hotspot satu HP dari HP lain. Pasang aplikasi ini di dua HP milikmu, lalu pilih peran tiap HP.", 15, false, C_MUTED));
        gap(root, 16);
        LinearLayout a = card("HP hotspot (HP A)");
        a.addView(tv("HP yang menyalakan hotspot dan menyimpan kartu SIM. Aplikasi akan melaporkan sinyal, baterai, dan perangkat yang tersambung.", 14, false, C_TEXT));
        addBtn(a, btn("Pakai HP ini sebagai HP hotspot", true, v -> { prefs.mode("A"); askPerms(); show(); startTicker(); }));
        LinearLayout b = card("HP pemantau (HP B)");
        b.addView(tv("HP yang tersambung ke hotspot HP A. Menampilkan status HP A dan memberi peringatan kalau ada masalah.", 14, false, C_TEXT));
        addBtn(b, btn("Pakai HP ini sebagai HP pemantau", true, v -> { prefs.mode("B"); askPerms(); show(); startTicker(); }));
    }

    // ------------------------------------------------------------------ HP hotspot (A)

    private void buildA() {
        root.addView(tv("HP Hotspot", 26, true, C_PRIMARY));
        root.addView(tv("HP ini melaporkan keadaannya ke HP pemantau.", 14, false, C_MUTED));
        gap(root, 12);

        LinearLayout s = card("Layanan");
        aSvc = tv("", 18, true, C_TEXT);
        s.addView(aSvc);
        aToggle = btn("Mulai layanan", true, v -> {
            if (HotspotService.running) { HotspotService.stop(this); }
            else { askPerms(); HotspotService.start(this); }
            h.postDelayed(this::tick, 600);
        });
        addBtn(s, aToggle);

        LinearLayout p = card("PIN pemasangan");
        TextView pin = tv(prefs.pin(), 38, true, C_TEXT);
        pin.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        pin.setLetterSpacing(0.15f);
        p.addView(pin);
        p.addView(tv("Masukkan PIN ini di HP pemantau. Tanpa PIN yang benar, tidak ada yang bisa melihat status HP ini.", 13, false, C_MUTED));
        addBtn(p, btn("Buat PIN baru", false, v -> new AlertDialog.Builder(this).setTitle("Buat PIN baru?")
            .setMessage("HP pemantau harus memasukkan PIN yang baru.")
            .setPositiveButton("Buat", (d, w) -> { prefs.newPin(); show(); startTicker(); }).setNegativeButton("Batal", null).show()));

        LinearLayout ad = card("Alamat HP ini");
        aAddr = tv("", 15, false, C_TEXT);
        ad.addView(aAddr);

        LinearLayout lv = card("Keadaan sekarang");
        aLive = tv("Membaca…", 15, false, C_TEXT);
        lv.addView(aLive);

        LinearLayout t = card("Alat");
        addBtn(t, btn("Buka pengaturan hotspot", false, v -> openHotspotSettings()));
        addBtn(t, btn("Izinkan berjalan terus di latar belakang", false, v -> batteryExempt()));
        addBtn(t, btn("Ganti peran HP ini", false, v -> changeRole()));
        tick();
    }

    private void openHotspotSettings() {
        try {
            Intent i = new Intent();
            i.setClassName("com.android.settings", "com.android.settings.TetherSettings");
            if (i.resolveActivity(getPackageManager()) == null) i = new Intent(Settings.ACTION_WIRELESS_SETTINGS);
            startActivity(i);
        } catch (Throwable t) { toast("Tidak bisa membuka pengaturan. Buka manual: Pengaturan > Koneksi > Hotspot."); }
    }

    private void batteryExempt() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm.isIgnoringBatteryOptimizations(getPackageName())) { toast("Sudah diizinkan."); return; }
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
        } catch (Throwable t) { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
    }

    private void changeRole() {
        new AlertDialog.Builder(this).setTitle("Ganti peran?").setMessage("Layanan yang sedang berjalan akan dihentikan.")
            .setPositiveButton("Ganti", (d, w) -> { HotspotService.stop(this); MonitorService.stop(this); prefs.mode(""); show(); }).setNegativeButton("Batal", null).show();
    }

    private void tickA() {
        boolean on = HotspotService.running;
        aSvc.setText(on ? "● Aktif" : "○ Berhenti");
        aSvc.setTextColor(on ? C_OK : C_BAD);
        aToggle.setText(on ? "Hentikan layanan" : "Mulai layanan");
        if (!aBusy) {
            aBusy = true;
            bg.execute(() -> {
                try {
                    Collector c = Collector.get(this);
                    c.startListening();
                    JSONObject s = c.snapshot();
                    String[] hs = c.hotspotIface();
                    aLiveText = describe(s);
                    final String addr = hs == null
                        ? "Hotspot belum menyala di HP ini. Nyalakan dulu lewat Pengaturan > Hotspot."
                        : "Alamat untuk HP pemantau: " + hs[1] + "\n(HP pemantau mengisinya sendiri kalau tersambung ke hotspot ini.)";
                    h.post(() -> { if (aAddr != null) aAddr.setText(addr); if (aLive != null) aLive.setText(aLiveText); });
                } catch (Throwable t) { /* abaikan */ } finally { aBusy = false; }
            });
        }
    }

    // ------------------------------------------------------------------ HP pemantau (B)

    private void buildB() {
        root.addView(tv("HP Pemantau", 26, true, C_PRIMARY));
        root.addView(tv("Tersambung ke hotspot HP A, lalu memantaunya.", 14, false, C_MUTED));
        gap(root, 12);

        LinearLayout c = card("Koneksi");
        bConn = tv("", 20, true, C_TEXT);
        c.addView(bConn);
        gap(c, 8);
        bIp = new EditText(this);
        bIp.setHint("Alamat HP A (kosong = otomatis)");
        bIp.setInputType(InputType.TYPE_CLASS_PHONE);
        bIp.setSingleLine(true);
        bIp.setText(prefs.targetIp());
        c.addView(bIp);
        bPin = new EditText(this);
        bPin.setHint("PIN dari HP A (6 angka)");
        bPin.setInputType(InputType.TYPE_CLASS_NUMBER);
        bPin.setSingleLine(true);
        bPin.setText(prefs.targetPin());
        c.addView(bPin);
        bToggle = btn("Mulai memantau", true, v -> {
            if (Monitor.running) { MonitorService.stop(this); }
            else {
                String ip = bIp.getText().toString().trim(), pin = bPin.getText().toString().trim();
                if (!ip.isEmpty() && !Logic.validIp(ip)) { toast("Alamat tidak valid. Contoh: 192.168.43.1, atau kosongkan."); return; }
                if (pin.length() != 6) { toast("PIN harus 6 angka. Lihat di layar HP A."); return; }
                prefs.targetIp(ip); prefs.targetPin(pin);
                askPerms();
                Monitor.last = null; Monitor.http = 0; Monitor.error = "";
                MonitorService.start(this);
            }
            h.postDelayed(this::tick, 600);
        });
        addBtn(c, bToggle);

        LinearLayout s = card("Sinyal seluler HP A");
        bSig = tv("-", 20, true, C_TEXT);
        s.addView(bSig);
        LinearLayout bt = card("Baterai HP A");
        bBat = tv("-", 16, false, C_TEXT);
        bt.addView(bBat);
        LinearLayout hs = card("Hotspot HP A");
        bHot = tv("-", 15, false, C_TEXT);
        hs.addView(bHot);
        LinearLayout dt = card("Data seluler HP A");
        bData = tv("-", 15, false, C_TEXT);
        dt.addView(bData);
        LinearLayout wf = card("Sambungan WiFi ke HP A");
        bWifi = tv("-", 15, false, C_TEXT);
        wf.addView(bWifi);

        LinearLayout t = card("Alat");
        addBtn(t, btn("Bunyikan HP A (cari HP)", false, v -> command("ring")));
        addBtn(t, btn("Hentikan bunyi di HP A", false, v -> command("stopring")));
        addBtn(t, btn("Minta HP A membuka pengaturan hotspot", false, v -> command("settings")));
        addBtn(t, btn("Pengaturan peringatan", false, v -> alertSettings()));
        addBtn(t, btn("Ganti peran HP ini", false, v -> changeRole()));
        t.addView(tv("Android tidak mengizinkan aplikasi menyalakan atau mematikan hotspot sendiri. Tombol \"Minta HP A membuka pengaturan\" mengirim notifikasi ke HP A; tinggal diketuk di sana.", 12, false, C_MUTED));
        tick();
    }

    static String gatewayIp(Context c) {
        try {
            WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            DhcpInfo d = wm.getDhcpInfo();
            return d == null || d.gateway == 0 ? "" : Logic.ipFromInt(d.gateway);
        } catch (Throwable t) { return ""; }
    }

    private void command(String name) {
        final String ip = Logic.validIp(prefs.targetIp()) ? prefs.targetIp() : gatewayIp(this), pin = prefs.targetPin();
        if (!Logic.validIp(ip) || pin.length() != 6) { toast("Isi alamat dan PIN lalu tekan Mulai memantau dulu."); return; }
        bg.execute(() -> {
            Monitor.Result r = Monitor.call(ip, pin, "/cmd?name=" + name, 2500, 4000);
            String msg;
            try { msg = r.code == 200 ? new JSONObject(r.body).optString("msg", "Terkirim") : r.code == 401 ? "PIN salah" : r.code == -1 ? "HP A tidak terjangkau" : "Gagal (" + r.code + ")"; }
            catch (Throwable t) { msg = "Gagal membaca jawaban"; }
            final String m = msg;
            h.post(() -> toast(m));
        });
    }

    private void alertSettings() {
        final Logic.Rules r = prefs.rules();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        final EditText weak = num("Sinyal dianggap lemah di bawah (dBm)", String.valueOf(r.weakDbm), true, box);
        final EditText batt = num("Peringatan baterai di bawah (%)", String.valueOf(r.battPct), false, box);
        final EditText data = num("Batas data harian (MB, 0 = mati)", String.valueOf(r.dataLimitMb), false, box);
        final EditText intv = num("Cek setiap (detik)", String.valueOf(prefs.intervalSec()), false, box);
        final CheckBox cDown = chk("Peringatan HP A tidak terjangkau", r.notifyDown, box);
        final CheckBox cWeak = chk("Peringatan sinyal lemah", r.notifyWeak, box);
        final CheckBox cHot = chk("Peringatan hotspot mati", r.notifyHotspot, box);
        final CheckBox cBat = chk("Peringatan baterai rendah", r.notifyBatt, box);
        final CheckBox cData = chk("Peringatan batas data", r.notifyData, box);
        ScrollView sv = new ScrollView(this); sv.addView(box);
        new AlertDialog.Builder(this).setTitle("Pengaturan peringatan").setView(sv)
            .setPositiveButton("Simpan", (d, w) -> {
                Logic.Rules n = new Logic.Rules();
                n.weakDbm = (int) parse(weak, r.weakDbm); if (n.weakDbm > -60) n.weakDbm = -60; if (n.weakDbm < -130) n.weakDbm = -130;
                n.battPct = (int) Math.max(1, Math.min(90, parse(batt, r.battPct)));
                n.dataLimitMb = Math.max(0, parse(data, r.dataLimitMb));
                n.notifyDown = cDown.isChecked(); n.notifyWeak = cWeak.isChecked(); n.notifyHotspot = cHot.isChecked(); n.notifyBatt = cBat.isChecked(); n.notifyData = cData.isChecked();
                prefs.rules(n); prefs.intervalSec((int) parse(intv, 3));
                toast("Tersimpan");
            }).setNegativeButton("Batal", null).show();
    }

    private long parse(EditText e, long def) { try { return Long.parseLong(e.getText().toString().trim()); } catch (Throwable t) { return def; } }

    private EditText num(String label, String val, boolean negative, LinearLayout box) {
        box.addView(tv(label, 13, false, C_MUTED));
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | (negative ? InputType.TYPE_NUMBER_FLAG_SIGNED : 0));
        e.setText(val); e.setSingleLine(true);
        box.addView(e);
        return e;
    }

    private CheckBox chk(String label, boolean on, LinearLayout box) {
        CheckBox c = new CheckBox(this); c.setText(label); c.setChecked(on);
        box.addView(c);
        return c;
    }

    private void tickB() {
        boolean on = Monitor.running;
        bToggle.setText(on ? "Berhenti memantau" : "Mulai memantau");
        JSONObject s = Monitor.last;
        long age = s == null ? -1 : (System.currentTimeMillis() - Monitor.lastOkMs) / 1000;
        boolean live = on && Monitor.http == 200 && age >= 0 && age < 12;
        if (!on) { bConn.setText("○ Belum memantau"); bConn.setTextColor(C_MUTED); }
        else if (Monitor.http == 401) { bConn.setText("● PIN salah"); bConn.setTextColor(C_BAD); }
        else if (live) { bConn.setText("● Terhubung · " + Monitor.latencyMs + " ms"); bConn.setTextColor(C_OK); }
        else if (Monitor.http == 0) { bConn.setText("● Menghubungkan…"); bConn.setTextColor(C_WARN); }
        else { bConn.setText("● Tidak terjangkau"); bConn.setTextColor(C_BAD); }
        if (on && !Monitor.error.isEmpty() && !live) bConn.setText(bConn.getText() + "\n" + Monitor.error);

        bWifi.setText(Monitor.wifiRssi < 0 && Monitor.wifiRssi > -127
            ? Monitor.wifiRssi + " dBm · " + Logic.wifiQuality(Monitor.wifiRssi) + (Monitor.latencyMs >= 0 && live ? "\nWaktu tempuh ke HP A " + Monitor.latencyMs + " ms" : "")
            : "Tidak tersambung ke WiFi.");

        if (s == null) {
            bSig.setText("-"); bBat.setText("-"); bHot.setText("-"); bData.setText("-");
            return;
        }
        JSONObject sig = s.optJSONObject("signal"), bat = s.optJSONObject("battery"), hot = s.optJSONObject("hotspot"), data = s.optJSONObject("data");
        int dbm = sig == null ? -999 : sig.optInt("dbm", -999), lvl = sig == null ? -1 : sig.optInt("level", -1);
        if (lvl < 0 && dbm > -200) lvl = Logic.levelFromDbm(dbm);
        bSig.setText((dbm > -200 ? dbm + " dBm   " : "") + Logic.bars(lvl) + "\n" + Logic.levelName(lvl)
            + (sig == null ? "" : " · " + sig.optString("type", "?") + (sig.optString("operator", "").isEmpty() ? "" : " · " + sig.optString("operator"))));
        bSig.setTextColor(lvl >= 3 ? C_OK : lvl >= 2 ? C_WARN : lvl >= 0 ? C_BAD : C_TEXT);
        if (!live && age >= 0) bSig.setText(bSig.getText() + "\n(data terakhir " + age + " detik lalu)");

        bBat.setText(bat == null ? "-" : bat.optInt("pct", -1) + "%" + (bat.optBoolean("charging") ? " · sedang diisi" : "") + " · suhu " + String.format(java.util.Locale.US, "%.1f", bat.optDouble("tempC", 0)) + "°C");

        if (hot == null) bHot.setText("-");
        else {
            StringBuilder b = new StringBuilder(hot.optBoolean("on") ? "Menyala" : "Mati");
            if (hot.optBoolean("on")) {
                JSONArray cl = hot.optJSONArray("clients");
                int n = cl == null ? 0 : cl.length();
                if (hot.optBoolean("clientsKnown") && n > 0) {
                    b.append(" · ").append(n).append(" perangkat tersambung");
                    for (int i = 0; i < n; i++) { JSONObject c = cl.optJSONObject(i); if (c != null) b.append("\n• ").append(c.optString("ip")).append("  ").append(c.optString("mac")); }
                } else b.append("\nDaftar perangkat tidak bisa dibaca di HP A (dibatasi Android). Minimal HP ini tersambung.");
                b.append("\nTrafik hotspot: ↓ ").append(Logic.fmtBytes(hot.optLong("txBytes", -1))).append("  ↑ ").append(Logic.fmtBytes(hot.optLong("rxBytes", -1)));
            }
            bHot.setText(b.toString());
        }
        bData.setText(data == null ? "-" : "Hari ini: " + Logic.fmtBytes(data.optLong("todayBytes", -1))
            + "\nKecepatan: ↓ " + Logic.fmtRate(data.optDouble("rxBps", -1)) + "  ↑ " + Logic.fmtRate(data.optDouble("txBps", -1)));
    }

    // ------------------------------------------------------------------ teks status HP A

    static String describe(JSONObject s) {
        JSONObject sig = s.optJSONObject("signal"), bat = s.optJSONObject("battery"), hot = s.optJSONObject("hotspot"), data = s.optJSONObject("data");
        int dbm = sig == null ? -999 : sig.optInt("dbm", -999), lvl = sig == null ? -1 : sig.optInt("level", -1);
        StringBuilder b = new StringBuilder();
        b.append("Sinyal: ").append(dbm > -200 ? dbm + " dBm · " : "").append(Logic.levelName(lvl)).append(' ').append(Logic.bars(lvl));
        if (sig != null) b.append(" · ").append(sig.optString("type", "?"));
        if (bat != null) b.append("\nBaterai: ").append(bat.optInt("pct", -1)).append("%").append(bat.optBoolean("charging") ? " (diisi)" : "");
        if (hot != null) {
            b.append("\nHotspot: ").append(hot.optBoolean("on") ? "menyala" : "mati");
            JSONArray cl = hot.optJSONArray("clients");
            if (hot.optBoolean("on")) b.append(cl != null && cl.length() > 0 ? " · " + cl.length() + " perangkat" : " · jumlah perangkat tidak terbaca");
        }
        if (data != null) b.append("\nData hari ini: ").append(Logic.fmtBytes(data.optLong("todayBytes", -1)));
        return b.toString();
    }

    private void tick() {
        if (aSvc != null) tickA();
        else if (bConn != null) tickB();
    }
}
