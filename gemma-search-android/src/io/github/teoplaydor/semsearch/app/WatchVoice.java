package io.github.teoplaydor.semsearch.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.teoplaydor.semsearch.R;
import io.github.teoplaydor.semsearch.core.Adpcm;
import io.github.teoplaydor.semsearch.core.OggOpus;
import io.github.teoplaydor.semsearch.core.Pcm;
import io.github.teoplaydor.semsearch.core.Spoken;
import io.github.teoplaydor.semsearch.core.Utterances;
import io.github.teoplaydor.semsearch.core.WristGesture;

/**
 * A voice note said into the watch (the watch held to the mouth records, and sends the recording when lowered —
 * {@link WristLink}): its sound decoded (the watch's Ogg Opus, its Opus packets, WAV or PCM), what was said recognised
 * by GigaAM on the phone when it is downloaded ({@link Speech}), else by the phone's recognizer — the sound handed to
 * it, the phone's microphone not used (Android 13+) — and made a note
 * the way a quick note is ({@link Capture}: a reminder if a time was said, a question answered from the notes), the
 * recording kept with it. Nothing said in it: nothing kept. No recognition on this phone: the recording is kept as a
 * note to listen to (and found among the sounds). The phone shows what became of it in a quiet notification.
 */
final class WatchVoice {
    /** What became of the note ({@link WristGesture#VOICE_SAVED}…) and a line about it. */
    interface Done {
        void done(int result, String line);
    }

    /** Quieter than this at its loudest (RMS over 50 ms, of full scale): nothing was said. */
    static final float SILENT = 0.006f;
    /** At most this much of a note (seconds). */
    static final int MAX_SECONDS = 90;
    static final String CHANNEL = "watch_notes";
    static final int NOTIFICATION = 4303;

    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private WatchVoice() {
    }

