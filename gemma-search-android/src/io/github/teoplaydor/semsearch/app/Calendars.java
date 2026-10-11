package io.github.teoplaydor.semsearch.app;

import android.app.Activity;
import android.content.Intent;
import android.provider.CalendarContract;
import android.widget.Toast;

import io.github.teoplaydor.semsearch.core.NoteText;
import io.github.teoplaydor.semsearch.core.Spoken;

/**
 * A note with a time, to the phone's calendar: the calendar app opens with the event filled in (its title, the time,
 * an hour long, repeating as the reminder does) — one tap there keeps it. No calendar permission is needed.
 */
final class Calendars {
    private Calendars() {
    }

    static Intent event(String text, long at, int repeat) {
        Intent i = new Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, at)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, at + 3_600_000L)
                .putExtra(CalendarContract.Events.TITLE, NoteText.title(text, 80))
                .putExtra(CalendarContract.Events.DESCRIPTION, NoteText.plain(text));
        String rule = Spoken.rrule(repeat);
        if (rule != null) i.putExtra(CalendarContract.Events.RRULE, rule);
        return i;
    }

    static void add(Activity a, IndexStore.Item note) {
        if (note == null || note.remind <= 0) return;
        try {
            a.startActivity(event(note.body, note.remind, note.repeat));
        } catch (Exception e) {
            Toast.makeText(a, "На телефоне нет календаря", Toast.LENGTH_LONG).show();
        }
    }
}
