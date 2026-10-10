package io.github.teoplaydor.semsearch.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.widget.RemoteViews;

import io.github.teoplaydor.semsearch.R;

/** The home screen widget: «Сказать заметку» (the microphone at once) and a button to type one. */
public final class QuickWidget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) m.updateAppWidget(id, views(c));
    }

    static RemoteViews views(Context c) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_quick);
        v.setOnClickPendingIntent(R.id.widget_voice, PendingIntent.getActivity(c, 1, QuickNotes.intent(c, false),
                PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000 /* IMMUTABLE */));
        v.setOnClickPendingIntent(R.id.widget_type, PendingIntent.getActivity(c, 2, QuickNotes.intent(c, true),
                PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000));
        return v;
    }
}
