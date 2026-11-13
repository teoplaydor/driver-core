package local.secretary;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

import io.github.teoplaydor.semsearch.core.MiniJson;
import io.github.teoplaydor.semsearch.secretary.Api;

/**
 * «Связь с сервером» — теперь без сервера: секретарь живёт в самом приложении ({@link Core}), а этот класс отвечает
 * на те же адреса, что раньше отвечал сервер (/hook/phone, /api/...), прямо здесь. Поэтому Listener, Tasks и страница
 * чата работают как раньше, ничего не зная о том, что сервера больше нет.
 */
final class Net {
    private Net() {}

    static String sha256(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static String get(String path, int timeoutMs) throws IOException {
        return call(path, null);
    }

    static String postJson(String path, String json, int timeoutMs) throws IOException {
        return call(path, json);
    }

    static String post(String path, byte[] body, String contentType, int timeoutMs) throws IOException {
        return call(path, new String(body, StandardCharsets.UTF_8));
    }

    /** Как сервер: пачка уведомлений телефона (и в «Тишине» — что с каждым сделать) или API страницы чата. */
    private static String call(String path, String body) throws IOException {
        Core core = Core.instance();
        if (core == null) throw new IOException("секретарь ещё не запущен");
        if (path.startsWith("/hook/phone")) {
            Map<String, Object> data;
            try {
                data = MiniJson.obj(MiniJson.parse(body == null ? "" : body));
            } catch (IOException e) {
                throw new IOException("HTTP 400: bad json");
            }
            List<Object> items = data == null ? null : MiniJson.arr(data.get("items"));
            if (items == null) throw new IOException("HTTP 400: no items");
            return MiniJson.write(core.phoneHook(items, path.contains("verdicts=1")));
        }
        Api.Reply r = core.api.handle(path, body);
        if (r.status >= 400) throw new IOException("HTTP " + r.status + ": " + r.json);
        return r.json;
    }
}
