import io.github.teoplaydor.semsearch.core.Answers;

import java.util.ArrayList;
import java.util.List;

/**
 * Questions to the notes answered by their own lines (no model): the line with the question's words, its source's
 * title helping; a shopping list's items left to tick; a document's sentence; a photo's text. Made-up notes.
 * usage: AnswersTest
 */
public class AnswersTest {
    static int bad;
    static final List<String[]> SOURCES = new ArrayList<String[]>();

    static {
        SOURCES.add(new String[]{null, "Пароль от домашнего Wi-Fi: Lisa2024! — сеть Keenetic-5G"});
        SOURCES.add(new String[]{null, "Код домофона у Саши: 47К1290, подъезд 3"});
        SOURCES.add(new String[]{null, "Купить:\n☐ молоко\n☑ хлеб\n☐ сыр\n☐ кофе в зёрнах"});
        SOURCES.add(new String[]{null, "День рождения Оли — 15 марта"});
        SOURCES.add(new String[]{"Договор аренды.pdf", "Арендатор вносит плату до 10 числа каждого месяца. Размер платы составляет "
                + "35 000 рублей в месяц. Договор заключён на 11 месяцев."});
        SOURCES.add(new String[]{null, "Машина на парковке ТЦ, уровень -2, место 148"});
        SOURCES.add(new String[]{"фото", "ООО \"Техносила\"\nПылесос Dyson V12\nГарантия 24 мес."});
    }

    static void check(String q, String want, int source) {
        List<Answers.Line> ls = Answers.best(q, SOURCES, 3);
        boolean ok = !ls.isEmpty() && ls.get(0).text.equals(want) && ls.get(0).source == source;
        if (!ok) bad++;
        System.out.println((ok ? "ok   " : "FAIL ") + "«" + q + "» → " + (ls.isEmpty() ? "nothing" : "«" + ls.get(0).text + "» (" + ls.get(0).source + ")")
                + (ok ? "" : "   expected «" + want + "»"));
    }

    public static void main(String[] args) {
        check("Какой код от домофона у Саши?", "Код домофона у Саши: 47К1290, подъезд 3", 1);
        check("Какой пароль от вайфая?", "Пароль от домашнего Wi-Fi: Lisa2024! — сеть Keenetic-5G", 0);
        check("Когда день рождения Оли?", "День рождения Оли — 15 марта", 3);
        check("Сколько платить за аренду?", "Размер платы составляет 35 000 рублей в месяц.", 4);
        check("Где машина?", "Машина на парковке ТЦ, уровень -2, место 148", 5);
        check("Какая гарантия на пылесос?", "Гарантия 24 мес.", 6);
        boolean list = Answers.asksList("Что нужно купить?") && Answers.asksList("что осталось в списке покупок")
                && !Answers.asksList("Какой код домофона?")
                && "молоко, сыр и кофе в зёрнах".equals(Answers.openItems(SOURCES.get(2)[1]));
        if (!list) bad++;
        System.out.println((list ? "ok   " : "FAIL ") + "«Что нужно купить?» → " + Answers.openItems(SOURCES.get(2)[1]));
        boolean none = Answers.best("Какой номер паспорта?", SOURCES, 3).isEmpty();
        if (!none) bad++;
        System.out.println((none ? "ok   " : "FAIL ") + "not in the notes: nothing");
        String p = Answers.prompt("Какой код?", java.util.Arrays.asList("Код домофона у Саши: 47К1290", "Пароль Wi-Fi"));
        boolean pr = p.contains("1. Код домофона у Саши: 47К1290\n2. Пароль Wi-Fi\n") && p.endsWith("Вопрос: Какой код?");
        if (!pr) bad++;
        System.out.println((pr ? "ok   " : "FAIL ") + "the model's prompt: numbered lines, then the question");
        System.out.println(bad == 0 ? "ANSWERS OK" : bad + " FAILED");
        if (bad != 0) System.exit(1);
    }
}
