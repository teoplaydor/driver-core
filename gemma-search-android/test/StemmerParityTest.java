import io.github.teoplaydor.semsearch.core.RussianStemmer;

import java.io.*;
import java.nio.charset.StandardCharsets;

/** Compares RussianStemmer with the Snowball reference (tools/stemmer_reference.py output: word TAB stem). */
public class StemmerParityTest {
    public static void main(String[] a) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(a[0]), StandardCharsets.UTF_8));
        String line;
        int n = 0, bad = 0;
        while ((line = r.readLine()) != null) {
            String[] p = line.split("\t");
            n++;
            String got = RussianStemmer.stem(p[0]);
            if (!got.equals(p[1])) {
                if (++bad <= 15) System.out.println(p[0] + ": expected " + p[1] + ", got " + got);
            }
        }
        System.out.println("words " + n + ", mismatches " + bad);
        if (bad > 0) System.exit(1);
    }
}
