package io.github.teoplaydor.semsearch.app;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.view.KeyEvent;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import io.github.teoplaydor.semsearch.core.VoiceNote;
import io.github.teoplaydor.semsearch.core.WristGesture;

/**
 * Tracks by a turn of the wrist: the phone is a small Bluetooth LE device the watch connects to (a GATT server with
 * one service). The watch's own little app recognises the turn itself and sends one short packet per turn — the
 * phone's radio and processor stay idle in between — or, if it cannot, streams its raw accelerometer and the phone
 * recognises the turn ({@link WristGesture}). The phone decides what the turn means (big enough, which way) and presses
 * «next» or «previous» like a headset's button would — only while music is playing. Any watch whose apps can be a
 * Bluetooth LE client works; no maker's SDK on the phone.
 *
 * <p>Only one watch is listened to: the first that says HELLO is remembered (its random id), others are turned away
 * until the user forgets it. The watch gets a one-byte answer to vibrate on, and «pause» / «stream» as the music stops
 * and plays again, so it sends nothing while there is nothing to switch.
 *
 * <p>Voice notes: the watch held to the mouth records, and when lowered sends the recording in VOICE packets (a lost
 * write is asked for again); the phone makes it a note ({@link WatchVoice}) and tells the watch what became of it.
 */
public final class WristLink {
    static final String PREF_ON = "wrist_on", PREF_LEFT = "wrist_left_hand", PREF_TURN = "wrist_turn", PREF_AXIS = "wrist_axis",
            PREF_WATCH = "wrist_watch", PREF_WATCH_NAME = "wrist_watch_name", PREF_LAST = "wrist_last", PREF_LAST_AT = "wrist_last_at",
            PREF_VOICE = "wrist_voice", PREF_VOICE_ONLINE = "wrist_voice_online", PREF_DEBUG_UNTIL = "wrist_debug_until";
    /** How long the watch sends its sensor to the log once asked. */
    static final long DEBUG_MS = 30 * 60_000L;
    static final UUID SERVICE = UUID.fromString(WristGesture.SERVICE), SAMPLES = UUID.fromString(WristGesture.SAMPLES),
            EVENTS = UUID.fromString(WristGesture.EVENTS);
    /** The standard «Client Characteristic Configuration» descriptor (notifications on/off). */
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private WristLink() {
    }

    static SharedPreferences prefs(Context c) {
        return Engine.get(c).prefs();
    }

    // ------------------------------------------------------------------ settings

    public static boolean on(Context c) {
        return prefs(c).getBoolean(PREF_ON, false);
    }

    public static boolean leftHand(Context c) {
        return prefs(c).getBoolean(PREF_LEFT, true);
    }

    /** Voice notes from the watch (held to the mouth): on unless switched off. */
    public static boolean voiceNotes(Context c) {
        return prefs(c).getBoolean(PREF_VOICE, true);
    }

    public static void setVoiceNotes(Context c, boolean on) {
        prefs(c).edit().putBoolean(PREF_VOICE, on).apply();
        reconfigure(c);
    }

    /**
     * Voice notes recognized by the phone's usual (network) recognizer — more exact, the sound goes out — rather than on
     * the phone itself. Off unless switched on.
     */
    public static boolean voiceOnline(Context c) {
        return prefs(c).getBoolean(PREF_VOICE_ONLINE, false);
    }

    public static void setVoiceOnline(Context c, boolean on) {
        prefs(c).edit().putBoolean(PREF_VOICE_ONLINE, on).apply();
    }

    /**
     * The log asked for (until a time): the watch sends its sensor sample by sample and its own doings, for
     * {@link WristLog}. On for half an hour, then off by itself (it costs the watch's battery).
     */
    public static boolean debug(Context c) {
        return debugUntil(c) > System.currentTimeMillis();
    }

    static long debugUntil(Context c) {
        return prefs(c).getLong(PREF_DEBUG_UNTIL, 0);
    }

    public static void setDebug(Context c, boolean on) {
        prefs(c).edit().putLong(PREF_DEBUG_UNTIL, on ? System.currentTimeMillis() + DEBUG_MS : 0).apply();
        WristLog.add(c, "DEBUG", on ? "on for 30 min: the watch sends its sensor and its doings" : "off");
        reconfigure(c);
    }

    /** How far to turn, degrees: 30, 40 or 55. */
    public static int turn(Context c) {
        return prefs(c).getInt(PREF_TURN, 40);
    }

