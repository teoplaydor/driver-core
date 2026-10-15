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

    /** The answer's voice to the watch's speaker, as it comes. */
    void play(final short[] pcm, final int rate) {
        final int g = gen;
        try {
            out.execute(new Runnable() {
                @Override
                public void run() {
                    if (g != gen || !on) return; // spoken over since
                    try {
                        if (track == null || trackRate != rate) {
                            if (track != null) track.release();
                            int min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                            track = new AudioTrack(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(), new AudioFormat.Builder().setSampleRate(rate)
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                                    Math.max(min, rate / 5 * 2), AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
                            AudioDeviceInfo dev = device(c, false);
                            if (dev != null) track.setPreferredDevice(dev);
                            trackRate = rate;
                            track.play();
                        }
                        byte[] b = new byte[pcm.length * 2];
                        for (int i = 0; i < pcm.length; i++) {
                            b[2 * i] = (byte) pcm[i];
                            b[2 * i + 1] = (byte) (pcm[i] >> 8);
                        }
                        track.write(b, 0, b.length);
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
        int n = RATE / 8;
        short[] t = new short[2 * n];
        for (int k = 0; k < 2; k++) {
            double f = up ? (k == 0 ? 660 : 990) : (k == 0 ? 990 : 660);
            for (int i = 0; i < n; i++) {
                double env = Math.sin(Math.PI * i / n);
                t[k * n + i] = (short) (6000 * env * Math.sin(2 * Math.PI * f * i / RATE));
            }
        }
        play(t, RATE);
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
