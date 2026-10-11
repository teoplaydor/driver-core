import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.imageio.ImageIO;

import com.google.ai.edge.litertlm.LiteRtLmJniException;
import com.google.ai.edge.litertlm.SamplerConfig;

import io.github.teoplaydor.semsearch.core.Answers;
import io.github.teoplaydor.semsearch.core.GemmaChat;
import io.github.teoplaydor.semsearch.core.ImagePreprocessor;
import io.github.teoplaydor.semsearch.core.LiteRtEmbedder;
import io.github.teoplaydor.semsearch.core.LiteRtRuntime;
import io.github.teoplaydor.semsearch.core.PatternSource;

/**
 * The LiteRT-LM path against a stand-in native library whose JNI glue is LiteRT-LM 0.18's own
 * (test/litert/fake_litertlm_jni.cc): our Java mirror classes must satisfy the real library's lookups
 * (InputData$Text/getText, InputData$Image/getBytes, EmbeddingResponse(float[]), LiteRtLmJniException(String),
 * boxed options), and LiteRtEmbedder must pass the right engine settings, prompts, budgets and batches.
 * Also: loading the extracted libraries in passes, with an optional one that cannot load.
 * usage: LiteRtJniTest <dir with liblitertlm_jni.so, libLiteRtGpuAccelerator.so, libLiteRtOpenClAccelerator.so>
 */
public class LiteRtJniTest {
    static native String lastCall();

    static int bad;

    static void check(boolean ok, String what) {
        if (!ok) bad++;
        System.out.println((ok ? "ok   " : "FAIL ") + what);
    }

    static final LiteRtEmbedder.ImageEncoder PNG = new LiteRtEmbedder.ImageEncoder() {
        @Override
        public byte[] encode(ImagePreprocessor.Source s) throws Exception {
            BufferedImage img = new BufferedImage(s.width(), s.height(), BufferedImage.TYPE_INT_ARGB);
            img.setRGB(0, 0, s.width(), s.height(), s.argb(s.width(), s.height()), 0, s.width());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        }
    };

    static double norm(float[] v) {
        double n = 0;
        for (float x : v) n += x * x;
        return Math.sqrt(n);
    }

