package io.github.teoplaydor.semsearch.core;

/**
 * A turn of the wrist to the right or to the left and back, from a watch's accelerometer: any watch whose own small app
 * can talk Bluetooth LE (Wear OS, Xiaomi Vela, Zepp OS, Garmin…). One instance per watch connection.
 *
 * <p>Two ways to get there. A watch that can, recognises the turn itself ({@link Finder}, the same code in the watches'
 * JavaScript) and sends one short TURN packet per turn — the phone's radio and processor stay idle in between. A watch
 * that cannot streams its raw accelerometer and the phone runs the same {@link Finder}. Either way the phone decides
 * what the turn means: whether it was big enough ({@link #turn}), and which way it went by the axis it went around.
 *
 * <p>Nothing about the watch is assumed: the units (m/s², milli-g, raw counts) are learnt from the size of gravity when
 * the hand is still; the axes from one turn «to the right» the user makes once ({@link #learn()}), or, until then, the
 * common layout (x across the dial toward 3 o'clock, which on the left wrist points to the hand).
 *
 * <p>The protocol (little-endian; fits the 20 bytes of a Bluetooth LE write, more samples in a packet when the link
 * allows):
 * <pre>
 *   HELLO  [0]=0x00 [1]=version: 1 — samples follow, 2 — the watch recognises turns, TURN packets follow
 *          [2..5]=watch id (u32, random, kept by the watch) [6..7]=samples per second (u16)
 *          [8..9]=counts per g (u16, 0 = not known) [10..]=platform, ASCII (≤ 10)
 *   ACCEL  [0]=0x10|n (n = 1..15 samples) [1]=sequence (u8, +1 per packet) then n × (x, y, z) as int16
 *   TURN   [0]=0x20 [1]=sequence (u8) [2]=how far, degrees (u8) [3]=how long, 10 ms (u8)
 *          [4..9]=the axis it went around, in the watch's axes, × 10000 (3 × int16)
 * </pre>
 * The phone answers on {@link #EVENTS} with one byte: {@link #EV_NEXT}, {@link #EV_PREVIOUS}, {@link #EV_LEARNED},
 * {@link #EV_IGNORED} (a turn while no music is playing) — the watch vibrates; and {@link #EV_PAUSE} when the music
 * stops, {@link #EV_STREAM} when it plays again: a watch that listens to them switches its sensor off in between and
 * saves its battery (the connection stays).
 */
public final class WristGesture {
    /** The phone's Bluetooth LE service, the watch writes samples to {@link #SAMPLES}, reads answers from {@link #EVENTS}. */
    public static final String SERVICE = "a7b7e001-9ca3-4574-a1f0-3c9840e24d01",
            SAMPLES = "a7b7e002-9ca3-4574-a1f0-3c9840e24d01",
            EVENTS = "a7b7e003-9ca3-4574-a1f0-3c9840e24d01";
    /** HELLO versions: the watch streams samples / the watch recognises turns and sends TURN packets. */
    public static final int VERSION = 1, VERSION_TURNS = 2;
    public static final int KIND_HELLO = 0, KIND_ACCEL = 1, KIND_TURN = 2;
    public static final int EV_IGNORED = 0, EV_NEXT = 1, EV_PREVIOUS = 2, EV_LEARNED = 3, EV_PAUSE = 4, EV_STREAM = 5;

    /** What a sample (or a packet) ended: nothing, a turn to the right, to the left, the right turn learnt. */
    public static final int NONE = 0, RIGHT = 1, LEFT = 2, LEARNED = 3;

    /** Around the forearm: the turn's axis within this many degrees of it. */
    static final float AXIS_DEG = 40;
    /** Samples per second when the watch has not said. */
    static final int DEFAULT_RATE = 25;

    /** How far the hand has to turn (degrees): 30 sensitive, 40 usual, 55 only clear turns. */
    public float turn = 40;

    // the watch
    private int watchId, rate = DEFAULT_RATE, perG, version;
    private String platform = "";
    private boolean hello;
    private int lastSeq = -1;
    private long samples, packets, gaps, turns;

    // the direction: the axis a turn to the right goes around, in the watch's own axes
    private final float[] right = {1, 0, 0};
    private boolean learnt, learning;
    private final float[] lastAxis = new float[3];
    private float lastPeak, lastTook;

