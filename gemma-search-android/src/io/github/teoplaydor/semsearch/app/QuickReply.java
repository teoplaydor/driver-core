package io.github.teoplaydor.semsearch.app;

import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/**
 * A reply to the «Быстрая заметка» notification — typed in the shade, or said on a watch (Wear OS and Galaxy Watch
 * show such a reply as a microphone): it becomes a note, the notification says what was kept.
 */
public final class QuickReply extends BroadcastReceiver {
    static final String KEY = "quick_text";

    @Override
    public void onReceive(final Context c, Intent i) {
        Bundle r = RemoteInput.getResultsFromIntent(i);
        CharSequence said = r == null ? null : r.getCharSequence(KEY);
        if (said == null || said.toString().trim().isEmpty()) {
            QuickNotes.showShade(c, null);
            return;
        }
        final PendingResult held = goAsync();
        Capture.save(c, said.toString(), new Engine.Callback<String>() {
            @Override
            public void done(String line, Exception e) {
                QuickNotes.showShade(c, line);
                if (held != null) held.finish();
            }
        });
    }
}
