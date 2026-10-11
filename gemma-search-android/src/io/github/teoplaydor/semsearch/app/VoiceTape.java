package io.github.teoplaydor.semsearch.app;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.ParcelFileDescriptor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * The voice of a quick note kept while it is recognized (Android 13+): the app records the microphone itself and
 * hands the same sound to the recognizer through a pipe (RecognizerIntent's audio source) — one microphone, two
 * listeners. 16 kHz mono, at most two minutes.
 */
final class VoiceTape {
    static final int RATE = 16000;
    static final int MAX_SECONDS = 120;
    static final String EXTRA_SOURCE = "android.speech.extra.AUDIO_SOURCE", EXTRA_CHANNELS = "android.speech.extra.AUDIO_SOURCE_CHANNEL_COUNT",
            EXTRA_ENCODING = "android.speech.extra.AUDIO_SOURCE_ENCODING", EXTRA_RATE = "android.speech.extra.AUDIO_SOURCE_SAMPLING_RATE";

    private final AudioRecord rec;
    private final ParcelFileDescriptor read, write;
    private final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
    private final LinkedBlockingQueue<byte[]> toPipe = new LinkedBlockingQueue<byte[]>();
    private volatile boolean on = true;
    /** The loudest sample so far (0…1): whether anything was said. */
    volatile float peak;
    private Thread recorder, feeder;

    private VoiceTape(AudioRecord rec, ParcelFileDescriptor[] pipe) {
        this.rec = rec;
        this.read = pipe[0];
        this.write = pipe[1];
    }

    /** Recording started (null: the microphone could not be opened). */
    static VoiceTape start() {
        try {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            AudioRecord r = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE) * 2);
            if (r.getState() != AudioRecord.STATE_INITIALIZED) {
                r.release();
                return null;
            }
            VoiceTape t = new VoiceTape(r, ParcelFileDescriptor.createPipe());
            r.startRecording();
            t.run();
            return t;
        } catch (Exception e) {
            return null;
        }
    }

    /** The end the recognizer reads. */
    ParcelFileDescriptor source() {
        return read;
    }

    private void run() {
        recorder = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buf = new byte[3200]; // 0.1 s
                int total = 0;
                while (on && total < RATE * 2 * MAX_SECONDS) {
                    int n = rec.read(buf, 0, buf.length);
                    if (n <= 0) {
                        if (n < 0) break;
                        continue;
                    }
                    synchronized (pcm) {
                        pcm.write(buf, 0, n);
                    }
                    for (int i = 0; i + 1 < n; i += 2) {
                        float v = Math.abs((short) ((buf[i] & 0xFF) | (buf[i + 1] << 8))) / 32768f;
                        if (v > peak) peak = v;
                    }
                    total += n;
                    byte[] copy = new byte[n];
                    System.arraycopy(buf, 0, copy, 0, n);
                    toPipe.offer(copy);
                }
                toPipe.offer(new byte[0]); // the end
            }
        }, "voice-tape");
        // writing to the pipe may wait for the recognizer: on a thread of its own, so the recording never does
        feeder = new Thread(new Runnable() {
            @Override
            public void run() {
                OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(write);
                try {
                    while (true) {
                        byte[] b = toPipe.take();
                        if (b.length == 0) break;
                        out.write(b);
                    }
                } catch (Exception ignored) {
                    // the recognizer closed its end
                } finally {
                    try {
                        out.close();
                    } catch (IOException ignored) {
                        // closed
                    }
                }
            }
        }, "voice-pipe");
        recorder.start();
        feeder.start();
    }

    /** Recording stopped: the sound as 16-bit samples (little-endian bytes). */
    byte[] stop() {
        on = false;
        try {
            if (recorder != null) recorder.join(1000);
        } catch (InterruptedException ignored) {
            // on
        }
        try {
            rec.stop();
        } catch (Exception ignored) {
            // not recording
        }
        rec.release();
        toPipe.offer(new byte[0]);
        try {
            read.close();
        } catch (IOException ignored) {
            // closed
        }
        synchronized (pcm) {
            return pcm.toByteArray();
        }
    }

    /** 16-bit samples as a WAV file's bytes. */
    static byte[] wav(byte[] pcm16) {
        float[] f = new float[pcm16.length / 2];
        for (int i = 0; i < f.length; i++) f[i] = (short) ((pcm16[2 * i] & 0xFF) | (pcm16[2 * i + 1] << 8)) / 32768f;
        return io.github.teoplaydor.semsearch.core.Pcm.wav16(f, RATE);
    }
}
