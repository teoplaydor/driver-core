package io.github.teoplaydor.semsearch.core;

import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.TreeMap;

/**
 * A voice note coming from the watch in VOICE packets ({@link WristGesture}): «start», the bytes at their offsets,
 * «end» — put together in order. A gap (a write lost on the way) is found at once and told ({@link #MISSING}, from
 * where), so that the watch sends again from there; bytes that came after the gap are kept and fill in when it closes.
 */
public final class VoiceNote {
    /** What a packet did: nothing (not a voice packet, another note), a new note, more bytes, a gap, the whole note. */
    public static final int NONE = 0, STARTED = 1, PROGRESS = 2, MISSING = 3, COMPLETE = 4;
    /** More than this is not a voice note (a minute of 16 kHz PCM is 1.9 MB). */
    static final int MAX_BYTES = 8 << 20;

    private int id = -1, codec, rate;
    private long millis, total = -1;
    private ByteArrayOutputStream have = new ByteArrayOutputStream();
    private final TreeMap<Long, byte[]> ahead = new TreeMap<Long, byte[]>();
    private boolean ended, done;

    public int id() {
        return id;
    }

    public int codec() {
        return codec;
    }

    public int rate() {
        return rate;
    }

    /** How long the watch says it was (ms; 0 until the end). */
    public long millis() {
        return millis;
    }

    /** The bytes in order so far (all of them once {@link #COMPLETE}). */
    public byte[] bytes() {
        return have.toByteArray();
    }

    public long received() {
        return have.size();
    }

    /** How many bytes the note has, once known (−1 before). */
    public long total() {
        return total;
    }

    /** Where the bytes stop being in order: what to send again from. */
    public long missingFrom() {
        return have.size();
    }

    public boolean complete() {
        return done;
    }

    /** One packet from the watch (any, the voice ones are taken). */
    public int packet(byte[] p) {
        if (p == null || p.length < 2) return NONE;
        int kind = p[0] & 0xff;
        if (kind == WristGesture.VOICE_START) {
            if (p.length < 9) return NONE;
            id = p[1] & 0xff;
            codec = p[2] & 0xff;
            rate = WristGesture.u16(p, 3);
            long t = WristGesture.u32(p, 5);
            total = t > 0 ? t : -1;
            millis = 0;
            have = new ByteArrayOutputStream();
            ahead.clear();
            ended = false;
            done = false;
            return STARTED;
        }
        if ((p[1] & 0xff) != id || done) return NONE;
        if (kind == WristGesture.VOICE_DATA) {
            if (p.length < 6) return NONE;
            long at = WristGesture.u32(p, 2);
            int n = p.length - 6;
            if (at + n > MAX_BYTES) return NONE;
            long size = have.size();
            if (at > size) {
                // after a gap: kept for later, the gap told
                byte[] b = new byte[n];
                System.arraycopy(p, 6, b, 0, n);
                ahead.put(at, b);
                return MISSING;
            }
            if (at + n > size) have.write(p, 6 + (int) (size - at), (int) (at + n - size));
            fill();
            return finish(PROGRESS);
        }
        if (kind == WristGesture.VOICE_END) {
            if (p.length < 10) return NONE;
            total = WristGesture.u32(p, 2);
            millis = WristGesture.u32(p, 6);
            ended = true;
            return finish(have.size() < total ? MISSING : PROGRESS);
        }
        return NONE;
    }

    /** Bytes kept from after a gap that now follow on. */
    private void fill() {
        while (!ahead.isEmpty()) {
            Map.Entry<Long, byte[]> e = ahead.firstEntry();
            long size = have.size(), at = e.getKey();
            byte[] b = e.getValue();
            if (at > size) return;
            ahead.pollFirstEntry();
            if (at + b.length > size) have.write(b, (int) (size - at), (int) (at + b.length - size));
        }
    }

    private int finish(int otherwise) {
        if (ended && have.size() >= total) {
            done = true;
            return COMPLETE;
        }
        return otherwise;
    }
}
