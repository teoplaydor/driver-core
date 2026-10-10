package io.github.teoplaydor.semsearch.app;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** «Заметка голосом» in the quick settings: the quick note at once, the lock screen too. */
public final class QuickTile extends TileService {
    @Override
    public void onStartListening() {
        Tile t = getQsTile();
        if (t == null) return;
        t.setState(Tile.STATE_INACTIVE);
        t.updateTile();
    }

    @Override
    public void onClick() {
        Intent i = QuickNotes.intent(this, false).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= 34) {
            // Android 14 takes only a PendingIntent here
            try {
                PendingIntent pi = PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000 /* IMMUTABLE */);
                TileService.class.getMethod("startActivityAndCollapse", PendingIntent.class).invoke(this, pi);
                return;
            } catch (Exception ignored) {
                // the older call below
            }
        }
        startActivityAndCollapse(i);
    }
}
