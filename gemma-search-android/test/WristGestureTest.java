import io.github.teoplaydor.semsearch.core.WristGesture;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Wrist turns from a watch's raw accelerometer, on a simulated hand: the watch is turned by the motion and its
 * accelerometer reads gravity plus the watch's own acceleration (it sits on the wrist, 3 cm off the forearm's axis),
 * with sensor noise; the samples go through the Bluetooth packets as a watch would send them. Turns right and left on
 * either hand, at 25, 50 and 12 Hz, in milli-g, raw counts or m/s²; a watch with its own axes taught by one turn; and
 * what must not count: raising the hand to look, walking, turning and staying, a small or a slow turn, a lost packet.
 * usage: WristGestureTest
 */
public class WristGestureTest {
    static int bad;
    static final double G = 9.80665;

    /** The motion of the watch: orientation (rotation from rest) and position, by time. */
    interface Motion {
        /** Rotation axis (world, unit) and angle (radians) at time t (s); position offset from the turn's centre. */
        double angle(double t);
    }

    /** A simulated stream: segments of still / turn, sampled at a rate, with noise. */
    static final class Sim {
        final int rate;
        final Random rnd = new Random(7);
        final List<double[]> out = new ArrayList<double[]>(); // watch-frame specific force, m/s²
        double noise = 0.02 * G;

        Sim(int rate) {
            this.rate = rate;
        }

        Sim still(double s) {
            return move(new double[]{1, 0, 0}, new double[]{0, 0, 0.03}, s, new Motion() {
                @Override
                public double angle(double t) {
                    return 0;
                }
            });
        }

        /** A turn of {@code deg} around {@code axis} and back, in {@code s} seconds (sin² profile). */
        Sim turn(double[] axis, final double deg, final double s, boolean back) {
            final double a = Math.toRadians(deg);
            if (back) {
                return move(axis, new double[]{0, 0, 0.03}, s, new Motion() {
                    @Override
                    public double angle(double t) {
                        double x = Math.sin(Math.PI * t / s);
                        return a * x * x;
                    }
                });
            }
            // turned in s/2 and held there for 2.5 s
            return move(axis, new double[]{0, 0, 0.03}, s / 2 + 2.5, new Motion() {
                @Override
                public double angle(double t) {
                    double x = Math.sin(Math.PI * Math.min(t, s / 2) / s);
                    return a * x * x;
                }
            });
        }

        /** Raising the hand: a bend at the elbow (25 cm back) around the world's y axis, and down again. */
        Sim raise(final double deg, final double s) {
            final double a = Math.toRadians(deg);
            return move(new double[]{0, 1, 0}, new double[]{0.25, 0, 0.03}, s, new Motion() {
                @Override
                public double angle(double t) {
                    double x = Math.sin(Math.PI * t / s);
                    return -a * x * x;
                }
            });
        }

        /** Walking: the arm swings around the shoulder's axis (sideways, world y) back and forth. */
        Sim walk(final double s) {
            return move(new double[]{0, 1, 0}, new double[]{0, 0, -0.6}, s, new Motion() {
                @Override
                public double angle(double t) {
                    return Math.toRadians(28) * Math.sin(2 * Math.PI * t / 1.1);
                }
            });
        }

        Sim move(double[] axis, double[] p0, double s, Motion m) {
            int n = (int) Math.round(s * rate);
            double h = 1e-3;
            for (int i = 0; i < n; i++) {
                double t = i / (double) rate;
                double th = m.angle(t);
                double[][] r = rot(axis, th);
                // the watch's acceleration: the second difference of its position
                double[] p1 = mul(rot(axis, m.angle(t - h)), p0), p2 = mul(r, p0), p3 = mul(rot(axis, m.angle(t + h)), p0);
                double[] acc = new double[3];
                for (int k = 0; k < 3; k++) acc[k] = (p1[k] - 2 * p2[k] + p3[k]) / (h * h);
                // the accelerometer: (acceleration − gravity) in the watch's own axes
                double[] f = {acc[0], acc[1], acc[2] + G};
                double[] w = mulT(r, f);
                for (int k = 0; k < 3; k++) w[k] += noise * rnd.nextGaussian();
                out.add(w);
            }
            return this;
        }
    }

