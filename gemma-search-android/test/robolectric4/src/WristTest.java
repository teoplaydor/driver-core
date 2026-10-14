import static org.junit.Assert.*;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.bluetooth.BluetoothGattServerCallback;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.media.AudioManager;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBluetoothGattServer;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowSpeechRecognizer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;

import io.github.teoplaydor.semsearch.app.Engine;
import io.github.teoplaydor.semsearch.app.IndexStore;
import io.github.teoplaydor.semsearch.app.WristLink;
import io.github.teoplaydor.semsearch.app.WristService;
import io.github.teoplaydor.semsearch.core.Pcm;
import io.github.teoplaydor.semsearch.core.WristGesture;

/**
 * Tracks by a turn of the wrist, the phone's side, through Robolectric's Bluetooth: the service opens the GATT server
 * (one service: samples written in, answers notified out) and advertises it; a watch connects, says HELLO and streams
 * its accelerometer; a turn right while music plays is «next», left is «previous», a turn with no music does nothing;
 * samples before a HELLO and another watch are turned away; the direction is taught by one turn.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class WristTest {
    static final double G = 9.80665;

    /** The accelerometer of a left wrist, still / turned around the forearm by deg and back in s seconds, in milli-g. */
    static short[] stream(int rate, double... segs) {
        java.util.List<Short> out = new java.util.ArrayList<Short>();
        java.util.Random rnd = new java.util.Random(3);
        for (int i = 0; i < segs.length; i += 2) {
            double deg = segs[i], s = segs[i + 1];
            int n = (int) Math.round(s * rate);
            for (int k = 0; k < n; k++) {
                double t = k / (double) rate, x = Math.sin(Math.PI * t / s);
                double th = Math.toRadians(deg) * x * x;
                // gravity seen by a watch turned by th around its x axis (face up at rest)
                double[] w = {0, Math.sin(th), Math.cos(th)};
                for (int j = 0; j < 3; j++) out.add((short) Math.round((w[j] + 0.01 * rnd.nextGaussian()) * 1000));
            }
        }
        short[] a = new short[out.size()];
        for (int i = 0; i < a.length; i++) a[i] = out.get(i);
        return a;
    }

    static int seq;

    /** The watch writes its samples, 3 a packet (20 bytes). */
    static void send(ShadowBluetoothGattServer sg, BluetoothDevice dev, BluetoothGattCharacteristic ch, short[] xyz) {
        for (int i = 0; i + 3 <= xyz.length / 3; i += 3) {
            sg.notifyOnCharacteristicWriteRequest(dev, 100 + seq, ch, false, false, 0, WristGesture.accel(seq++ & 0xff, xyz, i, 3));
        }
    }

    static Bundle said(String s) {
        Bundle b = new Bundle();
        b.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, new ArrayList<String>(Collections.singletonList(s)));
        return b;
    }

    /** A note's packets as the watch writes them: long writes (prepared, then executed) of `chunk` bytes; `lose`: one lost. */
    static void sendNote(ShadowBluetoothGattServer sg, BluetoothDevice dev, BluetoothGattCharacteristic ch, int id, int codec, byte[] b,
                         int chunk, int lose, boolean end) {
        BluetoothGattServerCallback cb = sg.getGattServerCallback();
        cb.onCharacteristicWriteRequest(dev, 300, ch, false, true, 0, WristGesture.voiceStart(id, codec, 16000, 0));
        int k = 0;
        for (int o = 0; o < b.length; o += chunk, k++) {
            if (k == lose) continue;
            byte[] p = WristGesture.voiceData(id, o, b, o, Math.min(chunk, b.length - o));
            // in parts of 200 (the link's MTU), put together on «execute»
            for (int q = 0; q < p.length; q += 200) {
                cb.onCharacteristicWriteRequest(dev, 301, ch, true, true, q, Arrays.copyOfRange(p, q, Math.min(p.length, q + 200)));
            }
            cb.onExecuteWrite(dev, 302, true);
        }
        if (end) cb.onCharacteristicWriteRequest(dev, 303, ch, false, true, 0, WristGesture.voiceEnd(id, b.length, b.length / 32));
    }

    /**
     * Voice notes from the watch: the settings told (the left hand, voice notes, the MTU once the watch asked for more);
     * a recording in long writes with one lost — asked for again from there; whole — recognised from the sound handed to
     * the recognizer (no microphone) and made a note with its reminder and the recording, the watch told «saved» with the
     * line; the same note again (a new link) answered as before, not saved twice; silence — nothing.
     */
    static void voiceNotes(Application app, WristLink.Server server, ShadowBluetoothGattServer sg, BluetoothDevice watch,
                           BluetoothGattCharacteristic samples, BluetoothGattDescriptor cccd) throws Exception {
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true);
        final Engine e = Engine.get(app);
        Robo.waitFor("store", () -> e.store() != null);
        sg.getGattServerCallback().onDescriptorWriteRequest(watch, 7, cccd, false, true, 0, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        assertArrayEquals("settings told: left hand, voice notes, MTU 23",
                WristGesture.config(WristGesture.CFG_LEFT_HAND | WristGesture.CFG_VOICE, 23), server.lastMessage);
        sg.getGattServerCallback().onMtuChanged(watch, 247);
        assertArrayEquals("…and again with the bigger MTU", WristGesture.config(3, 247), server.lastMessage);
        WristLink.setVoiceNotes(app, false);
        assertArrayEquals("voice notes off: told", WristGesture.config(WristGesture.CFG_LEFT_HAND, 247), server.lastMessage);
        WristLink.setVoiceNotes(app, true);

        // 1.5 s of a voice-like sound (WAV, as a watch that records WAV sends it)
        float[] x = new float[24000];
        for (int i = 0; i < x.length; i++) x[i] = (float) (0.3 * Math.sin(2 * Math.PI * 180 * i / 16000.0) * Math.sin(Math.PI * i / x.length));
        byte[] wav = Pcm.wav16(x, 16000);
        sendNote(sg, watch, samples, 5, WristGesture.CODEC_WAV, wav, 400, 3, true);
        assertArrayEquals("a write lost: «again from 1200»", WristGesture.voiceFrom(5, 1200), server.lastMessage);
        assertTrue(WristLink.status(), WristLink.status().contains("заметка голосом"));
        // sent again from there
        BluetoothGattServerCallback cb = sg.getGattServerCallback();
        for (int o = 1200; o < wav.length; o += 400) {
            cb.onCharacteristicWriteRequest(watch, 310, samples, false, true, 0, WristGesture.voiceData(5, o, wav, o, Math.min(400, wav.length - o)));
        }
        Robo.waitFor("the recognizer", () -> ShadowSpeechRecognizer.getLatestSpeechRecognizer() != null);
        SpeechRecognizer sr = ShadowSpeechRecognizer.getLatestSpeechRecognizer();
        Intent asked = Shadows.shadowOf(sr).getLastRecognizerIntent();
        assertNotNull("the sound handed to the recognizer, no microphone", asked.getParcelableExtra("android.speech.extra.AUDIO_SOURCE"));
        assertEquals(16000, asked.getIntExtra("android.speech.extra.AUDIO_SOURCE_SAMPLING_RATE", 0));
        Shadows.shadowOf(sr).triggerOnResults(said("Напомни завтра в 9 купить хлеб"));
        Robo.waitFor("the watch told", () -> server.lastMessage != null && server.lastMessage[0] == WristGesture.EV_VOICE_DONE);
        byte[] done = server.lastMessage;
        String line = new String(done, 3, done.length - 3, StandardCharsets.UTF_8);
        assertEquals("note 5", 5, done[1]);
        assertEquals("saved", WristGesture.VOICE_SAVED, done[2]);
        assertTrue(line, line.toLowerCase().startsWith("сохранено: купить хлеб") && line.contains("напомнит"));
        IndexStore.Item note = null;
        for (IndexStore.Item n : e.store().notes()) if (n.body.toLowerCase().contains("купить хлеб")) note = n;
        assertNotNull("the note", note);
        assertTrue("with its reminder", note.remind > System.currentTimeMillis());
        final long id = note.id;
        Robo.waitFor("the recording kept", () -> {
            for (IndexStore.Item n : e.store().notes()) if (n.id == id && n.audio != null) return true;
            return false;
        });
        assertTrue(WristLink.last(app), WristLink.last(app).contains("заметка с часов: Сохранено"));
        NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        boolean shown = false;
        for (android.service.notification.StatusBarNotification n : Shadows.shadowOf(nm).getActiveNotifications()) {
            if ("Заметка с часов".equals(n.getNotification().extras.getString(Notification.EXTRA_TITLE))) shown = true;
        }
        assertTrue("the phone shows it too", shown);

        // the same note again (the watch had no answer, a new link): answered as before, not a second note
        server.lastMessage = null;
        sendNote(sg, watch, samples, 5, WristGesture.CODEC_WAV, wav, 400, -1, true);
        assertArrayEquals("the same answer", done, server.lastMessage);
        int notes = 0;
        for (IndexStore.Item n : e.store().notes()) if (n.body.toLowerCase().contains("купить хлеб")) notes++;
        assertEquals("one note", 1, notes);

        // silence: nothing kept, the watch told
        sendNote(sg, watch, samples, 6, WristGesture.CODEC_WAV, Pcm.wav16(new float[16000], 16000), 400, -1, true);
        Robo.waitFor("silence told", () -> server.lastMessage != null && server.lastMessage[0] == WristGesture.EV_VOICE_DONE
                && server.lastMessage[1] == 6);
        assertEquals("silent", WristGesture.VOICE_SILENT, server.lastMessage[2]);

        // two phrases with a pause between: each heard alone (a recognizer stops at a pause), the words put together;
        // the recognizer not to end a phrase at a breath, in Russian
        float[] two = new float[16000 * 4];
        for (int i = 0; i < two.length; i++) {
            double t = i / 16000.0;
            boolean on = (t > 0.3 && t < 1.3) || (t > 2.5 && t < 3.5);
            two[i] = on ? (float) (0.3 * Math.sin(2 * Math.PI * 200 * t)) : 0f;
        }
        final SpeechRecognizer before = ShadowSpeechRecognizer.getLatestSpeechRecognizer();
        sendNote(sg, watch, samples, 7, WristGesture.CODEC_WAV, Pcm.wav16(two, 16000), 400, -1, true);
        Robo.waitFor("the first phrase", () -> ShadowSpeechRecognizer.getLatestSpeechRecognizer() != before);
        final SpeechRecognizer first = ShadowSpeechRecognizer.getLatestSpeechRecognizer();
        Intent i1 = Shadows.shadowOf(first).getLastRecognizerIntent();
        assertEquals(5000L, i1.getLongExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 0));
        assertTrue(i1.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE), i1.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE).startsWith("ru"));
        Shadows.shadowOf(first).triggerOnResults(said("Купить молоко"));
        Robo.waitFor("the second phrase", () -> ShadowSpeechRecognizer.getLatestSpeechRecognizer() != first);
        Shadows.shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer()).triggerOnResults(said("и хлеб"));
        Robo.waitFor("both told", () -> server.lastMessage != null && server.lastMessage[0] == WristGesture.EV_VOICE_DONE
                && server.lastMessage[1] == 7);
        String both = new String(server.lastMessage, 3, server.lastMessage.length - 3, StandardCharsets.UTF_8);
        assertTrue(both, both.toLowerCase().contains("купить молоко и хлеб"));
    }

    @Test
    public void wrist() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(app).grantPermissions("android.permission.BLUETOOTH_ADVERTISE", "android.permission.BLUETOOTH_CONNECT",
                "android.permission.POST_NOTIFICATIONS");
        BluetoothManager bm = (BluetoothManager) app.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm.getAdapter();
        Shadows.shadowOf(ad).setEnabled(true);
        Shadows.shadowOf(ad).setIsMultipleAdvertisementSupported(true);
        assertFalse("no Bluetooth LE: not offered", WristLink.supported(app));
        Shadows.shadowOf(app.getPackageManager()).setSystemFeature(android.content.pm.PackageManager.FEATURE_BLUETOOTH_LE, true);
        assertTrue(WristLink.supported(app));
        assertTrue(WristLink.hasPermissions(app));

        // on: the service holds the link
        WristLink.setOn(app, true);
        ServiceController<WristService> sc = Robolectric.buildService(WristService.class).create();
        sc.startCommand(0, 1);
        WristLink.Server server = WristLink.current();
        assertNotNull("the link is up", server);
        assertNotNull("GATT server opened", server.gatt);
        BluetoothGattService s = server.gatt.getService(UUID.fromString(WristGesture.SERVICE));
        assertNotNull("our service", s);
        BluetoothGattCharacteristic samples = s.getCharacteristic(UUID.fromString(WristGesture.SAMPLES));
        BluetoothGattCharacteristic events = s.getCharacteristic(UUID.fromString(WristGesture.EVENTS));
        assertNotNull(samples);
        assertNotNull(events);
        assertTrue("samples written without an answer", (samples.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0);
        assertTrue("answers notified", (events.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0);
        assertNotNull("notifications switch", events.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")));
        assertEquals("advertising", 1, Shadows.shadowOf(ad.getBluetoothLeAdvertiser()).getAdvertisementRequestCount());
        assertTrue(WristLink.status(), WristLink.status().startsWith("ждёт часы"));

        ShadowBluetoothGattServer sg = Shadows.shadowOf(server.gatt);
        BluetoothDevice watch = ad.getRemoteDevice("C0:FF:EE:00:00:01");
        sg.notifyConnection(watch);
        assertEquals("the link reached the phone", "часы подключаются…", WristLink.status());
        // a watch connected: advertising stops at once, so the radio is freer for its service discovery
        assertEquals("advertising stops on connect", 0, Shadows.shadowOf(ad.getBluetoothLeAdvertiser()).getAdvertisementRequestCount());
        // samples before a HELLO: not listened to
        AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        Shadows.shadowOf(am).setIsMusicActive(true);
        send(sg, watch, samples, stream(25, 0, 1, 70, 0.6, 0, 1));
        assertEquals("nothing before HELLO", -1, server.lastEvent);
        assertTrue("counted on the screen: " + WristLink.status(), WristLink.status().contains("без приветствия"));

        // HELLO: this watch is remembered; it subscribes to the answers
        sg.notifyOnCharacteristicWriteRequest(watch, 1, samples, false, true, 0, WristGesture.hello(0x0badcafe, 25, 1000, "vela"));
        assertTrue(WristLink.status(), WristLink.status().contains("vela") && WristLink.status().contains("25 Гц")
                && WristLink.status().contains("жду отсчёты"));
        assertEquals("still not advertising", 0, Shadows.shadowOf(ad.getBluetoothLeAdvertiser()).getAdvertisementRequestCount());
        Context c = app;
        BluetoothGattDescriptor cccd = events.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
        // (Robolectric has no descriptor-write helper: the callback is called as the stack would)
        sg.getGattServerCallback().onDescriptorWriteRequest(watch, 2, cccd, false, true, 0, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        assertEquals("music plays: stream", WristGesture.EV_STREAM, server.lastEvent);
        assertTrue(WristLink.status(), WristLink.status().contains("музыка играет"));

        // a turn right while music plays: «next»
        send(sg, watch, samples, stream(25, 0, 1, 70, 0.6, 0, 1));
        assertEquals("next", WristGesture.EV_NEXT, server.lastEvent);
        assertTrue(WristLink.status(), WristLink.last(app).contains("следующий"));
        // left: «previous»
        send(sg, watch, samples, stream(25, -70, 0.6, 0, 1));
        assertEquals("previous", WristGesture.EV_PREVIOUS, server.lastEvent);
        assertTrue(WristLink.last(app).contains("предыдущий"));
        // no music: the turn is seen, nothing pressed, the watch told
        Shadows.shadowOf(am).setIsMusicActive(false);
        send(sg, watch, samples, stream(25, 70, 0.6, 0, 1));
        assertEquals("ignored", WristGesture.EV_IGNORED, server.lastEvent);
        assertTrue(WristLink.last(app).contains("музыка не играла"));
        // the music stopped: the watch is told to pause (and to stream again when it plays)
        ShadowLooper.idleMainLooper(2100, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertEquals("pause", WristGesture.EV_PAUSE, server.lastEvent);
        assertTrue("the screen says why the watch went quiet: " + WristLink.status(), WristLink.status().contains("пауза"));
        ShadowLooper.idleMainLooper(2100, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertEquals("told once", WristGesture.EV_PAUSE, server.lastEvent);
        Shadows.shadowOf(am).setIsMusicActive(true);
        ShadowLooper.idleMainLooper(2100, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertEquals("stream again", WristGesture.EV_STREAM, server.lastEvent);
        // after the pause the hand has to be still first: a turn at once does not count
        send(sg, watch, samples, stream(25, 70, 0.6, 0, 1));
        assertEquals("nothing from a stale position", WristGesture.EV_STREAM, server.lastEvent);

        // another watch: turned away
        BluetoothDevice other = ad.getRemoteDevice("C0:FF:EE:00:00:02");
        sg.notifyConnection(other);
        sg.notifyOnCharacteristicWriteRequest(other, 3, samples, false, false, 0, WristGesture.hello(0x12345678, 25, 1000, "zepp"));
        assertTrue(WristLink.status(), WristLink.status().contains("уже подключены"));
        assertTrue("its connection cancelled", sg.isConnectionCancelled(other));
        server.lastEvent = -1;
        send(sg, other, samples, stream(25, 0, 1, 70, 0.6, 0, 1));
        assertEquals("the other watch's turns do nothing", -1, server.lastEvent);

        // left/right mixed up on this watch: taught by one turn «to the right» (here: the other way round)
        assertTrue(WristLink.learn());
        send(sg, watch, samples, stream(25, 0, 1, -70, 0.6, 0, 1));
        assertEquals("learnt", WristGesture.EV_LEARNED, server.lastEvent);
        assertTrue(WristLink.learnt(c));
        send(sg, watch, samples, stream(25, -70, 0.6, 0, 1));
        assertEquals("now this way is next", WristGesture.EV_NEXT, server.lastEvent);

        // the watch goes away: advertising again
        sg.notifyDisconnection(watch);
        ShadowLooper.idleMainLooper();
        assertTrue(WristLink.status(), WristLink.status().contains("отключились"));
        assertEquals("advertising again", 1, Shadows.shadowOf(ad.getBluetoothLeAdvertiser()).getAdvertisementRequestCount());

        // forgotten: the next watch is welcome
        WristLink.forgetWatch(c);
        assertFalse(WristLink.learnt(c));
        sg.notifyConnection(other);
        sg.notifyOnCharacteristicWriteRequest(other, 4, samples, false, false, 0, WristGesture.hello(0x12345678, 50, 0, "zepp"));
        assertTrue(WristLink.status(), WristLink.status().contains("zepp") && WristLink.status().contains("50 Гц"));

        // a watch that finds the turns itself: one packet per turn; a small one is shown with the reason, a real one presses
        sg.notifyOnCharacteristicWriteRequest(other, 5, samples, false, true, 0,
                WristGesture.hello(WristGesture.VERSION_TURNS, 0x12345678, 16, 981, "zepp"));
        assertTrue(WristLink.status(), WristLink.status().contains("распознаёт повороты сам"));
        sg.notifyOnCharacteristicWriteRequest(other, 6, samples, false, true, 0, WristGesture.rollPacket(0, 26, 300, WristGesture.SOURCE_GRAVITY, 0.2f, 4));
        assertTrue(WristLink.status(), WristLink.status().contains("поворот 26° за 0,3 с") && WristLink.status().contains("мало"));
        server.lastEvent = -1;
        sg.notifyOnCharacteristicWriteRequest(other, 7, samples, false, true, 0, WristGesture.rollPacket(1, 64, 350, WristGesture.SOURCE_GRAVITY, 0.2f, 6));
        assertEquals("a turn found on the watch: next", WristGesture.EV_NEXT, server.lastEvent);
        assertTrue(WristLink.last(app), WristLink.last(app).contains("следующий") && WristLink.last(app).contains("64°"));

        voiceNotes(app, server, sg, other, samples, cccd);

        // off: the service and the link are gone
        WristLink.setOn(c, false);
        sc.destroy();
        assertNull(WristLink.current());
        assertTrue("GATT server closed", sg.isClosed());
    }

}
