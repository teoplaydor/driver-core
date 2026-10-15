package io.github.teoplaydor.semsearch.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;

import io.github.teoplaydor.semsearch.core.GeminiLive;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The watch as a Bluetooth headset (its «calls through the watch»): the phone opens the call's sound channel to it (SCO,
 * as in a phone call — no call is made) and both ways go as a stream: the watch's microphone is the phone's input, the
 * phone's voice output plays on the watch's speaker. For a conversation with Gemini: no pieces, no wait, and the
 * phone's echo cancelling on the voice — Gemini can be spoken over.
 */
final class Headset {
    interface Voice {
        /** 16-bit samples at {@link #RATE} from the watch's microphone, 20 ms at a time. */
        void heard(short[] pcm);
    }

    static final int RATE = 16000;
    /** AudioDeviceInfo.TYPE_BLUETOOTH_SCO. */
    static final int SCO = 7;

    private final Context c;
    private final AudioManager am;
    private AudioRecord rec;
    private AudioTrack track;
    private int trackRate;
    private volatile boolean on;
    private Thread reader;
    private final ExecutorService out = Executors.newSingleThreadExecutor();
    private volatile int gen;
    private int modeBefore;
    private boolean scoStarted;

    Headset(Context c) {
        this.c = c.getApplicationContext();
        this.am = (AudioManager) this.c.getSystemService(Context.AUDIO_SERVICE);
    }

