package io.github.teoplaydor.semsearch.core;

/**
 * A turn of the wrist to the right or to the left and back, from a watch's raw accelerometer streamed to the phone:
 * any watch whose own small app can send its accelerometer over Bluetooth LE (Wear OS, Xiaomi Vela, Zepp OS, Garmin…)
 * — the watch only sends numbers, the phone recognises the turn. One instance per watch connection.
 *
 * <p>Nothing about the watch is assumed: the units (m/s², milli-g, raw counts) are learnt from the size of gravity when
 * the hand is still; the axes from one turn «to the right» the user makes once ({@link #learn()}), or, until then, the
 * common layout (x across the dial toward 3 o'clock, which on the left wrist points to the hand).
 *
 * <p>The turn: the hand still for a moment, then turned around the forearm by at least {@link #turn} degrees and back
 * within {@link #MAX_MS}. Gravity seen by the watch turns with it, and the axis it turns around tells the direction —
 * around the forearm a twist, around any other axis (raising the hand, swinging the arm while walking) nothing.
 *
 * <p>The protocol (little-endian; fits the 20 bytes of a Bluetooth LE write, more samples in a packet when the link
 * allows):
 * <pre>
 *   HELLO  [0]=0x00 [1]=version (1) [2..5]=watch id (u32, random, kept by the watch) [6..7]=samples per second (u16)
 *          [8..9]=counts per g (u16, 0 = not known) [10..]=platform, ASCII (≤ 10)
 *   ACCEL  [0]=0x10|n (n = 1..15 samples) [1]=sequence (u8, +1 per packet) then n × (x, y, z) as int16
 * </pre>
 * The phone answers on {@link #EVENTS} with one byte: {@link #EV_NEXT}, {@link #EV_PREVIOUS}, {@link #EV_LEARNED},
 * {@link #EV_IGNORED} (a turn while no music is playing) — the watch vibrates; and {@link #EV_PAUSE} when the music
 * stops, {@link #EV_STREAM} when it plays again: a watch that listens to them sends nothing in between and saves its
 * battery (the connection stays).
 */
public final class WristGesture {
    /** The phone's Bluetooth LE service, the watch writes samples to {@link #SAMPLES}, reads answers from {@link #EVENTS}. */
    public static final String SERVICE = "a7b7e001-9ca3-4574-a1f0-3c9840e24d01",
            SAMPLES = "a7b7e002-9ca3-4574-a1f0-3c9840e24d01",
            EVENTS = "a7b7e003-9ca3-4574-a1f0-3c9840e24d01";
    public static final int VERSION = 1;
    public static final int KIND_HELLO = 0, KIND_ACCEL = 1;
    public static final int EV_IGNORED = 0, EV_NEXT = 1, EV_PREVIOUS = 2, EV_LEARNED = 3, EV_PAUSE = 4, EV_STREAM = 5;

    /** What a sample (or a packet) ended: nothing, a turn to the right, to the left, the right turn learnt. */
    public static final int NONE = 0, RIGHT = 1, LEFT = 2, LEARNED = 3;

    /** The turn back has to come within this long. */
    public static final long MAX_MS = 1200;
    /** Shorter than this is a jolt, not a turn. */
    static final long MIN_MS = 90;
    /** The hand still for this long makes the starting position. */
    static final long STILL_MS = 280;
    /** The turn has to start within this long after the hand was still. */
    static final long FRESH_MS = 350;
    /** Leaving the starting position (degrees). */
    static final float START_DEG = 14;
    /** Still: every sample within this many degrees of their mean. */
    static final float STILL_DEG = 5;
    /** Around the forearm: the turn's axis within this many degrees of it. */
    static final float AXIS_DEG = 40;
    /** Samples per second when the watch has not said. */
    static final int DEFAULT_RATE = 25;

    /** How far the hand has to turn (degrees): 30 sensitive, 40 usual, 55 only clear turns. */
    public float turn = 40;

    // the watch
    private int watchId, rate = DEFAULT_RATE, perG;
    private String platform = "";
    private boolean hello;
    private int lastSeq = -1;
    private long samples, packets, gaps;

    // the direction: the axis a turn to the right goes around, in the watch's own axes
    private final float[] right = {1, 0, 0};
    private boolean learnt, learning;
    private final float[] lastAxis = new float[3];

    // the signal
    private final float[] f = new float[3];
    private boolean primed;
    private float g; // the size of gravity in the watch's units, learnt when the hand is still
    private boolean gSeen;
    private float[] ring = new float[0];
    private int ringN, ringAt;

    // the turn
    private final float[] rest = new float[3];
    private boolean haveRest;
    private double lastStill = Double.NEGATIVE_INFINITY;
    private boolean armed;
    private double armedAt;
    private float peakDeg;
    private final float[] peak = new float[3];

    public WristGesture() {
        sizeRing();
    }

    // ------------------------------------------------------------------ the watch

    /** The id the watch sent (0 until its HELLO). */
    public int watchId() {
        return watchId;
    }

    public boolean hasHello() {
        return hello;
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
        return gSeen ? g : 0;
    }

    public long samples() {
        return samples;
    }

    public long packets() {
        return packets;
    }

