package io.github.teoplaydor.semsearch.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** The «разомнись» check, fired by the alarm every few minutes (it reads only usage statistics, then re-arms). */
public final class WellbeingCheck extends BroadcastReceiver {
    @Override
    public void onReceive(final Context c, Intent i) {
        final PendingResult held = goAsync();
        final Context app = c.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Wellbeing.check(app);
                } finally {
                    if (held != null) held.finish();
                }
            }
        }, "wellbeing").start();
    }
}