    /** The axis of a turn to the right, learnt from the watch (null: not learnt). */
    static float[] axis(Context c) {
        String s = prefs(c).getString(PREF_AXIS, null);
        if (s == null) return null;
        String[] p = s.split(",");
        if (p.length != 3) return null;
        try {
            return new float[]{Float.parseFloat(p[0]), Float.parseFloat(p[1]), Float.parseFloat(p[2])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static boolean learnt(Context c) {
        return axis(c) != null;
    }

    /** The watch listened to (its HELLO id), 0: none yet — the next one to say HELLO is kept. */
    static int watch(Context c) {
        return prefs(c).getInt(PREF_WATCH, 0);
    }

    static String watchName(Context c) {
        return prefs(c).getString(PREF_WATCH_NAME, "");
    }

    public static void setOn(Context c, boolean on) {
        prefs(c).edit().putBoolean(PREF_ON, on).apply();
        if (on) start(c);
        else c.stopService(new Intent(c, WristService.class));
    }

    public static void setHand(Context c, boolean left) {
        // the learnt axis belongs to the hand it was learnt on
        prefs(c).edit().putBoolean(PREF_LEFT, left).remove(PREF_AXIS).apply();
        reconfigure(c);
    }

    public static void setTurn(Context c, int deg) {
        prefs(c).edit().putInt(PREF_TURN, deg).apply();
        reconfigure(c);
    }

    /** Another watch may be listened to: the next one to say HELLO. */
    public static void forgetWatch(Context c) {
        prefs(c).edit().remove(PREF_WATCH).remove(PREF_WATCH_NAME).remove(PREF_AXIS).apply();
        Server s = server;
        if (s != null) s.dropAll();
        reconfigure(c);
    }

    // ------------------------------------------------------------------ what the phone can do

    /** The phone can be a Bluetooth LE device others connect to (most phones since Android 8). */
    public static boolean supported(Context c) {
        if (!c.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) return false;
        BluetoothManager bm = (BluetoothManager) c.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
        if (ad == null) return false;
        try {
            return !ad.isEnabled() || ad.isMultipleAdvertisementSupported();
        } catch (SecurityException e) {
            return true;
        }
    }

    /** Android 12+: «Nearby devices» (advertising and the connection); before that nothing to ask. */
    static String[] permissions() {
        return Build.VERSION.SDK_INT >= 31 ? new String[]{"android.permission.BLUETOOTH_ADVERTISE", "android.permission.BLUETOOTH_CONNECT"}
                : new String[0];
    }

    public static boolean hasPermissions(Context c) {
        for (String p : permissions()) {
            if (c.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    /** Starts the service holding the link (when on and allowed). */
    static boolean start(Context c) {
        if (!on(c) || !hasPermissions(c)) return false;
        try {
            Intent i = new Intent(c, WristService.class);
            Context.class.getMethod("startForegroundService", Intent.class).invoke(c, i);
            return true;
        } catch (Exception e) {
            android.util.Log.w("SemSearch", "wrist service not started", e);
            return false;
        }
    }

    // ------------------------------------------------------------------ state for the screen

    static volatile Server server;

    /** The live link (null while off). */
    public static Server current() {
        return server;
    }
    private static volatile String status = "";
    private static Runnable listener;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** «ждёт часы», «Xiaomi (vela) · 25 Гц», «Bluetooth выключен»… */
    public static String status() {
        return status;
    }

    /** The screen showing the state (one at a time; null to stop). */
    static void listen(Runnable r) {
        listener = r;
    }

    static void setStatus(String s) {
        status = s;
        final Runnable r = listener;
        if (r != null) MAIN.post(r);
        WristService.update();
    }

    /** The last turn's result, for the screen: «вправо → следующий трек», «влево, но музыка не играла». */
    public static String last(Context c) {
        return prefs(c).getString(PREF_LAST, "");
    }

    /** Teach the direction: the next turn the watch sends is the turn to the right. False: no watch connected. */
    public static boolean learn() {
        Server s = server;
        return s != null && s.learn();
    }

    static void reconfigure(Context c) {
        Server s = server;
        if (s != null) s.reconfigure();
        setStatus(status);
    }

    // ------------------------------------------------------------------ the music

    /** «Next» or «previous», the way a headset's button presses it: whichever player has the music now acts. */
    static void press(Context c, int keyCode) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        long now = SystemClock.uptimeMillis();
        try {
            am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
            am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
        } catch (RuntimeException e) {
            android.util.Log.w("SemSearch", "media key not delivered", e);
        }
    }

    static boolean musicPlaying(Context c) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        return am != null && am.isMusicActive();
    }

    // ------------------------------------------------------------------ the link

    /** The phone's side: the GATT server, the advertising, one stream per connected watch. */
    public static final class Server {
        final Context c;
        public BluetoothGattServer gatt;
        private BluetoothLeAdvertiser adv;
        private boolean advertising;
        private BluetoothGattCharacteristic events;
        private final Map<String, Conn> conns = new HashMap<String, Conn>();
        /** The last answer sent to the watch (for the tests and the log). */
        public volatile int lastEvent = -1;
        /** The last longer message sent to the watch (config, voice), for the tests. */
        public volatile byte[] lastMessage;
        /** Tests: every longer message sent to the watch (null — not kept). */
        public volatile List<byte[]> messages;
        /**
         * Voice notes heard lately, by watch, note id, length and duration: the same note sent again (its end said again,
         * or all of it after a new link) is answered as before, not made a second note. Null result: still being made.
         */
        private final LinkedHashMap<String, Object[]> heard = new LinkedHashMap<String, Object[]>();

        /** One connected watch. */
        final class Conn {
            final BluetoothDevice dev;
            final WristGesture d = new WristGesture();
            boolean trusted, notify;
            /** What the watch was last told: 1 stream, 0 pause, -1 nothing yet. */
            int told = -1;
            /** For the screen: how many sample packets have arrived, the largest tilt seen, and when it was last shown. */
            long packets;
            float maxTilt;
            long shownAt;
            /** Sample packets that came without (or after a refused) HELLO. */
            long unheard;
            /** The watch's turns already shown on the screen. */
            long turnsShown;
            /** The link's MTU (23 until the watch asks for more): how much one notification holds. */
            int mtu = 23;
            /** A voice note coming in; where a gap was last asked for, and when. */
            final VoiceNote voice = new VoiceNote();
            long askedFrom = -1, askedAt;
            /** A long write in parts (prepared writes) until it is executed. */
            ByteArrayOutputStream prepared;
            /** The conversation (with Gemini) this watch is in: its voice's id; -1 — none. */
            int liveId = -1;
            /** The conversation's way to this watch. */
            final Talk.Watch talk = new Talk.Watch() {
                @Override
                public void send(byte[] packet) {
                    message(Conn.this, packet);
                }

                @Override
                public int room() {
                    return mtu - 3;
                }
            };

            Conn(BluetoothDevice dev) {
                this.dev = dev;
                configure(d);
            }
        }

        Server(Context c) {
            this.c = c.getApplicationContext();
        }

        /** While a watch listens: is the music playing? Told when it changes (a cheap look every 2 s). */
        static final long MUSIC_MS = 2000;
        private final Handler tick = new Handler(Looper.getMainLooper());
        private final Runnable music = new Runnable() {
            @Override
            public void run() {
                boolean any = false;
                synchronized (Server.this) {
                    for (Conn k : conns.values()) {
                        if (k.trusted && k.notify) {
                            tell(k);
                            any = true;
                        }
                    }
                }
                if (any) tick.postDelayed(this, MUSIC_MS);
            }
        };

        /** Tell the watch whether to stream (only on a change); starting again, the old samples do not count. */
        private void tell(Conn k) {
            int playing = musicPlaying(c) ? 1 : 0;
            if (k.told == playing) return;
            k.told = playing;
            log("MUSIC", playing == 1 ? "playing" : "not playing");
            if (playing == 1) k.d.restart();
            answer(k, playing == 1 ? WristGesture.EV_STREAM : WristGesture.EV_PAUSE);
            // said on the screen too: a paused watch sends nothing, and without this the line just stops changing
            setStatus(name(k) + (k.d.recognises() ? " · распознаёт повороты сам · " : " · ") + k.d.rate() + " Гц · "
                    + (playing == 1 ? "музыка играет — поверните кисть" : "пауза: на телефоне не играет музыка")
                    + (voiceNotes(c) ? " · заметки: часы ко рту" : ""));
        }

        /** 0,3 — seconds with one decimal, the Russian way. */
        static String seconds(float ms) {
            return String.valueOf(Math.round(ms / 100) / 10.0).replace('.', ',');
        }

        private static String name(Conn k) {
            return k.d.platform().isEmpty() ? "часы" : k.d.platform();
        }

        /** A trusted watch now listens: tell it at once, then keep looking. */
        private void listening(Conn k) {
            if (!k.trusted || !k.notify) return;
            sendConfig(k);
            tell(k);
            tick.removeCallbacks(music);
            tick.postDelayed(music, MUSIC_MS);
        }

        /** The watch connected or gone as a headset (its Bluetooth calls): the watch told how a conversation goes now. */
        private final android.media.AudioDeviceCallback headsets = new android.media.AudioDeviceCallback() {
            @Override
            public void onAudioDevicesAdded(android.media.AudioDeviceInfo[] added) {
                for (android.media.AudioDeviceInfo d : added) {
                    if (d.getType() == Headset.SCO) {
                        log("HEADSET", "connected: " + d.getProductName());
                        reconfigure();
                        return;
                    }
                }
            }

            @Override
            public void onAudioDevicesRemoved(android.media.AudioDeviceInfo[] removed) {
                for (android.media.AudioDeviceInfo d : removed) {
                    if (d.getType() == Headset.SCO) {
                        log("HEADSET", "gone: " + d.getProductName());
                        reconfigure();
                        return;
                    }
                }
            }
        };
        private boolean headsetsWatched;

        /** Opens the server and starts advertising; false (with the reason in the status) when it cannot. */
        synchronized boolean open() {
            if (!headsetsWatched) {
                AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
                if (am != null) {
                    am.registerAudioDeviceCallback(headsets, tick);
                    headsetsWatched = true;
                }
            }
            if (gatt != null) return true;
            BluetoothManager bm = (BluetoothManager) c.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
            if (ad == null) {
                setStatus("на телефоне нет Bluetooth");
                return false;
            }
            try {
                if (!ad.isEnabled()) {
                    setStatus("Bluetooth выключен");
                    return false;
                }
                gatt = bm.openGattServer(c, callback);
                if (gatt == null) {
                    setStatus("Bluetooth не отвечает");
                    return false;
                }
                BluetoothGattService s = new BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY);
                BluetoothGattCharacteristic samples = new BluetoothGattCharacteristic(SAMPLES,
                        BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                        BluetoothGattCharacteristic.PERMISSION_WRITE);
                events = new BluetoothGattCharacteristic(EVENTS,
                        BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_READ,
                        BluetoothGattCharacteristic.PERMISSION_READ);
                events.addDescriptor(new BluetoothGattDescriptor(CCCD,
                        BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE));
                events.setValue(new byte[]{0});
                s.addCharacteristic(samples);
                s.addCharacteristic(events);
                adv = ad.getBluetoothLeAdvertiser();
                gatt.addService(s);
                advertise(true); // and again when the service is registered (onServiceAdded), which is idempotent
                return true;
            } catch (SecurityException e) {
                setStatus("нет разрешения «Устройства поблизости»");
                close();
                return false;
            }
        }

        synchronized void close() {
            if (headsetsWatched) {
                AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
                if (am != null) am.unregisterAudioDeviceCallback(headsets);
                headsetsWatched = false;
            }
            tick.removeCallbacks(music);
            tick.removeCallbacks(released);
            outbox.clear();
            inFlight = false;
            advertise(false);
            if (gatt != null) {
                try {
                    gatt.close();
                } catch (RuntimeException e) {
                    // already gone with the adapter
                }
            }
            gatt = null;
            conns.clear();
        }

        /** Advertising while no trusted watch is streaming: found by its service, «low power» (once a second). */
        private void advertise(boolean on) {
            if (adv == null || on == advertising) return;
            try {
                if (on) {
                    AdvertiseSettings set = new AdvertiseSettings.Builder()
                            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
                            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                            .setConnectable(true)
                            .setTimeout(0)
                            .build();
                    AdvertiseData data = new AdvertiseData.Builder()
                            .setIncludeDeviceName(false)
                            .addServiceUuid(new ParcelUuid(SERVICE))
                            .build();
                    adv.startAdvertising(set, data, advertised);
                    if (!trustedConnected()) setStatus(watch(c) == 0 ? "ждёт часы — откройте приложение на часах" : "ждёт часы");
                } else {
                    adv.stopAdvertising(advertised);
                }
                advertising = on;
            } catch (SecurityException e) {
                setStatus("нет разрешения «Устройства поблизости»");
            } catch (RuntimeException e) {
                android.util.Log.w("SemSearch", "advertising", e);
            }
        }

        private final AdvertiseCallback advertised = new AdvertiseCallback() {
            @Override
            public void onStartFailure(int code) {
                advertising = false;
                setStatus(code == ADVERTISE_FAILED_FEATURE_UNSUPPORTED ? "телефон не умеет быть Bluetooth-устройством для часов"
                        : "Bluetooth занят (" + code + ")");
            }
        };

        private boolean trustedConnected() {
            for (Conn k : conns.values()) if (k.trusted) return true;
            return false;
        }

        void configure(WristGesture d) {
            d.turn = turn(c);
            d.setRight(axis(c), leftHand(c));
        }

        synchronized void reconfigure() {
            for (Conn k : conns.values()) {
                configure(k.d);
                if (k.trusted) sendConfig(k);
            }
        }

        /** The watch's settings: which hand, voice notes on, and the link's MTU (how big its writes may be). */
        private void sendConfig(Conn k) {
            boolean debug = debug(c);
            boolean talk = Talk.on(c), talkHeadset = talk && Talk.headsetUsable(c), talkWatch = talk && Talk.onWatch(c) && k.mtu - 3 >= Talk.MIN_ROOM;
            // the watch at the mouth works for a conversation even with voice notes off
            int flags = (leftHand(c) ? WristGesture.CFG_LEFT_HAND : 0) | (voiceNotes(c) || talk ? WristGesture.CFG_VOICE : 0)
                    | (debug ? WristGesture.CFG_DEBUG : 0) | (talk ? WristGesture.CFG_TALK : 0) | (talkWatch ? WristGesture.CFG_TALK_WATCH : 0)
                    | (talkHeadset ? WristGesture.CFG_TALK_HEADSET : 0);
            log("CFG", "left=" + leftHand(c) + " voice=" + voiceNotes(c) + " debug=" + debug + " talk=" + talk + (talk ? talkHeadset
                    ? " through the watch as a headset (" + Headset.name(c) + ")" : " answers on the " + (talkWatch ? "watch" : "phone") : "")
                    + " mtu=" + k.mtu);
            message(k, WristGesture.config(flags, k.mtu));
            tick.removeCallbacks(debugOver);
            if (debug) tick.postDelayed(debugOver, debugUntil(c) - System.currentTimeMillis() + 500);
        }

        /** The half hour of the log is over: the watch told to stop sending its sensor. */
        private final Runnable debugOver = new Runnable() {
            @Override
            public void run() {
                log("DEBUG", "over");
                WristLink.reconfigure(c);
            }
        };

        private void log(String tag, String text) {
            WristLog.add(c, tag, text);
        }

        /** A device's address for the log: its last two bytes are enough to tell one from another. */
        private static String who(BluetoothDevice dev) {
            String a = dev == null ? null : dev.getAddress();
            return a == null ? "?" : "…" + a.substring(Math.max(0, a.length() - 5));
        }

        synchronized boolean learn() {
            boolean any = false;
            for (Conn k : conns.values()) {
                if (k.trusted) {
                    k.d.learn();
                    any = true;
                }
            }
            return any;
        }

        synchronized void dropAll() {
            for (Conn k : conns.values()) {
                try {
                    if (gatt != null) gatt.cancelConnection(k.dev);
                } catch (RuntimeException e) {
                    // gone already
                }
            }
            conns.clear();
            advertise(true);
        }

        private final BluetoothGattServerCallback callback = new BluetoothGattServerCallback() {
            @Override
            public void onServiceAdded(int status, BluetoothGattService service) {
                // the service is registered now: safe for a watch to discover it, so start announcing
                synchronized (Server.this) {
                    if (!trustedConnected()) advertise(true);
                }
            }

            @Override
            public void onConnectionStateChange(BluetoothDevice dev, int st, int state) {
                synchronized (Server.this) {
                    log("CONN", who(dev) + (state == BluetoothProfile.STATE_CONNECTED ? " connected" : state == BluetoothProfile.STATE_DISCONNECTED
                            ? " disconnected" : " state " + state) + " status " + st);
                    if (state == BluetoothProfile.STATE_CONNECTED) {
                        if (!conns.containsKey(dev.getAddress())) conns.put(dev.getAddress(), new Conn(dev));
                        // a watch connected: stop announcing (the radio is freer for the watch's service discovery)
                        advertise(false);
                        // the link reached the phone: the watch's HELLO comes next
                        if (!trustedConnected()) setStatus("часы подключаются…");
                    } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                        Conn k = conns.remove(dev.getAddress());
                        if (k != null && k.liveId >= 0) Talk.stop();
                        if (k != null && k.trusted && !trustedConnected()) {
                            advertise(true);
                            setStatus("часы отключились — ждёт");
                        } else if (k != null && !trustedConnected()) {
                            setStatus("подключение оборвалось до приветствия часов — ждёт");
                        }
                    }
                }
            }

            @Override
            public void onCharacteristicWriteRequest(BluetoothDevice dev, int id, BluetoothGattCharacteristic ch, boolean prepared,
                                                     boolean needsAnswer, int offset, byte[] value) {
                if (prepared && SAMPLES.equals(ch.getUuid())) {
                    // a long write (a voice note's bytes in a write bigger than the link's MTU): its parts kept until executed
                    boolean ok;
                    synchronized (Server.this) {
                        Conn k = conn(dev);
                        if (offset == 0 || k.prepared == null) k.prepared = new ByteArrayOutputStream();
                        ok = offset == k.prepared.size() && value != null && k.prepared.size() + value.length <= 4096;
                        if (ok) k.prepared.write(value, 0, value.length);
                        else k.prepared = null;
                    }
                    if (needsAnswer) respond(dev, id, ok ? BluetoothGatt.GATT_SUCCESS : BluetoothGatt.GATT_INVALID_OFFSET, offset, value);
                    return;
                }
                boolean ok = SAMPLES.equals(ch.getUuid()) && !prepared && offset == 0;
                // a strict central wants the written value echoed in the response
                if (needsAnswer) respond(dev, id, ok ? BluetoothGatt.GATT_SUCCESS : BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, ok ? value : null);
                if (ok) received(dev, value);
            }

            @Override
            public void onExecuteWrite(BluetoothDevice dev, int id, boolean execute) {
                byte[] v = null;
                synchronized (Server.this) {
                    Conn k = conns.get(dev.getAddress());
                    if (k != null && k.prepared != null && execute) v = k.prepared.toByteArray();
                    if (k != null) k.prepared = null;
                }
                respond(dev, id, BluetoothGatt.GATT_SUCCESS, null);
                if (v != null && v.length > 0) received(dev, v);
            }

            @Override
            public void onNotificationSent(BluetoothDevice dev, int status) {
                // out: the next may go
                tick.removeCallbacks(released);
                tick.post(released);
            }

            @Override
            public void onMtuChanged(BluetoothDevice dev, int mtu) {
                synchronized (Server.this) {
                    Conn k = conn(dev);
                    k.mtu = Math.max(23, mtu);
                    log("MTU", who(dev) + " " + mtu);
                    if (k.trusted && k.notify) sendConfig(k);
                }
            }

            @Override
            public void onDescriptorWriteRequest(BluetoothDevice dev, int id, BluetoothGattDescriptor d, boolean prepared,
                                                 boolean needsAnswer, int offset, byte[] value) {
                if (CCCD.equals(d.getUuid())) {
                    synchronized (Server.this) {
                        Conn k = conn(dev);
                        k.notify = value != null && value.length > 0 && (value[0] & 1) != 0;
                        log("CCCD", who(dev) + " answers " + (k.notify ? "on" : "off"));
                        listening(k);
                    }
                }
                // echo the value back: some centrals (Vela) wait on an exact write response before the subscribe completes
                if (needsAnswer) respond(dev, id, BluetoothGatt.GATT_SUCCESS, value);
            }

            @Override
            public void onDescriptorReadRequest(BluetoothDevice dev, int id, int offset, BluetoothGattDescriptor d) {
                boolean on;
                synchronized (Server.this) {
                    on = conn(dev).notify;
                }
                respond(dev, id, BluetoothGatt.GATT_SUCCESS, new byte[]{(byte) (on ? 1 : 0), 0});
            }

            @Override
            public void onCharacteristicReadRequest(BluetoothDevice dev, int id, int offset, BluetoothGattCharacteristic ch) {
                respond(dev, id, BluetoothGatt.GATT_SUCCESS, new byte[]{(byte) Math.max(0, lastEvent)});
            }
        };

        private Conn conn(BluetoothDevice dev) {
            Conn k = conns.get(dev.getAddress());
            if (k == null) {
                k = new Conn(dev);
                conns.put(dev.getAddress(), k);
            }
            return k;
        }

        private void respond(BluetoothDevice dev, int id, int status, byte[] value) {
            respond(dev, id, status, 0, value);
        }

        private void respond(BluetoothDevice dev, int id, int status, int offset, byte[] value) {
            BluetoothGattServer g = gatt;
            if (g == null) return;
            try {
                g.sendResponse(dev, id, status, offset, value);
            } catch (RuntimeException e) {
                // the watch went away
            }
        }

        /** A packet from the watch. */
        void received(BluetoothDevice dev, byte[] p) {
            if (p == null || p.length < 2) return;
            int r;
            Conn k;
            synchronized (this) {
                k = conn(dev);
                boolean hello = ((p[0] & 0xff) >>> 4) == WristGesture.KIND_HELLO;
                if (!hello && !k.trusted) {
                    // samples before a HELLO, or from a watch turned away: not listened to, but counted on the screen
                    // while no watch is listened to (so «nothing arrives» and «arrives unannounced» look different)
                    k.unheard++;
                    long now = System.currentTimeMillis();
                    if (k.unheard == 1 || k.unheard % 100 == 0) log("UNHEARD", who(dev) + " " + k.unheard + " packets before a HELLO, kind " + ((p[0] & 0xff) >>> 4));
                    if (!trustedConnected() && now - k.shownAt > 900) {
                        k.shownAt = now;
                        setStatus("часы шлют отсчёты без приветствия (" + k.unheard + ") — «Стоп» и «Старт» на часах");
                    }
                    return;
                }
                int kind = (p[0] & 0xff) >>> 4;
                if (kind == WristGesture.KIND_VOICE) {
                    voice(k, p);
                    return;
                }
                if (kind == WristGesture.KIND_RAW) {
                    // the watch's sensor as it came, with its own times (only while the log is asked for)
                    double[][] ss = WristGesture.rawSamples(p);
                    if (ss != null) for (double[] q : ss) log("S", sample(q[0], q[1], q[2], q[3], q[4]));
                    return;
                }
                if (kind == WristGesture.KIND_DIAG) {
                    log("W", WristGesture.diagText(p));
                    return;
                }
                r = k.d.packet(p);
                if (kind == WristGesture.KIND_ACCEL && debug(c) && k.d.hasHello()) {
                    // a watch that streams: its samples (the times by its pace)
                    int n = p[0] & 0x0f;
                    long first = k.d.samples() - n;
                    for (int i = 0; i < n && 2 + 6 * i + 5 < p.length; i++) {
                        log("S", sample((first + i) * 1000.0 / k.d.rate(), (short) ((p[2 + 6 * i] & 0xff) | (p[3 + 6 * i] << 8)),
                                (short) ((p[4 + 6 * i] & 0xff) | (p[5 + 6 * i] << 8)), (short) ((p[6 + 6 * i] & 0xff) | (p[7 + 6 * i] << 8)),
                                Double.NaN));
                    }
                }
                if (kind == WristGesture.KIND_ROLL && p.length >= 8) {
                    log("ROLL", String.format(Locale.ROOT, "seq=%d twist=%.1f took=%d src=%s slope=%.2f dslope=%d -> %s why=%s turn=%.0f sign=%s learnt=%s music=%s",
                            p[1] & 0xff, (short) ((p[2] & 0xff) | (p[3] << 8)) / 10f, (p[4] & 0xff) * 10,
                            p[5] == WristGesture.SOURCE_COMPASS ? "compass" : "gravity", p[6] / 100f, p[7] & 0xff,
                            r == WristGesture.RIGHT ? "RIGHT" : r == WristGesture.LEFT ? "LEFT" : r == WristGesture.LEARNED ? "LEARNED" : "NONE",
                            whyName(k.d.why()), k.d.turn, k.d.lastAxis()[0] > 0 ? "+" : "-", k.d.learnt(), musicPlaying(c)));
                } else if (kind == WristGesture.KIND_TURN || kind == WristGesture.KIND_SWING) {
                    log("OLD", "a packet of the first watch apps (kind " + kind + "): the watch app wants updating");
                } else if (kind != WristGesture.KIND_ACCEL && kind != WristGesture.KIND_HELLO) {
                    log("?", "kind " + kind + " " + hex(p));
                } else if (kind == WristGesture.KIND_ACCEL && r != WristGesture.NONE) {
                    log("TURN", "found by the phone in the streamed samples -> " + (r == WristGesture.RIGHT ? "RIGHT" : r == WristGesture.LEFT
                            ? "LEFT" : "LEARNED") + String.format(Locale.ROOT, " twist=%.1f took=%.0f", k.d.lastPeak(), k.d.lastTook()));
                }
                if (hello) {
                    if (!k.d.hasHello()) return;
                    int known = watch(c);
                    String name = name(k);
                    // accept the watch that connects: when none is remembered, when it is the remembered one, or when the
                    // remembered one is not currently streaming (so a re-installed watch with a new id is welcomed, not
                    // turned away). Only a different watch while one is actively connected is refused.
                    boolean accept = known == 0 || known == k.d.watchId() || !trustedConnected();
                    log("HELLO", String.format(Locale.ROOT, "%s v=%d id=%08x rate=%d perG=%d platform=%s known=%08x -> %s", who(dev),
                            p[1] & 0xff, k.d.watchId(), k.d.rate(), k.d.perG(), k.d.platform(), known, accept ? "accepted" : "refused"));
                    if (accept) {
                        float[] ax = axis(c);
                        log("SETTINGS", "turn=" + turn(c) + " hand=" + (leftHand(c) ? "left" : "right") + " learnt=" + (ax != null)
                                + " voice=" + voiceNotes(c) + " online=" + voiceOnline(c) + " debug=" + debug(c));
                    }
                    if (!accept) {
                        k.trusted = false;
                        setStatus("другие часы (" + name + ") уже подключены — «Забыть часы», чтобы сменить");
                        try {
                            gatt.cancelConnection(dev);
                        } catch (RuntimeException e) {
                            // gone
                        }
                        conns.remove(dev.getAddress());
                        return;
                    }
                    if (known != k.d.watchId()) {
                        prefs(c).edit().putInt(PREF_WATCH, k.d.watchId()).putString(PREF_WATCH_NAME, name).apply();
                    } else {
                        prefs(c).edit().putString(PREF_WATCH_NAME, name).apply();
                    }
                    k.trusted = true;
                    k.told = -1; // a new HELLO (the watch reconnected or changed its pace): told again
                    setStatus(k.d.recognises() ? name + " · распознаёт повороты сам · " + k.d.rate() + " Гц"
                            : name + " · " + k.d.rate() + " Гц · жду отсчёты");
                    advertise(false); // found: nothing more to announce
                    listening(k);
                    return;
                }
            }
            if (r == WristGesture.LEARNED) {
                float[] a = k.d.lastAxis();
                prefs(c).edit().putString(PREF_AXIS, String.format(Locale.ROOT, "%.4f,%.4f,%.4f", a[0], a[1], a[2])).apply();
                synchronized (this) {
                    reconfigure();
                }
                remember("направление выучено");
                answer(k, WristGesture.EV_LEARNED);
                return;
            }
            if (r == WristGesture.RIGHT || r == WristGesture.LEFT) {
                // a turn: shown below
            } else if (k.d.recognises()) {
                // the watch found a turn that means nothing here: say why, so the user knows what to change
                synchronized (this) {
                    if (k.d.turns() != k.turnsShown) {
                        k.turnsShown = k.d.turns();
                        if (k.d.why() == WristGesture.WHY_OLD_WATCH_APP) {
                            setStatus(name(k) + " · на часах старое приложение — установите новое, оно считает повороты иначе");
                            return;
                        }
                        float peak = k.d.lastPeak();
                        String why;
                        if (k.d.why() == WristGesture.WHY_SMALL) {
                            why = "мало, нужно " + Math.round(k.d.turn) + "° (или «Насколько поворачивать»)";
                        } else if (peak > WristGesture.MAX_TWIST) {
                            why = "слишком много для поворота кисти — похоже, двигалась вся рука";
                        } else {
                            why = "рука поднималась или опускалась — это не поворот кисти";
                        }
                        setStatus(name(k) + " · поворот " + Math.round(peak) + "° за " + seconds(k.d.lastTook()) + " с"
                                + (k.d.lastByCompass() ? " (компас)" : "") + " — " + why);
                    }
                }
                return;
            } else {
                // samples flowing: show the live tilt now and then, so it is clear the phone sees the movement
                synchronized (this) {
                    k.packets++;
                    k.maxTilt = Math.max(k.maxTilt * 0.9f, k.d.tilt());
                    long now = System.currentTimeMillis();
                    if (now - k.shownAt > 900) {
                        k.shownAt = now;
                        int[] raw = k.d.lastRaw();
                        String learnt = learnt(c) ? "" : " · направление не задано";
                        setStatus(name(k) + " · " + k.d.rate() + " Гц · пакетов " + k.packets + " · (" + raw[0] + "," + raw[1] + ","
                                + raw[2] + ") · наклон " + Math.round(k.maxTilt) + "°" + learnt);
                        k.maxTilt = k.d.tilt();
                    }
                }
                return;
            }
            boolean right = r == WristGesture.RIGHT;
            if (!musicPlaying(c)) {
                log("PRESS", "none: music not playing");
                remember((right ? "вправо" : "влево") + ", но музыка не играла");
                answer(k, WristGesture.EV_IGNORED);
                return;
            }
            press(c, right ? KeyEvent.KEYCODE_MEDIA_NEXT : KeyEvent.KEYCODE_MEDIA_PREVIOUS);
            log("PRESS", right ? "next" : "previous");
            remember((right ? "вправо → следующий трек" : "влево → предыдущий трек") + " (" + Math.round(k.d.lastPeak()) + "° за "
                    + seconds(k.d.lastTook()) + " с" + (k.d.lastByCompass() ? ", по компасу" : "") + ")");
            answer(k, right ? WristGesture.EV_NEXT : WristGesture.EV_PREVIOUS);
        }

        /** A voice note's packet: a gap asked for again (at most once a second), the whole note made a note. */
        private void voice(final Conn k, byte[] p) {
            int type = p[0] & 0xff;
            // a conversation (with Gemini): its voice streamed on as it comes, no note made
            if (type == WristGesture.VOICE_START && p.length >= 5 && (p[2] & WristGesture.CODEC_LIVE) != 0) {
                k.liveId = p[1] & 0xff;
                log("VOICE", "live id=" + k.liveId + " codec=" + (p[2] & 0x7f) + " rate=" + ((p[3] & 0xff) | (p[4] & 0xff) << 8));
                Talk.start(c, k.talk, k.liveId, p[2] & 0xff, (p[3] & 0xff) | (p[4] & 0xff) << 8);
                return;
            }
            if (type == WristGesture.VOICE_PLAYED && p.length >= 4) {
                Talk.played(p[1] & 0xff, p[2] & 0xff, p[3] == 0);
                return;
            }
            if (k.liveId >= 0 && (p[1] & 0xff) == k.liveId && (type == WristGesture.VOICE_DATA || type == WristGesture.VOICE_END)) {
                if (type == WristGesture.VOICE_DATA && p.length > 6) {
                    long offset = (p[2] & 0xffL) | (p[3] & 0xffL) << 8 | (p[4] & 0xffL) << 16 | (p[5] & 0xffL) << 24;
                    Talk.data(k.liveId, offset, p, 6, p.length - 6);
                } else if (type == WristGesture.VOICE_END) {
                    log("VOICE", "live end id=" + k.liveId);
                    Talk.end(k.liveId);
                }
                return;
            }
            if (type == WristGesture.VOICE_START) k.liveId = -1; // a note now, not the conversation
            int r = k.voice.packet(p);
            if (r == VoiceNote.STARTED) {
                log("VOICE", "start id=" + k.voice.id() + " codec=" + k.voice.codec() + " rate=" + k.voice.rate());
                k.askedFrom = -1;
                setStatus(name(k) + " · заметка голосом…");
                return;
            }
            if (r == VoiceNote.MISSING) {
                long now = SystemClock.elapsedRealtime(), from = k.voice.missingFrom();
                if (from != k.askedFrom || now - k.askedAt > 1000) {
                    log("VOICE", "missing id=" + k.voice.id() + " from=" + from + " (have " + k.voice.received() + ")");
                    k.askedFrom = from;
                    k.askedAt = now;
                    message(k, WristGesture.voiceFrom(k.voice.id(), from));
                }
                return;
            }
            boolean again = r == VoiceNote.NONE && (p[0] & 0xff) == WristGesture.VOICE_END && k.voice.complete()
                    && (p[1] & 0xff) == k.voice.id();
            if (r != VoiceNote.COMPLETE && !again) return;
            final int id = k.voice.id();
            final String key = k.d.watchId() + ":" + id + ":" + k.voice.total() + ":" + k.voice.millis();
            Object[] before = heard.get(key);
            log("VOICE", "complete id=" + id + " bytes=" + k.voice.total() + " ms=" + k.voice.millis() + (before != null ? " (heard before)" : ""));
            if (before != null) {
                // the same note again: answered as before (or still being made — the answer follows)
                if (before[0] != null) message(k, WristGesture.voiceDone(id, (Integer) before[0], (String) before[1], k.mtu - 3));
                return;
            }
            heard.put(key, new Object[]{null, null});
            while (heard.size() > 16) {
                Iterator<String> it = heard.keySet().iterator();
                it.next();
                it.remove();
            }
            final String who = name(k);
            setStatus(who + " · заметка с часов: распознаю…");
            WatchVoice.handle(c, k.voice.bytes(), k.voice.codec(), k.voice.rate(), new WatchVoice.Done() {
                @Override
                public void done(int result, String line) {
                    log("VOICE", "done id=" + id + " result=" + result + " " + line);
                    synchronized (Server.this) {
                        heard.put(key, new Object[]{result, line});
                        // to the watch now connected (it may have reconnected meanwhile)
                        for (Conn now : conns.values()) {
                            if (now.trusted) message(now, WristGesture.voiceDone(id, result, line, now.mtu - 3));
                        }
                    }
                    setStatus(who + " · заметка с часов готова");
                    remember("заметка с часов: " + line);
                }
            });
        }

        static String sample(double t, double x, double y, double z, double h) {
            return String.format(Locale.ROOT, "wt=%.0f x=%.2f y=%.2f z=%.2f h=%s", t, x, y, z, Double.isNaN(h) ? "-" : String.format(Locale.ROOT, "%.1f", h));
        }

        static String hex(byte[] p) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < p.length && i < 40; i++) b.append(String.format("%02x", p[i] & 0xff));
            return b.toString();
        }

        static String whyName(int why) {
            switch (why) {
                case WristGesture.WHY_OK:
                    return "ok";
                case WristGesture.WHY_SMALL:
                    return "small";
                case WristGesture.WHY_NOT_FOREARM:
                    return "not-forearm";
                case WristGesture.WHY_OLD_WATCH_APP:
                    return "old-app";
                default:
                    return String.valueOf(why);
            }
        }

        static String evName(int ev) {
            switch (ev) {
                case WristGesture.EV_IGNORED:
                    return "ignored";
                case WristGesture.EV_NEXT:
                    return "next";
                case WristGesture.EV_PREVIOUS:
                    return "previous";
                case WristGesture.EV_LEARNED:
                    return "learned";
                case WristGesture.EV_PAUSE:
                    return "pause";
                case WristGesture.EV_STREAM:
                    return "stream";
                default:
                    return String.valueOf(ev);
            }
        }

        /** A longer message to the watch (settings, voice notes), if it listens. */
        private void message(Conn k, byte[] v) {
            lastMessage = v;
            List<byte[]> m = messages;
            if (m != null) m.add(v);
            notifyWatch(k, v);
        }

        private void remember(String what) {
            prefs(c).edit().putString(PREF_LAST, what).putLong(PREF_LAST_AT, System.currentTimeMillis()).apply();
            setStatus(status);
        }

        /** One byte to the watch, if it listens: it vibrates. */
        private void answer(Conn k, int ev) {
            lastEvent = ev;
            log("EV", evName(ev) + (k.notify ? "" : " (the watch does not listen)"));
            notifyWatch(k, new byte[]{(byte) ev});
        }

        /** How long to wait for «sent» before the next notification goes anyway (some stacks never say it). */
        static final long NOTIFY_MS = 120;
        /** Notifications to the watch, one at a time: Android sends the next only after the last is out. */
        private final java.util.ArrayDeque<Object[]> outbox = new java.util.ArrayDeque<Object[]>();
        private boolean inFlight;
        private final Runnable released = new Runnable() {
            @Override
            public void run() {
                synchronized (Server.this) {
                    inFlight = false;
                }
                flush();
            }
        };

        private void notifyWatch(Conn k, byte[] v) {
            synchronized (this) {
                if (!k.notify) return;
                outbox.add(new Object[]{k, v});
                if (inFlight) return;
            }
            flush();
        }

        /** The next notification out, if none is on its way. */
        private void flush() {
            Object[] o;
            synchronized (this) {
                if (inFlight) return;
                o = outbox.poll();
                if (o == null) return;
                inFlight = true;
            }
            Conn k = (Conn) o[0];
            BluetoothGattServer g = gatt;
            boolean sent = false;
            if (g != null && events != null) {
                try {
                    events.setValue((byte[]) o[1]);
                    sent = g.notifyCharacteristicChanged(k.dev, events, false);
                } catch (RuntimeException e) {
                    // the watch went away
                }
            }
            tick.removeCallbacks(released);
            tick.postDelayed(released, sent ? NOTIFY_MS : 0);
        }
    }
}