    // the raw samples (a watch that streams them)
    private final Finder finder = new Finder();
    private int rawX, rawY, rawZ;

    // ------------------------------------------------------------------ the watch

    /** The id the watch sent (0 until its HELLO). */
    public int watchId() {
        return watchId;
    }

    public boolean hasHello() {
        return hello;
    }

    /** Does the watch recognise turns itself (TURN packets) rather than stream samples? */
    public boolean recognises() {
        return version >= VERSION_TURNS;
    }

    public String platform() {
        return platform;
    }

    public int rate() {
        return rate;
    }

    /** Counts per g the watch says it sends (0: not said). */
    public int perG() {
        return perG;
    }

    /** The size of gravity measured in the watch's units (0 until the hand was still once). */
    public float gravity() {
        return finder.gravity();
    }

    /** For the screen: how far the watch is tilted from where the hand last rested, in degrees (streamed samples). */
    public float tilt() {
        return finder.tilt();
    }

    /** For the screen: the last raw sample received (x, y, z in the watch's own units), to see if it moves at all. */
    public int[] lastRaw() {
        return new int[]{rawX, rawY, rawZ};
    }

    public long samples() {
        return samples;
    }

    public long packets() {
        return packets;
    }

    /** Turns that came (TURN packets, or found in the samples), whatever they meant. */
    public long turns() {
        return turns;
    }

    /** Packets lost on the way (by the sequence numbers). */
    public long gaps() {
        return gaps;
    }

    /** The last turn: how far (degrees) and how long (ms). */
    public float lastPeak() {
        return lastPeak;
    }

    public float lastTook() {
        return lastTook;
    }

    // ------------------------------------------------------------------ direction

    /** The next turn (whichever way) is the turn to the right: its axis is kept. */
    public void learn() {
        learning = true;
    }

    public boolean learning() {
        return learning;
    }

    /** The axis of a turn to the right learnt earlier (3 numbers), or for the left/right hand when none was. */
    public void setRight(float[] axis, boolean leftHand) {
        if (axis != null && axis.length == 3 && norm(axis) > 0.5f) {
            float n = norm(axis);
            for (int i = 0; i < 3; i++) right[i] = axis[i] / n;
            learnt = true;
        } else {
            // x toward 3 o'clock: to the hand on the left wrist, to the elbow on the right one
            right[0] = leftHand ? 1 : -1;
            right[1] = 0;
            right[2] = 0;
            learnt = false;
        }
    }

    /** The axis of the last turn seen (what {@link #learn()} keeps). */
    public float[] lastAxis() {
        return lastAxis.clone();
    }

    public boolean learnt() {
        return learnt;
    }

    // ------------------------------------------------------------------ packets

    /** One packet from the watch; what it ended ({@link #NONE}, {@link #RIGHT}, {@link #LEFT}, {@link #LEARNED}). */
    public int packet(byte[] p) {
        if (p == null || p.length < 2) return NONE;
        int kind = (p[0] & 0xff) >>> 4, n = p[0] & 0x0f;
        if (kind == KIND_HELLO) {
            if (p.length < 10 || (p[1] & 0xff) < 1) return NONE;
            version = p[1] & 0xff;
            watchId = u16(p, 2) | (u16(p, 4) << 16);
            int r = u16(p, 6);
            rate = r >= 1 && r <= 400 ? r : DEFAULT_RATE;
            perG = u16(p, 8); // what the watch says; the size of gravity is still measured, it decides
            StringBuilder sb = new StringBuilder();
            for (int i = 10; i < p.length && i < 20; i++) {
                int ch = p[i] & 0xff;
                if (ch >= 0x20 && ch < 0x7f) sb.append((char) ch);
            }
            platform = sb.toString().trim();
            hello = true;
            lastSeq = -1;
            finder.reset();
            return NONE;
        }
        if (kind == KIND_TURN) {
            if (p.length < 10) return NONE;
            packets++;
            float[] axis = {(short) u16(p, 4) / 10000f, (short) u16(p, 6) / 10000f, (short) u16(p, 8) / 10000f};
            return decide(p[2] & 0xff, (p[3] & 0xff) * 10f, axis);
        }
        if (kind != KIND_ACCEL || n == 0 || p.length < 2 + 6 * n) return NONE;
        packets++;
        int seq = p[1] & 0xff;
        if (lastSeq >= 0 && seq != ((lastSeq + 1) & 0xff)) {
            // lost on the way: the time moves on by what was lost, and a turn half seen is not trusted
            int lost = (seq - lastSeq - 1) & 0xff;
            gaps += lost;
            samples += (long) lost * n;
            finder.reset();
        }
        lastSeq = seq;
        int out = NONE;
        for (int i = 0; i < n; i++) {
            int o = 2 + 6 * i;
            int r = sample((short) u16(p, o), (short) u16(p, o + 2), (short) u16(p, o + 4));
            if (out == NONE) out = r;
        }
        return out;
    }

