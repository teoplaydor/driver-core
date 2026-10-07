package io.github.teoplaydor.semsearch.core;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Lists and downloads the files of a Hugging Face model repo (ONNX export of EmbeddingGemma 2).
 * Downloads are resumable (HTTP Range) and verified against the size reported by the Hub.
 */
public final class HfRepo {
    public static final String DEFAULT_REPO = "onnx-community/embeddinggemma-2-ONNX";
    private static final String DEFAULT_HOST = "https://huggingface.co";
    private static final String UA = "SemSearch-Android/0.1";

    /** Quantisation preference for CPU inference: smallest first, fp16 variants last. */
    public static final String[] DTYPE_SUFFIXES = {"_q4", "_quantized", "_int8", "_uint8", "", "_q4f16", "_fp16"};

    public static final class RemoteFile {
        public final String path;
        public final long size;

        RemoteFile(String path, long size) {
            this.path = path;
            this.size = size;
        }
    }

    public static final class Plan {
        public final List<RemoteFile> files = new ArrayList<RemoteFile>();
        public String textModel, visionModel;
        public long totalBytes;
    }

    public interface Progress {
        /** @return false to cancel */
        boolean onProgress(String file, long fileDone, long fileTotal, long allDone, long allTotal);
    }

    private final String host;
    private final String repo;
    private final String token;

    public HfRepo(String repo, String token) {
        this(DEFAULT_HOST, repo, token);
    }

    /** @param host Hub base URL (overridable for tests or mirrors). */
    public HfRepo(String host, String repo, String token) {
        this.host = host;
        this.repo = repo.trim();
        this.token = token == null || token.trim().isEmpty() ? null : token.trim();
    }

    public List<RemoteFile> listFiles() throws IOException {
        HttpURLConnection c = open(host + "/api/models/" + repo + "?blobs=true", -1);
        int code = c.getResponseCode();
        if (code == 401 || code == 403) {
            throw new IOException("Доступ к " + repo + " закрыт (HTTP " + code + "). Укажите токен Hugging Face.");
        }
        if (code == 404) throw new IOException("Репозиторий " + repo + " не найден (HTTP 404)");
        if (code != 200) throw new IOException("Hugging Face API: HTTP " + code);
        Map<String, Object> info;
        InputStream in = c.getInputStream();
        try {
            info = MiniJson.obj(new MiniJson(new InputStreamReader(new BufferedInputStream(in), "UTF-8")).readValue());
        } finally {
            in.close();
        }
        List<RemoteFile> out = new ArrayList<RemoteFile>();
        List<Object> siblings = MiniJson.arr(info.get("siblings"));
        if (siblings == null) throw new IOException("Hugging Face API: нет списка файлов");
        for (Object o : siblings) {
            Map<String, Object> s = MiniJson.obj(o);
            String name = MiniJson.str(s, "rfilename", null);
            if (name == null) continue;
            long size = MiniJson.num(s, "size", -1);
            Map<String, Object> lfs = MiniJson.obj(s.get("lfs"));
            if (lfs != null && MiniJson.num(lfs, "size", -1) > 0) size = MiniJson.num(lfs, "size", -1);
            out.add(new RemoteFile(name, size));
        }
        return out;
    }

    /** Chooses config/tokenizer files and the best available quantisation of each ONNX component. */
    public static Plan plan(List<RemoteFile> files, boolean withVision) throws IOException {
        Plan p = new Plan();
        for (RemoteFile f : files) {
            String n = f.path;
            if (n.indexOf('/') < 0 && n.endsWith(".json")) p.files.add(f);
        }
        p.textModel = pickComponent(files, "model", p);
        if (p.textModel == null) throw new IOException("В репозитории нет onnx/model*.onnx");
        if (withVision) p.visionModel = pickComponent(files, "vision_encoder", p);
        for (RemoteFile f : p.files) p.totalBytes += Math.max(0, f.size);
        boolean hasTok = false;
        for (RemoteFile f : p.files) if (f.path.equals("tokenizer.json")) hasTok = true;
        if (!hasTok) throw new IOException("В репозитории нет tokenizer.json");
        return p;
    }

    private static String pickComponent(List<RemoteFile> files, String component, Plan p) {
        for (String suffix : DTYPE_SUFFIXES) {
            String main = "onnx/" + component + suffix + ".onnx";
            RemoteFile mf = find(files, main);
            if (mf == null) continue;
            p.files.add(mf);
            for (RemoteFile f : files) {
                if (f.path.startsWith(main + "_data")) p.files.add(f);
            }
            return main;
        }
        return null;
    }

    private static RemoteFile find(List<RemoteFile> files, String path) {
        for (RemoteFile f : files) if (f.path.equals(path)) return f;
        return null;
    }