    static double[][] rot(double[] u, double th) {
        double c = Math.cos(th), s = Math.sin(th), C = 1 - c, x = u[0], y = u[1], z = u[2];
        return new double[][]{
                {c + x * x * C, x * y * C - z * s, x * z * C + y * s},
                {y * x * C + z * s, c + y * y * C, y * z * C - x * s},
                {z * x * C - y * s, z * y * C + x * s, c + z * z * C}};
    }

    static double[] mul(double[][] r, double[] v) {
        double[] o = new double[3];
        for (int i = 0; i < 3; i++) o[i] = r[i][0] * v[0] + r[i][1] * v[1] + r[i][2] * v[2];
        return o;
    }

    static double[] mulT(double[][] r, double[] v) {
        double[] o = new double[3];
        for (int i = 0; i < 3; i++) o[i] = r[0][i] * v[0] + r[1][i] * v[1] + r[2][i] * v[2];
        return o;
    }

    /** How a watch reports its axes: Android's, or another layout (a permutation and signs). */
    interface Axes {
        double[] map(double[] w);
    }

    static final Axes ANDROID = new Axes() {
        @Override
        public double[] map(double[] w) {
            return w;
        }
    };
    /** A watch with axes of its own: y→x, z→y, x→−z (a left-handed set). */
    static final Axes ODD = new Axes() {
        @Override
        public double[] map(double[] w) {
            return new double[]{w[1], w[2], -w[0]};
        }
    };

    /** Through the packets: in {@code perG} counts per g (or m/s² ×100 when perG is 0), n samples a packet. */
    static List<Integer> run(WristGesture d, Sim sim, Axes axes, int perG, int n, int dropPacket) {
        short[] xyz = new short[3 * sim.out.size()];
        for (int i = 0; i < sim.out.size(); i++) {
            double[] w = axes.map(sim.out.get(i));
            for (int k = 0; k < 3; k++) {
                double v = perG > 0 ? w[k] / G * perG : w[k] * 100;
                xyz[3 * i + k] = (short) Math.max(-32768, Math.min(32767, Math.round(v)));
            }
        }
        List<Integer> got = new ArrayList<Integer>();
        int seq = 0;
        for (int i = 0; i + n <= sim.out.size(); i += n) {
            byte[] p = WristGesture.accel(seq & 0xff, xyz, i, n);
            seq++;
            if (seq - 1 == dropPacket) continue;
            int r = d.packet(p);
            if (r != WristGesture.NONE) got.add(r);
        }
        return got;
    }

    static WristGesture watch(int rate, int perG, String platform, boolean leftHand) {
        WristGesture d = new WristGesture();
        d.packet(WristGesture.hello(0x5eed1234, rate, perG, platform));
        d.setRight(null, leftHand);
        return d;
    }

    static String names(List<Integer> got) {
        StringBuilder sb = new StringBuilder();
        for (int r : got) sb.append(sb.length() == 0 ? "" : " ").append(r == 1 ? "RIGHT" : r == 2 ? "LEFT" : r == 3 ? "LEARNED" : "?");
        return sb.length() == 0 ? "nothing" : sb.toString();
    }

    static void check(String what, List<Integer> got, int... want) {
        boolean ok = got.size() == want.length;
        for (int i = 0; ok && i < want.length; i++) ok = got.get(i) == want[i];
        if (!ok) bad++;
        List<Integer> w = new ArrayList<Integer>();
        for (int x : want) w.add(x);
        System.out.println((ok ? "ok   " : "FAIL ") + what + ": " + names(got) + (ok ? "" : "   expected " + names(w)));
    }

    static final double[] FOREARM = {1, 0, 0};
    static final double[] BACKWARD = {-1, 0, 0};
    static final int R = WristGesture.RIGHT, L = WristGesture.LEFT;