    /**
     * A player that starts at once: a small buffer (80 ms), low-latency mode (Android 8+) and playing from the first
     * 20 ms written (Android 12+) — by default a player waits for its whole buffer to fill before the first sound.
     */
    static AudioTrack track(int usage, int rate) {
        int min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack.Builder b = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(Math.max(min, rate * 2 * 80 / 1000)).setTransferMode(AudioTrack.MODE_STREAM);
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                // AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
                AudioTrack.Builder.class.getMethod("setPerformanceMode", int.class).invoke(b, 1);
            } catch (ReflectiveOperationException ignored) {
                // the usual mode
            }
        }
        AudioTrack t = b.build();
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                AudioTrack.class.getMethod("setStartThresholdInFrames", int.class).invoke(t, rate / 50);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // starts once its buffer is full
            }
        }
        return t;
    }

    /** 16-bit samples written (as bytes, little-endian). */
    static void write(AudioTrack t, short[] pcm) {
        byte[] b = new byte[pcm.length * 2];
        for (int i = 0; i < pcm.length; i++) {
            b[2 * i] = (byte) pcm[i];
            b[2 * i + 1] = (byte) (pcm[i] >> 8);
        }
        t.write(b, 0, b.length);
    }

    /** The watch connected as a headset now (a Bluetooth SCO device among the outputs), preferring one named like it. */
    static AudioDeviceInfo device(Context c, boolean input) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return null;
        AudioDeviceInfo any = null;
        String watch = WristLink.watchName(c).toLowerCase();
        for (AudioDeviceInfo d : am.getDevices(input ? AudioManager.GET_DEVICES_INPUTS : AudioManager.GET_DEVICES_OUTPUTS)) {
            if (d.getType() != SCO) continue;
            String name = label(d).toLowerCase();
            if (!name.isEmpty() && (!watch.isEmpty() && (name.contains(watch) || watch.contains(name)) || name.contains("watch"))) return d;
            if (any == null) any = d;
        }
        return any;
    }

    static boolean available(Context c) {
        return device(c, false) != null;
    }

    static String name(Context c) {
        AudioDeviceInfo d = device(c, false);
        return d == null ? "" : label(d).isEmpty() ? "Bluetooth" : label(d);
    }

    private static String label(AudioDeviceInfo d) {
        CharSequence n = d.getProductName();
        return n == null ? "" : n.toString().trim();
    }

    /** The call's channel to the watch opened, its microphone listened to (throws with why not). */
    synchronized void open(final Voice voice) throws Exception {
        if (on) return;
        AudioDeviceInfo dev = device(c, false);
        if (dev == null) throw new IllegalStateException("часы не подключены как гарнитура (Bluetooth-звонки на часах)");
        modeBefore = am.getMode();
        am.setMode(AudioManager.MODE_IN_COMMUNICATION);
        boolean routed = false;
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                @SuppressWarnings("unchecked")
                List<AudioDeviceInfo> list = (List<AudioDeviceInfo>) AudioManager.class.getMethod("getAvailableCommunicationDevices").invoke(am);
                AudioDeviceInfo pick = null;
                for (AudioDeviceInfo d : list) {
                    if (d.getType() != SCO) continue;
                    if (pick == null || label(d).equals(label(dev))) pick = d;
                }
                if (pick != null) {
                    routed = (Boolean) AudioManager.class.getMethod("setCommunicationDevice", AudioDeviceInfo.class).invoke(am, pick);
                }
            } catch (ReflectiveOperationException e) {
                routed = false;
            }
        }
        if (!routed) {
            // older Android: the headset's channel the old way
            am.startBluetoothSco();
            am.setBluetoothScoOn(true);
            scoStarted = true;
        }
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord r = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE / 5 * 2));
        if (r.getState() != AudioRecord.STATE_INITIALIZED) {
            r.release();
            closeRoute();
            throw new IllegalStateException("микрофон часов не открылся (нужен доступ к микрофону; откройте приложение)");
        }
        AudioDeviceInfo in = device(c, true);
        if (in != null) r.setPreferredDevice(in);
        // the phone's own echo cancelling and noise suppression on the call's voice, where it has them
        try {
            if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
                android.media.audiofx.AcousticEchoCanceler aec = android.media.audiofx.AcousticEchoCanceler.create(r.getAudioSessionId());
                if (aec != null) aec.setEnabled(true);
            }
            if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
                android.media.audiofx.NoiseSuppressor ns = android.media.audiofx.NoiseSuppressor.create(r.getAudioSessionId());
                if (ns != null) ns.setEnabled(true);
            }
        } catch (RuntimeException ignored) {
            // without them
        }
        r.startRecording();
        rec = r;
        on = true;
        // the player for the answers made now, on the call's channel, at the answers' rate: their first sound not delayed
        try {
            out.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        player(GeminiLive.OUT_RATE);
                    } catch (RuntimeException e) {
                        WristLog.add(c, "TALK", "headset speaker: " + e);
                    }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // closed
        }
        reader = new Thread(new Runnable() {
            @Override
            public void run() {
                short[] b = new short[RATE / 50];
                while (on) {
                    int n = rec.read(b, 0, b.length);
                    if (n <= 0) {
                        if (n < 0) break;
                        continue;
                    }
                    voice.heard(java.util.Arrays.copyOf(b, n));
                }
            }
        }, "headset-mic");
        reader.setDaemon(true);
        reader.start();
    }

    boolean isOpen() {
        return on;
    }

    /** The player on the call's channel at this rate (on the player's thread). */
    private AudioTrack player(int rate) {
        if (track == null || trackRate != rate) {
            if (track != null) track.release();
            track = track(AudioAttributes.USAGE_VOICE_COMMUNICATION, rate);
            AudioDeviceInfo dev = device(c, false);
            if (dev != null) track.setPreferredDevice(dev);
            trackRate = rate;
            track.play();
        }
        return track;
    }

    /** The answer's voice to the watch's speaker, as it comes; {@code mark} (or null) run once it is in the player. */
    void play(final short[] pcm, final int rate, final Runnable mark) {
        final int g = gen;
        try {
            out.execute(new Runnable() {
                @Override
                public void run() {
                    if (g != gen || !on) return; // spoken over since
                    try {
                        write(player(rate), pcm);
                        if (mark != null) mark.run();
                    } catch (RuntimeException e) {
                        WristLog.add(c, "TALK", "headset speaker: " + e);
                    }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // closed
        }
    }

    /** What is still to play dropped at once (spoken over). */
    void hush() {
        gen++;
        try {
            out.execute(new Runnable() {
                @Override
                public void run() {
                    if (track == null) return;
                    try {
                        track.pause();
                        track.flush();
                        track.play();
                    } catch (RuntimeException ignored) {
                        // gone
                    }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // closed
        }
    }

    /** A short tone in the headset: rising — Gemini listens; falling — the conversation is over. */
    void tone(boolean up) {
        // at the answers' rate: the same player, nothing made again when the answer comes
        int r = GeminiLive.OUT_RATE, n = r / 8;
        short[] t = new short[2 * n];
        for (int k = 0; k < 2; k++) {
            double f = up ? (k == 0 ? 660 : 990) : (k == 0 ? 990 : 660);
            for (int i = 0; i < n; i++) {
                double env = Math.sin(Math.PI * i / n);
                t[k * n + i] = (short) (6000 * env * Math.sin(2 * Math.PI * f * i / r));
            }
        }
        play(t, r, null);
    }

    /** The channel closed, the phone's sound as before. */
    synchronized void close() {
        if (!on && rec == null) return;
        on = false;
        if (rec != null) {
            try {
                rec.stop();
            } catch (RuntimeException ignored) {
                // stopped
            }
            rec.release();
            rec = null;
        }
        try {
            out.execute(new Runnable() {
                @Override
                public void run() {
                    if (track != null) {
                        try {
                            track.stop();
                        } catch (RuntimeException ignored) {
                            // stopped
                        }
                        track.release();
                        track = null;
                    }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // closed
        }
        out.shutdown();
        closeRoute();
    }

    private void closeRoute() {
        try {
            if (Build.VERSION.SDK_INT >= 31) AudioManager.class.getMethod("clearCommunicationDevice").invoke(am);
        } catch (ReflectiveOperationException ignored) {
            // older
        }
        if (scoStarted) {
            am.setBluetoothScoOn(false);
            am.stopBluetoothSco();
            scoStarted = false;
        }
        am.setMode(modeBefore);
    }
}
