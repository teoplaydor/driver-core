import io.github.teoplaydor.semsearch.core.HfTokenizer;
import io.github.teoplaydor.semsearch.core.MiniJson;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Compares HfTokenizer with reference ids produced by the Rust `tokenizers` library
 * (tools/tokenizer_reference.py). Usage: TokenizerParityTest tokenizer.json cases.jsonl
 */
public class TokenizerParityTest {
    public static void main(String[] args) throws Exception {
        File json = new File(args[0]);
        File cache = new File(args[0] + ".cache");
        cache.delete();
        long t0 = System.nanoTime();
        HfTokenizer tok = HfTokenizer.load(json, cache);
        long t1 = System.nanoTime();
        HfTokenizer cached = HfTokenizer.load(json, cache);
        long t2 = System.nanoTime();
        System.out.printf("parse %.0f ms, cached load %.0f ms, vocab %d%n", (t1 - t0) / 1e6, (t2 - t1) / 1e6, tok.vocabSize());

        int total = 0, bad = 0;
        BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(args[1]), StandardCharsets.UTF_8));
        String line;
        long encNs = 0;
        while ((line = r.readLine()) != null) {
            Map<String, Object> c = MiniJson.obj(MiniJson.parse(line));
            String text = (String) c.get("text");
            List<Object> exp = MiniJson.arr(c.get("ids"));
            for (HfTokenizer t : new HfTokenizer[]{tok, cached}) {
                long s = System.nanoTime();
                int[] got = t.encode(text);
                encNs += System.nanoTime() - s;
                total++;
                boolean ok = got.length == exp.size();
                for (int i = 0; ok && i < got.length; i++) ok = got[i] == ((Number) exp.get(i)).intValue();
                if (!ok) {
                    bad++;
                    if (bad <= 5) {
                        System.out.println("MISMATCH for " + MiniJson.write(text));
                        System.out.println("  expected " + exp);
                        System.out.println("  got      " + Arrays.toString(got));
                    }
                }
            }
        }
        System.out.printf("cases %d, mismatches %d, avg encode %.3f ms%n", total, bad, encNs / 1e6 / Math.max(1, total));
        if (bad > 0) System.exit(1);
    }
}