    private static int u16(byte[] p, int o) {
        return (p[o] & 0xff) | ((p[o + 1] & 0xff) << 8);
    }

    /** A HELLO packet (for the watch side and the tests); version 1: samples follow. */
    public static byte[] hello(int watchId, int rate, int perG, String platform) {
        return hello(VERSION, watchId, rate, perG, platform);
    }

    /** A HELLO packet of a version: {@link #VERSION} samples follow, {@link #VERSION_TURNS} TURN packets follow. */
    public static byte[] hello(int version, int watchId, int rate, int perG, String platform) {
        byte[] name = platform == null ? new byte[0] : platform.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        int len = Math.min(10, name.length);
        byte[] p = new byte[10 + len];
        p[0] = (byte) (KIND_HELLO << 4);
        p[1] = (byte) version;
        p[2] = (byte) watchId;
        p[3] = (byte) (watchId >>> 8);
        p[4] = (byte) (watchId >>> 16);
        p[5] = (byte) (watchId >>> 24);
        p[6] = (byte) rate;
        p[7] = (byte) (rate >>> 8);
        p[8] = (byte) perG;
        p[9] = (byte) (perG >>> 8);
        System.arraycopy(name, 0, p, 10, len);
        return p;
    }

    /** An ACCEL packet of samples {x, y, z, x, y, z…} (for the watch side and the tests). */
    public static byte[] accel(int seq, short[] xyz, int from, int n) {
        byte[] p = new byte[2 + 6 * n];
        p[0] = (byte) ((KIND_ACCEL << 4) | n);
        p[1] = (byte) seq;
        for (int i = 0; i < 3 * n; i++) {
            short v = xyz[3 * from + i];
            p[2 + 2 * i] = (byte) v;
            p[3 + 2 * i] = (byte) (v >>> 8);
        }
        return p;
    }

    /** A TURN packet: how far (degrees), how long (ms), around which axis (the watch's axes, unit length). */
    public static byte[] turnPacket(int seq, float peakDeg, float tookMs, float[] axis) {
        byte[] p = new byte[10];
        p[0] = (byte) (KIND_TURN << 4);
        p[1] = (byte) seq;
        p[2] = (byte) Math.max(0, Math.min(255, Math.round(peakDeg)));
        p[3] = (byte) Math.max(0, Math.min(255, Math.round(tookMs / 10)));
        for (int i = 0; i < 3; i++) {
            int v = Math.max(-32768, Math.min(32767, Math.round(axis[i] * 10000)));
            p[4 + 2 * i] = (byte) v;
            p[5 + 2 * i] = (byte) (v >>> 8);
        }
        return p;
    }

    // ------------------------------------------------------------------ samples

    /** The stream starts again after a pause: what was seen before does not count. */
    public void restart() {
        finder.reset();
    }

    /** One accelerometer sample in the watch's units, at the watch's rate (streamed by a watch). */
    public int sample(float x, float y, float z) {
        rawX = Math.round(x);
        rawY = Math.round(y);
        rawZ = Math.round(z);
        double t = samples * 1000.0 / rate;
        samples++;
        if (!finder.sample(x, y, z, t)) return NONE;
        return decide(finder.peak(), finder.took(), finder.axis());
    }

    /** What a turn means: big enough? around the forearm? which way? (or, being taught, the way to the right). */
    private int decide(float peakDeg, float tookMs, float[] axis) {
        float n = norm(axis);
        if (n < 0.5f) return NONE;
        turns++;
        lastPeak = peakDeg;
        lastTook = tookMs;
        for (int i = 0; i < 3; i++) lastAxis[i] = axis[i] / n;
        if (peakDeg < turn) return NONE;
        if (learning) {
            learning = false;
            setRight(lastAxis, true);
            return LEARNED;
        }
        float d = dot(lastAxis, right);
        float cos = (float) Math.cos(Math.toRadians(AXIS_DEG));
        if (d >= cos) return RIGHT;
        if (d <= -cos) return LEFT;
        return NONE; // not around the forearm
    }