    public static void main(String[] args) throws Exception {
        File lib = new File(args[0]);
        // the GPU accelerator first in the list, the JNI library in the middle, a broken optional library last
        List<String> issues = LiteRtRuntime.load(lib, Arrays.asList("libLiteRtGpuAccelerator.so",
                LiteRtRuntime.JNI_LIB, "libLiteRtOpenClAccelerator.so"));
        check(issues.size() == 1 && issues.get(0).startsWith("libLiteRtOpenClAccelerator.so"),
                "libraries load in passes; the broken optional one is reported: " + issues);

        File tmp = new File(System.getProperty("java.io.tmpdir"), "litert-test-" + System.nanoTime());
        tmp.mkdirs();
        File model = new File(tmp, "embeddinggemma-2.litertlm");
        try (FileOutputStream o = new FileOutputStream(model)) {
            o.write(new byte[]{1, 2, 3});
        }
        File cache = new File(tmp, "cache");

        // missing model: the native exception arrives as our LiteRtLmJniException
        try {
            new LiteRtEmbedder(new File(tmp, "missing.litertlm"), LiteRtEmbedder.GPU, 4, 280, cache, 2, PNG);
            check(false, "missing model throws");
        } catch (LiteRtLmJniException e) {
            check(e.getMessage().contains("Failed to open model file"), "missing model: " + e.getMessage());
        }

        // CPU engine: threads reach the native side
        LiteRtEmbedder cpu = new LiteRtEmbedder(model, LiteRtEmbedder.CPU, 4, 280, cache, 1, PNG);
        check(lastCall().contains("backend=CPU vision=CPU audio= ") && lastCall().contains("threads=4/-1"),
                "CPU engine: " + lastCall());
        cpu.close();

        LiteRtEmbedder m = new LiteRtEmbedder(model, LiteRtEmbedder.GPU, 4, 280, cache, 2, PNG);
        String create = lastCall();
        check(create.equals("create fd=-1 path=" + model.getPath() + " backend=GPU vision=GPU audio= cache=" + cache.getPath()
                + " npu=|| threads=-1/-1 max_input=-1 vision_tokens=280 activation=-1"), "GPU engine: " + create);

        float[] q = m.embedQuery("кот на диване");
        check(lastCall().equals("embed text[task: search result | query: кот на диване] normalize=true special=- size=- vision_tokens=-"),
                "query prompt, UTF-8, options: " + lastCall());
        check(Math.abs(norm(q) - 1) < 1e-5 && q.length == 16 && m.embeddingDim() == 16, "unit vector of the model's size");
        check(Arrays.equals(q, m.embedQuery("кот на диване")), "same input, same vector");
        m.embedDocument("Пароль от Wi-Fi");
        check(lastCall().startsWith("embed text[title: none | text: Пароль от Wi-Fi]"), "document prompt: " + lastCall());

        // a note longer than the runtime's largest text signature: retried with its beginning
        StringBuilder longNote = new StringBuilder();
        while (longNote.length() < 5000) longNote.append("очень длинная заметка ");
        float[] d = m.embedDocument(longNote.toString());
        check(d != null && lastCall().startsWith("embed text[title: none | text: очень") && lastCall().length() < 4200,
                "long note retried shorter (" + lastCall().length() + " chars in the call)");

        // pictures: PNG bytes, the budget per call, batches of 2 (then the rest one by one)
        List<ImagePreprocessor.Source> imgs = new ArrayList<ImagePreprocessor.Source>();
        for (int i = 0; i < 3; i++) imgs.add(new PatternSource(64 + i, 48, i));
        float[][] e = m.embedImages(imgs, 70);
        check(e.length == 3 && Math.abs(norm(e[2]) - 1) < 1e-5, "3 pictures → 3 unit vectors");
        check(lastCall().equals("embed image/png normalize=true special=- size=- vision_tokens=70"), "last one alone: " + lastCall());
        m.embedImages(imgs.subList(0, 2), 280);
        check(lastCall().equals("batch 2: {image/png} {image/png} normalize=true special=- size=- vision_tokens=280"),
                "batch of 2 in one call: " + lastCall());
        float[][] again = m.embedImages(imgs.subList(0, 2), 70);
        check(Arrays.equals(again[0], e[0]) && Arrays.equals(again[1], e[1]), "batched = one by one");
        m.embedImage(imgs.get(0), 0);
        check(lastCall().endsWith("vision_tokens=-"), "budget 0: the runtime's default");
        m.embedVideo(imgs.subList(0, 2), 0);
        check(lastCall().equals("embed image/png,image/png normalize=true special=- size=- vision_tokens=70"),
                "video: frames as pictures of one input: " + lastCall());
        check(m.lastTimingsMs()[0] >= 0 && m.lastTimingsMs()[1] == 0, "timings: all of it on the picture side");

        // a bundle without the requested budget's signature: created with its default, budgets not passed per call
        LiteRtEmbedder plain = new LiteRtEmbedder(model, LiteRtEmbedder.GPU, 4, 0, cache, 1, PNG);
        check(lastCall().contains("vision_tokens=-1"), "default signature: " + lastCall());
        plain.embedImage(imgs.get(0), 280);
        check(lastCall().endsWith("vision_tokens=-"), "…and the default budget for every picture: " + lastCall());
        plain.close();

        m.close();
        check("delete".equals(lastCall()), "close frees the engine");
        m.close();
        try {
            m.embedQuery("x");
            check(false, "closed engine refuses");
        } catch (IllegalStateException ex) {
            check(true, "closed engine refuses: " + ex.getMessage());
        }
        // Gemma for answers: the generative engine, a session with our sampler read through its getters, the prompt
        // (Cyrillic) as text, the answer with its turn marker taken off; a session per question, freed after
        try {
            new GemmaChat(new File(tmp, "missing.litertlm"), LiteRtEmbedder.GPU, 2048, 4, cache);
            check(false, "missing Gemma throws");
        } catch (LiteRtLmJniException ge) {
            check(ge.getMessage().contains("Model file not found"), "missing Gemma: " + ge.getMessage());
        }
        GemmaChat chat = new GemmaChat(model, LiteRtEmbedder.GPU, 2048, 4, cache);
        check(lastCall().equals("engine path=" + model.getPath() + " backend=GPU vision= audio= tokens=2048 images=0 cache=" + cache.getPath()
                + " benchmark=false speculative=- npu=|| threads=-1/-1 vision_tokens=-1 activation=-1"), "Gemma engine: " + lastCall());
        String prompt = Answers.prompt("Какой код домофона у Саши?", Arrays.asList("Заметка: Код домофона у Саши: 47К1290", "Заметка: Пароль"));
        String said = chat.generate(prompt);
        check(said.equals("Ответ: Заметка: Код домофона у Саши: 47К1290"), "Gemma's answer, its turn marker off: " + said);
        check(lastCall().startsWith("generate [top_p k=40 p=0.90 t=0.20 seed=1] text[Ответь на вопрос") && lastCall().endsWith("Вопрос: Какой код домофона у Саши?] | session deleted"),
                "our sampler read by its getters, the prompt as text, the session freed: " + lastCall().substring(0, 50));
        GemmaChat cpuChat = new GemmaChat(model, LiteRtEmbedder.CPU, 1024, 4, null);
        check(lastCall().contains("backend=CPU") && lastCall().contains("threads=4/-1") && lastCall().contains("tokens=1024 images=0 cache= "),
                "Gemma on the CPU: " + lastCall());
        cpuChat.close();
        try {
            StringBuilder huge = new StringBuilder();
            for (int i = 0; i < 500; i++) huge.append("очень длинная заметка ");
            chat.generate(huge.toString());
            check(false, "too long throws");
        } catch (LiteRtLmJniException ge) {
            check(ge.getMessage().contains("kv-cache"), "too long for the context: " + ge.getMessage());
        }
        chat.close();
        check("delete engine".equals(lastCall()), "close frees Gemma's engine");
        try {
            chat.generate("x");
            check(false, "closed Gemma refuses");
        } catch (IllegalStateException ex) {
            check(true, "closed Gemma refuses");
        }
        // sampler settings as LiteRT-LM checks them
        try {
            new SamplerConfig(0, 0.9, 0.2, 1);
            check(false, "topK 0 refused");
        } catch (IllegalArgumentException ex) {
            check(true, "topK 0 refused");
        }
        LiteRtRuntime.deleteTree(tmp);
        System.out.println(bad == 0 ? "LITERT JNI OK" : bad + " FAILED");
        if (bad != 0) System.exit(1);
    }
}
