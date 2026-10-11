import io.github.teoplaydor.semsearch.core.PhotoEvent;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Events in the text read on photos (as an OCR writes it, with its slips): a train ticket, a boarding pass, a cinema
 * ticket, a doctor's appointment slip, a receipt with a warranty; a chat screenshot and a past ticket — nothing.
 * «Now» is Wednesday 14 October 2026, 15:20 in Moscow. All the texts are made up.
 * usage: PhotoEventTest
 */
public class PhotoEventTest {
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
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT);
        f.setTimeZone(TZ);
        return f.format(new Date(t));
    }

    static void check(String what, String text, String kind, String title, String at, long beforeMin) {
        List<PhotoEvent> es = PhotoEvent.find(text, NOW - 3_600_000L, NOW, TZ);
        PhotoEvent e = null;
        for (PhotoEvent x : es) if (kind == null || x.kind.equals(kind)) e = x;
        boolean ok = kind == null ? es.isEmpty()
                : e != null && e.title.equals(title) && fmt(e.at).equals(at) && e.before == beforeMin * 60_000L;
        if (!ok) bad++;
        System.out.println((ok ? "ok   " : "FAIL ") + what + ": " + (e == null ? (es.isEmpty() ? "nothing" : es.get(0).title)
                : e.kind + " «" + e.title + "» " + fmt(e.at) + " " + e.beforeLabel()) + (ok ? "" : "   expected " + kind + " «" + title + "» " + at));
    }

    public static void main(String[] args) {
        check("train", "ЭЛЕКТРОННЫЙ БИЛЕТ\nОАО «РЖД»\nПоезд 024А МОСКВА — КАЗАНЬ\nОтправление 21.1O.2026 14 : 35 (МСК)\n"
                + "Прибытие 22.10.2026 06:10\nВагон 05 Место 017", PhotoEvent.TRAIN, "Поезд 024А Москва — Казань", "2026-10-21 14:35", 120);
        check("boarding pass", "BOARDING PASS / ПОСАДОЧНЫЙ ТАЛОН\nAEROFLOT\nРейс SU 1234\nMOSCOW SVO → SOCHI AER\nДата 18 окт 2026\n"
                + "Вылет 07:45\nВыход на посадку B12", PhotoEvent.FLIGHT, "Рейс SU 1234", "2026-10-18 07:45", 180);
        check("cinema", "КАРО 11 Октябрь\nБилет на сеанс\n«Дюна: Часть третья»\n25.10.2026 19:30\nЗал 5 Ряд 7 Место 12",
                PhotoEvent.SHOW, "Кино: Дюна: Часть третья", "2026-10-25 19:30", 90);
        check("doctor", "Талон на приём к врачу\nСтоматолог-терапевт Иванова И.И.\nДата: 16.10.2026\nВремя: 09:20\nКабинет 214",
                PhotoEvent.DOCTOR, "Приём · стоматолог", "2026-10-16 09:20", 120);
        check("warranty", "ООО \"Техносила\"\nКассовый чек 12.03.2026 18:42\nПылесос Dyson V12\nГарантия 24 мес.\nИТОГО 45990.00",
                PhotoEvent.WARRANTY, "Гарантия (Техносила) до 12 марта 2028", "2028-03-12 10:00", 14 * 24 * 60);
        check("warranty in years", "ИП Петров\nНоутбук\nГарантийный срок 1 год\n03.09.2026", PhotoEvent.WARRANTY,
                "Гарантия (Петров) до 3 сентября 2027", "2027-09-03 10:00", 14 * 24 * 60);
        check("a chat screenshot", "Маша: встречаемся 20.10 в 19:00 у кафе?\nЯ: давай", null, null, null, 0);
        check("a past ticket", "Поезд 116С\nОтправление 02.09.2026 10:15\nВагон 3", null, null, null, 0);
        System.out.println(bad == 0 ? "PHOTO EVENTS OK" : bad + " FAILED");
        if (bad != 0) System.exit(1);
    }
}
