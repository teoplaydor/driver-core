package io.github.teoplaydor.semsearch.core;

/**
 * A turn of the wrist to the right or to the left and back, from a watch's accelerometer (and compass): any watch whose
 * own small app can talk Bluetooth LE (Wear OS, Xiaomi Vela, Zepp OS, Garmin…). One instance per watch connection.
 *
 * <p>The turn is a twist around the forearm, and the forearm lies along the watch's x axis on every watch (x across
 * the dial toward 3 o'clock). So the whole thing is one number: how far the watch has twisted around x — the roll of
 * gravity in the dial's y–z plane while the forearm is not upright, the compass heading while it is (the arm hanging
 * along the body: then a twist turns the watch around gravity itself, which only the compass sees). A turn is that
 * number swinging away from where it has been lately and back, quickly ({@link Finder}). Nothing has to be still
 * before: the «where it has been lately» is a moving average, and a turn while walking or while the hand is busy is
 * seen the same way.
 *
 * <p>A watch that can finds the turn itself (the same {@link Finder} in the watches' JavaScript) and sends one short
 * ROLL packet per turn — the phone's radio and processor stay idle in between. A watch that cannot streams its raw
 * accelerometer and the phone runs the same {@link Finder}. Either way the phone decides what the turn means: big enough
 * ({@link #turn}), the forearm's slope kept (a raised hand is not a twist), and which side is «right» (for the left or
 * the right hand, or as taught by one turn — {@link #learn()}).
 *
 * <p>The protocol (little-endian; fits the 20 bytes of a Bluetooth LE write):
 * <pre>
 *   HELLO  [0]=0x00 [1]=version: 1 — samples follow, 2 — the watch finds the turns, 3 — and plays an answer's pieces
 *          as MP3
 *          [2..5]=watch id (u32, random, kept by the watch) [6..7]=samples per second (u16)
 *          [8..9]=counts per g (u16, 0 = not known) [10..]=platform, ASCII (≤ 10)
 *   ACCEL  [0]=0x10|n (n = 1..15 samples) [1]=sequence (u8, +1 per packet) then n × (x, y, z) as int16
 *   ROLL   [0]=0x40 [1]=sequence (u8) [2..3]=the twist, 0.1° (int16, right-handed around x) [4]=how long, 10 ms (u8)
 *          [5]=seen by: 1 gravity, 2 the compass [6]=the forearm's slope at rest (cos between «up» and x, × 100, int8)
 *          [7]=how far the slope changed meanwhile (degrees, u8)
 *   VOICE  [0]=0x50 start: [1]=note id (u8) [2]=codec (1 — the recorder's own file, Ogg Opus or WAV, told by its
 *                          first bytes; 2 — PCM 16-bit; 3 — WAV; 4 — Opus packets, each after its length, u16;
 *                          5 — IMA ADPCM of the recorder's PCM, see {@link Adpcm})
 *                          [3..4]=samples per second (u16) [5..8]=bytes (u32, 0 — not known yet)
 *          [0]=0x51 data:  [1]=note id [2..5]=offset (u32) then the bytes (as many as the link takes in one write)
 *          [0]=0x52 end:   [1]=note id [2..5]=bytes in all (u32) [6..9]=how long, ms (u32)
 *   RAW    [0]=0x60 [1]=sequence (u8) [2..5]=the watch's time of the first sample, ms (u32) [6]=n, then n × 10 bytes:
 *          ms after the first (u16), x, y, z × 100 (int16, the watch's units), compass heading, 0.1° (int16, −32768 none)
 *          — the sensor as it came, sent only while the phone asks ({@link #CFG_DEBUG}), for the log
 *   DIAG   [0]=0x70 then a line about what the watch did, UTF-8 — for the log, while the phone asks
 * </pre>
 * The phone answers on {@link #EVENTS}: one byte {@link #EV_NEXT}, {@link #EV_PREVIOUS}, {@link #EV_LEARNED},
 * {@link #EV_IGNORED} (a turn while no music is playing) — the watch vibrates; {@link #EV_PAUSE} when the music stops,
 * {@link #EV_STREAM} when it plays again (the watch sends no turns in between); and longer ones ({@link #config},
 * {@link #voiceFrom}, {@link #voiceDone}): {@link #EV_CONFIG} [1]=flags ({@link #CFG_LEFT_HAND}, {@link #CFG_VOICE}, {@link #CFG_DEBUG})
 * [2..3]=the link's MTU (u16, 0 — not known); {@link #EV_VOICE_FROM} [1]=note id [2..5]=offset to send again from;
 * {@link #EV_VOICE_DONE} [1]=note id [2]=what became of it ({@link #VOICE_SAVED}, {@link #VOICE_SILENT},
 * {@link #VOICE_KEPT}, {@link #VOICE_ANSWER}, {@link #VOICE_FAILED}) [3..]=a line about it, UTF-8, for the watch's
 * screen (cut to what one notification holds).
 */
