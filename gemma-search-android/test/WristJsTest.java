import io.github.teoplaydor.semsearch.core.VoiceNote;
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
 * And turns found on the watch (HELLO v2 and TURN packets), and the watches' JavaScript turn finder against the phone's
 * Java one over the same streams (finder_in.txt from WristGestureTest, finder_js.txt from proto_test.mjs).
 * usage: WristJsTest DIR (with vela.hex, zepp.hex, vela_app.hex, zepp_app.hex, fixed.hex, *_turns.hex, finder_*.txt)
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
        // raw samples streamed (the protocol module alone)
        for (String file : new String[]{"vela", "zepp"}) {
            String w = file;
            List<byte[]> ps = read(a[0] + "/" + file + ".hex");
            WristGesture d = new WristGesture();
            d.setRight(null, true);
            List<Integer> got = new ArrayList<Integer>();
            for (byte[] p : ps) {
                int r = d.packet(p);
                if (r != WristGesture.NONE) got.add(r);
            }
            ok(d.hasHello() && !d.recognises() && d.watchId() == 0x0badcafe && w.equals(d.platform()), file + ": hello read, id and platform");
            ok(Math.abs(d.rate() - 25) <= 1, file + ": measured rate " + d.rate() + " Hz");
            ok(d.gaps() == 0 && ps.size() > 20, file + ": " + ps.size() + " packets, no gaps");
            ok(got.equals(Arrays.asList(WristGesture.RIGHT, WristGesture.LEFT)), file + ": turns " + got + " (want [1, 2]: right, left)");
            ok(d.gravity() > 800 && d.gravity() < 1100, file + ": gravity in its units " + Math.round(d.gravity()));
        }
        // turns found on the watch: HELLO v2, then one TURN packet per turn
        ok(Arrays.equals(fixed.get(2), WristGesture.hello(WristGesture.VERSION_TURNS, 0x0badcafe, 16, 981, "vela")),
                "HELLO v2: the same bytes as the phone's");
        ok(Arrays.equals(fixed.get(3), WristGesture.rollPacket(9, 61.64f, 344, WristGesture.SOURCE_GRAVITY, 0.37f, 12.4f))
                && Arrays.equals(fixed.get(4), WristGesture.rollPacket(10, -48.25f, 410, WristGesture.SOURCE_COMPASS, -0.93f, 3)),
                "ROLL: the same bytes (gravity, the compass)");
        // the voice note's packets, as the phone reads them
        VoiceNote vn = new VoiceNote();
        boolean voice = vn.packet(fixed.get(5)) == VoiceNote.STARTED && vn.id() == 7 && vn.codec() == WristGesture.CODEC_RECORDED && vn.rate() == 16000
                && vn.packet(fixed.get(6)) == VoiceNote.MISSING && vn.missingFrom() == 0
                && vn.packet(fixed.get(7)) == VoiceNote.MISSING && vn.missingFrom() == 0 && vn.millis() == 4321;
        ok(voice, "VOICE: start, bytes at 70000, end at 70005 — the first 70000 asked for again");
        ok(Arrays.equals(fixed.get(5), WristGesture.voiceStart(7, WristGesture.CODEC_RECORDED, 16000, 0))
                && Arrays.equals(fixed.get(6), WristGesture.voiceData(7, 70000, new byte[]{1, 2, 3, (byte) 250, (byte) 255}, 0, 5))
                && Arrays.equals(fixed.get(7), WristGesture.voiceEnd(7, 70005, 4321)), "VOICE: the same bytes as the phone's");
        // a recording sent by the watch's JavaScript with a write lost on the way (and sent again when asked): whole
        List<byte[]> wire = read(a[0] + "/voice_js.hex");
        byte[] want = read(a[0] + "/voice_js_bytes.hex").get(0);
        VoiceNote vj = new VoiceNote();
        int missing = 0;
        boolean complete = false;
        for (byte[] p : wire) {
            int r = vj.packet(p);
            if (r == VoiceNote.MISSING) missing++;
            if (r == VoiceNote.COMPLETE) complete = true;
        }
        ok(complete && Arrays.equals(vj.bytes(), want) && missing > 0 && vj.millis() == 4321,
                "VOICE from the watch's JavaScript: a write lost, told (" + missing + "×), sent again — the recording whole ("
                        + want.length + " bytes)");
        // the same by the Vela page itself (a write failed and written again, the phone's «again from 238»)
        List<byte[]> page = read(a[0] + "/vela_voice_app.hex");
        byte[] frames = read(a[0] + "/vela_voice_bytes.hex").get(0);
        VoiceNote vp = new VoiceNote();
        boolean whole = false;
        for (byte[] p : page) if (vp.packet(p) == VoiceNote.COMPLETE) whole = true;
        ok(whole && Arrays.equals(vp.bytes(), frames) && vp.codec() == WristGesture.CODEC_RECORDED && vp.rate() == 16000,
                "VOICE from the Vela page: the recorder's frames whole on the phone (" + frames.length + " bytes, Ogg as recorded)");
        // (the protocol module alone, and the apps' pages themselves)
        for (String file : new String[]{"vela_turns", "zepp_turns", "vela_s5_turns", "vela_app", "vela_s5_app", "vela_hanging_app", "zepp_app"}) {
            List<byte[]> ps = read(a[0] + "/" + file + ".hex");
            WristGesture d = new WristGesture();
            d.setRight(null, true);
            List<Integer> got = new ArrayList<Integer>();
            for (byte[] p : ps) {
                int r = d.packet(p);
                if (r != WristGesture.NONE) got.add(r);
            }
            ok(d.recognises() && d.watchId() != 0 && file.startsWith(d.platform()) && ps.size() == 3 && got.equals(Arrays.asList(WristGesture.RIGHT, WristGesture.LEFT)),
                    file + ": found on the watch, " + ps.size() + " packets in all (HELLO, two turns): " + got
                            + String.format(" (last %.0f° in %.0f ms)", d.lastPeak(), d.lastTook()));
        }

        // the watches' JavaScript finder and the phone's Java one: the same turns in the same streams
        java.nio.file.Path in = Paths.get(a[0], "finder_in.txt"), js = Paths.get(a[0], "finder_js.txt");
        if (Files.exists(in) && Files.exists(js)) {
            List<String> java = new ArrayList<String>();
            WristGesture.Finder f = null;
            String name = "";
            for (String l : Files.readAllLines(in, StandardCharsets.US_ASCII)) {
                if (l.startsWith("# ")) {
                    name = l.substring(2).trim();
                    f = new WristGesture.Finder();
                    continue;
                }
                if (l.trim().isEmpty() || f == null) continue;
                String[] v = l.trim().split(" ");
                double h = v.length > 4 && !v[4].equals("NaN") ? Double.parseDouble(v[4]) : Double.NaN;
                if (f.sample(Double.parseDouble(v[1]), Double.parseDouble(v[2]), Double.parseDouble(v[3]), Double.parseDouble(v[0]), h)) {
                    java.add(name + " " + f.twist() + " " + f.took() + " " + f.source() + " " + f.slope() + " " + f.slopeChange());
                }
            }
            List<String> jsl = new ArrayList<String>();
            for (String l : Files.readAllLines(js, StandardCharsets.US_ASCII)) if (!l.trim().isEmpty()) jsl.add(l.trim());
            boolean same = java.size() == jsl.size();
            String first = "";
            for (int i = 0; same && i < java.size(); i++) {
                String[] x = java.get(i).split(" "), y = jsl.get(i).split(" ");
                same = x[0].equals(y[0]);
                for (int k = 1; same && k < 6; k++) {
                    double xv = Double.parseDouble(x[k]), yv = Double.parseDouble(y[k]);
                    same = Math.abs(xv - yv) <= (k == 2 ? 0.2 : k == 4 ? 1e-4 : 0.01);
                }
                if (!same) first = "\n     java: " + java.get(i) + "\n     js:   " + jsl.get(i);
            }
            ok(same, "the watches' finder and the phone's: the same " + java.size() + " turns (JavaScript " + jsl.size() + ")" + first);
        }
        System.out.println(bad == 0 ? "WATCH PROTOCOL OK" : bad + " FAILED");
        if (bad != 0) System.exit(1);
    }
}
