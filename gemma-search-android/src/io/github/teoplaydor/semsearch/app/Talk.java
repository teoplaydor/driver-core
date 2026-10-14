package io.github.teoplaydor.semsearch.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.teoplaydor.semsearch.core.Adpcm;
import io.github.teoplaydor.semsearch.core.GeminiLive;
import io.github.teoplaydor.semsearch.core.Pcm;
import io.github.teoplaydor.semsearch.core.WristGesture;

/**
 * A spoken conversation with Gemini through the watch (the Gemini Live API, the user's own key): the watch held to the
 * mouth streams the voice as it is said (IMA ADPCM, 16 kHz); the phone decodes it and passes it on to Gemini as it
 * comes; Gemini's spoken answer comes back as it is made and plays — on the watch (pieces of a WAV file, IMA ADPCM at
 * 8 kHz, ~4 KB a second, cut where the voice is quietest) or on the phone (its speaker, or headphones). Half duplex:
 * while an answer plays the voice is not passed on — the watch's microphone would hear the answer and Gemini would take
 * it for the user. Gemini may save a note (save_note: made the way a voice note is, with its reminder) or look in the
 * notes (ask_notes). The hand lowered: what was said is answered, the conversation kept a while — raised again, it goes
 * on — then closed.
 */
final class Talk {
    static final String PREF_KEY = "gemini_key", PREF_MODEL = "gemini_model", PREF_ON = "wrist_talk", PREF_ON_WATCH = "wrist_talk_watch",
            PREF_LAST = "gemini_last";
    /** The conversation kept this long after the hand went down and the answer ended. */
    static final long KEEP_MS = 45_000;
    /** The answer for the watch in pieces: the first this long (heard sooner), the next ones longer (fewer gaps). */
    static final int FIRST_SEG_MS = 1200, SEG_MS = 2400;
    /** The answer's voice on the watch: 8 kHz (its speaker is small; ~4 KB a second over Bluetooth). */
    static final int WATCH_RATE = 8000;
    /** Answers on the watch only over a link that takes this much in a notification (MTU ≥ 100). */
    static final int MIN_ROOM = 97;
    /** After an answer has played, the voice is passed on again this much later (the room's echo). */
    static final long ECHO_MS = 300;
    /** Voice said before Gemini is ready kept at most this long (seconds). */
    static final int EARLY_S = 6;

    /** Tests: Gemini's address («ws://127.0.0.1:port»). */
    static volatile String hostForTest;

    /** The watch the conversation goes through. */
    interface Watch {
        void send(byte[] packet);

        /** Bytes a notification holds (MTU − 3). */
        int room();
    }

    // ------------------------------------------------------------------ settings

    /** The watch at the mouth is a conversation with Gemini (not a note): chosen, and a key given. */
    static boolean on(Context c) {
        return WristLink.prefs(c).getBoolean(PREF_ON, false) && !key(c).isEmpty();
    }

    static boolean chosen(Context c) {
        return WristLink.prefs(c).getBoolean(PREF_ON, false);
    }

    static void setOn(Context c, boolean on) {
        WristLink.prefs(c).edit().putBoolean(PREF_ON, on).apply();
        WristLink.reconfigure(c);
    }

    static String key(Context c) {
        return WristLink.prefs(c).getString(PREF_KEY, "").trim();
    }

    static void setKey(Context c, String key) {
        WristLink.prefs(c).edit().putString(PREF_KEY, key == null ? "" : key.trim()).remove(PREF_MODEL).apply();
        WristLink.reconfigure(c);
    }

    /** The answers play on the watch (else on the phone); on unless switched off. */
    static boolean onWatch(Context c) {
        return WristLink.prefs(c).getBoolean(PREF_ON_WATCH, true);
    }

    static void setOnWatch(Context c, boolean on) {
        WristLink.prefs(c).edit().putBoolean(PREF_ON_WATCH, on).apply();
        WristLink.reconfigure(c);
    }

    /** The model chosen (or found best when the key was checked); "" — not yet. */
    static String model(Context c) {
        return WristLink.prefs(c).getString(PREF_MODEL, "");
    }

    static void setModel(Context c, String m) {
        WristLink.prefs(c).edit().putString(PREF_MODEL, m).apply();
    }

    /** The last exchange, for the settings. */
    static String last(Context c) {
        return WristLink.prefs(c).getString(PREF_LAST, "");
    }

    static String host() {
        return hostForTest != null ? hostForTest : GeminiLive.HOST;
    }

    /** The models that can talk, best first (the key checked on the way). Off the main thread. */
    static List<String> models(Context c) throws Exception {
        return GeminiLive.liveModels(host(), key(c));
    }

