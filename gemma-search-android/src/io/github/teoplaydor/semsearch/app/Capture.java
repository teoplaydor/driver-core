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

    /** What became of it: the line, and the note made (null — none: a question answered, a search, nothing said). */
    interface Saved {
        void saved(String line, IndexStore.Item note);
    }

    static void save(final Context c, String said, final Engine.Callback<String> done) {
        save(c, said, null, new Saved() {
            @Override
            public void saved(String line, IndexStore.Item note) {
                done.done(line, null);
            }
        });
    }

    /** The same, with the voice it was said in (a WAV file's bytes) kept with the note; null — none. */
    static void save(final Context c, String said, final byte[] voice, final Saved done) {
        final Engine e = Engine.get(c);
        final Spoken.Result r = Spoken.parse(said, System.currentTimeMillis(), TimeZone.getDefault());
        if (r.query != null) {
            done.saved("Поиск «" + r.query + "» — в приложении", null);
            return;
        }
        if (r.question != null) {
            e.answer(r.question, new Engine.Callback<Engine.Answer>() {
                @Override
                public void done(Engine.Answer a, Exception err) {
                    done.saved(a != null && a.text != null ? a.text : "В заметках ответа нет", null);
                }
            });
            return;
        }
        if (r.text.trim().isEmpty()) {
            done.saved("Пусто — ничего не сохранено", null);
            return;
        }
        if (r.beforeEvent != null) {
            e.findEventDate(r.beforeEvent, new Engine.Callback<Long>() {
                @Override
                public void done(Long date, Exception err) {
                    long at = date != null && date > 0 ? Math.max(date - r.beforeMs, System.currentTimeMillis() + 60_000L) : 0;
                    add(c, r.text, at, r.repeat, null, false, at > 0 ? null : "дата «" + r.beforeEvent + "» не найдена в заметках", voice,
                            done);
                }
            });
            return;
        }
        add(c, r.text, r.remindAt, r.repeat, r.place, r.leaving, null, voice, done);
    }

    private static void add(final Context c, String text, final long at, final int repeat, final String place, final boolean leaving,
                            final String warn, final byte[] voice, final Saved done) {
        Engine.get(c).addNote(text, null, false, at, repeat, new Engine.Callback<IndexStore.Item>() {
            @Override
            public void done(IndexStore.Item it, Exception err) {
                if (err != null || it == null) {
                    done.saved("Не сохранилось" + (err != null && err.getMessage() != null ? ": " + err.getMessage() : ""), null);
                    return;
                }
                if (voice != null) Engine.get(c).setVoice(it, voice);
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
                done.saved(line, it);
            }
        });
    }
}
