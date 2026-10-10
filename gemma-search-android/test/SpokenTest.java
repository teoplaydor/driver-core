import io.github.teoplaydor.semsearch.core.Spoken;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * What is said for a quick note, understood: the note's text, when to remind, how often, a list, a search. «Now» is
 * Wednesday 14 October 2026, 15:20 in Moscow; each phrase as a recognizer writes it.
 * usage: SpokenTest
 */
public class SpokenTest {
    static int bad;
    static final TimeZone TZ = TimeZone.getTimeZone("Europe/Moscow");
    static final long NOW;

    static {
        Calendar c = Calendar.getInstance(TZ);
        c.clear();
        c.set(2026, Calendar.OCTOBER, 14, 15, 20, 0);
        NOW = c.getTimeInMillis();
    }

    static String fmt(long t) {
        if (t == 0) return "—";
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm EEE", Locale.ROOT);
        f.setTimeZone(TZ);
        return f.format(new Date(t));
    }

    /** @param when "yyyy-MM-dd HH:mm", "+N" minutes from now, or null for none */
    static void check(String said, String text, String when, int repeat) {
        Spoken.Result r = Spoken.parse(said, NOW, TZ);
        long want = 0;
        if (when != null) {
            if (when.startsWith("+")) want = NOW + Long.parseLong(when.substring(1)) * 60_000L;
            else {
                try {
                    SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT);
                    f.setTimeZone(TZ);
                    want = f.parse(when).getTime();
                } catch (Exception e) {
                    throw new IllegalArgumentException(when);
                }
            }
        }
        boolean ok = r.query == null && r.text.equals(text) && r.remindAt == want && r.repeat == repeat;
        if (!ok) bad++;
        System.out.println((ok ? "ok   " : "FAIL ") + "«" + said + "» → «" + r.text.replace("\n", " | ") + "» " + fmt(r.remindAt)
                + (r.repeat != 0 ? " " + Spoken.repeatLabel(r.repeat) : "")
                + (ok ? "" : "   expected «" + text.replace("\n", " | ") + "» " + fmt(want) + (repeat != 0 ? " " + Spoken.repeatLabel(repeat) : "")));
    }

    static void search(String said, String query) {
        Spoken.Result r = Spoken.parse(said, NOW, TZ);
        boolean ok = query.equals(r.query);
        if (!ok) bad++;
        System.out.println((ok ? "ok   " : "FAIL ") + "«" + said + "» → search «" + r.query + "»");
    }

    public static void main(String[] args) {
        System.out.println("now " + fmt(NOW));
        // when
        check("Напомни завтра в 9 позвонить маме", "Позвонить маме", "2026-10-15 09:00", 0);
        check("напомни мне через 20 минут выключить духовку", "Выключить духовку", "+20", 0);
        check("через полчаса забрать посылку", "Забрать посылку", "+30", 0);
        check("напомни через 2 часа", "Напоминание", "+120", 0);
        check("в пятницу в 18:30 встреча с Андреем", "Встреча с Андреем", "2026-10-16 18:30", 0);
        check("напомни в половине восьмого вечера погулять с собакой", "Погулять с собакой", "2026-10-14 19:30", 0);
        check("в четверть девятого полить цветы", "Полить цветы", "2026-10-14 20:15", 0);
        check("без четверти пять созвон", "Созвон", "2026-10-14 16:45", 0);
        check("завтра утром купить хлеб", "Купить хлеб", "2026-10-15 09:00", 0);
        check("сегодня вечером забрать ребёнка из сада", "Забрать ребёнка из сада", "2026-10-14 19:00", 0);
        check("в субботу на рынок", "На рынок", "2026-10-17 09:00", 0);
        check("на выходных разобрать шкаф", "Разобрать шкаф", "2026-10-17 09:00", 0);
        check("в 7 вечера ужин с родителями", "Ужин с родителями", "2026-10-14 19:00", 0);
        check("в 3 позвонить Саше", "Позвонить Саше", "2026-10-15 15:00", 0);
        check("в 9", "Напоминание", "2026-10-14 21:00", 0);
        check("встреча в 12 дня в кафе", "Встреча в кафе", "2026-10-15 12:00", 0);
        check("15 марта в 10:30 день рождения Оли", "День рождения Оли", "2027-03-15 10:30", 0);
        check("21.10 в 14:00 стоматолог", "Стоматолог", "2026-10-21 14:00", 0);
        check("1 января в полночь поздравить всех", "Поздравить всех", "2027-01-01 00:00", 0);
        check("через неделю сдать книги в библиотеку", "Сдать книги в библиотеку", "2026-10-21 09:00", 0);
        check("напомни через 3 дня в 10 оплатить интернет", "Оплатить интернет", "2026-10-17 10:00", 0);
        check("напомни мне пожалуйста завтра в 8:15 что нужно взять зонт", "Нужно взять зонт", "2026-10-15 08:15", 0);
        check("послезавтра в 6 утра такси в аэропорт", "Такси в аэропорт", "2026-10-16 06:00", 0);
        check("через пять минут проверить чайник", "Проверить чайник", "+5", 0);
        check("напомни через двадцать пять минут снять пиццу", "Снять пиццу", "+25", 0);
        check("25 числа заплатить за квартиру", "Заплатить за квартиру", "2026-10-25 09:00", 0);
        check("в среду встреча", "Встреча", "2026-10-21 09:00", 0);
        // how often
        check("напомни каждый день в 8 утра пить таблетки", "Пить таблетки", "2026-10-15 08:00", Spoken.DAILY);
        check("по будням в 7:30 зарядка", "Зарядка", "2026-10-15 07:30", Spoken.WEEKDAYS);
        check("каждый понедельник в 10 планёрка", "Планёрка", "2026-10-19 10:00", Spoken.WEEKLY);
        check("каждое утро витамины", "Витамины", "2026-10-15 09:00", Spoken.DAILY);
        check("каждый вечер в 22 выключить роутер", "Выключить роутер", "2026-10-14 22:00", Spoken.DAILY);
        check("каждый месяц 5 числа показания счётчиков", "Показания счётчиков", "2026-11-05 09:00", Spoken.MONTHLY);
        check("по средам в 18 бассейн", "Бассейн", "2026-10-14 18:00", Spoken.WEEKLY);
        // lists, the rest
        check("Купить молоко, хлеб и сыр", "Купить:\n☐ молоко\n☐ хлеб\n☐ сыр", null, 0);
        check("ну короче надо сделать отчёт, позвонить в банк и записаться к врачу",
                "Надо сделать:\n☐ отчёт\n☐ позвонить в банк\n☐ записаться к врачу", null, 0);
        check("молоко, хлеб, яйца, сыр", "☐ молоко\n☐ хлеб\n☐ яйца\n☐ сыр", null, 0);
        check("завтра купить хлеб, масло и кофе", "Купить:\n☐ хлеб\n☐ масло\n☐ кофе", "2026-10-15 09:00", 0);
        check("зайти в 3 магазина за подарками", "Зайти в 3 магазина за подарками", null, 0);
        check("запиши код от домофона 4512", "Код от домофона 4512", null, 0);
        check("идея: сделать приложение для заметок голосом", "Идея: сделать приложение для заметок голосом", null, 0);
        check("Позвонить в банк и уточнить про кредит", "Позвонить в банк и уточнить про кредит", null, 0);
        Spoken.Result want = Spoken.parse("напомни позвонить в банк", NOW, TZ);
        boolean asks = want.wantsReminder && want.remindAt == 0 && want.text.equals("Позвонить в банк");
        if (!asks) bad++;
        System.out.println((asks ? "ok   " : "FAIL ") + "«напомни» without a time: the time is to be asked");
        // a search
        search("найди фото с котом", "фото с котом");
        search("Покажи чеки из кафе", "чеки из кафе");
        search("найди мне договор аренды", "договор аренды");
        // the next of a repeating reminder
        long mon = Spoken.parse("каждый понедельник в 10 планёрка", NOW, TZ).remindAt;
        long fri = Spoken.parse("по будням в 7:30 зарядка", NOW + 2 * 86_400_000L, TZ).remindAt; // asked on Friday afternoon
        boolean nx = fmt(Spoken.next(mon, Spoken.WEEKLY, TZ)).startsWith("2026-10-26 10:00")
                && fmt(fri).startsWith("2026-10-19 07:30") && fmt(Spoken.next(fri, Spoken.WEEKDAYS, TZ)).startsWith("2026-10-20 07:30")
                && fmt(Spoken.next(fri - 3 * 86_400_000L, Spoken.WEEKDAYS, TZ)).startsWith("2026-10-19 07:30");
        if (!nx) bad++;
        System.out.println((nx ? "ok   " : "FAIL ") + "next: a week later; asked on Friday, the weekdays begin Monday; after Friday comes Monday");
        System.out.println(bad == 0 ? "SPOKEN OK" : bad + " FAILED");
        if (bad != 0) System.exit(1);
    }
}