public final class WristGesture {
    /** The phone's Bluetooth LE service, the watch writes samples to {@link #SAMPLES}, reads answers from {@link #EVENTS}. */
    public static final String SERVICE = "a7b7e001-9ca3-4574-a1f0-3c9840e24d01",
            SAMPLES = "a7b7e002-9ca3-4574-a1f0-3c9840e24d01",
            EVENTS = "a7b7e003-9ca3-4574-a1f0-3c9840e24d01";
    /** HELLO versions: the watch streams samples / the watch finds the turns itself. */
    public static final int VERSION = 1, VERSION_TURNS = 2, VERSION_MP3 = 3;
    /** EV_TALK_SEG's format: a WAV file (IMA ADPCM), an MP3 file. */
    public static final int TALK_WAV = 0, TALK_MP3 = 1;
    /**
     * VOICE_PLAYED's outcome: played; the player would not; not whole (a write lost); the file not written; the player
     * said nothing at all (no start, no end).
     */
    public static final int PLAYED = 0, NOT_PLAYED = 1, NOT_WHOLE = 2, NOT_WRITTEN = 3, SILENT = 4;
    public static final int KIND_HELLO = 0, KIND_ACCEL = 1, KIND_TURN = 2, KIND_SWING = 3, KIND_ROLL = 4, KIND_VOICE = 5,
            KIND_RAW = 6, KIND_DIAG = 7;
    /** VOICE packets; VOICE_PLAYED — a piece of a spoken answer played out on the watch ({@link #voicePlayed}). */
    public static final int VOICE_START = 0x50, VOICE_DATA = 0x51, VOICE_END = 0x52, VOICE_PLAYED = 0x53;
    public static final int CODEC_RECORDED = 1, CODEC_PCM16 = 2, CODEC_WAV = 3, CODEC_OPUS_PACKETS = 4, CODEC_ADPCM = 5;
    /** With a VOICE start's codec: a conversation as it is said (not a note) — no resends, the phone streams it on. */
    public static final int CODEC_LIVE = 0x80;
    /**
     * Phone → watch: settings, «send again from», a note's outcome; in a conversation the answer's voice for the watch
     * to play (pieces of a WAV file, then «that piece is whole»), a line of what was said, «the conversation is over».
     */
    public static final int EV_IGNORED = 0, EV_NEXT = 1, EV_PREVIOUS = 2, EV_LEARNED = 3, EV_PAUSE = 4, EV_STREAM = 5,
            EV_CONFIG = 6, EV_VOICE_FROM = 8, EV_VOICE_DONE = 9, EV_TALK_AUDIO = 10, EV_TALK_SEG = 11, EV_TALK_TEXT = 12,
            EV_TALK_END = 13;
    /**
     * EV_CONFIG's flags: the watch on the left hand, voice notes on, the sensor and the watch's doings to the log; the
     * watch at the mouth is a conversation (with Gemini) rather than a note, its answers played on the watch; the
     * conversation goes through the watch as a Bluetooth headset (the watch's app only says when it starts and ends).
     */
    public static final int CFG_LEFT_HAND = 1, CFG_VOICE = 2, CFG_DEBUG = 4, CFG_TALK = 8, CFG_TALK_WATCH = 16, CFG_TALK_HEADSET = 32;
    /**
     * What became of a voice note: a note made (with its reminder…), nothing said in it, kept as a recording without
     * text (no recognition on this phone), a question answered or a search (not a note), the recording not readable.
     */
    public static final int VOICE_SAVED = 0, VOICE_SILENT = 1, VOICE_KEPT = 2, VOICE_ANSWER = 3, VOICE_FAILED = 4;
    public static final int SOURCE_GRAVITY = 1, SOURCE_COMPASS = 2;

    /** What a sample (or a packet) ended: nothing, a turn to the right, to the left, the right turn learnt. */
    public static final int NONE = 0, RIGHT = 1, LEFT = 2, LEARNED = 3;

