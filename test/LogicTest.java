import id.sinyalku.app.Logic;
import java.util.List;

public class LogicTest {
    static int n = 0;
    static void ok(String name, boolean c) { if (!c) { System.out.println("GAGAL - " + name); System.exit(1); } n++; System.out.println("ok  - " + name); }

    public static void main(String[] a) {
        String arp = "IP address       HW type     Flags       HW address            Mask     Device\n"
            + "192.168.43.12    0x1         0x2         aa:bb:cc:dd:ee:01     *        ap0\n"
            + "192.168.43.13    0x1         0x0         00:00:00:00:00:00     *        ap0\n"
            + "192.168.43.14    0x1         0x2         AA:BB:CC:DD:EE:02     *        ap0\n";
        List<Logic.Client> c = Logic.parseArp(arp);
        ok("arp: hanya baris lengkap", c.size() == 2 && c.get(1).mac.equals("aa:bb:cc:dd:ee:02") && c.get(0).dev.equals("ap0"));
        List<Logic.Client> d = Logic.parseIpNeigh("192.168.43.12 dev ap0 lladdr aa:bb:cc:dd:ee:01 REACHABLE\n192.168.43.20 dev ap0  FAILED\n192.168.43.21 dev ap0 lladdr aa:bb:cc:dd:ee:03 STALE\n");
        ok("ip neigh: melewati FAILED", d.size() == 2 && d.get(1).ip.equals("192.168.43.21"));
        ok("antarmuka hotspot", Logic.isHotspotIface("ap0") && Logic.isHotspotIface("swlan0") && Logic.isHotspotIface("wlan1") && Logic.isHotspotIface("rndis0") && !Logic.isHotspotIface("wlan0") && !Logic.isHotspotIface("rmnet_data1") && !Logic.isHotspotIface("lo"));
        ok("level dari dBm", Logic.levelFromDbm(-80) == 4 && Logic.levelFromDbm(-100) == 2 && Logic.levelFromDbm(-120) == 0 && Logic.levelFromDbm(-999) == -1 && Logic.levelFromDbm(0) == -1);
        ok("batang sinyal", Logic.bars(2).equals("▂▄··") && Logic.bars(4).equals("▂▄▆█") && Logic.bars(-1).equals("····"));
        ok("format byte dan laju", Logic.fmtBytes(1536).equals("2 KB") && Logic.fmtBytes(5L * 1024 * 1024).equals("5.0 MB") && Logic.fmtRate(125000).equals("1.0 Mbps") && Logic.fmtRate(500).equals("4 kbps"));
        ok("IP dari bilangan DhcpInfo", Logic.ipFromInt(0x012BA8C0).equals("192.168.43.1"));
        ok("validasi IP", Logic.validIp("192.168.43.1") && !Logic.validIp("300.1.1.1") && !Logic.validIp("abc") && !Logic.validIp("1.2.3"));

        Logic.Rules rules = new Logic.Rules();
        Logic.AlertEngine e = new Logic.AlertEngine();
        Logic.Reading up = new Logic.Reading(); up.reachable = true; up.dbm = -90; up.level = 3; up.battPct = 80; up.hotspotOn = true;
        Logic.Reading dn = new Logic.Reading(); dn.reachable = false;
        long t = 1_000_000;
        ok("terjangkau normal: tanpa peringatan", e.update(up, rules, t).isEmpty());
        ok("putus 1-2 kali: belum ada peringatan", e.update(dn, rules, t += 3000).isEmpty() && e.update(null, rules, t += 3000).isEmpty());
        List<Logic.Alert> a3 = e.update(dn, rules, t += 3000);
        ok("putus 3 kali: satu peringatan mendesak", a3.size() == 1 && a3.get(0).key.equals("down") && a3.get(0).urgent);
        ok("tetap putus: tidak diulang", e.update(dn, rules, t += 3000).isEmpty());
        List<Logic.Alert> ab = e.update(up, rules, t += 3000);
        ok("tersambung lagi: ada pemberitahuan", ab.size() == 1 && ab.get(0).key.equals("up"));

        Logic.Reading weak = new Logic.Reading(); weak.reachable = true; weak.dbm = -110; weak.level = 1; weak.battPct = 80;
        ok("sinyal lemah 1-2 kali: diam", e.update(weak, rules, t += 3000).isEmpty() && e.update(weak, rules, t += 3000).isEmpty());
        List<Logic.Alert> aw = e.update(weak, rules, t += 3000);
        ok("sinyal lemah 3 kali: peringatan", aw.size() == 1 && aw.get(0).key.equals("weak"));
        ok("sinyal lemah terus: ada jeda 10 menit", e.update(weak, rules, t += 3000).isEmpty());
        ok("setelah 10 menit: diulang", e.update(weak, rules, t += 11 * 60_000L).size() == 1);
        e.update(up, rules, t += 3000);
        ok("sinyal pulih mereset hitungan", e.update(weak, rules, t += 3000).isEmpty());

        Logic.Reading low = new Logic.Reading(); low.reachable = true; low.dbm = -80; low.level = 4; low.battPct = 15; low.charging = false;
        List<Logic.Alert> ba = e.update(low, rules, t += 3000);
        ok("baterai rendah: peringatan", ba.size() == 1 && ba.get(0).key.equals("batt"));
        low.charging = true;
        ok("baterai rendah tapi diisi: diam", e.update(low, rules, t += 20 * 60_000L).isEmpty());

        rules.dataLimitMb = 100;
        Logic.Reading big = new Logic.Reading(); big.reachable = true; big.dbm = -80; big.level = 4; big.battPct = 90; big.dataTodayBytes = 120L * 1024 * 1024;
        List<Logic.Alert> da = e.update(big, rules, t += 3000);
        ok("batas data tercapai: peringatan sekali", da.size() == 1 && da.get(0).key.equals("data") && e.update(big, rules, t += 3000).isEmpty());

        Logic.Reading hs = new Logic.Reading(); hs.reachable = true; hs.dbm = -80; hs.level = 4; hs.battPct = 90; hs.hotspotOn = false;
        e.update(hs, rules, t += 3000); e.update(hs, rules, t += 3000);
        List<Logic.Alert> ha = e.update(hs, rules, t += 3000);
        ok("hotspot mati padahal terjangkau: peringatan", ha.size() == 1 && ha.get(0).key.equals("hs"));

        Logic.Rules off = new Logic.Rules(); off.notifyDown = false;
        Logic.AlertEngine e2 = new Logic.AlertEngine();
        e2.update(dn, off, t); e2.update(dn, off, t); 
        ok("peringatan bisa dimatikan", e2.update(dn, off, t).isEmpty() && e2.isDown());
        System.out.println("\n" + n + " pengujian lulus");
    }
}