    // ------------------------------------------------------------------ the turn itself

    /**
     * Finds turns in an accelerometer's samples, by their real times (the rate may wander): the hand still for a moment
     * (where it rests), then away from there by more than {@link #START_DEG} and back near it within {@link #MAX_MS}.
     * A quick turn is seen by the watch as gravity turning and the watch's own acceleration on top (it sits on the wrist,
     * a few cm off the forearm's axis): at the start and at the end the watch swings a little the other way, at the far
     * end further. So the swing that went furthest decides, and a turn ends only when, after coming back, no further
     * swing follows within {@link #GRACE_MS}. The same code is in the watches' JavaScript (watch/vela/src/common/proto.js).
     */
    public static final class Finder {
        /** The hand still: over this long (and 3 samples at least), every sample within STILL_DEG of their mean. */
        static final double STILL_MS = 320, STILL_DEG = 10, STILL_MAG = 0.18;
        /** The turn starts this far from the rest, and within FRESH_MS after the hand was still. */
        static final double START_DEG = 14, FRESH_MS = 600;
        /** Back near the rest: within BACK_DEG, or within BACK_FRAC of how far it went. */
        static final double BACK_DEG = 15, BACK_FRAC = 0.5;
        /** Smaller turns are not reported; a turn takes MIN_MS at least and MAX_MS at most (slower is not a gesture). */
        static final double MIN_DEG = 22, MIN_MS = 90, MAX_MS = 900;
        /** After coming back: this long for a further, bigger swing (the one before was the run-up). */
        static final double GRACE_MS = 140;
        /** The low-pass of the signal (Hz): smooths the watch's own jolt at the start of a quick turn. */
        static final double CUT_HZ = 5;
        /** Beyond these (× gravity): a knock or a throw, not a turn. */
        static final double KNOCK_HI = 3.0, KNOCK_LO = 0.25;

        static final int N = 64;
        private final double[] ts = new double[N];
        private final double[] ux = new double[N], uy = new double[N], uz = new double[N], mg = new double[N];
        private int count, at;
        private final double[] f = new double[3];
        private boolean primed;
        private double lastT;
        private double g;
        private boolean gSeen;

        private final double[] rest = new double[3];
        private boolean haveRest;
        private double lastStill = Double.NEGATIVE_INFINITY;
        /** 0 waiting, 1 away from the rest, 2 back — waiting whether a bigger swing follows. */
        private int state;
        private double startT, endT;
        private double peak, took, tilt;
        private final double[] peakU = new double[3];
        private final double[] axis = new double[3];

        public void reset() {
            count = 0;
            primed = false;
            haveRest = false;
            lastStill = Double.NEGATIVE_INFINITY;
            state = 0;
        }

        public float gravity() {
            return gSeen ? (float) g : 0;
        }

        /** How far from where the hand last rested (degrees), for the screen. */
        public float tilt() {
            return (float) tilt;
        }

        /** The turn just found: how far it went (degrees), how long it took (ms), its axis (the watch's axes, unit). */
        public float peak() {
            return (float) peak;
        }

        public float took() {
            return (float) took;
        }

        public float[] axis() {
            return new float[]{(float) axis[0], (float) axis[1], (float) axis[2]};
        }