    /** The note's bytes as they came ({@link WristGesture} codec, samples a second): made a note, told back on the main thread. */
    static void handle(final Context c, final byte[] bytes, final int codec, final int rate, final Done done) {
        final Context app = c.getApplicationContext();
        WORK.execute(new Runnable() {
            @Override
            public void run() {
                float[] x;
                try {
                    x = decode(bytes, codec, rate);
                } catch (Exception e) {
                    android.util.Log.w("SemSearch", "watch voice", e);
                    x = null;
                }
                WristLog.add(app, "VOICE", "decoded codec=" + codec + " bytes=" + bytes.length + " head=" + WristLink.Server.hex(
                        java.util.Arrays.copyOf(bytes, Math.min(8, bytes.length))) + (x == null ? " -> not readable" : String.format(Locale.ROOT,
                        " -> %.2f s, loudest %.3f", x.length / (float) Pcm.RATE, loudest(x))));
                if (x == null || x.length < Pcm.RATE / 4) {
                    // the recording itself to the log (base64), to see what the watch sends
                    if (bytes.length <= 256 * 1024) WristLog.add(app, "VOICE-RAW", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP));
                    finish(app, done, WristGesture.VOICE_FAILED, "Запись с часов не разобрать — включите журнал и пришлите его", null);
                    return;
                }
                if (loudest(x) < SILENT) {
                    finish(app, done, WristGesture.VOICE_SILENT, "Тишина — ничего не сохранено", null);
                    return;
                }
                // cleaned (rumble out, loudness evened) and split at its pauses: a recognizer stops at the first pause,
                // so each phrase is heard alone and the words put together
                final float[] y = Utterances.clean(x, Pcm.RATE);
                final List<int[]> phrases = Utterances.phrases(y, Pcm.RATE);
                final byte[] wav = Pcm.wav16(y, Pcm.RATE);
                final float seconds = x.length / (float) Pcm.RATE;
                if (phrases.isEmpty()) {
                    finish(app, done, WristGesture.VOICE_SILENT, "Слов не расслышал — ничего не сохранено", null);
                    return;
                }
                if (Engine.get(app).speechReady()) {
                    // GigaAM on the phone: the whole note at once (in pieces of up to ~22 s), punctuated
                    String words = null;
                    try {
                        words = Speech.text(Engine.get(app), y, phrases, new Speech.Log() {
                            @Override
                            public void line(String text) {
                                WristLog.add(app, "VOICE", text);
                            }
                        });
                    } catch (Throwable e) {
                        WristLog.add(app, "VOICE", "gigaam failed: " + e + " — the phone's recognizer instead");
                    }
                    if (words != null) {
                        final String w = words;
                        MAIN.post(new Runnable() {
                            @Override
                            public void run() {
                                made(app, w, w.isEmpty() ? SpeechRecognizer.ERROR_NO_MATCH : 0, wav, seconds, done);
                            }
                        });
                        return;
                    }
                }
                final boolean local = !WristLink.voiceOnline(app) && onDeviceAvailable(app);
                StringBuilder ps = new StringBuilder();
                for (int[] q : phrases) ps.append(String.format(Locale.ROOT, " %.2f-%.2f", q[0] / (float) Pcm.RATE, q[1] / (float) Pcm.RATE));
                WristLog.add(app, "VOICE", "phrases " + phrases.size() + ":" + ps + (local ? " on the phone" : " usual recognizer")
                        + (WristLink.voiceOnline(app) ? " (online)" : ""));
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        recognizeAll(app, y, phrases, 0, new StringBuilder(), 0, local, new Words() {
                            @Override
                            public void heard(String words, int error) {
                                made(app, words, error, wav, seconds, done);
                            }
                        });
                    }
                });
            }
        });
    }

    /** What was recognised (or not) becomes a note, an answer, or nothing. */
    private static void made(final Context c, String words, int error, final byte[] wav, float seconds, final Done done) {
        WristLog.add(c, "VOICE", "heard " + (words != null ? "«" + words + "»" : "nothing") + " error=" + error);
        if (words != null && !words.trim().isEmpty()) {
            Spoken.Result r = Spoken.parse(words, System.currentTimeMillis(), TimeZone.getDefault());
            final boolean answer = r.query != null || r.question != null;
            Capture.save(c, words, wav, new Capture.Saved() {
                @Override
                public void saved(String line, IndexStore.Item note) {
                    int result = answer ? WristGesture.VOICE_ANSWER : note != null ? WristGesture.VOICE_SAVED : WristGesture.VOICE_SILENT;
                    finish(c, done, result, line, note);
                }
            });
            return;
        }
        if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
            finish(c, done, WristGesture.VOICE_SILENT, "Слов не расслышал — ничего не сохранено", null);
            return;
        }
        // no recognition here (no recognizer, no network, Android before 13): the recording kept, to listen to
        final String title = "Голосовая заметка с часов, " + clock(System.currentTimeMillis());
        Engine.get(c).addNote(title, null, false, 0, Spoken.ONCE, new Engine.Callback<IndexStore.Item>() {
            @Override
            public void done(IndexStore.Item it, Exception err) {
                if (it == null) {
                    finish(c, done, WristGesture.VOICE_FAILED, "Не сохранилось", null);
                    return;
                }
                Engine.get(c).setVoice(it, wav);
                finish(c, done, WristGesture.VOICE_KEPT, "Сохранена запись без текста (распознавание недоступно)", it);
            }
        });
    }

    private static void finish(final Context c, final Done done, final int result, final String line, final IndexStore.Item note) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                show(c, result, line, note);
                done.done(result, line);
            }
        });
    }

    // ------------------------------------------------------------------ the sound

    /** The note as mono samples at 16 kHz (null: not a format known here). */
    static float[] decode(byte[] b, int codec, int rate) throws IOException {
        float[] mono;
        int at;
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F') {
            Pcm.Wav w = Pcm.readWav(new ByteArrayInputStream(b), MAX_SECONDS);
            mono = w.samples;
            at = w.rate;
        } else if (codec != WristGesture.CODEC_ADPCM && codec != WristGesture.CODEC_PCM16 && OggOpus.read(b) != null) {
            OggOpus o = OggOpus.read(b);
            mono = opus(o.head, o.channels, o.preSkip, o.packets);
            at = 48000;
        } else if (codec == WristGesture.CODEC_OPUS_PACKETS) {
            java.util.List<byte[]> packets = OggOpus.lengthPrefixed(b);
            if (packets.isEmpty()) return null;
            try {
                mono = opus(OggOpus.head(1, 312, rate > 0 ? rate : 16000), 1, 312, packets);
            } catch (IOException e) {
                mono = null;
            }
            if (mono == null || mono.length == 0) {
                // not Opus after all: the recorder's frames may be plain PCM
                java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
                for (byte[] p : packets) all.write(p, 0, p.length);
                mono = pcmIfLikely(all.toByteArray());
            }
            at = mono != null && mono.length > 0 && looksPcm ? (rate > 0 ? rate : 16000) : 48000;
        } else if (codec == WristGesture.CODEC_ADPCM) {
            // the recorder's PCM, made a quarter by the watch (IMA ADPCM)
            mono = Adpcm.decode(b);
            at = rate > 0 ? rate : 16000;
        } else if (codec == WristGesture.CODEC_PCM16) {
            int n = Math.min(b.length / 2, MAX_SECONDS * Math.max(rate, 1));
            mono = new float[n];
            for (int i = 0; i < n; i++) mono[i] = (short) ((b[2 * i] & 0xff) | (b[2 * i + 1] << 8)) / 32768f;
            at = rate;
        } else {
            // not what it said it was: plain PCM, if it looks like sound
            mono = pcmIfLikely(b);
            at = rate > 0 ? rate : 16000;
        }
        if (mono == null || at <= 0) return null;
        return Pcm.resample(mono, at, Pcm.RATE);
    }

    private static boolean looksPcm;

    /**
     * Bytes as 16-bit PCM if they look like sound — neighbouring samples alike (sound is smooth from sample to sample,
     * compressed bytes are not); else null.
     */
    static float[] pcmIfLikely(byte[] b) {
        looksPcm = false;
        int n = b.length / 2;
        if (n < 1600) return null;
        float[] x = new float[n];
        double sxy = 0, sxx = 0;
        for (int i = 0; i < n; i++) x[i] = (short) ((b[2 * i] & 0xff) | (b[2 * i + 1] << 8)) / 32768f;
        for (int i = 1; i < n; i++) {
            sxy += x[i] * x[i - 1];
            sxx += x[i] * x[i];
        }
        if (sxx <= 0 || sxy / sxx < 0.5) return null;
        looksPcm = true;
        return x;
    }

    /** Opus packets through the phone's decoder (Android's own, «audio/opus»): mono samples at 48 kHz. */
    static float[] opus(byte[] head, int channels, int preSkip, java.util.List<byte[]> packets) throws IOException {
        MediaCodec dec = null;
        try {
            MediaFormat f = MediaFormat.createAudioFormat("audio/opus", 48000, Math.max(1, channels));
            f.setByteBuffer("csd-0", ByteBuffer.wrap(head));
            f.setByteBuffer("csd-1", nanos(preSkip * 1_000_000_000L / 48000));
            f.setByteBuffer("csd-2", nanos(80_000_000L));
            dec = MediaCodec.createDecoderByType("audio/opus");
            dec.configure(f, null, null, 0);
            dec.start();
            int ch = Math.max(1, channels);
            float[] buf = new float[48000 * 4];
            int have = 0, next = 0;
            long pts = 0;
            boolean floats = false, inputDone = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long deadline = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < deadline) {
                if (!inputDone) {
                    int ib = dec.dequeueInputBuffer(10_000);
                    if (ib >= 0) {
                        ByteBuffer in = dec.getInputBuffer(ib);
                        if (next >= packets.size() || in == null) {
                            dec.queueInputBuffer(ib, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            byte[] p = packets.get(next++);
                            in.clear();
                            in.put(p, 0, Math.min(p.length, in.remaining()));
                            dec.queueInputBuffer(ib, 0, Math.min(p.length, in.capacity()), pts, 0);
                            pts += OggOpus.samples48k(p) * 1_000_000L / 48000;
                        }
                    }
                }
                int ob = dec.dequeueOutputBuffer(info, 10_000);
                if (ob == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = dec.getOutputFormat();
                    ch = of.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? of.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : ch;
                    floats = of.containsKey("pcm-encoding") && of.getInteger("pcm-encoding") == 4; // ENCODING_PCM_FLOAT
                    continue;
                }
                if (ob < 0) continue;
                ByteBuffer o = dec.getOutputBuffer(ob);
                if (o != null && info.size > 0) {
                    o.position(info.offset);
                    o.limit(info.offset + info.size);
                    o.order(ByteOrder.nativeOrder());
                    int count = floats ? info.size / 4 : info.size / 2;
                    if (have + count > buf.length) buf = java.util.Arrays.copyOf(buf, Math.max(buf.length * 2, have + count));
                    for (int k = 0; k < count; k++) buf[have++] = floats ? o.getFloat() : o.getShort() / 32768f;
                }
                dec.releaseOutputBuffer(ob, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0 || have >= 48000 * ch * MAX_SECONDS) break;
            }
            int frames = have / ch;
            return Pcm.mono(java.util.Arrays.copyOf(buf, frames * ch), ch);
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw new IOException("Opus не декодировался: " + e.getMessage(), e);
        } finally {
            if (dec != null) {
                try {
                    dec.stop();
                } catch (IllegalStateException ignored) {
                    // never started
                }
                dec.release();
            }
        }
    }

    private static ByteBuffer nanos(long ns) {
        ByteBuffer b = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder());
        b.putLong(ns);
        b.flip();
        return b;
    }

    /** The loudest 50 ms (RMS). */
    static float loudest(float[] x) {
        int w = Pcm.RATE / 20;
        float best = 0;
        for (int i = 0; i + w <= x.length; i += w / 2) {
            double s = 0;
            for (int k = i; k < i + w; k++) s += x[k] * x[k];
            best = Math.max(best, (float) Math.sqrt(s / w));
        }
        return best;
    }

    /** Samples from..to as 16-bit (little-endian bytes), with so much quiet before and after. */
    static byte[] pcm16(float[] x, int from, int to, int lead, int tail) {
        byte[] b = new byte[2 * (lead + (to - from) + tail)];
        for (int i = from; i < to; i++) {
            int v = Math.round(Math.max(-1f, Math.min(1f, x[i])) * 32767f);
            int o = 2 * (lead + i - from);
            b[o] = (byte) v;
            b[o + 1] = (byte) (v >> 8);
        }
        return b;
    }

    // ------------------------------------------------------------------ the words

    /** Lead-in and tail of quiet around a phrase: a recognizer misses a word that starts its sound, and waits for an end. */
    static final int LEAD = Pcm.RATE * 3 / 10, TAIL = Pcm.RATE * 7 / 10;

    /**
     * The phrases one after another, their words put together. A phrase with no words is passed over; the error kept
     * is the first one, told when no phrase gave words (no recognizer at all — at once).
     */
    static void recognizeAll(final Context c, final float[] y, final List<int[]> phrases, final int i, final StringBuilder text,
                             final int firstError, final boolean local, final Words got) {
        if (i >= phrases.size()) {
            String all = text.toString().trim();
            got.heard(all.isEmpty() ? null : all, all.isEmpty() ? firstError : 0);
            return;
        }
        int[] p = phrases.get(i);
        byte[] pcm = pcm16(y, p[0], p[1], LEAD, TAIL);
        float seconds = (p[1] - p[0] + LEAD + TAIL) / (float) Pcm.RATE;
        recognize(c, pcm, seconds, local, false, new Words() {
            @Override
            public void heard(String words, int error) {
                WristLog.add(c, "VOICE", "phrase " + (i + 1) + " -> " + (words != null ? "«" + words + "»" : "no words") + " error=" + error);
                if (words != null && !words.trim().isEmpty()) {
                    if (text.length() > 0) text.append(' ');
                    text.append(words.trim());
                } else if (i == 0 && error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                        && (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)) {
                    // no recognizer here: the rest would fail the same way
                    got.heard(null, error);
                    return;
                }
                recognizeAll(c, y, phrases, i + 1, text, firstError != 0 ? firstError : error, local, got);
            }
        });
    }

    /** The language the notes are said in: the phone's if Russian, else Russian (the notes are read in Russian). */
    static String language() {
        Locale l = Locale.getDefault();
        return "ru".equals(l.getLanguage()) ? l.toLanguageTag() : "ru-RU";
    }

    interface Words {
        /** What was said (null: nothing), and the recognizer's error (0: none). */
        void heard(String words, int error);
    }

    static boolean onDeviceAvailable(Context c) {
        if (Build.VERSION.SDK_INT < 31) return false;
        try {
            return (Boolean) SpeechRecognizer.class.getMethod("isOnDeviceRecognitionAvailable", Context.class).invoke(null, c);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * The sound handed to the recognizer through a pipe (Android 13+: RecognizerIntent's audio source), on the phone
     * itself when it can, else the phone's usual recognizer; on the main thread.
     */
    static void recognize(final Context c, final byte[] pcm16, final float seconds, final boolean local, final boolean triedOther,
                          final Words got) {
        if (Build.VERSION.SDK_INT < 33) {
            got.heard(null, SpeechRecognizer.ERROR_CLIENT);
            return;
        }
        SpeechRecognizer r = null;
        if (local) {
            try {
                r = (SpeechRecognizer) SpeechRecognizer.class.getMethod("createOnDeviceSpeechRecognizer", Context.class).invoke(null, c);
            } catch (Exception e) {
                r = null;
            }
        }
        final boolean onDevice = r != null;
        if (r == null) {
            if (!SpeechRecognizer.isRecognitionAvailable(c)) {
                got.heard(null, SpeechRecognizer.ERROR_CLIENT);
                return;
            }
            r = SpeechRecognizer.createSpeechRecognizer(c);
        }
        final ParcelFileDescriptor[] pipe;
        try {
            pipe = ParcelFileDescriptor.createPipe();
        } catch (IOException e) {
            r.destroy();
            got.heard(null, SpeechRecognizer.ERROR_CLIENT);
            return;
        }
        final SpeechRecognizer sr = r;
        final String[] partial = {""};
        final boolean[] over = {false};
        final Runnable[] timeout = new Runnable[1];
        final Words end = new Words() {
            @Override
            public void heard(String words, int error) {
                if (over[0]) return;
                over[0] = true;
                MAIN.removeCallbacks(timeout[0]);
                try {
                    sr.destroy();
                } catch (RuntimeException ignored) {
                    // gone
                }
                try {
                    pipe[0].close();
                } catch (IOException ignored) {
                    // closed
                }
                if ((words == null || words.trim().isEmpty()) && onDevice && !triedOther && error != SpeechRecognizer.ERROR_NO_MATCH
                        && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT && SpeechRecognizer.isRecognitionAvailable(c)) {
                    // the phone's own recognition has no Russian (yet) or is busy: the usual recognizer then
                    recognize(c, pcm16, seconds, false, true, got);
                    return;
                }
                got.heard(words, error);
            }
        };
        timeout[0] = new Runnable() {
            @Override
            public void run() {
                end.heard(partial[0].isEmpty() ? null : partial[0], SpeechRecognizer.ERROR_SPEECH_TIMEOUT);
            }
        };
        sr.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
            }

            @Override
            public void onBeginningOfSpeech() {
            }

            @Override
            public void onRmsChanged(float db) {
            }

            @Override
            public void onBufferReceived(byte[] buffer) {
            }

            @Override
            public void onEndOfSpeech() {
            }

            @Override
            public void onError(int error) {
                end.heard(partial[0].isEmpty() ? null : partial[0], error);
            }

            @Override
            public void onResults(Bundle b) {
                ArrayList<String> list = b == null ? null : b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                String best = list != null && !list.isEmpty() ? list.get(0) : null;
                String words = best != null && !best.trim().isEmpty() ? best : partial[0].isEmpty() ? null : partial[0];
                end.heard(words, words != null ? 0 : SpeechRecognizer.ERROR_NO_MATCH);
            }

            @Override
            public void onPartialResults(Bundle b) {
                ArrayList<String> list = b == null ? null : b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (list != null && !list.isEmpty() && list.get(0) != null) partial[0] = list.get(0);
            }

            @Override
            public void onEvent(int type, Bundle params) {
            }
        });
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language())
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language())
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, !WristLink.voiceOnline(c))
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, c.getPackageName())
                // the whole phrase is there: not to be ended at a breath inside it
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 5000L)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 5000L)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, (long) Math.round(seconds * 1000))
                .putExtra(VoiceTape.EXTRA_SOURCE, pipe[0])
                .putExtra(VoiceTape.EXTRA_CHANNELS, 1)
                .putExtra(VoiceTape.EXTRA_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
                .putExtra(VoiceTape.EXTRA_RATE, Pcm.RATE);
        MAIN.postDelayed(timeout[0], Math.round(seconds * 1000) + 25_000);
        try {
            sr.startListening(i);
        } catch (RuntimeException e) {
            end.heard(null, SpeechRecognizer.ERROR_CLIENT);
            return;
        }
        // the sound into the pipe (it waits while the recognizer reads), then closed: the end of the speech
        new Thread(new Runnable() {
            @Override
            public void run() {
                OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]);
                try {
                    for (int o = 0; o < pcm16.length; o += 3200) out.write(pcm16, o, Math.min(3200, pcm16.length - o));
                } catch (IOException ignored) {
                    // the recognizer closed its end
                } finally {
                    try {
                        out.close();
                    } catch (IOException ignored) {
                        // closed
                    }
                }
            }
        }, "watch-voice-pipe").start();
    }

    // ------------------------------------------------------------------ the notification

    private static String clock(long t) {
        Calendar k = Calendar.getInstance();
        k.setTimeInMillis(t);
        return String.format(Locale.ROOT, "%d:%02d", k.get(Calendar.HOUR_OF_DAY), k.get(Calendar.MINUTE));
    }

    /** «Заметка с часов»: what became of it, quietly; the note opens from it. Nothing for silence. */
    static void show(Context c, int result, String line, IndexStore.Item note) {
        if (result == WristGesture.VOICE_SILENT) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                Class<?> ch = Class.forName("android.app.NotificationChannel");
                Object channel = ch.getConstructor(String.class, CharSequence.class, int.class)
                        .newInstance(CHANNEL, "Заметки с часов", 2 /* IMPORTANCE_LOW */);
                NotificationManager.class.getMethod("createNotificationChannel", ch).invoke(nm, channel);
                b = (Notification.Builder) Notification.Builder.class.getConstructor(Context.class, String.class).newInstance(c, CHANNEL);
            } catch (Exception e) {
                b = new Notification.Builder(c);
            }
        } else {
            b = new Notification.Builder(c);
        }
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (note != null) open.setAction(MainActivity.ACTION_OPEN_NOTE).putExtra(Reminders.EXTRA_NOTE, note.id);
        PendingIntent pi = PendingIntent.getActivity(c, 7, open, PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000 /* FLAG_IMMUTABLE */);
        String title = result == WristGesture.VOICE_ANSWER ? "Ответ на вопрос с часов"
                : result == WristGesture.VOICE_FAILED ? "Заметка с часов не получилась" : "Заметка с часов";
        b.setSmallIcon(R.drawable.ic_tile_mic)
                .setContentTitle(title)
                .setContentText(line)
                .setStyle(new Notification.BigTextStyle().bigText(line))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(Notification.PRIORITY_LOW);
        try {
            nm.notify(NOTIFICATION, b.build());
        } catch (SecurityException e) {
            // notifications not allowed
        }
    }
}
