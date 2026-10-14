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
            PREF_VOICE = "wrist_voice", PREF_VOICE_ONLINE = "wrist_voice_online";
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

        /** Opens the server and starts advertising; false (with the reason in the status) when it cannot. */
        synchronized boolean open() {
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
            int flags = (leftHand(c) ? WristGesture.CFG_LEFT_HAND : 0) | (voiceNotes(c) ? WristGesture.CFG_VOICE : 0);
            message(k, WristGesture.config(flags, k.mtu));
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
                    if (state == BluetoothProfile.STATE_CONNECTED) {
                        if (!conns.containsKey(dev.getAddress())) conns.put(dev.getAddress(), new Conn(dev));
                        // a watch connected: stop announcing (the radio is freer for the watch's service discovery)
                        advertise(false);
                        // the link reached the phone: the watch's HELLO comes next
                        if (!trustedConnected()) setStatus("часы подключаются…");
                    } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                        Conn k = conns.remove(dev.getAddress());
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
                    if (!trustedConnected() && now - k.shownAt > 900) {
                        k.shownAt = now;
                        setStatus("часы шлют отсчёты без приветствия (" + k.unheard + ") — «Стоп» и «Старт» на часах");
                    }
                    return;
                }
                if (((p[0] & 0xff) >>> 4) == WristGesture.KIND_VOICE) {
                    voice(k, p);
                    return;
                }
                r = k.d.packet(p);
                if (hello) {
                    if (!k.d.hasHello()) return;
                    int known = watch(c);
                    String name = name(k);
                    // accept the watch that connects: when none is remembered, when it is the remembered one, or when the
                    // remembered one is not currently streaming (so a re-installed watch with a new id is welcomed, not
                    // turned away). Only a different watch while one is actively connected is refused.
                    boolean accept = known == 0 || known == k.d.watchId() || !trustedConnected();
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
                remember((right ? "вправо" : "влево") + ", но музыка не играла");
                answer(k, WristGesture.EV_IGNORED);
                return;
            }
            press(c, right ? KeyEvent.KEYCODE_MEDIA_NEXT : KeyEvent.KEYCODE_MEDIA_PREVIOUS);
            remember((right ? "вправо → следующий трек" : "влево → предыдущий трек") + " (" + Math.round(k.d.lastPeak()) + "° за "
                    + seconds(k.d.lastTook()) + " с" + (k.d.lastByCompass() ? ", по компасу" : "") + ")");
            answer(k, right ? WristGesture.EV_NEXT : WristGesture.EV_PREVIOUS);
        }

        /** A voice note's packet: a gap asked for again (at most once a second), the whole note made a note. */
        private void voice(final Conn k, byte[] p) {
            int r = k.voice.packet(p);
            if (r == VoiceNote.STARTED) {
                k.askedFrom = -1;
                setStatus(name(k) + " · заметка голосом…");
                return;
            }
            if (r == VoiceNote.MISSING) {
                long now = SystemClock.elapsedRealtime(), from = k.voice.missingFrom();
                if (from != k.askedFrom || now - k.askedAt > 1000) {
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

        /** A longer message to the watch (settings, voice notes), if it listens. */
        private void message(Conn k, byte[] v) {
            lastMessage = v;
            notifyWatch(k, v);
        }

        private void remember(String what) {
            prefs(c).edit().putString(PREF_LAST, what).putLong(PREF_LAST_AT, System.currentTimeMillis()).apply();
            setStatus(status);
        }

        /** One byte to the watch, if it listens: it vibrates. */
        private void answer(Conn k, int ev) {
            lastEvent = ev;
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
