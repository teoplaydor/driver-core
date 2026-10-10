package io.github.teoplaydor.semsearch.app;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import io.github.teoplaydor.semsearch.core.Pcm;

/**
 * The sound of a recording, a song, a voice message or a video's soundtrack as the audio encoder takes it: mono 16 kHz,
 * the silence at the start skipped, at most so many seconds. Any format the phone plays (MediaExtractor + MediaCodec:
 * AAC, MP3, Opus, Vorbis, FLAC, AMR…); WAV read directly.
 */
final class Sound {
    private Sound() {
    }

    /**
     * @param seconds  how much sound (from its start)
     * @param skipMax  how much silence at the start may be skipped (seconds)
     */
    static float[] decode(Context c, Uri uri, double seconds, double skipMax) throws IOException {
        int rate;
        float[] mono;
        InputStream head = c.getContentResolver().openInputStream(uri);
        if (head == null) throw new IOException("файл не открылся");
        BufferedInputStream in = new BufferedInputStream(head, 1 << 16);
        try {
            in.mark(16);
            byte[] magic = new byte[12];
            int n = 0, r;
            while (n < 12 && (r = in.read(magic, n, 12 - n)) > 0) n += r;
            in.reset();
            if (n == 12 && magic[0] == 'R' && magic[1] == 'I' && magic[2] == 'F' && magic[3] == 'F' && magic[8] == 'W' && magic[9] == 'A') {
                Pcm.Wav w = Pcm.readWav(in, seconds + skipMax);
                rate = w.rate;
                mono = w.samples;
            } else {
                in.close();
                in = null;
                float[][] out = new float[1][];
                rate = codec(c, uri, seconds + skipMax, out);
                mono = out[0];
            }
        } finally {
            if (in != null) in.close();
        }
        if (mono.length == 0) throw new IOException("в файле нет звука");
        float[] x = Pcm.resample(mono, rate, Pcm.RATE);
        int start = Pcm.soundStart(x, Pcm.RATE, (int) (skipMax * Pcm.RATE));
        return Pcm.slice(x, start, (int) (seconds * Pcm.RATE));
    }

    /** The first audio track through the phone's decoder: mono samples into {@code out[0]}; returns the rate. */
    private static int codec(Context c, Uri uri, double seconds, float[][] out) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec dec = null;
        try {
            ex.setDataSource(c, uri, null);
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    fmt = f;
                    break;
                }
            }
            if (track < 0) throw new IOException("в файле нет звуковой дорожки");
            ex.selectTrack(track);
            int rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            dec.configure(fmt, null, null, 0);
            dec.start();
            boolean floats = false;
            long want = (long) (seconds * rate) * channels;
            float[] buf = new float[(int) Math.min(want, 1 << 20)];
            int have = 0;
            boolean inputDone = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long deadline = System.currentTimeMillis() + 60_000;
            while (have < want && System.currentTimeMillis() < deadline) {
                if (!inputDone) {
                    int ib = dec.dequeueInputBuffer(10_000);
                    if (ib >= 0) {
                        ByteBuffer b = dec.getInputBuffer(ib);
                        int size = b == null ? -1 : ex.readSampleData(b, 0);
                        if (size < 0) {
                            dec.queueInputBuffer(ib, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            dec.queueInputBuffer(ib, 0, size, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int ob = dec.dequeueOutputBuffer(info, 10_000);
                if (ob == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = dec.getOutputFormat();
                    rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    floats = of.containsKey("pcm-encoding") && of.getInteger("pcm-encoding") == 4; // ENCODING_PCM_FLOAT
                    want = (long) (seconds * rate) * channels;
                    continue;
                }
                if (ob < 0) continue;
                ByteBuffer o = dec.getOutputBuffer(ob);
                if (o != null && info.size > 0) {
                    o.position(info.offset);
                    o.limit(info.offset + info.size);
                    o.order(ByteOrder.nativeOrder());
                    int count = floats ? info.size / 4 : info.size / 2;
                    if (have + count > buf.length) {
                        long grow = Math.min(Math.max((long) buf.length * 2, have + count), Math.max(want, have + count));
                        buf = java.util.Arrays.copyOf(buf, (int) grow);
                    }
                    for (int k = 0; k < count; k++) buf[have++] = floats ? o.getFloat() : o.getShort() / 32768f;
                }
                dec.releaseOutputBuffer(ob, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
            }
            int frames = (int) Math.min(have, want) / channels;
            out[0] = Pcm.mono(java.util.Arrays.copyOf(buf, frames * channels), channels);
            return rate;
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw new IOException("звук не декодировался: " + e.getMessage(), e);
        } finally {
            if (dec != null) {
                try {
                    dec.stop();
                } catch (IllegalStateException ignored) {
                    // never started
                }
                dec.release();
            }
            ex.release();
        }
    }

    /** "0:42", "12:05", "1:02:33". */
    static String duration(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s >= 3600) return String.format(java.util.Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60);
        return String.format(java.util.Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }
}
