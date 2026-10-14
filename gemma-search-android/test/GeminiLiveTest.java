import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.github.teoplaydor.semsearch.core.Adpcm;
import io.github.teoplaydor.semsearch.core.GeminiLive;
import io.github.teoplaydor.semsearch.core.MiniJson;
import io.github.teoplaydor.semsearch.core.WristGesture;

/**
 * A conversation with Gemini Live from the phone's side (core/GeminiLive over core/WebSocket), against a stand-in
 * server held to Google's SDK (test/gemini/live_mock.py): the setup the same as the SDK's; voice up in 100 ms pieces,
 * every message valid for the SDK; what was heard, a function call answered, the answer's voice (24 kHz) and text, turn
 * complete; a second turn after «the stream paused»; a wrong key closed with the server's reason; the live models
 * listed over two pages and the best one first. And the answer's voice for the watch: IMA ADPCM WAV the way FFmpeg
 * reads it; the watch's live voice decoded piece by piece as in one go; the talk packets.
 *
 * usage: GeminiLiveTest PORT SETUP_REF_JSON CLIENT_LOG OUT_DIR
 */
public class GeminiLiveTest {
    static int bad = 0;

    static void ok(boolean c, String what) {
        System.out.println((c ? "ok   " : "FAIL ") + what);
        if (!c) bad++;
    }

    /** Keys camelCased (the server takes both; the SDK writes some nested ones in snake_case). */
    static Object camel(Object o) {
        if (o instanceof Map) {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            for (Map.Entry<String, Object> e : MiniJson.obj(o).entrySet()) {
                String k = e.getKey();
                StringBuilder b = new StringBuilder();
                for (int i = 0; i < k.length(); i++) {
                    char c = k.charAt(i);
                    if (c == '_' && i + 1 < k.length()) b.append(Character.toUpperCase(k.charAt(++i)));
                    else b.append(c);
                }
                out.put(b.toString(), camel(e.getValue()));
            }
            return out;
        }
        if (o instanceof List) {
            List<Object> out = new ArrayList<Object>();
            for (Object x : MiniJson.arr(o)) out.add(camel(x));
            return out;
        }
        return o;
    }

    /** The same tree with every object's keys in order (the server reads them in any order). */
    static Object sorted(Object o) {
        if (o instanceof Map) {
            Map<String, Object> out = new java.util.TreeMap<String, Object>();
            for (Map.Entry<String, Object> e : MiniJson.obj(o).entrySet()) out.put(e.getKey(), sorted(e.getValue()));
            return out;
        }
        if (o instanceof List) {
            List<Object> out = new ArrayList<Object>();
            for (Object x : MiniJson.arr(o)) out.add(sorted(x));
            return out;
        }
        return o;
    }

    static Map<String, Object> saveNote() {
        Map<String, Object> text = new LinkedHashMap<String, Object>();
        text.put("type", "STRING");
        text.put("description", "Текст");
        Map<String, Object> props = new LinkedHashMap<String, Object>();
        props.put("text", text);
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("type", "OBJECT");
        params.put("properties", props);
        params.put("required", new ArrayList<Object>(Arrays.asList("text")));
        Map<String, Object> f = new LinkedHashMap<String, Object>();
        f.put("name", "save_note");
        f.put("description", "Сохранить заметку");
        f.put("parameters", params);
        return f;
    }