    // ------------------------------------------------------------------ one conversation

    private static Talk current;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    final Context c;
    private volatile Watch watch;
    private GeminiLive live;
    private boolean connecting, closing, goingAway;
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final ExecutorService speaker = Executors.newSingleThreadExecutor();
    private final List<short[]> early = new ArrayList<short[]>();
    private int earlySamples;
    private Adpcm.Decoder dec = new Adpcm.Decoder();
    private int id = -1;
    private long expected;
    private boolean handDown;
    /** An answer is coming (its voice arriving) — the voice not passed on; until then it plays on (see {@link #muted}). */
    private boolean answering, turnDone = true;
    private long playingUntil;
    private boolean watchAudio;
    private short[] ans = new short[0];
    private int ansN, ansRate = 24000;
    private int seg, segsOut, playFailures;
    private AudioTrack track;
    private final StringBuilder heard = new StringBuilder(), said = new StringBuilder();
    private long voiceSent, connectAt;
    private final Runnable closer = new Runnable() {
        @Override
        public void run() {
            close("тишина " + KEEP_MS / 1000 + " с");
        }
    };

    private Talk(Context c, Watch w) {
        this.c = c.getApplicationContext();
        this.watch = w;
    }

    private void log(String text) {
        WristLog.add(c, "TALK", text);
    }

    /** The watch at the mouth (a VOICE start marked live): the conversation started, or the one still open goes on. */
    static synchronized void start(Context c, Watch w, int id, int codec, int rate) {
        if ((codec & 0x7f) != WristGesture.CODEC_ADPCM || rate != GeminiLive.IN_RATE) {
            // only the watch's PCM made ADPCM at 16 kHz goes on as it comes (an older watch app records Opus)
            WristLog.add(c, "TALK", "the watch's voice is codec " + (codec & 0x7f) + " at " + rate + " Hz — not for a conversation");
            w.send(WristGesture.talkText("Для разговора обновите приложение на часах", w.room()));
            w.send(WristGesture.talkEnd());
            return;
        }
        Talk t = current;
        if (t == null || t.closing) {
            t = new Talk(c, w);
            current = t;
            t.connect();
        } else {
            t.watch = w;
        }
        t.raised(id, codec, rate);
    }

    static synchronized boolean live(int id) {
        return current != null && current.id == id;
    }

    /** The voice's bytes ({@link WristGesture#voiceData}), in order. */
    static void data(int id, long offset, byte[] p, int from, int n) {
        Talk t;
        synchronized (Talk.class) {
            t = current;
        }
        if (t != null && t.id == id) t.voice(offset, p, from, n);
    }

    /** The hand lowered (VOICE end). */
    static void end(int id) {
        Talk t;
        synchronized (Talk.class) {
            t = current;
        }
        if (t != null && t.id == id) t.lowered();
    }

    /** The watch played a piece of the answer (or could not). */
    static void played(int id, int seg, boolean ok) {
        Talk t;
        synchronized (Talk.class) {
            t = current;
        }
        if (t != null) t.playedOut(seg, ok);
    }

    /** The watch went away: the conversation ends. */
    static void stop() {
        Talk t;
        synchronized (Talk.class) {
            t = current;
        }
        if (t != null) t.close("часы отключились");
    }

    private synchronized void raised(int id, int codec, int rate) {
        MAIN.removeCallbacks(closer);
        this.id = id;
        expected = 0;
        dec = new Adpcm.Decoder();
        handDown = false;
        watchAudio = onWatch(c) && watch.room() >= MIN_ROOM && playFailures < 2;
        log("raised id=" + id + " codec=" + (codec & 0x7f) + " rate=" + rate + " answers " + (watchAudio ? "on the watch" : "on the phone")
                + (live != null && live.ready() ? " (the conversation goes on)" : ""));
        WristLink.setStatus("разговор с Gemini…");
    }