    public static void main(String[] args) {
        // the packets themselves
        WristGesture h = watch(25, 1000, "wearos", true);
        boolean helloOk = h.hasHello() && h.watchId() == 0x5eed1234 && h.rate() == 25 && h.perG() == 1000 && "wearos".equals(h.platform());
        if (!helloOk) bad++;
        System.out.println((helloOk ? "ok   " : "FAIL ") + "hello: id " + Integer.toHexString(h.watchId()) + ", " + h.rate() + " Hz, " + h.platform());
        boolean fits = WristGesture.accel(0, new short[9], 0, 3).length == 20 && WristGesture.hello(1, 25, 1000, "garmin-fenix").length == 20;
        if (!fits) bad++;
        System.out.println((fits ? "ok   " : "FAIL ") + "3 samples and a hello fit a 20-byte write");
        int junk = h.packet(new byte[]{0x13, 0}) + h.packet(new byte[]{(byte) 0xf0, 1, 2}) + h.packet(null);
        if (junk != 0) bad++;
        System.out.println((junk == 0 ? "ok   " : "FAIL ") + "short and unknown packets are ignored");

        // left wrist, Android's axes, milli-g, 25 Hz, 3 samples a packet
        check("right turn", run(watch(25, 1000, "wearos", true),
                new Sim(25).still(1).turn(FOREARM, 70, 0.6, true).still(1), ANDROID, 1000, 3, -1), R);
        check("left turn", run(watch(25, 1000, "wearos", true),
                new Sim(25).still(1).turn(FOREARM, -70, 0.6, true).still(1), ANDROID, 1000, 3, -1), L);
        check("right, left, right with short pauses", run(watch(25, 1000, "wearos", true),
                new Sim(25).still(1).turn(FOREARM, 65, 0.55, true).still(0.5).turn(FOREARM, -65, 0.55, true).still(0.5)
                        .turn(FOREARM, 65, 0.55, true).still(1), ANDROID, 1000, 3, -1), R, L, R);
        check("a quick flick", run(watch(25, 1000, "wearos", true),
                new Sim(25).still(1).turn(FOREARM, 60, 0.35, true).still(1), ANDROID, 1000, 3, -1), R);
        // the right wrist: the dial's x looks to the elbow, so the same turn is around −x
        check("right hand, right turn", run(watch(25, 1000, "wearos", false),
                new Sim(25).still(1).turn(BACKWARD, 70, 0.6, true).still(1), ANDROID, 1000, 3, -1), R);
        check("right hand, left turn", run(watch(25, 1000, "wearos", false),
                new Sim(25).still(1).turn(BACKWARD, -70, 0.6, true).still(1), ANDROID, 1000, 3, -1), L);
        // other rates and units: 50 Hz in raw counts (4096 per g, the watch doesn't say), 12 Hz in m/s² ×100
        check("50 Hz, raw counts, 15 samples a packet", run(watch(50, 0, "zepp", true),
                new Sim(50).still(1).turn(FOREARM, 70, 0.6, true).still(1).turn(FOREARM, -70, 0.6, true).still(1), ANDROID, 4096, 15, -1), R, L);
        check("12 Hz, m/s²", run(watch(12, 0, "vela", true),
                new Sim(12).still(1.5).turn(FOREARM, 75, 0.7, true).still(1.5), ANDROID, 0, 3, -1), R);
        // 5 Hz: the Xiaomi Watch S5's «normal» pace (its faster «game» gave one value over and over) — a deliberate turn
        check("5 Hz, m/s², right then left", run(watch(5, 0, "vela", true),
                new Sim(5).still(2).turn(FOREARM, 75, 0.8, true).still(2).turn(FOREARM, -75, 0.8, true).still(2), ANDROID, 0, 3, -1), R, L);

        // sensitivity: a 28° turn is not enough by default, enough when sensitive (the watch's own acceleration as it
        // stops at the far end adds a few degrees to what it sees)
        check("28° at 40°: nothing", run(watch(25, 1000, "wearos", true),
                new Sim(25).still(1).turn(FOREARM, 28, 0.5, true).still(1), ANDROID, 1000, 3, -1));
        WristGesture sens = watch(25, 1000, "wearos", true);
        sens.turn = 30;
        check("28° at 30°: right", run(sens, new Sim(25).still(1).turn(FOREARM, 28, 0.5, true).still(1), ANDROID, 1000, 3, -1), R);

        // what must not count
        check("raising the hand to look and down", run(watch(25, 1000, "wearos", true),
                new Sim(25).still(1).raise(60, 0.8).still(1), ANDROID, 1000, 3, -1));
        check("walking, 20 s", run(watch(25, 1000, "wearos", true), new Sim(25).walk(20), ANDROID, 1000, 3, -1));
        check("turned and stayed", run(watch(25, 1000, "wearos", true),
                new Sim(25).still(1).turn(FOREARM, 70, 1.2, false), ANDROID, 1000, 3, -1));
        check("a slow turn (2 s)", run(watch(25, 1000, "wearos", true),
                new Sim(25).still(1).turn(FOREARM, 70, 2.0, true).still(1), ANDROID, 1000, 3, -1));
        check("a small turn (20°)", run(watch(25, 1000, "wearos", true),
                new Sim(25).still(1).turn(FOREARM, 20, 0.5, true).still(1), ANDROID, 1000, 3, -1));
        check("a turn right after walking (no pause)", run(watch(25, 1000, "wearos", true),
                new Sim(25).walk(3.3).turn(FOREARM, 70, 0.6, true), ANDROID, 1000, 3, -1));
        // a packet lost in the middle of a turn: that turn is dropped, the next one after a pause counts
        Sim lost = new Sim(25).still(1).turn(FOREARM, 70, 0.6, true).still(1).turn(FOREARM, -70, 0.6, true).still(1);
        int mid = (int) Math.round((1 + 0.3) * 25 / 3);
        WristGesture ld = watch(25, 1000, "wearos", true);
        check("a lost packet mid-turn", run(ld, lost, ANDROID, 1000, 3, mid), L);
        if (ld.gaps() != 1) bad++;
        System.out.println((ld.gaps() == 1 ? "ok   " : "FAIL ") + "the gap counted: " + ld.gaps());

        // a watch with its own axes: taught by one turn to the right, then both ways are right
        WristGesture odd = watch(25, 1000, "garmin", true);
        check("odd axes, untaught: not the right answer", run(odd,
                new Sim(25).still(1).turn(FOREARM, 70, 0.6, true).still(1), ODD, 1000, 3, -1));
        odd.learn();
        check("taught: turn right once", run(odd, new Sim(25).still(1).turn(FOREARM, 70, 0.6, true).still(1), ODD, 1000, 3, -1),
                WristGesture.LEARNED);
        float[] ax = odd.lastAxis();
        boolean learnt = odd.learnt() && Math.abs(ax[2]) > 0.9f;
        if (!learnt) bad++;
        System.out.println((learnt ? "ok   " : "FAIL ") + String.format("learnt axis (%.2f, %.2f, %.2f)", ax[0], ax[1], ax[2]));
        check("odd axes, taught: right, left", run(odd, new Sim(25).still(1).turn(FOREARM, 70, 0.6, true).still(0.8)
                .turn(FOREARM, -70, 0.6, true).still(1), ODD, 1000, 3, -1), R, L);
        // the learnt axis kept and given back (as the app does after a restart)
        WristGesture again = watch(25, 1000, "garmin", true);
        again.setRight(ax, true);
        check("learnt axis restored", run(again, new Sim(25).still(1).turn(FOREARM, -70, 0.6, true).still(1), ODD, 1000, 3, -1), L);

        System.out.println(bad == 0 ? "WRIST GESTURES OK" : bad + " FAILED");
        if (bad != 0) System.exit(1);
    }
}
