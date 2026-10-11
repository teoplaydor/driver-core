package io.github.teoplaydor.semsearch.app;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;

import java.util.concurrent.Executor;

import io.github.teoplaydor.semsearch.R;

/**
 * The ways to open a quick note, set up from the settings: the app as the phone's assistant (holding the power or home
 * button), its own «Голосовая заметка» icon (what a side key's double press or Quick Tap can open), the tile in the
 * quick settings, the widget.
 */
final class QuickNotes {
    private QuickNotes() {
    }

    static Intent intent(Context c, boolean type) {
        Intent i = new Intent(c, QuickNoteActivity.class).setAction(QuickNoteActivity.ACTION);
        if (type) i.putExtra(QuickNoteActivity.EXTRA_TYPE, true);
        return i;
    }

    // ------------------------------------------------------------------ the assistant

    /** The app is the phone's assistant (Android 10+ tells; null: unknown). */
    static Boolean isAssistant(Context c) {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                Object rm = c.getSystemService(Class.forName("android.app.role.RoleManager"));
                return (Boolean) rm.getClass().getMethod("isRoleHeld", String.class).invoke(rm, "android.app.role.ASSISTANT");
            } catch (Exception ignored) {
                // the setting below
            }
        }
        try {
            String a = Settings.Secure.getString(c.getContentResolver(), "assistant");
            return a != null && a.startsWith(c.getPackageName() + "/");
        } catch (Exception e) {
            return null;
        }
    }

    /** Where the phone's assistant is chosen («Цифровой помощник»), as near as the phone lets an app open. */
    static void openAssistantSettings(Activity a) {
        String[] where = {"android.settings.VOICE_INPUT_SETTINGS", "android.settings.MANAGE_DEFAULT_APPS_SETTINGS", Settings.ACTION_SETTINGS};
        for (String w : where) {
            try {
                a.startActivity(new Intent(w));
                return;
            } catch (Exception ignored) {
                // the next
            }
        }
    }

    // ------------------------------------------------------------------ the icon

    private static ComponentName alias(Context c) {
        return new ComponentName(c.getPackageName(), "io.github.teoplaydor.semsearch.app.VoiceNote");
    }

    static boolean iconShown(Context c) {
        return c.getPackageManager().getComponentEnabledSetting(alias(c)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
    }

    static void showIcon(Context c, boolean on) {
        c.getPackageManager().setComponentEnabledSetting(alias(c), on ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                : PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP);
    }

    // ------------------------------------------------------------------ the shade, a watch

    static final String PREF_SHADE = "quick_shade";
    private static final String CHANNEL = "quick_note";

    static boolean shadeOn(Context c) {
        return Engine.get(c).prefs().getBoolean(PREF_SHADE, false);
    }

    static void setShade(Context c, boolean on) {
        Engine.get(c).prefs().edit().putBoolean(PREF_SHADE, on).apply();
        if (on) showShade(c, null);
        else {
            android.app.NotificationManager nm = (android.app.NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel("quick", 1);
        }
    }

    /**
     * «Быстрая заметка» in the shade (quiet, at the bottom), answered with text — or by voice on a watch, where phone
     * notifications come with their replies. {@code status}: what the last reply became.
     */
    static void showShade(Context c, CharSequence status) {
        if (!shadeOn(c)) return;
        android.app.NotificationManager nm = (android.app.NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        android.app.Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                Class<?> ch = Class.forName("android.app.NotificationChannel");
                Object channel = ch.getConstructor(String.class, CharSequence.class, int.class)
                        .newInstance(CHANNEL, "Быстрая заметка в шторке", 2 /* IMPORTANCE_LOW */);
                android.app.NotificationManager.class.getMethod("createNotificationChannel", ch).invoke(nm, channel);
                b = (android.app.Notification.Builder) android.app.Notification.Builder.class
                        .getConstructor(Context.class, String.class).newInstance(c, CHANNEL);
            } catch (Exception e) {
                b = new android.app.Notification.Builder(c);
            }
        } else {
            b = new android.app.Notification.Builder(c);
        }
        android.app.RemoteInput in = new android.app.RemoteInput.Builder(QuickReply.KEY).setLabel("Заметка или напоминание").build();
        // a reply needs a mutable PendingIntent (the text is put into it)
        int mutable = Build.VERSION.SDK_INT >= 31 ? 0x02000000 /* FLAG_MUTABLE */ : 0;
        android.app.PendingIntent reply = android.app.PendingIntent.getBroadcast(c, 0, new Intent(c, QuickReply.class),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | mutable);
        android.app.PendingIntent voice = android.app.PendingIntent.getActivity(c, 3, intent(c, false),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000);
        b.setSmallIcon(R.drawable.ic_tile_mic)
                .setContentTitle("Быстрая заметка")
                .setContentText(status != null ? status : "Ответьте — текст станет заметкой: «завтра в 9 позвонить маме»")
                .setStyle(new android.app.Notification.BigTextStyle().bigText(status != null ? status
                        : "Ответьте — текст станет заметкой: «завтра в 9 позвонить маме». На часах — голосом."))
                .setContentIntent(voice)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setPriority(android.app.Notification.PRIORITY_LOW)
                .addAction(new android.app.Notification.Action.Builder(0, "Заметка", reply).addRemoteInput(in).build())
                .addAction(new android.app.Notification.Action.Builder(0, "Голосом", voice).build());
        try {
            nm.notify("quick", 1, b.build());
        } catch (SecurityException e) {
            // notifications not allowed
        }
    }

    // ------------------------------------------------------------------ the tile, the widget

    /** Android 13+: the system's own «add this tile?»; false when the phone can't ask (then by hand). */
    static boolean askForTile(final Activity a, final Runnable added) {
        if (Build.VERSION.SDK_INT < 33) return false;
        try {
            Object sbm = a.getSystemService(Class.forName("android.app.StatusBarManager"));
            Class<?> consumer = Class.forName("java.util.function.Consumer");
            Object cb = java.lang.reflect.Proxy.newProxyInstance(a.getClassLoader(), new Class<?>[]{consumer},
                    new java.lang.reflect.InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) {
                            if (!"accept".equals(m.getName())) return m.getName().equals("hashCode") ? System.identityHashCode(proxy)
                                    : m.getName().equals("equals") ? (Object) (proxy == args[0]) : null;
                            // 0, 1 (TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED / ALREADY_ADDED), 2 (ADDED)
                            int r = args != null && args[0] instanceof Integer ? (Integer) args[0] : -1;
                            if ((r == 1 || r == 2) && added != null) a.runOnUiThread(added);
                            return null;
                        }
                    });
            Executor main = new Executor() {
                @Override
                public void execute(Runnable r) {
                    a.runOnUiThread(r);
                }
            };
            sbm.getClass().getMethod("requestAddTileService", ComponentName.class, CharSequence.class,
                    android.graphics.drawable.Icon.class, Executor.class, consumer)
                    .invoke(sbm, new ComponentName(a, QuickTile.class), a.getString(R.string.tile_label),
                            android.graphics.drawable.Icon.createWithResource(a, R.drawable.ic_tile_mic), main, cb);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Android 8+: the launcher's own «add this widget?»; false when it can't ask (then by hand). */
    static boolean askForWidget(Context c) {
        if (Build.VERSION.SDK_INT < 26) return false;
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            if (!(Boolean) AppWidgetManager.class.getMethod("isRequestPinAppWidgetSupported").invoke(m)) return false;
            return (Boolean) AppWidgetManager.class.getMethod("requestPinAppWidget", ComponentName.class, android.os.Bundle.class,
                    android.app.PendingIntent.class).invoke(m, new ComponentName(c, QuickWidget.class), null, null);
        } catch (Exception e) {
            return false;
        }
    }
}
