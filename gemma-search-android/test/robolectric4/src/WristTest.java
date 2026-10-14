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

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;

import io.github.teoplaydor.semsearch.app.Engine;
import io.github.teoplaydor.semsearch.app.IndexStore;
import io.github.teoplaydor.semsearch.app.WristLink;
import io.github.teoplaydor.semsearch.app.WristLog;
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

        // what the watch now sends: its recorder's PCM as IMA ADPCM — decoded, heard
        final SpeechRecognizer prev = ShadowSpeechRecognizer.getLatestSpeechRecognizer();
        sendNote(sg, watch, samples, 8, WristGesture.CODEC_ADPCM, adpcm(x), 238, -1, true);
        Robo.waitFor("ADPCM heard", () -> ShadowSpeechRecognizer.getLatestSpeechRecognizer() != prev);
        Shadows.shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer()).triggerOnResults(said("Позвонить маме"));
        Robo.waitFor("ADPCM told", () -> server.lastMessage != null && server.lastMessage[0] == WristGesture.EV_VOICE_DONE
                && server.lastMessage[1] == 8);
        assertEquals("ADPCM: saved", WristGesture.VOICE_SAVED, server.lastMessage[2]);

        // bytes that are no sound the phone knows: «not readable», and the recording itself in the log to look at
        byte[] junk = new byte[3000];
        new java.util.Random(4).nextBytes(junk);
        sendNote(sg, watch, samples, 9, WristGesture.CODEC_RECORDED, junk, 238, -1, true);
        Robo.waitFor("junk told", () -> server.lastMessage != null && server.lastMessage[0] == WristGesture.EV_VOICE_DONE
                && server.lastMessage[1] == 9);
        assertEquals("not readable", WristGesture.VOICE_FAILED, server.lastMessage[2]);
        WristLog.flush();
        String log = new String(java.nio.file.Files.readAllBytes(WristLog.file(app).toPath()), StandardCharsets.UTF_8);
        assertTrue("the recording in the log", log.contains(" VOICE-RAW " + android.util.Base64.encodeToString(junk, android.util.Base64.NO_WRAP)));
    }

    /**
     * GigaAM on the phone (the stand-in from test/gigaam with the real export's files, inputs and outputs, on the desktop
     * ONNX Runtime): a note from the watch recognized by it, the phone's recognizer not asked; its pieces and what it
     * heard in the log; the note saved with those words. Off again: the phone's recognizer.
     */
    static void gigaam(Application app, WristLink.Server server, ShadowBluetoothGattServer sg, BluetoothDevice watch,
                       BluetoothGattCharacteristic samples) throws Exception {
        File fx = new File(System.getProperty("npu.fixture"));
        System.setProperty("onnxruntime.native.path", fx.getAbsolutePath());
        File giga = new File("build/test/gigaam/int8");
        assertTrue("the stand-in GigaAM (test/gigaam/make_gigaam_dummy.py) in " + giga, new File(giga, "v3_e2e_rnnt_encoder.int8.onnx").exists());
        java.lang.reflect.Field f = Engine.class.getDeclaredField("speechForTest");
        f.setAccessible(true);
        final Engine e = Engine.get(app);
        assertFalse(e.speechInstalled());

        // downloaded from the Hub (a stand-in Hub with the repo's fp32 and int8 graphs): the int8 ones taken, nothing else
        final java.util.Map<String, File> repo = new java.util.concurrent.ConcurrentSkipListMap<String, File>();
        for (File x : new File("build/test/gigaam/fp32").listFiles()) repo.put(x.getName(), x);
        repo.put("README.md", new File(giga, "config.json"));
        final java.util.List<String> fetched = Collections.synchronizedList(new ArrayList<String>());
        com.sun.net.httpserver.HttpServer hub = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        hub.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            byte[] body;
            if (path.equals("/api/models/istupakov/gigaam-v3-onnx")) {
                StringBuilder j = new StringBuilder("{\"id\":\"istupakov/gigaam-v3-onnx\",\"siblings\":[");
                for (java.util.Map.Entry<String, File> en : repo.entrySet()) {
                    j.append(j.charAt(j.length() - 1) == '[' ? "" : ",").append("{\"rfilename\":\"").append(en.getKey())
                            .append("\",\"size\":").append(en.getValue().length()).append("}");
                }
                body = j.append("]}").toString().getBytes(StandardCharsets.UTF_8);
            } else if (path.startsWith("/istupakov/gigaam-v3-onnx/resolve/main/") && repo.containsKey(path.substring(39))) {
                fetched.add(path.substring(39));
                body = java.nio.file.Files.readAllBytes(repo.get(path.substring(39)).toPath());
            } else {
                ex.sendResponseHeaders(404, -1);
                ex.close();
                return;
            }
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        hub.start();
        java.lang.reflect.Field hf = Engine.class.getDeclaredField("hubForTest");
        hf.setAccessible(true);
        hf.set(null, "http://127.0.0.1:" + hub.getAddress().getPort());
        try {
            // only the full graphs there: offered with their size, nothing downloaded yet
            e.downloadSpeech(false);
            Robo.waitFor("offered", () -> !e.speechDownloading && e.speechDlError != null);
            assertTrue(e.speechDlError, e.speechFullBytes > 0 && e.speechDlError.contains("нет версии int8") && fetched.isEmpty() && !e.speechInstalled());
            // the int8 graphs there too: those taken
            for (File x : giga.listFiles()) repo.put(x.getName(), x);
            e.downloadSpeech(false);
            Robo.waitFor("GigaAM downloaded", () -> !e.speechDownloading && (e.speechInstalled() || e.speechDlError != null));
            assertNull(e.speechDlError, e.speechDlError);
            assertTrue("int8, ready", e.speechInstalled() && e.speechInt8() && e.speechReady());
            Collections.sort(fetched);
            assertEquals("only the int8 graphs, the vocabulary and the settings", Arrays.asList("config.json", "v3_e2e_rnnt_decoder.int8.onnx",
                    "v3_e2e_rnnt_encoder.int8.onnx", "v3_e2e_rnnt_joint.int8.onnx", "v3_e2e_rnnt_vocab.txt"), fetched);
        } finally {
            hf.set(null, null);
            hub.stop(0);
        }
        try {
            // a few «syllables»: a gliding pitch with its harmonics, pauses between
            float[] x = new float[16000 * 3];
            java.util.Random r = new java.util.Random(11);
            for (int at = 3200; at + 6000 < x.length; at += 6000 + r.nextInt(3000)) {
                int len = 3000 + r.nextInt(3000);
                double f0 = 110 + r.nextInt(100), ph = 0;
                for (int i = 0; i < len; i++) {
                    ph += 2 * Math.PI * f0 * (1 + 0.2 * Math.sin(2 * Math.PI * 3 * i / 16000.0)) / 16000;
                    double v = 0;
                    for (int k = 1; k <= 12; k++) v += Math.sin(k * ph) / k;
                    x[at + i] = (float) (0.25 * v * Math.sin(Math.PI * i / len));
                }
            }
            final SpeechRecognizer last = ShadowSpeechRecognizer.getLatestSpeechRecognizer();
            WristLog.flush();
            final long logFrom = WristLog.file(app).length();
            sendNote(sg, watch, samples, 10, WristGesture.CODEC_WAV, Pcm.wav16(x, 16000), 400, -1, true);
            Robo.waitFor("GigaAM told", () -> server.lastMessage != null && server.lastMessage[0] == WristGesture.EV_VOICE_DONE
                    && server.lastMessage[1] == 10);
            assertSame("the phone's recognizer not asked", last, ShadowSpeechRecognizer.getLatestSpeechRecognizer());
            WristLog.flush();
            byte[] all = java.nio.file.Files.readAllBytes(WristLog.file(app).toPath());
            String log = new String(all, (int) logFrom, all.length - (int) logFrom, StandardCharsets.UTF_8);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("VOICE gigaam 1/1 [\\d.]+-[\\d.]+ s: «([^»]*)» in \\d+ ms").matcher(log);
            assertTrue(log, m.find());
            String words = m.group(1);
            System.out.println("GigaAM (stand-in) heard: «" + words + "»");
            assertTrue(log, log.contains("VOICE heard «" + words + "» error=0"));
            int result = server.lastMessage[2];
            assertTrue("saved or answered: " + result, !words.isEmpty() && (result == WristGesture.VOICE_SAVED || result == WristGesture.VOICE_ANSWER));
            // a broken model (half a file): the phone's recognizer instead, the note not lost
            File broken = java.nio.file.Files.createTempDirectory("gigaam-broken").toFile();
            for (String n : new String[]{"v3_e2e_rnnt_vocab.txt", "config.json"}) {
                java.nio.file.Files.copy(new File(giga, n).toPath(), new File(broken, n).toPath());
            }
            for (String n : new String[]{"encoder", "decoder", "joint"}) {
                java.nio.file.Files.write(new File(broken, "v3_e2e_rnnt_" + n + ".int8.onnx").toPath(), new byte[]{8, 7, 1, 2, 3});
            }
            f.set(null, broken);
            e.deleteSpeech(); // the loaded one let go (the folder of a test model is left alone)
            final SpeechRecognizer before = ShadowSpeechRecognizer.getLatestSpeechRecognizer();
            sendNote(sg, watch, samples, 11, WristGesture.CODEC_WAV, Pcm.wav16(x, 16000), 400, -1, true);
            Robo.waitFor("the phone's recognizer instead", () -> ShadowSpeechRecognizer.getLatestSpeechRecognizer() != before);
            Shadows.shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer()).triggerOnResults(said("Купить батарейки"));
            Robo.waitFor("told", () -> server.lastMessage != null && server.lastMessage[0] == WristGesture.EV_VOICE_DONE
                    && server.lastMessage[1] == 11);
            assertEquals("saved by the phone's recognizer", WristGesture.VOICE_SAVED, server.lastMessage[2]);
            WristLog.flush();
            String log2 = new String(java.nio.file.Files.readAllBytes(WristLog.file(app).toPath()), StandardCharsets.UTF_8);
            assertTrue("why, in the log", log2.contains("gigaam failed: ") && log2.contains("the phone's recognizer instead"));
        } finally {
            f.set(null, null);
        }
        assertTrue("the downloaded one still there", e.speechReady());
        e.deleteSpeech();
        assertFalse("deleted: the phone's recognizer", e.speechInstalled() || e.speechReady());
    }

    /**
     * A conversation with Gemini through the watch, against a stand-in Gemini Live held to Google's SDK
     * (test/gemini/live_mock.py): the watch told «at the mouth — a conversation, answers on the watch»; its live voice
     * (ADPCM) passed on as it comes; the model found from the list; a note saved by the function Gemini calls; the spoken
     * answer back to the watch in WAV pieces (IMA ADPCM, 8 kHz) and its text; the piece played; the hand down — the
     * conversation kept, then closed («over» to the watch). Then answers on the phone: none to the watch.
     */
    static void talk(Application app, WristLink.Server server, ShadowBluetoothGattServer sg, BluetoothDevice watch,
                     BluetoothGattCharacteristic samples) throws Exception {
        int port = 18800 + new java.util.Random().nextInt(400);
        File log = File.createTempFile("live", ".jsonl");
        Process mock = new ProcessBuilder("python3", "test/gemini/live_mock.py", "serve", String.valueOf(port), log.getPath())
                .redirectErrorStream(true).start();
        java.io.BufferedReader out = new java.io.BufferedReader(new java.io.InputStreamReader(mock.getInputStream(), StandardCharsets.UTF_8));
        assertEquals("the stand-in Gemini started", "ready", out.readLine());
        java.lang.reflect.Field host = Class.forName("io.github.teoplaydor.semsearch.app.Talk").getDeclaredField("hostForTest");
        host.setAccessible(true);
        host.set(null, "ws://127.0.0.1:" + port);
        final Engine e = Engine.get(app);
        try {
            e.prefs().edit().putString("gemini_key", "test-key").putBoolean("wrist_talk", true).putBoolean("wrist_talk_watch", true).apply();
            server.messages = Collections.synchronizedList(new ArrayList<byte[]>());
            WristLink.setVoiceNotes(app, true); // the watch told again
            byte[] cfg = server.lastMessage;
            assertEquals("told: at the mouth — a conversation, answers on the watch (MTU 247)",
                    WristGesture.CFG_TALK | WristGesture.CFG_TALK_WATCH, cfg[1] & (WristGesture.CFG_TALK | WristGesture.CFG_TALK_WATCH));

            // the watch at the mouth: its live voice, ADPCM as the watch makes it, in writes as it is said
            float[] x = new float[16000 * 13 / 10];
            for (int i = 0; i < x.length; i++) x[i] = (float) (0.3 * Math.sin(2 * Math.PI * 200 * i / 16000.0));
            byte[] voice = adpcm(x);
            BluetoothGattServerCallback cb = sg.getGattServerCallback();
            cb.onCharacteristicWriteRequest(watch, 400, samples, false, true, 0,
                    WristGesture.voiceStart(20, WristGesture.CODEC_ADPCM | WristGesture.CODEC_LIVE, 16000, 0));
            for (int o = 0; o < voice.length; o += 238) {
                cb.onCharacteristicWriteRequest(watch, 401, samples, false, true, 0,
                        WristGesture.voiceData(20, o, voice, o, Math.min(238, voice.length - o)));
                Thread.sleep(10);
            }
            // the note Gemini saved by calling save_note
            Robo.waitFor("the note Gemini saved", () -> {
                for (IndexStore.Item n : e.store().notes()) if (n.body.toLowerCase().contains("купить хлеб") && n.remind > System.currentTimeMillis()
                        && n.id > 0 && n.body.length() < 20) return true;
                return e.store().notes().size() > 0 && textTold(server).contains("Готово");
            });
            Robo.waitFor("the answer's text to the watch", () -> textTold(server).contains("Готово, напомню завтра в девять."));
            // the spoken answer: WAV pieces for the watch
            java.util.Map<Integer, java.io.ByteArrayOutputStream> pieces = new java.util.TreeMap<Integer, java.io.ByteArrayOutputStream>();
            java.util.Map<Integer, Long> whole = new java.util.TreeMap<Integer, Long>();
            synchronized (server.messages) {
                for (byte[] m : server.messages) {
                    if (m[0] == WristGesture.EV_TALK_AUDIO) {
                        int sg2 = m[1] & 0xff;
                        long off = (m[2] & 0xffL) | (m[3] & 0xffL) << 8 | (m[4] & 0xffL) << 16 | (m[5] & 0xffL) << 24;
                        java.io.ByteArrayOutputStream b = pieces.computeIfAbsent(sg2, k -> new java.io.ByteArrayOutputStream());
                        assertEquals("in order", b.size(), off);
                        b.write(m, 6, m.length - 6);
                        assertTrue("within the MTU", m.length <= 244);
                    } else if (m[0] == WristGesture.EV_TALK_SEG) {
                        whole.put(m[1] & 0xff, (m[2] & 0xffL) | (m[3] & 0xffL) << 8 | (m[4] & 0xffL) << 16 | (m[5] & 0xffL) << 24);
                    }
                }
            }
            assertFalse("pieces of the answer", pieces.isEmpty());
            int total = 0;
            for (java.util.Map.Entry<Integer, java.io.ByteArrayOutputStream> p : pieces.entrySet()) {
                byte[] wav = p.getValue().toByteArray();
                assertEquals("piece " + p.getKey() + " whole", Long.valueOf(wav.length), whole.get(p.getKey()));
                assertEquals("a WAV file", "RIFF", new String(wav, 0, 4, StandardCharsets.US_ASCII));
                total += io.github.teoplaydor.semsearch.core.Adpcm.fromImaWav(wav).length;
            }
            System.out.println("Gemini's answer to the watch: " + pieces.size() + " piece(s), " + total + " samples at 8 kHz");
            assertTrue("0.9 s of answer at 8 kHz: " + total, Math.abs(total - 7200) < 200);
            WristLog.flush();
            String wlog = new String(java.nio.file.Files.readAllBytes(WristLog.file(app).toPath()), StandardCharsets.UTF_8);
            assertTrue(wlog, wlog.contains("TALK connecting models/gemini-3-flash-native-audio-preview-2026-03-02"));
            assertTrue(wlog, wlog.contains("TALK call save_note") && wlog.contains("TALK turn: you «Напомни завтра в 9 купить хлеб» — Gemini «Готово, напомню завтра в девять.»"));
            // played on the watch; the hand down: kept a while, then closed and the watch told
            for (int sgn : pieces.keySet()) cb.onCharacteristicWriteRequest(watch, 402, samples, false, true, 0, WristGesture.voicePlayed(20, sgn, true));
            cb.onCharacteristicWriteRequest(watch, 403, samples, false, true, 0, WristGesture.voiceEnd(20, voice.length, 1300));
            Robo.settle(300);
            assertFalse("kept for a while", told(server, WristGesture.EV_TALK_END));
            // (what was said after the first answer is answered too; then the quiet, then «over»)
            long until = System.currentTimeMillis() + 15000;
            while (!told(server, WristGesture.EV_TALK_END) && System.currentTimeMillis() < until) {
                ShadowLooper.idleMainLooper(10, java.util.concurrent.TimeUnit.SECONDS);
                Thread.sleep(100);
            }
            assertTrue("closed, the watch told", told(server, WristGesture.EV_TALK_END));
            String sent = new String(java.nio.file.Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8);
            assertTrue("every message the SDK's; the voice went up", sent.contains("\"realtimeInput\"") && sent.contains("toolResponse")
                    && sent.contains("audioStreamEnd") && !sent.contains("test-key"));

            // answers on the phone now: nothing to the watch but the text
            e.prefs().edit().putBoolean("wrist_talk_watch", false).apply();
            WristLink.setVoiceNotes(app, true);
            server.messages.clear();
            cb.onCharacteristicWriteRequest(watch, 404, samples, false, true, 0,
                    WristGesture.voiceStart(21, WristGesture.CODEC_ADPCM | WristGesture.CODEC_LIVE, 16000, 0));
            for (int o = 0; o < voice.length; o += 238) {
                cb.onCharacteristicWriteRequest(watch, 405, samples, false, true, 0,
                        WristGesture.voiceData(21, o, voice, o, Math.min(238, voice.length - o)));
                Thread.sleep(10);
            }
            cb.onCharacteristicWriteRequest(watch, 406, samples, false, true, 0, WristGesture.voiceEnd(21, voice.length, 1300));
            Robo.waitFor("answered on the phone", () -> textTold(server).length() > 0);
            assertFalse("no voice to the watch", told(server, WristGesture.EV_TALK_AUDIO));
            System.out.println("on the phone: «" + textTold(server) + "»");

            // headphones on the phone: the answer plays there (not on the watch), the voice goes on all the time, and
            // Gemini spoken over stops at once (a conversation of its own: the one before closed first)
            java.lang.reflect.Method stopTalk = Class.forName("io.github.teoplaydor.semsearch.app.Talk").getDeclaredMethod("stop");
            stopTalk.setAccessible(true);
            stopTalk.invoke(null);
            android.media.AudioManager am = (android.media.AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            Shadows.shadowOf(am).setOutputDevices(Arrays.asList(org.robolectric.shadows.AudioDeviceInfoBuilder.newBuilder()
                    .setType(android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP).build()));
            e.prefs().edit().putBoolean("wrist_talk_watch", true).apply();
            WristLink.setVoiceNotes(app, true);
            server.messages.clear();
            WristLog.flush();
            final long logFrom = WristLog.file(app).length();
            cb.onCharacteristicWriteRequest(watch, 407, samples, false, true, 0,
                    WristGesture.voiceStart(22, WristGesture.CODEC_ADPCM | WristGesture.CODEC_LIVE, 16000, 0));
            for (int o = 0; o < voice.length; o += 238) {
                cb.onCharacteristicWriteRequest(watch, 408, samples, false, true, 0,
                        WristGesture.voiceData(22, o, voice, o, Math.min(238, voice.length - o)));
                Thread.sleep(10);
            }
            try {
                Robo.waitFor("the answer begun", () -> logSince(app, logFrom).contains("TALK answer in "));
            } catch (AssertionError err) {
                throw new AssertionError(err.getMessage() + "\n" + logSince(app, logFrom));
            }
            // spoken over: a second more of voice while it plays
            byte[] more = adpcm(java.util.Arrays.copyOf(x, 16000));
            for (int o = 0; o < more.length; o += 238) {
                cb.onCharacteristicWriteRequest(watch, 409, samples, false, true, 0,
                        WristGesture.voiceData(22, voice.length + o, more, o, Math.min(238, more.length - o)));
            }
            Robo.waitFor("stopped when spoken over", () -> logSince(app, logFrom).contains("TALK interrupted: the answer stopped"));
            String dl = logSince(app, logFrom);
            assertTrue(dl, dl.contains("answers in the headphones (may be spoken over)"));
            assertFalse("not to the watch", told(server, WristGesture.EV_TALK_AUDIO));
            java.util.regex.Matcher lm = java.util.regex.Pattern.compile("TALK answer in (\\d+) ms after the voice").matcher(dl);
            assertTrue(dl, lm.find());
            System.out.println("in the headphones: the answer began " + lm.group(1) + " ms after the voice, spoken over — stopped");
            cb.onCharacteristicWriteRequest(watch, 410, samples, false, true, 0, WristGesture.voiceEnd(22, voice.length + more.length, 2300));
        } finally {
            java.lang.reflect.Method stop = Class.forName("io.github.teoplaydor.semsearch.app.Talk").getDeclaredMethod("stop");
            stop.setAccessible(true);
            stop.invoke(null);
            host.set(null, null);
            server.messages = null;
            e.prefs().edit().putBoolean("wrist_talk", false).apply();
            mock.destroy();
        }
    }

    static String logSince(Application app, long from) throws Exception {
        WristLog.flush();
        byte[] all = java.nio.file.Files.readAllBytes(WristLog.file(app).toPath());
        return from >= all.length ? "" : new String(all, (int) from, all.length - (int) from, StandardCharsets.UTF_8);
    }

    static boolean told(WristLink.Server server, int ev) {
        synchronized (server.messages) {
            for (byte[] m : server.messages) if ((m[0] & 0xff) == ev) return true;
        }
        return false;
    }

    static String textTold(WristLink.Server server) {
        StringBuilder b = new StringBuilder();
        synchronized (server.messages) {
            for (byte[] m : server.messages) if (m[0] == WristGesture.EV_TALK_TEXT) b.append(new String(m, 1, m.length - 1, StandardCharsets.UTF_8)).append('|');
        }
        return b.toString();
    }

    /** The watch's IMA ADPCM (createAdpcm in proto.js) of samples −1…1, for the tests. */
    static byte[] adpcm(float[] x) {
        int[] step = {7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130,
                143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658, 724, 796, 876, 963, 1060, 1166, 1282, 1411,
                1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442,
                11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767};
        int[] idx = {-1, -1, -1, -1, 2, 4, 6, 8};
        byte[] out = new byte[(x.length + 1) / 2];
        int pred = 0, index = 0;
        for (int i = 0; i < x.length; i++) {
            int sample = Math.round(x[i] * 32767), diff = sample - pred, st = step[index], c = 0;
            if (diff < 0) {
                c = 8;
                diff = -diff;
            }
            int vp = st >> 3;
            if (diff >= st) {
                c |= 4;
                diff -= st;
                vp += st;
            }
            st >>= 1;
            if (diff >= st) {
                c |= 2;
                diff -= st;
                vp += st;
            }
            st >>= 1;
            if (diff >= st) {
                c |= 1;
                vp += st;
            }
            pred = Math.max(-32768, Math.min(32767, (c & 8) != 0 ? pred - vp : pred + vp));
            index = Math.max(0, Math.min(88, index + idx[c & 7]));
            out[i / 2] |= (byte) ((i & 1) == 0 ? c : c << 4);
        }
        return out;
    }

    /**
     * The log: what happened is written (HELLO, the settings, ROLL with what the phone made of it, PRESS, the music,
     * the voice notes); asked for, the watch is told (CFG_DEBUG) and its samples (RAW, with its own times, in batches as
     * the S5 gives them) and its lines (DIAG) are written too; then it is put in Documents and offered to send. The log
     * goes to build/test/watch/robo_wrist_log.txt for the replay tool's test.
     */
    static void debugLog(Application app, WristLink.Server server, ShadowBluetoothGattServer sg, BluetoothDevice watch,
                         BluetoothGattCharacteristic samples) throws Exception {
        WristLink.setDebug(app, true);
        assertTrue("the log asked for", WristLink.debug(app));
        assertEquals("the watch told", WristGesture.EV_CONFIG, server.lastMessage[0]);
        assertTrue("…with the debug flag", (server.lastMessage[1] & WristGesture.CFG_DEBUG) != 0);
        // the S5's stream: 20 a second in batches of 3 (one time each), whole m/s²; a turn right after 2 s
        java.util.List<double[]> ss = new java.util.ArrayList<double[]>();
        int n = 100;
        for (int k = 0; k < n; k++) {
            double t = k / 20.0, th = t > 2 && t < 2.4 ? Math.toRadians(65) * Math.pow(Math.sin(Math.PI * (t - 2) / 0.4), 2) : 0;
            ss.add(new double[]{100000 + (k / 3) * 150, 0, Math.round(G * Math.sin(th)), Math.round(G * Math.cos(th)), 123.4});
        }
        BluetoothGattServerCallback cb = sg.getGattServerCallback();
        for (int o = 0, seq = 0; o < n; o += 23, seq++) {
            double[][] part = ss.subList(o, Math.min(n, o + 23)).toArray(new double[0][]);
            cb.onCharacteristicWriteRequest(watch, 400 + seq, samples, false, true, 0, WristGesture.rawPacket(seq, part));
        }
        byte[] line = "поворот 61.4° за 243 мс гравитация — отправлен".getBytes(StandardCharsets.UTF_8);
        byte[] diag = new byte[1 + line.length];
        diag[0] = 0x70;
        System.arraycopy(line, 0, diag, 1, line.length);
        cb.onCharacteristicWriteRequest(watch, 420, samples, false, true, 0, diag);
        cb.onCharacteristicWriteRequest(watch, 421, samples, false, true, 0, WristGesture.rollPacket(2, 61.4f, 240, WristGesture.SOURCE_GRAVITY, 0, 2));
        WristLog.flush();
        String log = new String(java.nio.file.Files.readAllBytes(WristLog.file(app).toPath()), StandardCharsets.UTF_8);
        for (String want : new String[]{" HELLO ", " SETTINGS turn=", " CCCD ", " MUSIC ", " EV next", " PRESS next", " VOICE start",
                " VOICE phrases", " CFG left=", "debug=true", " S wt=100000 x=0.00 y=0.00 z=10.00 h=123.4", " W поворот 61.4°",
                " ROLL seq=2 twist=61.4 took=240 src=gravity"}) {
            assertTrue("the log has «" + want + "»", log.contains(want));
        }
        assertTrue("the turn's decision", log.matches("(?s).* ROLL seq=2 [^\n]*-> RIGHT why=ok turn=40 .*"));
        int sLines = log.split(" S wt=", -1).length - 1;
        assertEquals("every sample", n, sLines);
        java.io.File out = new java.io.File("build/test/watch/robo_wrist_log.txt");
        out.getParentFile().mkdirs();
        java.nio.file.Files.write(out.toPath(), log.getBytes(StandardCharsets.UTF_8));
        // put in Documents and offered to send
        android.app.Activity a = Robolectric.buildActivity(android.app.Activity.class).setup().get();
        android.net.Uri u = WristLog.share(a);
        assertNotNull("the log saved to send", u);
        WristLink.setDebug(app, false);
        assertFalse(WristLink.debug(app));
        assertEquals("the watch told: no more", 0, server.lastMessage[1] & WristGesture.CFG_DEBUG);
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
        gigaam(app, server, sg, other, samples);
        talk(app, server, sg, other, samples);
        debugLog(app, server, sg, other, samples);

        // off: the service and the link are gone
        WristLink.setOn(c, false);
        sc.destroy();
        assertNull(WristLink.current());
        assertTrue("GATT server closed", sg.isClosed());
    }

}
