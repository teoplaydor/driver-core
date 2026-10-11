package io.github.teoplaydor.semsearch.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.location.LocationManager;

/**
 * The phone came to a note's place (or left it, for «когда выйду из дома»): the reminder shows, and the note no
 * longer waits there.
 */
public final class PlaceAlert extends BroadcastReceiver {
    static final String ACTION = "io.github.teoplaydor.semsearch.PLACE";

    @Override
    public void onReceive(final Context c, Intent i) {
        final long id = i.getLongExtra(Reminders.EXTRA_NOTE, -1);
        if (id < 0) return;
        final boolean entering = i.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, true);
        final PendingResult held = goAsync();
        final Context app = c.getApplicationContext();
        Engine.get(app).afterOpen(new Runnable() {
            @Override
            public void run() {
                try {
                    IndexStore s = Engine.get(app).store();
                    IndexStore.Item n = s == null ? null : s.find(id);
                    if (n == null || n.place == null) {
                        Places.disarm(app, id);
                        return;
                    }
                    if (entering == n.leaving) return; // the other way: not yet
                    Reminders.notify(app, n.id, n.body, 0, io.github.teoplaydor.semsearch.core.Spoken.ONCE);
                    Places.disarm(app, id);
                    s.setPlace(n, null, false);
                    Engine.get(app).notifyChanged();
                } finally {
                    if (held != null) held.finish();
                }
            }
        });
    }
}
