import io.github.teoplaydor.semsearch.core.WristGesture;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The watch apps' JavaScript protocol (Vela, Zepp OS) read by the phone's Java: the same bytes for the same HELLO and
 * samples, and the packets of a simulated wrist recognised — right, then left — with the rate the watch measured:
 * made by the protocol module alone (test/watch/proto_test.mjs) and by the apps' pages themselves over stand-ins of
 * the watches' modules (test/watch/apps_test.mjs).
 * usage: WristJsTest DIR (with vela.hex, zepp.hex, vela_app.hex, zepp_app.hex, fixed.hex)
 */
public class WristJsTest {
    static int bad;

    static byte[] unhex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    static List<byte[]> read(String f) throws Exception {
        List<byte[]> out = new ArrayList<byte[]>();
        for (String l : Files.readAllLines(Paths.get(f), StandardCharsets.US_ASCII)) if (!l.trim().isEmpty()) out.add(unhex(l.trim()));
        return out;
    }

    static void ok(boolean c, String what) {
        if (!c) bad++;
        System.out.println((c ? "ok   " : "FAIL ") + what);
    }

    public static void main(String[] a) throws Exception {
        List<byte[]> fixed = read(a[0] + "/fixed.hex");
        ok(Arrays.equals(fixed.get(0), WristGesture.hello(0x0badcafe, 25, 981, "vela")), "HELLO: the same bytes as the phone's");
        short[] xyz = {1000, -1000, 32767, -32768, 0, 981, 32767, -32768, 1};
        ok(Arrays.equals(fixed.get(1), WristGesture.accel(7, xyz, 0, 3)), "ACCEL: the same bytes (clamped to int16)");
        for (String file : new String[]{"vela", "zepp", "vela_app", "zepp_app"}) {
            String w = file.replace("_app", "");
            List<byte[]> ps = read(a[0] + "/" + file + ".hex");
            WristGesture d = new WristGesture();
            d.setRight(null, true);
            List<Integer> got = new ArrayList<Integer>();
            for (byte[] p : ps) {
                int r = d.packet(p);
                if (r != WristGesture.NONE) got.add(r);
            }
            ok(d.hasHello() && (file.endsWith("_app") ? d.watchId() != 0 : d.watchId() == 0x0badcafe) && w.equals(d.platform()),
                    file + ": hello read, id and platform");
            ok(Math.abs(d.rate() - 25) <= 1, file + ": measured rate " + d.rate() + " Hz");
            ok(d.gaps() == 0 && ps.size() > 20, file + ": " + ps.size() + " packets, no gaps");
            ok(got.equals(Arrays.asList(WristGesture.RIGHT, WristGesture.LEFT)), file + ": turns " + got + " (want [1, 2]: right, left)");
            ok(d.gravity() > 800 && d.gravity() < 1100, file + ": gravity in its units " + Math.round(d.gravity()));
        }
        System.out.println(bad == 0 ? "WATCH PROTOCOL OK" : bad + " FAILED");
        if (bad != 0) System.exit(1);
    }
}