    /** Downloads all files of the plan into {@code dir}, keeping the repo's relative paths. */
    public void download(Plan plan, File dir, Progress progress) throws IOException {
        long allDone = 0;
        for (RemoteFile f : plan.files) {
            File dst = new File(dir, f.path);
            if (dst.exists() && (f.size < 0 || dst.length() == f.size)) {
                allDone += dst.length();
                continue;
            }
            allDone = downloadOne(f, dst, allDone, plan.totalBytes, progress);
        }
    }

    private long downloadOne(RemoteFile f, File dst, long allDone, long allTotal, Progress progress) throws IOException {
        File part = new File(dst.getPath() + ".part");
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("mkdir " + parent);
        long have = part.exists() ? part.length() : 0;
        if (f.size >= 0 && have > f.size) {
            part.delete();
            have = 0;
        }
        String url = host + "/" + repo + "/resolve/main/" + f.path;
        HttpURLConnection c = open(url, have);
        int code = c.getResponseCode();
        if (code == 416 && f.size >= 0 && have == f.size) {
            c.disconnect();
        } else {
            if (code == 401 || code == 403) {
                throw new IOException("Нет доступа к " + f.path + " (HTTP " + code + "): репозиторий закрыт — "
                        + "примите условия на huggingface.co и укажите токен");
            }
            if (code != 200 && code != 206) throw new IOException("HTTP " + code + " для " + f.path);
            boolean append = code == 206;
            if (!append) have = 0;
            long total = f.size >= 0 ? f.size : (c.getContentLength() >= 0 ? have + c.getContentLength() : -1);
            InputStream in = c.getInputStream();
            OutputStream out = new FileOutputStream(part, append);
            byte[] buf = new byte[1 << 16];
            long lastReport = 0;
            try {
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    have += n;
                    long now = System.currentTimeMillis();
                    if (now - lastReport > 250) {
                        lastReport = now;
                        if (progress != null && !progress.onProgress(f.path, have, total, allDone + have, allTotal)) {
                            throw new InterruptedIOException("Загрузка отменена");
                        }
                    }
                }
            } finally {
                out.close();
                in.close();
            }
        }
        if (f.size >= 0 && part.length() != f.size) {
            throw new IOException("Файл " + f.path + " скачан не полностью: " + part.length() + " из " + f.size);
        }
        if (dst.exists()) dst.delete();
        if (!part.renameTo(dst)) throw new IOException("rename " + part);
        return allDone + dst.length();
    }

    private HttpURLConnection open(String url, long rangeFrom) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(30000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", UA);
        if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
        if (rangeFrom > 0) c.setRequestProperty("Range", "bytes=" + rangeFrom + "-");
        return c;
    }

    /** Thrown when the user cancels a download. */
    public static final class InterruptedIOException extends IOException {
        public InterruptedIOException(String m) {
            super(m);
        }
    }

    /** Checks that every planned file is present locally. */
    public static boolean isComplete(Plan plan, File dir) {
        for (RemoteFile f : plan.files) {
            File d = new File(dir, f.path);
            if (!d.exists() || (f.size >= 0 && d.length() != f.size)) return false;
        }
        return true;
    }

    /** Persists the plan as a tiny JSON manifest so the app can reload without the network. */
    public static void saveManifest(Plan plan, String repo, File file) throws IOException {
        StringBuilder sb = new StringBuilder("{\"repo\":").append(MiniJson.write(repo));
        sb.append(",\"text\":").append(MiniJson.write(plan.textModel));
        sb.append(",\"vision\":").append(MiniJson.write(plan.visionModel));
        sb.append(",\"files\":[");
        for (int i = 0; i < plan.files.size(); i++) {
            RemoteFile f = plan.files.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"path\":").append(MiniJson.write(f.path)).append(",\"size\":").append(f.size).append('}');
        }
        sb.append("]}");
        OutputStream out = new FileOutputStream(file);
        try {
            out.write(sb.toString().getBytes("UTF-8"));
        } finally {
            out.close();
        }
    }

    public static Plan loadManifest(File file) throws IOException {
        Map<String, Object> m = MiniJson.obj(ModelConfig.readJson(file));
        if (m == null) return null;
        Plan p = new Plan();
        p.textModel = MiniJson.str(m, "text", null);
        p.visionModel = MiniJson.str(m, "vision", null);
        for (Object o : MiniJson.arr(m.get("files"))) {
            Map<String, Object> f = MiniJson.obj(o);
            RemoteFile rf = new RemoteFile(MiniJson.str(f, "path", ""), MiniJson.num(f, "size", -1));
            p.files.add(rf);
            p.totalBytes += Math.max(0, rf.size);
        }
        return p;
    }

    public static String manifestRepo(File file) throws IOException {
        return MiniJson.str(MiniJson.obj(ModelConfig.readJson(file)), "repo", null);
    }
}