    /** A twist keeps the forearm's slope: told by gravity, it changes by this much at most (degrees), or by 0.6 of the twist… */
    static final float KEEP_DEG = 25;
    /** …and a wrist does not twist further than this. */
    public static final float MAX_TWIST = 150;
    /** Why the last turn did nothing: it did something, too small, not a twist (the hand raised or lowered), old app. */
    public static final int WHY_OK = 0, WHY_SMALL = 1, WHY_NOT_FOREARM = 2, WHY_OLD_WATCH_APP = 3;
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

    // which way is right: +1 a twist around +x, −1 around −x (the left hand / the right hand, or taught)
    private int rightSign = 1;
    private boolean learnt, learning;
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

    /** Does the watch find the turns itself (ROLL packets) rather than stream samples? */
    public boolean recognises() {
        return version >= VERSION_TURNS;
    }

    /** The watch plays an answer's pieces as MP3 (its app 1.12 on). */
    public boolean playsMp3() {
        return version >= VERSION_MP3;
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

    /** The size of gravity measured in the watch's units (0 until a sample came). */
    public float gravity() {
        return finder.gravity();
    }

    /** For the screen: how far the watch is twisted from where it has been lately, in degrees (streamed samples). */
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

    /** Turns that came (ROLL packets, or found in the samples), whatever they meant. */
    public long turns() {
        return turns;
    }

    /** Packets lost on the way (by the sequence numbers). */
    public long gaps() {
        return gaps;
    }

    /** The last turn: how far (degrees), how long (ms). */
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

    /** The next turn (whichever way) is the turn to the right. */
    public void learn() {
        learning = true;
    }

    public boolean learning() {
        return learning;
    }

    /**
     * Which way is right: a learnt axis (3 numbers — only its side along x matters: the forearm is the watch's x), or
     * for the left/right hand when none was (x toward 3 o'clock points to the hand on the left wrist, to the elbow on the
     * right one). An axis learnt by the first versions that does not lie along x is not trusted.
     */
    public void setRight(float[] axis, boolean leftHand) {
        if (axis != null && axis.length == 3 && norm(axis) > 0.5f && Math.abs(axis[0]) >= 0.5f * norm(axis)) {
            rightSign = axis[0] > 0 ? 1 : -1;
            learnt = true;
        } else {
            rightSign = leftHand ? 1 : -1;
            learnt = false;
        }
    }

    /** The axis of a turn to the right (what {@link #learn()} keeps): ±x. */
    public float[] lastAxis() {
        return new float[]{rightSign, 0, 0};
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
        if (kind == KIND_ROLL) {
            if (p.length < 8) return NONE;
            packets++;
            return decide((short) u16(p, 2) / 10f, (p[4] & 0xff) * 10f, p[5] == SOURCE_COMPASS, p[7] & 0xff);
        }
        if (kind == KIND_TURN || kind == KIND_SWING) {
            // the first watch apps (1.1–1.2): their turns were measured otherwise — the watch app wants updating
            packets++;
            turns++;
            why = WHY_OLD_WATCH_APP;
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

    static int u16(byte[] p, int o) {
        return (p[o] & 0xff) | ((p[o + 1] & 0xff) << 8);
    }

    /** Little-endian u32 at o. */
    public static long u32(byte[] p, int o) {
        return (u16(p, o) | ((long) u16(p, o + 2) << 16)) & 0xffffffffL;
    }

    /** A HELLO packet (for the watch side and the tests); version 1: samples follow. */
    public static byte[] hello(int watchId, int rate, int perG, String platform) {
        return hello(VERSION, watchId, rate, perG, platform);
    }

    /** A HELLO packet of a version: {@link #VERSION} samples follow, {@link #VERSION_TURNS} the watch finds the turns. */
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
     * A ROLL packet: the twist (degrees, right-handed around the watch's x), how long (ms), seen by gravity or the
     * compass, the forearm's slope at rest (cos between «up» and x) and how far it changed (degrees).
     */
    public static byte[] rollPacket(int seq, float twistDeg, float tookMs, int source, float slope, float slopeChange) {
        byte[] p = new byte[8];
        p[0] = (byte) (KIND_ROLL << 4);
        p[1] = (byte) seq;
        put16(p, 2, Math.round(twistDeg * 10));
        p[4] = (byte) Math.max(0, Math.min(255, Math.round(tookMs / 10)));
        p[5] = (byte) source;
        p[6] = (byte) Math.max(-100, Math.min(100, Math.round(slope * 100)));
        p[7] = (byte) Math.max(0, Math.min(255, Math.round(slopeChange)));
        return p;
    }

    /** EV_CONFIG: the settings the watch acts on ({@link #CFG_LEFT_HAND}, {@link #CFG_VOICE}) and the link's MTU. */
    public static byte[] config(int flags, int mtu) {
        return new byte[]{(byte) EV_CONFIG, (byte) flags, (byte) mtu, (byte) (mtu >>> 8)};
    }

    /** EV_VOICE_FROM: send the note again from this offset (a write was lost on the way). */
    public static byte[] voiceFrom(int id, long offset) {
        byte[] p = new byte[6];
        p[0] = (byte) EV_VOICE_FROM;
        p[1] = (byte) id;
        put32(p, 2, offset);
        return p;
    }

    /** EV_VOICE_DONE: what became of the note, and a line about it cut to {@code max} bytes in all (whole letters). */
    public static byte[] voiceDone(int id, int result, String line, int max) {
        byte[] text = line == null ? new byte[0] : line.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int n = Math.max(0, Math.min(text.length, max - 3));
        while (n > 0 && n < text.length && (text[n] & 0xc0) == 0x80) n--; // not in the middle of a letter
        byte[] p = new byte[3 + n];
        p[0] = (byte) EV_VOICE_DONE;
        p[1] = (byte) id;
        p[2] = (byte) result;
        System.arraycopy(text, 0, p, 3, n);
        return p;
    }

    /** EV_TALK_AUDIO: bytes of answer piece {@code seg} (a WAV file) at {@code offset}. */
    public static byte[] talkAudio(int seg, long offset, byte[] b, int from, int n) {
        byte[] p = new byte[6 + n];
        p[0] = (byte) EV_TALK_AUDIO;
        p[1] = (byte) seg;
        put32(p, 2, offset);
        System.arraycopy(b, from, p, 6, n);
        return p;
    }

    /** EV_TALK_SEG: answer piece {@code seg} is whole — {@code bytes} long, {@code ms} to play, a WAV file. */
    public static byte[] talkSeg(int seg, long bytes, int ms) {
        return talkSeg(seg, bytes, ms, TALK_WAV);
    }

    /** EV_TALK_SEG with the piece's format ({@link #TALK_WAV}, {@link #TALK_MP3}). */
    public static byte[] talkSeg(int seg, long bytes, int ms, int format) {
        byte[] p = new byte[8];
        p[0] = (byte) EV_TALK_SEG;
        p[1] = (byte) seg;
        put32(p, 2, bytes);
        p[6] = (byte) Math.min(255, (ms + 99) / 100);
        p[7] = (byte) format;
        return p;
    }

    /** EV_TALK_TEXT: a line to show (what the answer says, what went wrong), cut to {@code max} bytes on whole letters. */
    public static byte[] talkText(String line, int max) {
        byte[] text = line == null ? new byte[0] : line.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int n = Math.max(0, Math.min(text.length, max - 1));
        while (n > 0 && n < text.length && (text[n] & 0xc0) == 0x80) n--;
        byte[] p = new byte[1 + n];
        p[0] = (byte) EV_TALK_TEXT;
        System.arraycopy(text, 0, p, 1, n);
        return p;
    }

    public static byte[] talkEnd() {
        return new byte[]{(byte) EV_TALK_END};
    }

    /** VOICE_PLAYED (watch → phone): answer piece {@code seg} of conversation {@code id} played (ok) or not. */
    public static byte[] voicePlayed(int id, int seg, boolean ok) {
        return voicePlayed(id, seg, ok ? PLAYED : NOT_PLAYED);
    }

    /** VOICE_PLAYED with its outcome ({@link #PLAYED}, {@link #NOT_PLAYED}, {@link #NOT_WHOLE}, {@link #NOT_WRITTEN}). */
    public static byte[] voicePlayed(int id, int seg, int outcome) {
        return new byte[]{(byte) VOICE_PLAYED, (byte) id, (byte) seg, (byte) outcome};
    }

    /** VOICE start, data and end (for the tests: the watch's side is in its JavaScript). */
    public static byte[] voiceStart(int id, int codec, int rate, long bytes) {
        byte[] p = new byte[9];
        p[0] = (byte) VOICE_START;
        p[1] = (byte) id;
        p[2] = (byte) codec;
        p[3] = (byte) rate;
        p[4] = (byte) (rate >>> 8);
        put32(p, 5, bytes);
        return p;
    }

    public static byte[] voiceData(int id, long offset, byte[] b, int from, int n) {
        byte[] p = new byte[6 + n];
        p[0] = (byte) VOICE_DATA;
        p[1] = (byte) id;
        put32(p, 2, offset);
        System.arraycopy(b, from, p, 6, n);
        return p;
    }

    public static byte[] voiceEnd(int id, long bytes, long ms) {
        byte[] p = new byte[10];
        p[0] = (byte) VOICE_END;
        p[1] = (byte) id;
        put32(p, 2, bytes);
        put32(p, 6, ms);
        return p;
    }

    /** RAW: the samples {watch time ms, x, y, z, heading degrees (NaN: none)}; null — not a RAW packet. */
    public static double[][] rawSamples(byte[] p) {
        if (p == null || p.length < 7 || ((p[0] & 0xff) >>> 4) != KIND_RAW) return null;
        long t0 = u32(p, 2);
        int n = Math.min(p[6] & 0xff, (p.length - 7) / 10);
        double[][] out = new double[n][];
        for (int i = 0; i < n; i++) {
            int o = 7 + 10 * i;
            int h = (short) u16(p, o + 8);
            out[i] = new double[]{t0 + u16(p, o), (short) u16(p, o + 2) / 100.0, (short) u16(p, o + 4) / 100.0,
                    (short) u16(p, o + 6) / 100.0, h == -32768 ? Double.NaN : h / 10.0};
        }
        return out;
    }

    /** A RAW packet (for the tests: the watch builds them in its JavaScript). */
    public static byte[] rawPacket(int seq, double[][] samples) {
        int n = samples.length;
        byte[] p = new byte[7 + 10 * n];
        long t0 = n > 0 ? (long) samples[0][0] : 0;
        p[0] = (byte) (KIND_RAW << 4);
        p[1] = (byte) seq;
        put32(p, 2, t0);
        p[6] = (byte) n;
        for (int i = 0; i < n; i++) {
            int o = 7 + 10 * i;
            double[] s = samples[i];
            put16(p, o, (int) Math.max(0, Math.min(32767, Math.round(s[0] - t0))));
            put16(p, o + 2, (int) Math.round(s[1] * 100));
            put16(p, o + 4, (int) Math.round(s[2] * 100));
            put16(p, o + 6, (int) Math.round(s[3] * 100));
            put16(p, o + 8, Double.isNaN(s[4]) ? -32768 : (int) Math.round(((s[4] % 360) + 360) % 360 * 10));
        }
        return p;
    }

    /** DIAG's line; null — not a DIAG packet. */
    public static String diagText(byte[] p) {
        if (p == null || p.length < 1 || ((p[0] & 0xff) >>> 4) != KIND_DIAG) return null;
        return new String(p, 1, p.length - 1, java.nio.charset.StandardCharsets.UTF_8);
    }

    static void put32(byte[] p, int o, long v) {
        for (int i = 0; i < 4; i++) p[o + i] = (byte) (v >>> (8 * i));
    }

    static void put16(byte[] p, int o, int v) {
        v = Math.max(-32768, Math.min(32767, v));
        p[o] = (byte) v;
        p[o + 1] = (byte) (v >>> 8);
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
        return decide(finder.twist(), finder.took(), finder.source() == SOURCE_COMPASS, finder.slopeChange());
    }

    /** What a twist means: a twist at all (the slope kept)? big enough? which way? (or, being taught, the way to the right). */
    private int decide(float twist, float tookMs, boolean byCompass, float slopeChange) {
        turns++;
        lastTook = tookMs;
        lastPeak = Math.abs(twist);
        lastByCompass = byCompass;
        if (!byCompass && slopeChange > Math.max(KEEP_DEG, 0.6f * lastPeak) || lastPeak > MAX_TWIST) {
            // the forearm's slope changed about as much as it twisted: the hand was raised or lowered (an arm swinging as
            // one walks tilts the forearm too, but much less than a twist turns it)
            why = WHY_NOT_FOREARM;
            return NONE;
        }
        if (lastPeak < turn) {
            why = WHY_SMALL;
            return NONE;
        }
        why = WHY_OK;
        if (learning) {
            learning = false;
            rightSign = twist > 0 ? 1 : -1;
            learnt = true;
            return LEARNED;
        }
        return twist * rightSign > 0 ? RIGHT : LEFT;
    }

    // ------------------------------------------------------------------ the turn itself

    /**
     * Finds turns in an accelerometer's samples (and the compass's heading, if the watch has one), by their real times:
     * one number — how far the watch has twisted around its x axis, the forearm — swinging away from where it has been
     * lately and back. Two such numbers, watched side by side; whichever sees the turn tells it:
     *
     * <ul>
     * <li>by gravity: its roll in the dial's y–z plane (atan2 of y and z), while gravity leans off the forearm at all
     *     ({@link #LEVER_GRAVITY}). The forearm's own slope does not enter it: raised, lowered, flat — the same twist
     *     gives the same number;</li>
     * <li>by the compass: the watch's whole orientation from gravity and the heading of its y axis, and the twist around
     *     x from one sample to the next added up (what a gyroscope would tell) — it sees a twist with the arm hanging
     *     along the body too, when the twist turns the watch around gravity itself. An arm swinging as one walks turns
     *     the watch around another axis and adds nothing to it.</li>
     * </ul>
     * For each: where it has been lately is a moving average (≈ {@link #BASE_MS}), how restless it has been the moving
     * average of how far it strays. A turn starts when the number strays by more than {@link #START_DEG} while it has
     * been calm enough ({@link #CALM_DEG}) — nothing has to be still: walking or a busy hand is calm enough. It ends when
     * the number comes back within half of how far it went (or {@link #BACK_DEG}) and no further swing follows within
     * {@link #GRACE_MS} (a quick turn's own jolt swings the watch a little the other way first: the swing that went
     * furthest decides). Slower than {@link #MAX_MS} is not a gesture — a new position. After a turn both numbers wait
     * {@link #QUIET_MS}, so that one turn is told once. The same code is in the watches' JavaScript
     * (watch/vela/src/common/proto.js).
     */
    public static final class Finder {
        /** The turn starts when the number strays this far from where it has been lately… */
        static final double START_DEG = 14;
        /** …while it has been this calm (the moving average of how far it strayed). */
        static final double CALM_DEG = 10;
        /** Back: within BACK_DEG, or within BACK_FRAC of how far it went. */
        static final double BACK_DEG = 15, BACK_FRAC = 0.4;
        /** Smaller turns are not reported; a turn takes MIN_MS at least and MAX_MS at most. */
        static final double MIN_DEG = 22, MIN_MS = 90, MAX_MS = 800;
        /** After coming back: this long for a further, bigger swing. */
        static final double GRACE_MS = 140;
        /** Where it has been lately, how restless: moving averages over about this long (ms). */
        static final double BASE_MS = 600, CALM_MS = 300;
        /** A number that has just become good is trusted after this long; after a turn, both wait QUIET_MS. */
        static final double SETTLE_MS = 250, QUIET_MS = 400;
        /** The low-pass of the accelerometer (Hz): smooths the watch's own jolt at the start of a quick turn. */
        static final double CUT_HZ = 5;
        /** Gravity's number is good while gravity leans off the forearm this far (sine), once in use down to LEVER_KEEP. */
        static final double LEVER_GRAVITY = 0.3, LEVER_KEEP = 0.25;
        /** The compass's number is good while the dial's y axis is no steeper than this (|cos| with «up»), then KEEP_Y. */
        static final double FLAT_Y = 0.92, KEEP_Y = 0.95;
        /** Beyond these (× gravity): a knock or a throw, not a turn. */
        static final double KNOCK_HI = 3.0, KNOCK_LO = 0.25;

        /** One number watched for a swing away and back. */
        private static final class Track {
            boolean good, baseSet;
            double goodT, raw, theta, base, calm;
            /** 0 waiting, 1 away, 2 back — waiting whether a bigger swing follows. */
            int state;
            double b0, peak, startT, endT, slope0, slopeMax;

            void lose() {
                good = false;
                baseSet = false;
                state = 0;
            }

            /** The number now (an angle, or an increment when inc), at t; true when a turn has just ended. */
            boolean step(double v, boolean inc, double t, double dt, double ux, double quietUntil) {
                if (!good) {
                    good = true;
                    goodT = t;
                    baseSet = false;
                }
                if (!baseSet) {
                    raw = v;
                    theta = inc ? 0 : v;
                    base = theta;
                    calm = 0;
                    baseSet = true;
                } else if (inc) {
                    theta += v;
                } else {
                    theta += wrapDeg(v - raw);
                    raw = v;
                }
                if (state == 0) {
                    double d = theta - base;
                    if (Math.abs(d) > START_DEG && calm <= CALM_DEG && t - goodT >= SETTLE_MS && t >= quietUntil) {
                        state = 1;
                        b0 = base;
                        peak = d;
                        startT = t;
                        slope0 = ux;
                        slopeMax = 0;
                        return false;
                    }
                    // where it has been lately, and how restless
                    calm += (1 - Math.exp(-dt / CALM_MS)) * (Math.abs(d) - calm);
                    base += (1 - Math.exp(-dt / BASE_MS)) * d;
                    return false;
                }
                double d = theta - b0;
                slopeMax = Math.max(slopeMax, Math.abs(Math.toDegrees(Math.acos(clamp(ux)) - Math.acos(clamp(slope0)))));
                if (Math.abs(d) > Math.abs(peak)) {
                    // further than before: a bigger swing (after the run-up, or still on the way)
                    peak = d;
                    state = 1;
                }
                if (state == 1) {
                    if (t - startT > MAX_MS) {
                        // turned and stayed: a new position, not a gesture; calm again before the next one
                        settle();
                        return false;
                    }
                    if (Math.abs(d) <= Math.max(BACK_DEG, BACK_FRAC * Math.abs(peak))) {
                        if (Math.abs(peak) < MIN_DEG) {
                            // a wobble
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
                settle();
                return endT - startT >= MIN_MS;
            }

            /** A turn already back counts even if the number goes away now. */
            boolean doneAlready() {
                return state == 2 && Math.abs(peak) >= MIN_DEG && endT - startT >= MIN_MS;
            }

            /** Back to waiting from where the number is now, restless enough that a moment of calm comes first. */
            void settle() {
                state = 0;
                base = theta;
                calm = 1.5 * CALM_DEG;
            }
        }

        private final double[] f = new double[3];
        private boolean primed;
        private double lastT;
        private double g;
        private boolean gSeen;
        private final Track grav = new Track(), comp = new Track();
        private double quietUntil = Double.NEGATIVE_INFINITY;
        private double twist, took, slopeOut, slopeChangeOut;
        private int sourceOut;
        private float tilt;

        public void reset() {
            primed = false;
            grav.lose();
            comp.lose();
            mSet = false;
        }

        public float gravity() {
            return gSeen ? (float) g : 0;
        }

        /** How far the watch is twisted from where it has been lately (degrees), for the screen. */
        public float tilt() {
            return tilt;
        }

        /** The turn just found: the twist (degrees, right-handed around x), how long it took (ms), seen by what. */
        public float twist() {
            return (float) twist;
        }

        public float took() {
            return (float) took;
        }

        public int source() {
            return sourceOut;
        }

        /** The forearm's slope at rest (cos between «up» and x), and how far it changed during the turn (degrees). */
        public float slope() {
            return (float) slopeOut;
        }

        public float slopeChange() {
            return (float) slopeChangeOut;
        }

        /** One sample at time t (ms); true when a turn has just ended. */
        public boolean sample(double x, double y, double z, double t) {
            return sample(x, y, z, t, Double.NaN);
        }

        /** One sample at time t (ms) with the compass heading then (radians, north 0, east π/2; NaN — none). */
        public boolean sample(double x, double y, double z, double t, double heading) {
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
            if (!gSeen) {
                g = m;
                gSeen = true;
            } else if (Math.abs(m - g) < 0.2 * g) {
                g += (1 - Math.exp(-dt / 2000)) * (m - g);
            }
            if (m > KNOCK_HI * g || m < KNOCK_LO * g) {
                // a knock or a throw: not a turn
                grav.state = 0;
                comp.state = 0;
                return false;
            }
            double ux = f[0] / m, uy = f[1] / m, uz = f[2] / m;
            double lever = Math.sqrt(uy * uy + uz * uz);
            boolean found = false;
            int by = 0;
            // gravity's number
            if (lever >= (grav.good ? LEVER_KEEP : LEVER_GRAVITY)) {
                if (grav.step(Math.toDegrees(Math.atan2(uy, uz)), false, t, dt, ux, quietUntil)) {
                    found = true;
                    by = SOURCE_GRAVITY;
                }
            } else {
                if (grav.doneAlready()) {
                    found = true;
                    by = SOURCE_GRAVITY;
                }
                grav.lose();
            }
            // the compass's number
            if (!Double.isNaN(heading) && Math.abs(uy) <= (comp.good ? KEEP_Y : FLAT_Y)) {
                double inc = orient(ux, uy, uz, heading);
                if (comp.step(inc, true, t, dt, ux, quietUntil) && !found) {
                    found = true;
                    by = SOURCE_COMPASS;
                }
            } else {
                mSet = false;
                if (comp.doneAlready() && !found) {
                    found = true;
                    by = SOURCE_COMPASS;
                }
                comp.lose();
            }
            Track tr = by == SOURCE_COMPASS ? comp : grav, other = by == SOURCE_COMPASS ? grav : comp;
            tilt = (float) Math.max(grav.good && grav.baseSet ? Math.abs(grav.theta - grav.base) : 0,
                    comp.good && comp.baseSet ? Math.abs(comp.theta - comp.base) : 0);
            if (!found) return false;
            if (other.good && other.state != 0 && Math.abs(other.peak) > Math.abs(tr.peak) && other.peak * tr.peak > 0) {
                // the other number is in the middle of the same turn and saw more of it: its measure
                tr = other;
                by = by == SOURCE_COMPASS ? SOURCE_GRAVITY : SOURCE_COMPASS;
                tr.endT = t;
            }
            twist = tr.peak;
            took = tr.endT - tr.startT;
            sourceOut = by;
            slopeOut = tr.slope0;
            slopeChangeOut = tr.slopeMax;
            // one turn told once: both numbers start afresh and wait a moment
            quietUntil = t + QUIET_MS;
            grav.settle();
            comp.settle();
            return true;
        }

        // the orientation: rows east, north, up — the world's axes in the watch's own
        private final double[] m = new double[9], mPrev = new double[9];
        private boolean mSet;

        /**
         * The watch's orientation from «up» (u) and the heading of its y axis (h: from north toward east), and how far it
         * twisted around its x axis since the last one (degrees; 0 the first time). Its y axis, laid flat, points h from
         * north; «clockwise seen from above» of it is y × up.
         */
        private double orient(double ux, double uy, double uz, double h) {
            // y laid flat: y − (y·up) up
            double yx = -uy * ux, yy = 1 - uy * uy, yz = -uy * uz;
            double yn = Math.sqrt(yx * yx + yy * yy + yz * yz);
            yx /= yn;
            yy /= yn;
            yz /= yn;
            // c = y × up
            double cx = yy * uz - yz * uy, cy = yz * ux - yx * uz, cz = yx * uy - yy * ux;
            double ch = Math.cos(h), sh = Math.sin(h);
            System.arraycopy(m, 0, mPrev, 0, 9);
            // east = cos h · c + sin h · y; north = cos h · y − sin h · c; up
            m[0] = ch * cx + sh * yx;
            m[1] = ch * cy + sh * yy;
            m[2] = ch * cz + sh * yz;
            m[3] = ch * yx - sh * cx;
            m[4] = ch * yy - sh * cy;
            m[5] = ch * yz - sh * cz;
            m[6] = ux;
            m[7] = uy;
            m[8] = uz;
            if (!mSet) {
                mSet = true;
                return 0;
            }
            // the turn in the watch's frame: Q = Mprev^T M; its rotation around x
            double q21 = 0, q12 = 0, tr = 0;
            for (int i = 0; i < 3; i++) {
                q21 += mPrev[3 * i + 2] * m[3 * i + 1];
                q12 += mPrev[3 * i + 1] * m[3 * i + 2];
                tr += mPrev[3 * i] * m[3 * i] + mPrev[3 * i + 1] * m[3 * i + 1] + mPrev[3 * i + 2] * m[3 * i + 2];
            }
            double ang = Math.acos(clamp((tr - 1) / 2));
            double k = ang < 1e-6 ? 0.5 : ang / (2 * Math.sin(ang));
            return Math.toDegrees(k * (q21 - q12));
        }

        private static double clamp(double c) {
            return Math.max(-1, Math.min(1, c));
        }

        static double wrapDeg(double d) {
            while (d > 180) d -= 360;
            while (d < -180) d += 360;
            return d;
        }
    }

    // ------------------------------------------------------------------ vectors

    static float norm(float[] v) {
        return (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }
}