    private void connect() {
        connecting = true;
        connectAt = SystemClock.elapsedRealtime();
        net.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String model = model(c);
                    if (model.isEmpty()) {
                        List<String> m = models(c);
                        if (m.isEmpty()) throw new java.io.IOException("нет моделей для живого разговора на этом ключе");
                        model = m.get(0);
                        setModel(c, model);
                    }
                    log("connecting " + model);
                    GeminiLive g = GeminiLive.connect(host(), key(c), config(model), listener);
                    synchronized (Talk.this) {
                        live = g;
                        if (closing) g.close();
                    }
                } catch (Exception e) {
                    fail("не подключился: " + e.getMessage());
                }
            }
        });
    }

    private GeminiLive.Config config(String model) {
        GeminiLive.Config cfg = new GeminiLive.Config();
        cfg.model = model;
        cfg.silenceMs = 700;
        cfg.systemInstruction = "Ты голосовой помощник, с которым говорят через умные часы: человек подносит часы ко рту и говорит, "
                + "ответ слушает из маленького динамика. Отвечай по-русски (или на языке, на котором спросили), коротко и по делу — "
                + "одно-три предложения, если не просят подробнее; без списков и разметки. Если просят что-то запомнить, записать или "
                + "напомнить — вызови save_note с текстом, как он сказан, вместе со временем напоминания словами («Напомни завтра в 9 "
                + "купить хлеб»), и коротко подтверди. Если спрашивают о том, что записано у человека в заметках (коды, адреса, "
                + "размеры, что он сохранял), — вызови ask_notes. Сейчас " + new SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm",
                new Locale("ru")).format(new Date()) + ".";
        cfg.functions.add(function("save_note", "Сохранить заметку в телефоне человека (с напоминанием, если названо время)",
                "text", "Текст заметки, как его сказал человек, со временем напоминания словами"));
        cfg.functions.add(function("ask_notes", "Найти ответ в заметках человека", "question", "Вопрос"));
        return cfg;
    }

    private static Map<String, Object> function(String name, String description, String param, String paramDescription) {
        Map<String, Object> p = new LinkedHashMap<String, Object>();
        p.put("type", "STRING");
        p.put("description", paramDescription);
        Map<String, Object> props = new LinkedHashMap<String, Object>();
        props.put(param, p);
        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put("type", "OBJECT");
        schema.put("properties", props);
        List<Object> req = new ArrayList<Object>();
        req.add(param);
        schema.put("required", req);
        Map<String, Object> f = new LinkedHashMap<String, Object>();
        f.put("name", name);
        f.put("description", description);
        f.put("parameters", schema);
        return f;
    }

    /** The voice is not passed on while an answer comes or plays (and a moment after). */
    private boolean muted() {
        return answering || SystemClock.elapsedRealtime() < playingUntil;
    }

    private void voice(long offset, byte[] p, int from, int n) {
        short[] pcm;
        synchronized (this) {
            if (offset < expected) {
                int skip = (int) Math.min(n, expected - offset);
                from += skip;
                n -= skip;
                offset += skip;
            } else if (offset > expected) {
                dec = new Adpcm.Decoder(); // a gap: the stream's state starts over
            }
            if (n <= 0) return;
            expected = offset + n;
            pcm = dec.decode(p, from, n);
            if (muted()) return;
            if (live == null || !live.ready()) {
                if (earlySamples < EARLY_S * GeminiLive.IN_RATE) {
                    early.add(pcm);
                    earlySamples += pcm.length;
                }
                return;
            }
        }
        send(pcm);
    }

    /** On the network thread (nothing once closed). */
    private void onNet(Runnable r) {
        try {
            net.execute(r);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // closed
        }
    }

    private void send(final short[] pcm) {
        onNet(new Runnable() {
            @Override
            public void run() {
                GeminiLive g = live;
                if (g == null || !g.ready()) return;
                try {
                    g.sendAudio(pcm, 0, pcm.length);
                    voiceSent += pcm.length;
                } catch (java.io.IOException e) {
                    // the close is told by the listener
                }
            }
        });
    }

    private void lowered() {
        synchronized (this) {
            handDown = true;
        }
        log(String.format(Locale.ROOT, "lowered id=%d, voice passed on %.1f s", id, voiceSent / (float) GeminiLive.IN_RATE));
        onNet(new Runnable() {
            @Override
            public void run() {
                GeminiLive g = live;
                try {
                    if (g != null && g.ready()) g.audioStreamEnd();
                } catch (java.io.IOException ignored) {
                    // closed: told by the listener
                }
            }
        });
        idleLater();
    }

    private final GeminiLive.Listener listener = new GeminiLive.Listener() {
        @Override
        public void onReady() {
            List<short[]> pending;
            synchronized (Talk.this) {
                connecting = false;
                pending = new ArrayList<short[]>(early);
                early.clear();
                earlySamples = 0;
            }
            log("ready in " + (SystemClock.elapsedRealtime() - connectAt) + " ms; voice said meanwhile " + pending.size() + " pieces");
            for (short[] p : pending) send(p);
            if (handDown) lowered();
        }

        @Override
        public void onAudio(short[] pcm, int rate) {
            List<byte[]> out = null;
            synchronized (Talk.this) {
                answering = true;
                turnDone = false;
                if (watchAudio) {
                    ansRate = rate;
                    if (ans.length < ansN + pcm.length) ans = java.util.Arrays.copyOf(ans, Math.max(ans.length * 2, ansN + pcm.length));
                    System.arraycopy(pcm, 0, ans, ansN, pcm.length);
                    ansN += pcm.length;
                    int target = (seg == 0 || segsOut == 0 && SystemClock.elapsedRealtime() > playingUntil ? FIRST_SEG_MS : SEG_MS) * rate / 1000;
                    out = ansN >= target ? segment(target) : new ArrayList<byte[]>();
                }
            }
            // to the watch outside the lock (the Bluetooth side holds its own while it hands the voice here)
            if (out != null) {
                for (byte[] b : out) tell(b);
                return;
            }
            speak(pcm, rate);
        }

        @Override
        public void onInputText(String text) {
            synchronized (Talk.this) {
                heard.append(text);
            }
        }

        @Override
        public void onOutputText(String text) {
            synchronized (Talk.this) {
                said.append(text);
            }
        }

        @Override
        public void onTurnComplete() {
            String you, gem;
            List<byte[]> out = null;
            synchronized (Talk.this) {
                if (watchAudio && ansN > 0) out = segment(ansN);
                answering = false;
                turnDone = true;
                if (!watchAudio) playingUntil = Math.max(playingUntil, SystemClock.elapsedRealtime()) + ECHO_MS;
                you = heard.toString().trim();
                gem = said.toString().trim();
                heard.setLength(0);
                said.setLength(0);
            }
            if (out != null) for (byte[] b : out) tell(b);
            log("turn: you «" + you + "» — Gemini «" + gem + "»");
            if (!gem.isEmpty()) {
                tell(WristGesture.talkText(gem, watch.room()));
                String line = (you.isEmpty() ? "" : "Вы: " + you + "\n") + "Gemini: " + gem;
                WristLink.prefs(c).edit().putString(PREF_LAST, line).apply();
                WristLink.setStatus("Gemini: " + (gem.length() > 80 ? gem.substring(0, 80) + "…" : gem));
            }
            if (goingAway) close("сервер попросил закончить");
            else idleLater();
        }

        @Override
        public void onInterrupted() {
            synchronized (Talk.this) {
                ansN = 0;
                answering = false;
            }
            log("interrupted");
        }

        @Override
        public void onToolCall(final String callId, final String name, final Map<String, Object> args) {
            log("call " + name + " " + args);
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    tool(callId, name, args);
                }
            });
        }

        @Override
        public void onGoAway(String timeLeft) {
            log("go away in " + timeLeft);
            goingAway = true;
        }

        @Override
        public void onClosed(int code, String reason) {
            log("closed " + code + " " + reason);
            boolean mine;
            synchronized (Talk.this) {
                mine = closing;
            }
            if (!mine) fail(code == 1000 ? "разговор закрыт" : "Gemini: " + (reason == null || reason.isEmpty() ? "код " + code : reason));
        }
    };

    private void tool(final String callId, final String name, Map<String, Object> args) {
        final GeminiLive g = live;
        Engine.Callback<String> answer = new Engine.Callback<String>() {
            @Override
            public void done(final String line, Exception err) {
                log("call " + name + " → " + line);
                if ("save_note".equals(name)) tell(WristGesture.talkText(line, watch.room()));
                onNet(new Runnable() {
                    @Override
                    public void run() {
                        Map<String, Object> r = new LinkedHashMap<String, Object>();
                        r.put("result", line == null ? "" : line);
                        try {
                            if (g != null && g.ready()) g.toolResponse(callId, name, r);
                        } catch (java.io.IOException ignored) {
                            // closed
                        }
                    }
                });
            }
        };
        Object text = args.get("save_note".equals(name) ? "text" : "question");
        String s = text == null ? "" : String.valueOf(text);
        if ("save_note".equals(name)) {
            Capture.save(c, s, answer);
        } else if ("ask_notes".equals(name)) {
            Engine.get(c).answer(s, new Engine.Callback<Engine.Answer>() {
                @Override
                public void done(Engine.Answer a, Exception err) {
                    answer.done(a != null && a.text != null ? a.text : "В заметках ответа нет", null);
                }
            });
        } else {
            answer.done("нет такой функции", null);
        }
    }

    // ------------------------------------------------------------------ the answer's voice

    /**
     * A piece of the answer for the watch: up to {@code target} samples, cut at the quietest moment near the end — its
     * packets (sent by the caller, outside the lock).
     */
    private List<byte[]> segment(int target) {
        List<byte[]> out = new ArrayList<byte[]>();
        int n = Math.min(target, ansN);
        if (n < ansN || target < ansN) {
            // the quietest 20 ms in the last 400 ms of the piece
            int frame = ansRate / 50, look = ansRate * 2 / 5, best = n;
            double quiet = Double.MAX_VALUE;
            for (int end = n; end >= Math.max(frame, n - look); end -= frame / 2) {
                double s = 0;
                for (int i = end - frame; i < end; i++) s += (double) ans[i] * ans[i];
                if (s < quiet) {
                    quiet = s;
                    best = end - frame / 2;
                }
            }
            n = best;
        }
        float[] x = new float[n];
        for (int i = 0; i < n; i++) x[i] = ans[i] / 32768f;
        System.arraycopy(ans, n, ans, 0, ansN - n);
        ansN -= n;
        float[] y = Pcm.resample(x, ansRate, WATCH_RATE);
        short[] s = new short[y.length];
        for (int i = 0; i < y.length; i++) s[i] = (short) Math.max(-32768, Math.min(32767, Math.round(y[i] * 32767)));
        byte[] wav = Adpcm.imaWav(s, WATCH_RATE);
        int ms = (int) (1000L * s.length / WATCH_RATE);
        seg = (seg + 1) & 0xff;
        int room = watch.room() - 6;
        for (int o = 0; o < wav.length; o += room) out.add(WristGesture.talkAudio(seg, o, wav, o, Math.min(room, wav.length - o)));
        out.add(WristGesture.talkSeg(seg, wav.length, ms));
        segsOut++;
        // when it will have played (sent at ~4 KB a second, then played after the ones before): the voice waits till then
        long now = SystemClock.elapsedRealtime();
        playingUntil = Math.max(playingUntil, now + wav.length / 4) + ms + ECHO_MS;
        log("piece " + seg + ": " + ms + " ms, " + wav.length + " bytes in " + ((wav.length + room - 1) / room) + " notifications");
        return out;
    }

    private void playedOut(int s, boolean ok) {
        boolean phone = false;
        synchronized (this) {
            segsOut = Math.max(0, segsOut - 1);
            if (!ok) playFailures++;
            if (segsOut == 0 && turnDone) playingUntil = SystemClock.elapsedRealtime() + ECHO_MS;
            if (playFailures >= 2 && watchAudio) {
                watchAudio = false;
                phone = true;
            }
        }
        if (phone) {
            log("the watch does not play the answers — on the phone from now on");
            tell(WristGesture.talkText("Звук в часах не играет — отвечаю через телефон", watch.room()));
        }
        if (!ok) log("piece " + s + " not played on the watch");
        idleLater();
    }

    /** The answer on the phone: its speaker, or the headphones. */
    private void speak(final short[] pcm, final int rate) {
        synchronized (this) {
            playingUntil = Math.max(playingUntil, SystemClock.elapsedRealtime()) + 1000L * pcm.length / rate;
        }
        try {
            speaker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if (track == null || track.getSampleRate() != rate) {
                        if (track != null) track.release();
                        int min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                        track = new AudioTrack(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(), new AudioFormat.Builder().setSampleRate(rate)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                                Math.max(min, rate), AudioTrack.MODE_STREAM, android.media.AudioManager.AUDIO_SESSION_ID_GENERATE);
                        track.play();
                    }
                    track.write(pcm, 0, pcm.length);
                } catch (RuntimeException e) {
                    log("phone speaker: " + e);
                }
            }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // closed
        }
    }

    private void tell(byte[] p) {
        Watch w = watch;
        if (w != null) w.send(p);
    }

    /** The hand down, the answer over and played: closed after a while (raised again — it goes on). */
    private void idleLater() {
        boolean idle;
        long wait;
        synchronized (this) {
            // pieces still playing on the watch: counted in playingUntil (their «played» may never come from an older watch)
            idle = handDown && turnDone && !answering;
            wait = Math.max(0, playingUntil - SystemClock.elapsedRealtime());
        }
        MAIN.removeCallbacks(closer);
        if (idle) MAIN.postDelayed(closer, KEEP_MS + wait);
    }

    private void fail(String why) {
        log("failed: " + why);
        tell(WristGesture.talkText(why, watch.room()));
        WristLink.setStatus(why);
        close(why);
    }

    void close(String why) {
        GeminiLive g;
        synchronized (this) {
            if (closing) return;
            closing = true;
            g = live;
        }
        MAIN.removeCallbacks(closer);
        log("closing: " + why);
        if (g != null) g.close();
        tell(WristGesture.talkEnd());
        synchronized (Talk.class) {
            if (current == this) current = null;
        }
        speaker.execute(new Runnable() {
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
        net.shutdown();
        speaker.shutdown();
    }
}
