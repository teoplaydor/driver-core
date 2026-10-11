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
 *          [4..9]=the axis it went around, in the watch's axes, × 10000 (3 × int16) — the first watch apps' turn
 *   SWING  [0]=0x30 [1]=sequence (u8) [2]=how far, degrees (u8) [3]=how long, 10 ms (u8)
 *          [4..9]=«up» at rest, [10..15]=«up» at the far end, in the watch's axes, × 10000 (3 × int16 each)
 *          [16..17]=how far the compass heading went, 0.1° (int16, east positive; −32768 — no compass)
 * </pre>
 * The turn is a twist around the forearm, measured around the forearm whatever way the arm is held: by gravity when
 * the forearm lies more or less flat (its turn around the forearm's axis, the forearm's own slope kept — that tells a
 * twist from raising the hand), by the compass when the forearm is upright (the arm along the body: a twist turns the
 * watch around gravity, which the accelerometer cannot see).
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
    public static final int KIND_HELLO = 0, KIND_ACCEL = 1, KIND_TURN = 2, KIND_SWING = 3;
    public static final int EV_IGNORED = 0, EV_NEXT = 1, EV_PREVIOUS = 2, EV_LEARNED = 3, EV_PAUSE = 4, EV_STREAM = 5;

    /** What a sample (or a packet) ended: nothing, a turn to the right, to the left, the right turn learnt. */
    public static final int NONE = 0, RIGHT = 1, LEFT = 2, LEARNED = 3;

    /** Around the forearm: the turn's axis within this many degrees of it (TURN packets of the first watch apps). */
    static final float AXIS_DEG = 40;
    /** Gravity tells the twist while it leans this far off the forearm at least (sine); below, only the compass can. */
    static final float LEVER_MIN = 0.3f;
    /** The compass is preferred while gravity leans less than this off the forearm (sine), if there is one. */
    static final float COMPASS_LEVER = 0.4f;
    /** A twist keeps the forearm's slope: the cosine between «up» and the forearm changes by this much at most… */
    static final float KEEP = 0.3f;
    /** …and, told by gravity, the angle by this much at most (degrees); a wrist does not twist further than MAX_TWIST. */
    static final float KEEP_DEG = 25, MAX_TWIST = 135;
    /** Why the last turn did nothing: it did something, too small, not around the forearm, upright with no compass. */
    public static final int WHY_OK = 0, WHY_SMALL = 1, WHY_NOT_FOREARM = 2, WHY_UPRIGHT = 3;
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
    private int why;
    private boolean lastByCompass;

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

    /** Why the last turn did nothing ({@link #WHY_OK} when it did something). */
    public int why() {
        return why;
    }

    /** Was the last turn seen by the compass (the forearm upright) rather than by gravity? */
    public boolean lastByCompass() {
        return lastByCompass;
    }

    // ------------------------------------------------------------------ direction

    /** The next turn (whichever way) is the turn to the right: its axis is kept. */
    public void learn() {
        learning = true;
    }

    public boolean learning() {
        return learning;
    }

    /**
     * The axis of a turn to the right learnt earlier (3 numbers), or for the left/right hand when none was. The forearm
     * lies along one of the watch's axes, so a learnt axis near one is taken as that axis (a turn taught with the arm
     * a little raised shows its axis a little off).
     */
    public void setRight(float[] axis, boolean leftHand) {
        if (axis != null && axis.length == 3 && norm(axis) > 0.5f) {
            float[] a = snap(axis);
            System.arraycopy(a, 0, right, 0, 3);
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

    /** The watch's axis (±x, ±y, ±z) within 35° of v, or v itself (unit). */
    static float[] snap(float[] v) {
        float n = norm(v);
        int best = 0;
        for (int i = 1; i < 3; i++) if (Math.abs(v[i]) > Math.abs(v[best])) best = i;
        float[] out = new float[3];
        if (Math.abs(v[best]) / n >= (float) Math.cos(Math.toRadians(35))) {
            out[best] = Math.signum(v[best]);
        } else {
            for (int i = 0; i < 3; i++) out[i] = v[i] / n;
        }
        return out;
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
        if (kind == KIND_SWING) {
            if (p.length < 18) return NONE;
            packets++;
            float[] rest = {(short) u16(p, 4) / 10000f, (short) u16(p, 6) / 10000f, (short) u16(p, 8) / 10000f};
            float[] far = {(short) u16(p, 10) / 10000f, (short) u16(p, 12) / 10000f, (short) u16(p, 14) / 10000f};
            int h = (short) u16(p, 16);
            return swing(p[2] & 0xff, (p[3] & 0xff) * 10f, rest, far, h == -32768 ? Float.NaN : h / 10f);
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

    /**
     * A SWING packet: how far (degrees, gravity or compass), how long (ms), «up» at rest and at the far end (the watch's
     * axes, unit), how far the compass heading went (degrees; NaN — no compass).
     */
    public static byte[] swingPacket(int seq, float peakDeg, float tookMs, float[] rest, float[] far, float headingDeg) {
        byte[] p = new byte[18];
        p[0] = (byte) (KIND_SWING << 4);
        p[1] = (byte) seq;
        p[2] = (byte) Math.max(0, Math.min(255, Math.round(peakDeg)));
        p[3] = (byte) Math.max(0, Math.min(255, Math.round(tookMs / 10)));
        for (int i = 0; i < 3; i++) {
            put16(p, 4 + 2 * i, Math.round(rest[i] * 10000));
            put16(p, 10 + 2 * i, Math.round(far[i] * 10000));
        }
        put16(p, 16, Float.isNaN(headingDeg) ? -32768 : Math.max(-32767, Math.min(32767, Math.round(headingDeg * 10))));
        return p;
    }

    private static void put16(byte[] p, int o, int v) {
        v = Math.max(-32768, Math.min(32767, v));
        p[o] = (byte) v;
        p[o + 1] = (byte) (v >>> 8);
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
        return swing(finder.peak(), finder.took(), finder.rest(), finder.peakUp(), finder.headingChange());
    }

    /**
     * What a turn means, from where «up» was at rest and at the far end (the watch's axes) and how far the compass went:
     * the twist around the forearm — by gravity when it leans off the forearm enough, by the compass when the forearm is
     * upright — big enough? which way? (or, being taught, the way to the right).
     */
    private int swing(float peakDeg, float tookMs, float[] rest, float[] far, float headingDeg) {
        float rn = norm(rest), fn = norm(far);
        if (rn < 0.5f || fn < 0.5f) return NONE;
        float[] r = {rest[0] / rn, rest[1] / rn, rest[2] / rn}, u = {far[0] / fn, far[1] / fn, far[2] / fn};
        turns++;
        lastTook = tookMs;
        lastPeak = peakDeg;
        float[] c = cross(r, u);
        float cn = norm(c);
        if (cn > 1e-6f) for (int i = 0; i < 3; i++) lastAxis[i] = -c[i] / cn;
        boolean compass = !Float.isNaN(headingDeg);
        if (learning) {
            // the forearm's axis and the way to the right: from gravity's turn when the forearm lay flat enough, else the
            // compass's (the forearm upright: its axis is where «up» is, the heading tells the way)
            float[] axis = null;
            float[] up = snap(r);
            boolean upright = compass && Math.abs(up[0]) + Math.abs(up[1]) + Math.abs(up[2]) == 1; // «up» along one axis
            if (upright && Math.abs(headingDeg) >= turn) {
                // a quick twist's own jolt moves gravity a little too, but here only the compass tells the twist
                axis = new float[]{-Math.signum(headingDeg) * up[0], -Math.signum(headingDeg) * up[1], -Math.signum(headingDeg) * up[2]};
            } else if (!upright && cn > (float) Math.sin(Math.toRadians(20)) && peakDeg >= turn) {
                axis = snap(lastAxis);
            }
            if (axis == null) {
                why = WHY_SMALL;
                return NONE;
            }
            learning = false;
            System.arraycopy(axis, 0, lastAxis, 0, 3);
            setRight(axis, true);
            why = WHY_OK;
            return LEARNED;
        }
        float c0 = dot(r, right), c1 = dot(u, right);
        float lever = (float) Math.sqrt(Math.max(0, 1 - c0 * c0)), lever1 = (float) Math.sqrt(Math.max(0, 1 - c1 * c1));
        if (Math.abs(c1 - c0) > KEEP) {
            // the forearm's slope changed: the hand was raised or lowered, not twisted
            why = WHY_NOT_FOREARM;
            return NONE;
        }
        float twist;
        if (compass && lever < COMPASS_LEVER) {
            // the forearm upright: the heading turned as the watch turned around it (east positive; the sign by which way
            // the forearm's axis points, up or down)
            twist = (c0 < 0 ? 1 : -1) * headingDeg;
            lastByCompass = true;
        } else if (lever >= LEVER_MIN && lever1 >= LEVER_MIN) {
            // gravity leans off the forearm at rest and at the far end alike (else its turn around the forearm is noise),
            // and the forearm's slope stayed
            if (Math.abs(Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, c1))) - Math.acos(Math.max(-1, Math.min(1, c0))))) > KEEP_DEG) {
                why = WHY_NOT_FOREARM;
                return NONE;
            }
            twist = twistAbout(right, r, u);
            lastByCompass = false;
        } else {
            why = WHY_UPRIGHT;
            return NONE;
        }
        lastPeak = Math.abs(twist);
        if (lastPeak > MAX_TWIST) {
            why = WHY_NOT_FOREARM;
            return NONE;
        }
        if (Math.abs(twist) < turn) {
            why = WHY_SMALL;
            return NONE;
        }
        why = WHY_OK;
        return twist > 0 ? RIGHT : LEFT;
    }

    /** How far the watch turned around k (degrees, right-handed) while «up», seen from the watch, went from a to b. */
    static float twistAbout(float[] k, float[] a, float[] b) {
        float ak = dot(a, k), bk = dot(b, k);
        float[] pa = {a[0] - ak * k[0], a[1] - ak * k[1], a[2] - ak * k[2]};
        float[] pb = {b[0] - bk * k[0], b[1] - bk * k[1], b[2] - bk * k[2]};
        double ang = Math.atan2(dot(k, cross(pa, pb)), dot(pa, pb));
        // gravity, seen from the watch, turned the other way
        return (float) -Math.toDegrees(ang);
    }

    /** A TURN packet of the first watch apps: big enough? around the forearm? which way? (or, being taught, the right). */
    private int decide(float peakDeg, float tookMs, float[] axis) {
        float n = norm(axis);
        if (n < 0.5f) return NONE;
        turns++;
        lastPeak = peakDeg;
        lastTook = tookMs;
        lastByCompass = false;
        for (int i = 0; i < 3; i++) lastAxis[i] = axis[i] / n;
        if (peakDeg < turn) {
            why = WHY_SMALL;
            return NONE;
        }
        if (learning) {
            learning = false;
            setRight(lastAxis, true);
            why = WHY_OK;
            return LEARNED;
        }
        float d = dot(lastAxis, right);
        float cos = (float) Math.cos(Math.toRadians(AXIS_DEG));
        why = WHY_OK;
        if (d >= cos) return RIGHT;
        if (d <= -cos) return LEFT;
        why = WHY_NOT_FOREARM;
        return NONE; // not around the forearm
    }

    // ------------------------------------------------------------------ the turn itself

    /**
     * Finds turns in an accelerometer's samples, by their real times (the rate may wander): the hand still for a moment
     * (where it rests), then away from there by more than {@link #START_DEG} and back near it within {@link #MAX_MS}.
     * A quick turn is seen by the watch as gravity turning and the watch's own acceleration on top (it sits on the wrist,
     * a few cm off the forearm's axis): at the start and at the end the watch swings a little the other way, at the far
     * end further. So the swing that went furthest decides, and a turn ends only when, after coming back, no further
     * swing follows within {@link #GRACE_MS}.
     *
     * <p>With the forearm upright (the arm hanging along the body, or raised) a twist turns the watch around gravity
     * itself, and gravity barely moves. Then the compass sees it: the heading of the watch's y axis turns by the twist.
     * So a watch that has one passes its heading with each sample, and the heading's swing counts as a swing too
     * (only while the forearm — the watch's x axis on the usual watches — is within ~50° of upright and the y axis lies
     * flat enough for a heading to mean something). What the turn was is told by where gravity pointed at rest and at
     * the far end ({@link #rest()}, {@link #peakUp()}) and how far the heading went ({@link #headingChange()}): the phone
     * works out the twist around the forearm from them. The same code is in the watches' JavaScript
     * (watch/vela/src/common/proto.js).
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
        /** The compass counts while the forearm (x) is this upright (|cos| at least) and the y axis this flat (at most). */
        static final double UPRIGHT = 0.6, FLAT_Y = 0.75;

        static final int N = 64;
        private final double[] ts = new double[N];
        private final double[] ux = new double[N], uy = new double[N], uz = new double[N], mg = new double[N], hd = new double[N];
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
        // the compass: the heading now and at rest (radians, NaN — none), the furthest it went (degrees, signed)
        private double curH = Double.NaN, restH = Double.NaN;
        private double gravPeak, headPeak, headAt;
        private boolean headUsed;

        public void reset() {
            count = 0;
            primed = false;
            haveRest = false;
            lastStill = Double.NEGATIVE_INFINITY;
            state = 0;
            restH = Double.NaN;
        }

        public float gravity() {
            return gSeen ? (float) g : 0;
        }

        /** How far from where the hand last rested (degrees, gravity or compass, whichever more), for the screen. */
        public float tilt() {
            return (float) tilt;
        }

        /** The turn just found: how far it went (degrees, gravity or compass), how long it took (ms). */
        public float peak() {
            return (float) peak;
        }

        public float took() {
            return (float) took;
        }

        /** The axis of the smallest rotation from rest to the far end (the watch's axes, unit). */
        public float[] axis() {
            return new float[]{(float) axis[0], (float) axis[1], (float) axis[2]};
        }

        /** Where «up» was at rest, and at the far end of the turn (the watch's axes, unit). */
        public float[] rest() {
            return new float[]{(float) rest[0], (float) rest[1], (float) rest[2]};
        }

        public float[] peakUp() {
            return new float[]{(float) peakU[0], (float) peakU[1], (float) peakU[2]};
        }

        /** How far the compass heading went during the turn (degrees, east positive), NaN when it did not count. */
        public float headingChange() {
            return headUsed ? (float) headAt : Float.NaN;
        }

        /** One sample at time t (ms); true when a turn has just ended. */
        public boolean sample(double x, double y, double z, double t) {
            return sample(x, y, z, t, Double.NaN);
        }

        /** One sample at time t (ms) with the compass heading then (radians, north 0, east π/2; NaN — none). */
        public boolean sample(double x, double y, double z, double t, double heading) {
            curH = heading;
            double dt = primed ? t - lastT : 0;
            if (primed && (dt < 0 || dt > 1000)) {
                // the clock went back or the samples stopped: start afresh (samples at the same time are still samples)
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
            hd[k] = heading;

            if (gSeen && (m > KNOCK_HI * g || m < KNOCK_LO * g)) {
                // a knock or a throw
                state = 0;
                lastStill = Double.NEGATIVE_INFINITY;
                return false;
            }
            if (state == 0 && still(t)) {
                // where the hand rests now (and where the watch faces)
                lastStill = t;
                haveRest = true;
            }
            if (!haveRest) return false;
            double c = rest[0] * ux[k] + rest[1] * uy[k] + rest[2] * uz[k];
            double grav = Math.acos(Math.max(-1, Math.min(1, c))) * 180 / Math.PI;
            double head = Double.isNaN(restH) || Double.isNaN(curH) ? Double.NaN : wrapDeg((curH - restH) * 180 / Math.PI);
            double deg = Double.isNaN(head) ? grav : Math.max(grav, Math.abs(head));
            tilt = deg;
            if (state == 0) {
                if (deg > START_DEG && t - lastStill <= FRESH_MS) {
                    state = 1;
                    startT = lastStill;
                    peak = deg;
                    gravPeak = grav;
                    copyU(k, peakU);
                    headUsed = !Double.isNaN(head);
                    headPeak = headUsed ? Math.abs(head) : 0;
                    headAt = headUsed ? head : 0;
                }
                return false;
            }
            if (grav > gravPeak) {
                // gravity's far end
                gravPeak = grav;
                copyU(k, peakU);
            }
            if (!Double.isNaN(head) && Math.abs(head) > headPeak) {
                // the compass's far end
                headPeak = Math.abs(head);
                headAt = head;
                headUsed = true;
            }
            if (deg > peak) {
                // further than before: a bigger swing (after the run-up, or still on the way)
                peak = deg;
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
            if (n < 1e-6) {
                axis[0] = axis[1] = axis[2] = 0;
            } else {
                // the watch turned around this axis (gravity, seen from the watch, turned the other way)
                axis[0] = -cx / n;
                axis[1] = -cy / n;
                axis[2] = -cz / n;
            }
            return true;
        }


        static double wrapDeg(double d) {
            while (d > 180) d -= 360;
            while (d < -180) d += 360;
            return d;
        }

        private void copyU(int k, double[] out) {
            out[0] = ux[k];
            out[1] = uy[k];
            out[2] = uz[k];
        }

        /**
         * Still over the last STILL_MS (3 samples at least): if so, the rest and the size of gravity are taken. With the
         * forearm upright a slow twist leaves gravity where it was, so then the compass has to be steady too (or the rest
         * would follow the twist), and where it rests is where the watch faces.
         */
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
            double h = Double.NaN;
            if (!Double.isNaN(curH) && Math.abs(sx) >= UPRIGHT && Math.abs(sy) <= FLAT_Y) {
                // the compass counts: steady too, its mean is where the watch faces
                double hc = 0, hs = 0;
                for (int i = 0; i < n; i++) {
                    int k = (at - 1 - i + N) % N;
                    if (Double.isNaN(hd[k])) continue;
                    hc += Math.cos(hd[k]);
                    hs += Math.sin(hd[k]);
                }
                h = Math.atan2(hs, hc);
                for (int i = 0; i < n; i++) {
                    int k = (at - 1 - i + N) % N;
                    if (!Double.isNaN(hd[k]) && Math.abs(wrapDeg((hd[k] - h) * 180 / Math.PI)) > STILL_DEG) return false;
                }
            }
            restH = h;
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