    /** Packets lost on the way (by the sequence numbers). */
    public long gaps() {
        return gaps;
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
            watchId = u16(p, 2) | (u16(p, 4) << 16);
            int r = u16(p, 6);
            rate = r >= 5 && r <= 400 ? r : DEFAULT_RATE;
            perG = u16(p, 8); // what the watch says; the size of gravity is still measured, it decides
            StringBuilder sb = new StringBuilder();
            for (int i = 10; i < p.length && i < 20; i++) {
                int ch = p[i] & 0xff;
                if (ch >= 0x20 && ch < 0x7f) sb.append((char) ch);
            }
            platform = sb.toString().trim();
            hello = true;
            lastSeq = -1;
            sizeRing();
            reset();
            return NONE;
        }
        if (kind != KIND_ACCEL || n == 0 || p.length < 2 + 6 * n) return NONE;
        packets++;
        int seq = p[1] & 0xff;
        if (lastSeq >= 0 && seq != ((lastSeq + 1) & 0xff)) {
            // lost on the way: the time moves on by what was lost, and a turn half seen is not trusted
            int lost = (seq - lastSeq - 1) & 0xff;
            gaps += lost;
            samples += (long) lost * n;
            reset();
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

    /** A HELLO packet (for the watch side and the tests). */
    public static byte[] hello(int watchId, int rate, int perG, String platform) {
        byte[] name = platform == null ? new byte[0] : platform.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        int len = Math.min(10, name.length);
        byte[] p = new byte[10 + len];
        p[0] = (byte) (KIND_HELLO << 4);
        p[1] = (byte) VERSION;
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

    // ------------------------------------------------------------------ samples

    private void sizeRing() {
        ringN = Math.max(3, Math.round(STILL_MS * rate / 1000f));
        ring = new float[4 * ringN];
        ringAt = 0;
    }

    /** The stream starts again after a pause: what was seen before does not count. */
    public void restart() {
        reset();
    }

    /** Forget the position (a gap, a new HELLO): the hand has to be still again before a turn counts. */
    private void reset() {
        armed = false;
        haveRest = false;
        primed = false;
        lastStill = Double.NEGATIVE_INFINITY;
        ringAt = 0;
    }

    /** One accelerometer sample in the watch's units, at the watch's rate. */
    public int sample(float x, float y, float z) {
        double t = samples * 1000.0 / rate;
        samples++;
        // a light low-pass (≈5 Hz): the jitter of the sensor and of the hand go, a turn stays
        float a = (float) (1 - Math.exp(-2 * Math.PI * 5.0 / rate));
        if (!primed) {
            f[0] = x;
            f[1] = y;
            f[2] = z;
            primed = true;
        } else {
            f[0] += a * (x - f[0]);
            f[1] += a * (y - f[1]);
            f[2] += a * (z - f[2]);
        }
        float m = norm(f);
        if (m <= 0) return NONE;

        int k = ringAt % ringN;
        ring[4 * k] = f[0] / m;
        ring[4 * k + 1] = f[1] / m;
        ring[4 * k + 2] = f[2] / m;
        ring[4 * k + 3] = m;
        ringAt++;
        if (ringAt >= ringN && still()) {
            if (!armed) {
                // the starting position: where the hand rests now
                mean(rest);
                haveRest = true;
            }
            lastStill = t;
            float mm = meanMag();
            if (!gSeen) {
                g = mm;
                gSeen = true;
            } else {
                g += 0.1f * (mm - g);
            }
        }

        if (gSeen && (m > 2.5f * g || m < 0.3f * g)) {
            // a knock or a throw: not a turn
            armed = false;
            lastStill = Double.NEGATIVE_INFINITY;
            return NONE;
        }
        if (!haveRest) return NONE;
        float deg = angle(rest, f);
        if (!armed) {
            if (deg > START_DEG && t - lastStill <= FRESH_MS) {
                armed = true;
                armedAt = t;
                peakDeg = deg;
                copy(f, peak);
            }
            return NONE;
        }
        if (deg > peakDeg) {
            peakDeg = deg;
            copy(f, peak);
        }
        double took = t - armedAt;
        if (took > MAX_MS) {
            // turned and stayed: a new position, not a gesture
            armed = false;
            haveRest = false;
            lastStill = Double.NEGATIVE_INFINITY;
            return NONE;
        }
        float back = Math.max(10, 0.35f * turn);
        if (deg > back) return NONE;
        // back at the start
        armed = false;
        if (peakDeg < turn || took < MIN_MS) return NONE;
        lastStill = Double.NEGATIVE_INFINITY; // still again before the next one
        return decide();
    }

    /** The turn's axis (the watch turned around it; gravity, seen from the watch, turned the other way). */
    private int decide() {
        float[] c = cross(rest, peak);
        float n = norm(c);
        if (n < 1e-6f) return NONE;
        for (int i = 0; i < 3; i++) lastAxis[i] = -c[i] / n;
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

    private boolean still() {
        float[] u = new float[3];
        mean(u);
        float mm = meanMag();
        float cos = (float) Math.cos(Math.toRadians(STILL_DEG));
        for (int i = 0; i < ringN; i++) {
            float d = ring[4 * i] * u[0] + ring[4 * i + 1] * u[1] + ring[4 * i + 2] * u[2];
            if (d < cos) return false;
            if (Math.abs(ring[4 * i + 3] - mm) > 0.08f * mm) return false;
        }
        return true;
    }

    private void mean(float[] out) {
        out[0] = out[1] = out[2] = 0;
        for (int i = 0; i < ringN; i++) {
            out[0] += ring[4 * i];
            out[1] += ring[4 * i + 1];
            out[2] += ring[4 * i + 2];
        }
        float n = norm(out);
        if (n > 0) for (int i = 0; i < 3; i++) out[i] /= n;
    }

    private float meanMag() {
        float s = 0;
        for (int i = 0; i < ringN; i++) s += ring[4 * i + 3];
        return s / ringN;
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

    /** The angle between two directions, degrees. */
    static float angle(float[] a, float[] b) {
        float na = norm(a), nb = norm(b);
        if (na == 0 || nb == 0) return 0;
        double c = dot(a, b) / (na * nb);
        return (float) Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, c))));
    }

    private static void copy(float[] a, float[] b) {
        b[0] = a[0];
        b[1] = a[1];
        b[2] = a[2];
    }
}
