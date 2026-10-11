package io.github.teoplaydor.semsearch.app;

import android.content.Context;

import java.util.TimeZone;

import io.github.teoplaydor.semsearch.core.NoteText;
import io.github.teoplaydor.semsearch.core.Spoken;

/**
 * What was said or typed somewhere without a screen of ours (a reply to the notification in the shade, a watch), made
 * into a note: with its reminder, «за день до дня рождения Оли» with the date found in the notes, at a place; a
 * question answered from the notes. One line comes back to show where it was said.
 */
final class Capture {
    private Capture() {
    }

    static void save(final Context c, String said, final Engine.Callback<String> done) {
        final Engine e = Engine.get(c);
        final Spoken.Result r = Spoken.parse(said, System.currentTimeMillis(), TimeZone.getDefault());
        if (r.query != null) {
            done.done("Поиск «" + r.query + "» — в приложении", null);
            return;
        }
        if (r.question != null) {
            e.answer(r.question, new Engine.Callback<Engine.Answer>() {
                @Override
                public void done(Engine.Answer a, Exception err) {
                    done.done(a != null && a.text != null ? a.text : "В заметках ответа нет", null);
                }
            });
            return;
        }
        if (r.text.trim().isEmpty()) {
            done.done("Пусто — ничего не сохранено", null);
            return;
        }
        if (r.beforeEvent != null) {
            e.findEventDate(r.beforeEvent, new Engine.Callback<Long>() {
                @Override
                public void done(Long date, Exception err) {
                    long at = date != null && date > 0 ? Math.max(date - r.beforeMs, System.currentTimeMillis() + 60_000L) : 0;
                    add(c, r.text, at, r.repeat, null, false, at > 0 ? null : "дата «" + r.beforeEvent + "» не найдена в заметках", done);
                }
            });
            return;
        }
        add(c, r.text, r.remindAt, r.repeat, r.place, r.leaving, null, done);
    }

    private static void add(final Context c, String text, final long at, final int repeat, final String place, final boolean leaving,
                            final String warn, final Engine.Callback<String> done) {
        Engine.get(c).addNote(text, null, false, at, repeat, new Engine.Callback<IndexStore.Item>() {
            @Override
            public void done(IndexStore.Item it, Exception err) {
                if (err != null || it == null) {
                    done.done("Не сохранилось" + (err != null && err.getMessage() != null ? ": " + err.getMessage() : ""), null);
                    return;
                }
                String line = "Сохранено: " + NoteText.title(it.body, 40);
                if (at > 0) line += " · напомнит " + NoteEditor.when(at) + (repeat != Spoken.ONCE ? ", " + Spoken.repeatLabel(repeat) : "");
                if (place != null) {
                    Places.Place p = Places.find(c, place);
                    if (p != null) {
                        Engine.get(c).setPlace(it, p.name, leaving);
                        line += " · " + (leaving ? "уходя: " : "у места: ") + p.name;
                    } else {
                        line += " · место «" + place + "» ещё не сохранено";
                    }
                }
                if (warn != null) line += " · " + warn;
                done.done(line, null);
            }
        });
    }
}
