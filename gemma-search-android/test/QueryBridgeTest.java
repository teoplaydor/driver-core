import io.github.teoplaydor.semsearch.core.QueryBridge;
import io.github.teoplaydor.semsearch.core.RussianStemmer;

import java.io.*;
import java.util.*;

/** Checks Russian query → English bridge on typical photo-search queries. usage: QueryBridgeTest assets/ru_en_lexicon.txt */
public class QueryBridgeTest {
    public static void main(String[] a) throws Exception {
        QueryBridge b = QueryBridge.load(new FileInputStream(a[0]));
        String[][] cases = {
                {"кот на диване", "cat on sofa"},
                {"Кошка спит на кровати", "cat sleeping on bed"},
                {"закат на море", "sunset on sea"},
                {"собака в парке", "dog in park"},
                {"красная машина", "red car"},
                {"чек из магазина", "receipt from store"},
                {"скриншот с текстом", "screenshot with text"},
                {"дети на пляже летом", "child on beach summer"},
                {"фото моей собаки", "photo my dog"},
                {"торт на день рождения", "cake on birthday"},
                {"ёлка и подарки", "christmas tree and gift"},
                {"горы зимой", "mountains winter"},
                {"кот с Барсиком", "cat"},
                {"найди фото котят", "photo kitten"},
                {"еда в ресторане", "food in restaurant"},
                {"iPhone на столе", "iphone on table"},
                {"ресторан", "restaurant"},
                {"в ресторанах", "restaurant"},
                {"много людей на площади", "people on square"},
                {"старый город ночью", "old city night"},
                {"голубое небо", "light blue sky"},
                {"голуби в парке", "pigeon in park"},
                {"птица летит", "bird flying"},
                {"пляж летом", "beach summer"},
                {"ванная комната", "bathroom room"},
        };
        int bad = 0;
        for (String[] c : cases) {
            QueryBridge.Result r = b.translate(c[0]);
            String got = r == null ? null : r.english;
            boolean ok = c[1].equals(got);
            if (!ok) bad++;
            System.out.println((ok ? "ok   " : "FAIL ") + c[0] + " -> " + got + (ok ? "" : "   (expected " + c[1] + ")")
                    + (r != null && !r.unknown.isEmpty() ? "   unknown=" + r.unknown : ""));
        }
        if (b.translate("Маша и Петя") != null) { bad++; System.out.println("FAIL names only should give null"); }
        if (QueryBridge.hasCyrillic("cat on sofa")) { bad++; System.out.println("FAIL hasCyrillic"); }
        System.out.println(bad == 0 ? "ALL OK" : bad + " FAILED");
        if (bad > 0) System.exit(1);
    }
}
