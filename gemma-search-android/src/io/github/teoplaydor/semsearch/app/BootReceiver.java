package io.github.teoplaydor.semsearch.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * After a reboot: resumes indexing-while-idle if it was on (the background jobs are persisted by themselves); the
 * notes' reminders set again (the engine does that as it opens its index; alarms do not outlive a reboot).
 */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())) return;
        if (IdleIndex.enabled(c) && AutoIndex.hasMediaAccess(c)) IdleIndex.start(c);
        final PendingResult held = goAsync();
        Engine.get(c).afterOpen(new Runnable() {
            @Override
            public void run() {
                if (held != null) held.finish();
            }
        });
    }
}
