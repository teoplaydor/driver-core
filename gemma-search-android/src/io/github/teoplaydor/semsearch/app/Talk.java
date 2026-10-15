package io.github.teoplaydor.semsearch.app;

import android.content.Context;
import android.media.AudioAttributes;
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
import io.github.teoplaydor.semsearch.core.SpeechTurns;
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
            PREF_LAST = "gemini_last", PREF_HEADSET = "wrist_talk_headset", PREF_END_PAUSE = "gemini_end_pause",
            PREF_THINK_REFUSED = "gemini_think_refused";
    /** The pause that ends a phrase (ms), as the settings offer it: the shorter, the sooner the answer. */
    static final int[] END_PAUSES = {200, 250, 400};
    static final int END_PAUSE = 250;
    /** After the watch's headset failed to open, not offered to the watch again for this long. */
    static final long HEADSET_PAUSE_MS = 10 * 60_000L;
    /** The hand down right after speaking: the headset kept this long for the answer to begin. */
    static final long ANSWER_WAIT_MS = 6000;
    private static volatile long headsetFailedAt;
    /** The conversation kept this long after the hand went down and the answer ended. */
    static final long KEEP_MS = 45_000;
    /** The answer for the watch in pieces: the first short (heard sooner), the next ones longer (fewer gaps). */
    static final int FIRST_SEG_MS = 700, SEG_MS = 1800;
    /** A piece of voice this loud (RMS, of 16-bit) is speech: the answer's delay is counted from the last such. */
    static final int LOUD = 600;
    /** The answer's voice on the watch as WAV (IMA ADPCM, an older watch app): 8 kHz (~4 KB a second over Bluetooth). */
    static final int WATCH_RATE = 8000;
    /** …as MP3 (the watch's app 1.12 on — its player takes no ADPCM): 16 kHz at 32 kbit/s, the same 4 KB a second. */
    static final int MP3_KBPS = 32;
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

        /** The watch plays the answer's pieces as MP3 (else WAV with IMA ADPCM). */
        boolean mp3();
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

    /**
     * The watch as a headset for the conversation (its Bluetooth calls on): the voice and the answer as a stream, both
     * ways at once. On unless switched off.
     */
    static boolean headset(Context c) {
        return WristLink.prefs(c).getBoolean(PREF_HEADSET, true);
    }

    static void setHeadset(Context c, boolean on) {
        WristLink.prefs(c).edit().putBoolean(PREF_HEADSET, on).apply();
        WristLink.reconfigure(c);
    }

    /** The headset way offered to the watch now: chosen, connected as a headset, and it did not just fail. */
    static boolean headsetUsable(Context c) {
        return headset(c) && Headset.available(c) && System.currentTimeMillis() - headsetFailedAt > HEADSET_PAUSE_MS;
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
    /** What was said before Gemini is ready: the frames of a turn, its start and end — sent in order once it is. */
    private final List<Object> early = new ArrayList<Object>();
    private int earlySamples;
    /** Gemini took the setup: what is said goes straight on. */
    private boolean ready;
    /** Where speech starts and ends, found on the phone — told to Gemini at once (it does not wait for the silence). */
    private final SpeechTurns turns = new SpeechTurns();
    private static final Object START = new Object(), END = new Object();
    /** When the last phrase's speech ended, when its end went to Gemini (elapsedRealtime). */
    private long speechEndAt, endSentAt;
    /** The answer asked for without thinking first (refused by a model: once more without, and not asked again). */
    private boolean thinkingOff, thinkingRetried;
    private String modelName = "";
    private Adpcm.Decoder dec = new Adpcm.Decoder();
    private int id = -1;
    private long expected;
    private boolean handDown;
    /** An answer is coming (its voice arriving) — the voice not passed on; until then it plays on (see {@link #muted}). */
    private boolean answering, turnDone = true;
    private long playingUntil;
    /**
     * Headphones on the phone: the answer plays there as it comes, and the voice goes on all the time — Gemini may be
     * spoken over (it stops at once: «interrupted»). Otherwise (the watch's or the phone's speaker) one at a time.
     */
    private boolean duplex;
    private long lastLoudAt, raisedAt;
    /** Speech passed on since the last answer began: its answer may still be coming (the headset kept for it). */
    private boolean unanswered;
    private boolean firstAudioOfTurn = true;
    /** Answers stopped (interrupted) so far: what was queued for the phone's speaker before is not played. */
    private volatile int speakGen;
    private boolean watchAudio;
    private short[] ans = new short[0];
    private int ansN, ansRate = 24000;
    private int seg, segsOut, playFailures;
    /** The watch as a headset this time (its microphone and speaker through the call's channel). */
    private boolean viaHeadset;
    private Headset headset;
    private boolean headsetReady, toneDue;
    private final Runnable headsetCloser = new Runnable() {
        @Override
        public void run() {
            closeHeadset("the answer over");
        }
    };
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
        turns.endSilenceMs = endPause(c);
    }

    /** The pause that ends a phrase, ms. */
    static int endPause(Context c) {
        return WristLink.prefs(c).getInt(PREF_END_PAUSE, END_PAUSE);
    }

    static void setEndPause(Context c, int ms) {
        WristLink.prefs(c).edit().putInt(PREF_END_PAUSE, ms).apply();
    }

    private void log(String text) {
        WristLog.add(c, "TALK", text);
    }

    /** The watch at the mouth (a VOICE start marked live): the conversation started, or the one still open goes on. */
    static synchronized void start(Context c, Watch w, int id, int codec, int rate) {
        boolean viaHeadset = (codec & 0x7f) == 0; // no voice from the watch's app: the headset carries it
        if (!viaHeadset && ((codec & 0x7f) != WristGesture.CODEC_ADPCM || rate != GeminiLive.IN_RATE)) {
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
    static void played(int id, int seg, int outcome) {
        Talk t;
        synchronized (Talk.class) {
            t = current;
        }
        if (t != null) t.playedOut(seg, outcome);
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
        raisedAt = SystemClock.elapsedRealtime();
        if (turns.active()) turns.finish(sink); // a phrase still open is told over first (Gemini would wait for its end)
        turns.reset();
        turns.endSilenceMs = endPause(c);
        viaHeadset = (codec & 0x7f) == 0;
        MAIN.removeCallbacks(headsetCloser);
        duplex = viaHeadset || headphones(c);
        watchAudio = !duplex && onWatch(c) && watch.room() >= MIN_ROOM && playFailures < 2;
        log("raised id=" + id + " codec=" + (codec & 0x7f) + " rate=" + rate + " answers " + (viaHeadset
                ? "through the watch as a headset (both ways at once)" : duplex ? "in the headphones (may be spoken over)"
                : watchAudio ? "on the watch" : "on the phone's speaker") + (live != null && live.ready() ? " (the conversation goes on)" : ""));
        if (viaHeadset) openHeadset();
        else if (!watchAudio) warmSpeaker();
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
                    GeminiLive.Config cfg = config(model);
                    boolean noThinking = noThinking(cfg, model);
                    synchronized (Talk.this) {
                        modelName = model;
                        thinkingOff = noThinking;
                    }
                    log("connecting " + model + (noThinking ? " (answers without thinking first)" : ""));
                    GeminiLive g = GeminiLive.connect(host(), key(c), cfg, listener);
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
        // the phone tells where speech starts and ends (SpeechTurns): no waiting for the server to hear the silence
        cfg.manualActivity = true;
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

    /**
     * The model asked to answer at once, without thinking first (the native-audio models think by default — time
     * before the first sound): Gemini 2.5 — a budget of 0, Gemini 3 — the least level. Not for a model that refused it.
     */
    private boolean noThinking(GeminiLive.Config cfg, String model) {
        String refused = WristLink.prefs(c).getString(PREF_THINK_REFUSED, "");
        if (("\n" + refused + "\n").contains("\n" + model + "\n")) return false;
        String m = model.toLowerCase(Locale.ROOT);
        if (m.contains("gemini-3")) {
            cfg.thinkingLevel = "MINIMAL";
            return true;
        }
        if (m.contains("2.5") && m.contains("native-audio")) {
            cfg.thinkingBudget = 0;
            return true;
        }
        return false;
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

    /** The call's channel to the watch opened (off the Bluetooth thread); its microphone's voice goes to Gemini as it comes. */
    private void openHeadset() {
        if (headset != null && headset.isOpen()) {
            headsetReady = true;
            return;
        }
        final Headset h = new Headset(c);
        headset = h;
        headsetReady = false;
        new Thread(new Runnable() {
            @Override
            public void run() {
                long t0 = SystemClock.elapsedRealtime();
                try {
                    if (c.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        throw new IllegalStateException("нет доступа к микрофону — разрешите его приложению");
                    }
                    if (!WristService.micAllowed() && !Engine.get(c).uiVisible) {
                        throw new IllegalStateException("откройте приложение на телефоне один раз — тогда микрофон часов будет доступен в фоне");
                    }
                    h.open(new Headset.Voice() {
                        @Override
                        public void heard(short[] pcm) {
                            boolean down;
                            synchronized (Talk.this) {
                                down = handDown || headset != h;
                            }
                            if (!down) onVoice(pcm);
                        }
                    });
                } catch (Exception e) {
                    headsetFailedAt = System.currentTimeMillis();
                    WristLink.reconfigure(c); // the watch records itself next time
                    fail("гарнитура часов не открылась: " + e.getMessage());
                    return;
                }
                boolean tone;
                synchronized (Talk.this) {
                    headsetReady = true;
                    tone = ready;
                    if (!tone) toneDue = true;
                }
                log("headset open in " + (SystemClock.elapsedRealtime() - t0) + " ms: " + Headset.name(c));
                if (tone) h.tone(true);
            }
        }, "headset-open").start();
    }

    /** The headset's channel closed (the watch's «call» over); the conversation itself may go on. */
    private void closeHeadset(String why) {
        Headset h;
        synchronized (this) {
            h = headset;
            headset = null;
            headsetReady = false;
        }
        if (h == null) return;
        log("headset closed: " + why);
        h.close();
    }

    /** The voice is not passed on while an answer comes or plays (and a moment after) — unless in headphones. */
    private boolean muted() {
        return !duplex && (answering || SystemClock.elapsedRealtime() < playingUntil);
    }

    /** Headphones (wired, Bluetooth, USB) on the phone now. */
    static boolean headphones(Context c) {
        android.media.AudioManager am = (android.media.AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return false;
        for (android.media.AudioDeviceInfo d : am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)) {
            int t = d.getType();
            // 3 wired headset, 4 wired headphones, 8 Bluetooth A2DP, 22 USB headset, 26 BLE headset
            if (t == 3 || t == 4 || t == 8 || t == 22 || t == 26) return true;
        }
        return false;
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
        }
        onVoice(pcm);
    }

    /** A piece of the voice (16 kHz), from the watch's app or its headset: on to Gemini, or kept till it is ready. */
    private void onVoice(short[] pcm) {
        synchronized (this) {
            double e = 0;
            for (short v : pcm) e += (double) v * v;
            boolean loud = pcm.length > 0 && Math.sqrt(e / pcm.length) >= LOUD;
            if (loud) lastLoudAt = SystemClock.elapsedRealtime();
            if (handDown) return;
            if (muted()) {
                // one at a time (the answer from a speaker): a phrase still going is over
                if (turns.active()) turns.finish(sink);
                return;
            }
            // while the answer plays (in the headphones, the watch as a headset) it may come back in the microphone:
            // speech must be louder and longer to cut it short
            turns.feed(pcm, duplex && (answering || SystemClock.elapsedRealtime() < playingUntil), sink);
        }
    }

    /** The turns found in the voice (called under the lock). */
    private final SpeechTurns.Sink sink = new SpeechTurns.Sink() {
        @Override
        public void start() {
            unanswered = true;
            queue(START);
        }

        @Override
        public void audio(short[] frame) {
            queue(frame);
        }

        @Override
        public void end(int silentMs) {
            speechEndAt = SystemClock.elapsedRealtime() - silentMs;
            queue(END);
        }
    };

    /** On to Gemini in order, or kept till it is ready (under the lock). */
    private void queue(Object o) {
        if (ready) {
            dispatch(o);
            return;
        }
        if (o instanceof short[]) {
            if (earlySamples >= EARLY_S * GeminiLive.IN_RATE) return;
            earlySamples += ((short[]) o).length;
        }
        early.add(o);
    }

    private void dispatch(final Object o) {
        onNet(new Runnable() {
            @Override
            public void run() {
                GeminiLive g = live;
                if (g == null || !g.ready()) return;
                try {
                    if (o == START) {
                        g.activityStart();
                    } else if (o == END) {
                        g.activityEnd();
                        synchronized (Talk.this) {
                            endSentAt = SystemClock.elapsedRealtime();
                        }
                    } else {
                        short[] f = (short[]) o;
                        g.sendAudio(f, 0, f.length);
                        voiceSent += f.length;
                    }
                } catch (java.io.IOException e) {
                    // the close is told by the listener
                }
            }
        });
    }

    /** On the network thread (nothing once closed). */
    private void onNet(Runnable r) {
        try {
            net.execute(r);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // closed
        }
    }

    private void lowered() {
        synchronized (this) {
            handDown = true;
            // the hand down ends the phrase at once: no pause to wait for
            turns.finish(sink);
        }
        log(String.format(Locale.ROOT, "lowered id=%d, voice passed on %.1f s", id, voiceSent / (float) GeminiLive.IN_RATE));
        idleLater();
    }

    private final GeminiLive.Listener listener = new GeminiLive.Listener() {
        @Override
        public void onReady() {
            int pending;
            synchronized (Talk.this) {
                connecting = false;
                ready = true;
                // in order, before anything said from now on
                for (Object o : early) dispatch(o);
                pending = early.size();
                early.clear();
                earlySamples = 0;
            }
            log("ready in " + (SystemClock.elapsedRealtime() - connectAt) + " ms; said meanwhile " + pending + " pieces");
            Headset h = null;
            synchronized (Talk.this) {
                if (toneDue && headsetReady) h = headset;
                toneDue = false;
            }
            if (h != null) h.tone(true); // Gemini listens: a rising tone in the watch
        }

        @Override
        public void onAudio(short[] pcm, int rate) {
            List<byte[]> out = null;
            Runnable mark = null;
            synchronized (Talk.this) {
                if (firstAudioOfTurn) {
                    // how soon the answer came: from the end of the speech (as the phone found it; else its last loud
                    // piece) to its first sound here — the pause that ended it, then Gemini's own time
                    firstAudioOfTurn = false;
                    unanswered = false;
                    long now = SystemClock.elapsedRealtime();
                    boolean found = speechEndAt >= raisedAt && speechEndAt > 0;
                    final long from = found ? speechEndAt : Math.max(lastLoudAt, raisedAt);
                    log("answer in " + (now - from) + " ms after the voice" + (found && endSentAt >= speechEndAt
                            ? " (the pause " + (endSentAt - speechEndAt) + " ms, Gemini " + (now - endSentAt) + " ms)" : ""));
                    mark = new Runnable() {
                        @Override
                        public void run() {
                            log("sound out " + (SystemClock.elapsedRealtime() - from) + " ms after the voice");
                        }
                    };
                }
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
            Headset h = headset;
            if (viaHeadset && h != null) {
                MAIN.removeCallbacks(headsetCloser); // the hand down before the answer came: kept till it has played
                synchronized (Talk.this) {
                    playingUntil = Math.max(playingUntil, SystemClock.elapsedRealtime()) + 1000L * pcm.length / rate;
                }
                h.play(pcm, rate, mark);
                return;
            }
            speak(pcm, rate, mark);
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
                firstAudioOfTurn = true;
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
                firstAudioOfTurn = true;
                speakGen++;
                playingUntil = 0;
            }
            log("interrupted: the answer stopped");
            Headset h = headset;
            if (h != null) h.hush();
            hush();
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
            boolean mine, again;
            String model;
            synchronized (Talk.this) {
                mine = closing;
                // refused before it was set up, having been asked to answer without thinking: once more without it
                again = !mine && !ready && thinkingOff && !thinkingRetried && (reason == null || !reason.toLowerCase(Locale.ROOT).contains("key"));
                if (again) thinkingRetried = true;
                model = modelName;
            }
            if (again) {
                String refused = WristLink.prefs(c).getString(PREF_THINK_REFUSED, "");
                WristLink.prefs(c).edit().putString(PREF_THINK_REFUSED, refused.isEmpty() ? model : refused + "\n" + model).apply();
                log("the model refused to answer without thinking: again with its own way");
                connect();
                return;
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
        byte[] wav;
        int ms, format;
        if (watch.mp3()) {
            // what any player plays (the watch's takes no ADPCM): MP3, 16 kHz — twice the sound of 8 kHz for the same bytes
            float[] y = Pcm.resample(x, ansRate, io.github.teoplaydor.semsearch.core.Mp3.RATE);
            for (int i = 0; i < y.length; i++) y[i] = Math.max(-1f, Math.min(1f, y[i]));
            wav = io.github.teoplaydor.semsearch.core.Mp3.encode(y, MP3_KBPS);
            ms = io.github.teoplaydor.semsearch.core.Mp3.durationMs(wav, MP3_KBPS);
            format = WristGesture.TALK_MP3;
        } else {
            float[] y = Pcm.resample(x, ansRate, WATCH_RATE);
            short[] s = new short[y.length];
            for (int i = 0; i < y.length; i++) s[i] = (short) Math.max(-32768, Math.min(32767, Math.round(y[i] * 32767)));
            wav = Adpcm.imaWav(s, WATCH_RATE);
            ms = (int) (1000L * s.length / WATCH_RATE);
            format = WristGesture.TALK_WAV;
        }
        seg = (seg + 1) & 0xff;
        int room = watch.room() - 6;
        for (int o = 0; o < wav.length; o += room) out.add(WristGesture.talkAudio(seg, o, wav, o, Math.min(room, wav.length - o)));
        out.add(WristGesture.talkSeg(seg, wav.length, ms, format));
        segsOut++;
        // when it will have played (sent at ~4 KB a second, then played after the ones before): the voice waits till then
        long now = SystemClock.elapsedRealtime();
        playingUntil = Math.max(playingUntil, now + wav.length / 4) + ms + ECHO_MS;
        log("piece " + seg + " (" + (format == WristGesture.TALK_MP3 ? "MP3" : "WAV") + "): " + ms + " ms, " + wav.length + " bytes in "
                + ((wav.length + room - 1) / room) + " notifications");
        return out;
    }

    private void playedOut(int s, int outcome) {
        boolean ok = outcome == WristGesture.PLAYED;
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
        String why = outcome == WristGesture.NOT_WHOLE ? "not whole on the watch (a notification lost)"
                : outcome == WristGesture.NOT_WRITTEN ? "the watch could not write its file"
                : outcome == WristGesture.SILENT ? "the watch's player said nothing (no start, no end)" : "the watch's player would not play it";
        if (!ok) log("piece " + s + " not played on the watch: " + why);
        if (phone) {
            log("the watch does not play the answers (" + why + ") — on the phone from now on");
            tell(WristGesture.talkText(outcome == WristGesture.NOT_WHOLE ? "Ответ теряется по Bluetooth — отвечаю через телефон"
                    : outcome == WristGesture.NOT_WRITTEN ? "Часы не записали ответ в файл — отвечаю через телефон"
                    : watch.mp3() ? "Звук в часах не играет — отвечаю через телефон"
                    : "Звук в часах не играет — обновите приложение на часах; пока отвечаю через телефон", watch.room()));
        }
        idleLater();
    }

    /** The answer on the phone stopped at once (spoken over): what is in the player dropped. */
    private void hush() {
        try {
            speaker.execute(new Runnable() {
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

    /** The phone's player made before the answer comes (making it then would delay the first sound). */
    private void warmSpeaker() {
        try {
            speaker.execute(new Runnable() {
                @Override
                public void run() {
                    if (track != null) return;
                    try {
                        track = Headset.track(AudioAttributes.USAGE_MEDIA, GeminiLive.OUT_RATE);
                        track.play();
                    } catch (RuntimeException e) {
                        log("phone speaker: " + e);
                    }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // closed
        }
    }

    /** The answer on the phone: its speaker, or the headphones. */
    private void speak(final short[] pcm, final int rate, final Runnable mark) {
        final int gen;
        synchronized (this) {
            playingUntil = Math.max(playingUntil, SystemClock.elapsedRealtime()) + 1000L * pcm.length / rate;
            gen = speakGen;
        }
        try {
            speaker.execute(new Runnable() {
            @Override
            public void run() {
                if (gen != speakGen) return; // spoken over since: not played
                try {
                    if (track == null || track.getSampleRate() != rate) {
                        if (track != null) track.release();
                        track = Headset.track(AudioAttributes.USAGE_MEDIA, rate);
                        track.play();
                    }
                    Headset.write(track, pcm);
                    if (mark != null) mark.run();
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
        boolean idle, awaited;
        long wait;
        synchronized (this) {
            // pieces still playing on the watch: counted in playingUntil (their «played» may never come from an older watch)
            idle = handDown && turnDone && !answering;
            wait = Math.max(0, playingUntil - SystemClock.elapsedRealtime());
            awaited = unanswered;
        }
        MAIN.removeCallbacks(closer);
        MAIN.removeCallbacks(headsetCloser);
        if (idle) {
            MAIN.postDelayed(closer, KEEP_MS + wait);
            // the watch's «call» released once the answer has played (raised again — opened again)
            // (what was said last may still be answered: a while given for it to begin)
            if (headset != null) MAIN.postDelayed(headsetCloser, Math.max(wait + 600, awaited ? ANSWER_WAIT_MS : 0));
        }
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
        MAIN.removeCallbacks(headsetCloser);
        log("closing: " + why);
        closeHeadset("the conversation closed");
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