    public static void main(String[] args) throws Exception {
        String host = "ws://127.0.0.1:" + args[0];
        File out = new File(args[3]);
        out.mkdirs();

        // the setup: what the SDK sends for the same configuration
        GeminiLive.Config c = new GeminiLive.Config();
        c.model = "gemini-test-native-audio";
        c.systemInstruction = "Ты голосовой помощник.";
        c.voice = "Kore";
        c.silenceMs = 700;
        c.functions.add(saveNote());
        Object mine = sorted(camel(MiniJson.parse(GeminiLive.setup(c))));
        Object sdk = sorted(camel(MiniJson.parse(new String(Files.readAllBytes(new File(args[1]).toPath()), StandardCharsets.UTF_8).trim())));
        ok(MiniJson.write(mine).equals(MiniJson.write(sdk)), "setup: the same as the SDK's for the same configuration\n     phone: "
                + MiniJson.write(mine) + "\n     SDK:   " + MiniJson.write(sdk));

        // a conversation
        final List<String> events = java.util.Collections.synchronizedList(new ArrayList<String>());
        final StringBuilder heard = new StringBuilder(), said = new StringBuilder();
        final int[] samples = {0}, rate = {0};
        final CountDownLatch ready = new CountDownLatch(1), tool = new CountDownLatch(1), turn1 = new CountDownLatch(1),
                turn2 = new CountDownLatch(1), closed = new CountDownLatch(1);
        final Object[] call = new Object[3];
        final int[] turns = {0};
        final Object[] closeInfo = new Object[2];
        GeminiLive.Listener l = new GeminiLive.Listener() {
            @Override
            public void onReady() {
                events.add("ready");
                ready.countDown();
            }

            @Override
            public void onAudio(short[] pcm, int r) {
                synchronized (samples) {
                    samples[0] += pcm.length;
                }
                rate[0] = r;
            }

            @Override
            public void onInputText(String t) {
                heard.append(t);
            }

            @Override
            public void onOutputText(String t) {
                said.append(t);
            }

            @Override
            public void onTurnComplete() {
                events.add("turn");
                if (++turns[0] == 1) turn1.countDown();
                else turn2.countDown();
            }

            @Override
            public void onInterrupted() {
                events.add("interrupted");
            }

            @Override
            public void onToolCall(String id, String name, Map<String, Object> a) {
                call[0] = id;
                call[1] = name;
                call[2] = a;
                tool.countDown();
            }

            @Override
            public void onGoAway(String timeLeft) {
                events.add("goAway " + timeLeft);
            }

            @Override
            public void onClosed(int code, String reason) {
                closeInfo[0] = code;
                closeInfo[1] = reason;
                closed.countDown();
            }
        };
        GeminiLive g = GeminiLive.connect(host, "test-key", c, l);
        ok(ready.await(10, TimeUnit.SECONDS) && g.ready(), "connected, the setup taken (setupComplete)" + (closeInfo[0] != null ? " — closed: " + closeInfo[0] + " " + closeInfo[1] : ""));
        if (!g.ready()) System.exit(1);
        short[] voice = new short[16000 * 12 / 10];
        for (int i = 0; i < voice.length; i++) voice[i] = (short) (6000 * Math.sin(2 * Math.PI * 220 * i / 16000.0));
        for (int o = 0; o < voice.length; o += 1600) g.sendAudio(voice, o, Math.min(1600, voice.length - o));
        ok(tool.await(10, TimeUnit.SECONDS) && "save_note".equals(call[1]) && "call-1".equals(call[0])
                && "Напомни завтра в 9 купить хлеб".equals(MiniJson.str(MiniJson.obj(call[2]), "text", "")),
                "a function call: save_note(«" + (call[2] == null ? "" : MiniJson.str(MiniJson.obj(call[2]), "text", "")) + "»)");
        Map<String, Object> res = new LinkedHashMap<String, Object>();
        res.put("result", "Сохранено: купить хлеб · напомнит завтра в 9:00");
        g.toolResponse((String) call[0], (String) call[1], res);
        ok(turn1.await(10, TimeUnit.SECONDS), "the answer: turn complete" + (closeInfo[0] != null ? " — closed: " + closeInfo[0] + " " + closeInfo[1] : ""));
        ok(heard.toString().equals("Напомни завтра в 9 купить хлеб"), "what was heard, its pieces joined: «" + heard + "»");
        ok(said.toString().equals("Готово, напомню завтра в девять."), "what the answer says: «" + said + "»");
        ok(rate[0] == 24000 && Math.abs(samples[0] - 21600) <= 2, "the answer's voice: " + samples[0] + " samples at " + rate[0] + " Hz (0.9 s)");
        // a second turn: a little voice, then «the stream paused»
        said.setLength(0);
        g.sendAudio(voice, 0, 8000);
        g.audioStreamEnd();
        ok(turn2.await(10, TimeUnit.SECONDS) && said.toString().equals("Всё хорошо, спасибо!"), "the stream paused: answered — «" + said + "»");
        g.close();
        ok(closed.await(5, TimeUnit.SECONDS), "closed (" + closeInfo[0] + ")");
        // every message valid for the SDK (the stand-in closes on one that is not): count them
        List<String> sent = Files.readAllLines(new File(args[2]).toPath(), StandardCharsets.UTF_8);
        int audio = 0, ends = 0, tools = 0;
        for (String s : sent) {
            if (s.contains("\"audio\"")) audio++;
            if (s.contains("audioStreamEnd")) ends++;
            if (s.contains("toolResponse")) tools++;
        }
        ok(sent.size() == 1 + audio + ends + tools && audio == 13 && ends == 1 && tools == 1 && !sent.get(1).contains("test-key"),
                "every message the SDK's: setup, " + audio + " pieces of voice, «paused», the function's answer (the key never in them)");

        // a wrong key: the server's reason
        final CountDownLatch refused = new CountDownLatch(1);
        final Object[] why = new Object[2];
        GeminiLive wrong = GeminiLive.connect(host, "wrong", c, new GeminiLive.Listener() {
            public void onReady() {
            }

            public void onAudio(short[] pcm, int r) {
            }

            public void onInputText(String t) {
            }

            public void onOutputText(String t) {
            }

            public void onTurnComplete() {
            }

            public void onInterrupted() {
            }

            public void onToolCall(String id, String name, Map<String, Object> a) {
            }

            public void onGoAway(String t) {
            }

            public void onClosed(int code, String reason) {
                why[0] = code;
                why[1] = reason;
                refused.countDown();
            }
        });
        ok(refused.await(10, TimeUnit.SECONDS) && Integer.valueOf(1008).equals(why[0]) && String.valueOf(why[1]).contains("API key not valid"),
                "a wrong key: closed with the server's reason (" + why[0] + " " + why[1] + ")");
        wrong.close();

        // the live models, both pages, best first
        List<String> models = GeminiLive.liveModels(host, "test-key");
        ok(models.equals(Arrays.asList("models/gemini-3-flash-native-audio-preview-2026-03-02", "models/gemini-2.5-flash-native-audio-preview-09-2025",
                "models/gemini-3-flash-live-preview", "models/gemini-live-2.5-flash-preview")), "the live models, best first: " + models);
        try {
            GeminiLive.liveModels(host, "wrong");
            ok(false, "a wrong key: an error");
        } catch (java.io.IOException e) {
            ok(e.getMessage().contains("API key not valid"), "a wrong key: «" + e.getMessage() + "»");
        }

        // the answer's voice for the watch: IMA ADPCM WAV at 8 kHz, as FFmpeg reads it
        short[] x = new short[8000 * 2 + 77];
        for (int i = 0; i < x.length; i++) {
            double t = i / 8000.0;
            x[i] = (short) (9000 * Math.sin(2 * Math.PI * 300 * t) + 3000 * Math.sin(2 * Math.PI * 1250 * t) * Math.sin(Math.PI * t));
        }
        byte[] wav = Adpcm.imaWav(x, 8000);
        File wf = new File(out, "talk_answer.wav");
        Files.write(wf.toPath(), wav);
        short[] mine2 = Adpcm.fromImaWav(wav);
        double sig = 0, err = 0;
        for (int i = 0; i < x.length; i++) {
            sig += (double) x[i] * x[i];
            err += (double) (x[i] - mine2[i]) * (x[i] - mine2[i]);
        }
        double snr = 10 * Math.log10(sig / Math.max(1, err));
        ok(mine2.length == x.length && snr > 20, String.format("IMA ADPCM WAV: %d samples back, SNR %.1f dB, %d bytes (%.1f KB a second)",
                mine2.length, snr, wav.length, wav.length / 1024.0 / (x.length / 8000.0)));
        Process p = new ProcessBuilder("ffmpeg", "-v", "error", "-i", wf.getPath(), "-f", "s16le", "-acodec", "pcm_s16le", "-").start();
        byte[] raw = all(p.getInputStream());
        String errs = new String(all(p.getErrorStream()), StandardCharsets.UTF_8);
        p.waitFor();
        short[] ff = new short[raw.length / 2];
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(ff);
        // FFmpeg's IMA rounds the step a little more exactly than the reference decoder: not the same samples, as good
        double fe = 0;
        for (int i = 0; i < Math.min(ff.length, x.length); i++) fe += (double) (x[i] - ff[i]) * (x[i] - ff[i]);
        double ffSnr = 10 * Math.log10(sig / Math.max(1, fe));
        ok(ff.length >= x.length && errs.isEmpty() && ffSnr > 20, String.format("…and FFmpeg (adpcm_ima_wav) plays it: %d samples, SNR %.1f dB",
                ff.length, ffSnr) + (errs.isEmpty() ? "" : " — " + errs.trim()));

        // the watch's live voice decoded piece by piece: the same as in one go
        byte[] stream = new byte[4000];
        new java.util.Random(3).nextBytes(stream);
        float[] whole = Adpcm.decode(stream);
        Adpcm.Decoder d = new Adpcm.Decoder();
        short[] a1 = d.decode(stream, 0, 1234), a2 = d.decode(stream, 1234, stream.length - 1234);
        boolean eq = a1.length + a2.length == whole.length;
        for (int i = 0; eq && i < whole.length; i++) eq = (i < a1.length ? a1[i] : a2[i - a1.length]) == Math.round(whole[i] * 32768);
        ok(eq, "the watch's live voice: decoded piece by piece — the same as in one go");

        // the talk packets
        byte[] ta = WristGesture.talkAudio(3, 1000, wav, 1000, 100);
        ok(ta.length == 106 && ta[0] == WristGesture.EV_TALK_AUDIO && ta[1] == 3 && (ta[2] & 0xff) == 0xe8 && ta[3] == 3 && ta[6] == wav[1000],
                "EV_TALK_AUDIO: piece, offset, bytes");
        byte[] ts = WristGesture.talkSeg(3, wav.length, 2010);
        ok(ts[0] == WristGesture.EV_TALK_SEG && ts[1] == 3 && ts[6] == 21, "EV_TALK_SEG: piece, length, tenths of a second");
        byte[] tt = WristGesture.talkText("Готово, напомню", 10);
        ok(tt[0] == WristGesture.EV_TALK_TEXT && new String(tt, 1, tt.length - 1, StandardCharsets.UTF_8).equals("Гото"), "EV_TALK_TEXT: cut on whole letters");
        java.nio.file.Files.write(new File(out, "talk_events.hex").toPath(), Arrays.asList(hex(ta), hex(ts), hex(WristGesture.talkText("Готово, напомню", 60)),
                hex(WristGesture.talkEnd()), hex(WristGesture.config(WristGesture.CFG_VOICE | WristGesture.CFG_TALK | WristGesture.CFG_TALK_WATCH, 247))),
                StandardCharsets.UTF_8);

        System.out.println(bad == 0 ? "GEMINI LIVE OK" : bad + " FAILED");
        System.exit(bad == 0 ? 0 : 1);
    }

    static byte[] all(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toByteArray();
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x & 0xff));
        return s.toString();
    }
}