        /** One sample at time t (ms); true when a turn has just ended. */
        public boolean sample(double x, double y, double z, double t) {
            double dt = primed ? t - lastT : 0;
            if (primed && (dt <= 0 || dt > 1000)) {
                // the clock went back or the samples stopped: start afresh
                reset();
                dt = 0;
            }
            lastT = t;
            if (!primed) {
                f[0] = x;
                f[1] = y;
                f[2] = z;
                primed = true;
            } else {
                double a = 1 - Math.exp(-2 * Math.PI * CUT_HZ * dt / 1000);
                f[0] += a * (x - f[0]);
                f[1] += a * (y - f[1]);
                f[2] += a * (z - f[2]);
            }
            double m = Math.sqrt(f[0] * f[0] + f[1] * f[1] + f[2] * f[2]);
            if (!(m > 0)) return false;
            int k = at;
            at = (at + 1) % N;
            if (count < N) count++;
            ts[k] = t;
            ux[k] = f[0] / m;
            uy[k] = f[1] / m;
            uz[k] = f[2] / m;
            mg[k] = m;

            if (gSeen && (m > KNOCK_HI * g || m < KNOCK_LO * g)) {
                // a knock or a throw
                state = 0;
                lastStill = Double.NEGATIVE_INFINITY;
                return false;
            }
            if (state == 0 && still(t)) {
                // where the hand rests now
                lastStill = t;
                haveRest = true;
            }
            if (!haveRest) return false;
            double deg = angleTo(k);
            tilt = deg;
            if (state == 0) {
                if (deg > START_DEG && t - lastStill <= FRESH_MS) {
                    state = 1;
                    startT = lastStill;
                    peak = deg;
                    copyU(k, peakU);
                }
                return false;
            }
            if (deg > peak) {
                // further than before: a bigger swing (after the run-up, or still on the way)
                peak = deg;
                copyU(k, peakU);
                state = 1;
            }
            if (state == 1) {
                if (t - startT > MAX_MS) {
                    // turned and stayed: a new position, not a gesture
                    state = 0;
                    haveRest = false;
                    lastStill = Double.NEGATIVE_INFINITY;
                    return false;
                }
                if (deg <= Math.max(BACK_DEG, BACK_FRAC * peak)) {
                    if (peak < MIN_DEG) {
                        // a wobble: still where it rests
                        state = 0;
                        return false;
                    }
                    state = 2;
                    endT = t;
                }
                return false;
            }
            // state 2: back; a further swing would have made state 1 above
            if (t - endT < GRACE_MS) return false;
            state = 0;
            lastStill = Double.NEGATIVE_INFINITY; // still again before the next one
            took = endT - startT;
            if (took < MIN_MS) return false;
            double cx = rest[1] * peakU[2] - rest[2] * peakU[1], cy = rest[2] * peakU[0] - rest[0] * peakU[2],
                    cz = rest[0] * peakU[1] - rest[1] * peakU[0];
            double n = Math.sqrt(cx * cx + cy * cy + cz * cz);
            if (n < 1e-6) return false;
            // the watch turned around this axis (gravity, seen from the watch, turned the other way)
            axis[0] = -cx / n;
            axis[1] = -cy / n;
            axis[2] = -cz / n;
            return true;
        }

        private double angleTo(int k) {
            double c = rest[0] * ux[k] + rest[1] * uy[k] + rest[2] * uz[k];
            return Math.acos(Math.max(-1, Math.min(1, c))) * 180 / Math.PI;
        }

        private void copyU(int k, double[] out) {
            out[0] = ux[k];
            out[1] = uy[k];
            out[2] = uz[k];
        }

        /** Still over the last STILL_MS (3 samples at least): if so, the rest and the size of gravity are taken. */
        private boolean still(double t) {
            int n = 0;
            double sx = 0, sy = 0, sz = 0, sm = 0;
            for (int i = 0; i < count; i++) {
                int k = (at - 1 - i + N) % N;
                if (n >= 3 && t - ts[k] > STILL_MS) break;
                sx += ux[k];
                sy += uy[k];
                sz += uz[k];
                sm += mg[k];
                n++;
            }
            if (n < 3 || t - ts[(at - n + N) % N] < STILL_MS * 0.66) return false;
            double len = Math.sqrt(sx * sx + sy * sy + sz * sz);
            if (len <= 0) return false;
            sx /= len;
            sy /= len;
            sz /= len;
            double mm = sm / n;
            double cos = Math.cos(STILL_DEG * Math.PI / 180);
            for (int i = 0; i < n; i++) {
                int k = (at - 1 - i + N) % N;
                if (ux[k] * sx + uy[k] * sy + uz[k] * sz < cos) return false;
                if (Math.abs(mg[k] - mm) > STILL_MAG * mm) return false;
            }
            rest[0] = sx;
            rest[1] = sy;
            rest[2] = sz;
            if (!gSeen) {
                g = mm;
                gSeen = true;
            } else {
                g += 0.1 * (mm - g);
            }
            return true;
        }
    }

    // ------------------------------------------------------------------ vectors

    static float norm(float[] v) {
        return (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }

    static float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    static float[] cross(float[] a, float[] b) {
        return new float[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }
}
