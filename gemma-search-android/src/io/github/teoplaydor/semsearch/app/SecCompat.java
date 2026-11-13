package io.github.teoplaydor.semsearch.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.os.Build;
import android.text.Html;

import java.util.ArrayList;
import java.util.List;

/**
 * Newer Android calls the secretary needs, reached by reflection (the app is compiled against Android 6's
 * android.jar, as the rest of SemSearch): notification channels, «Не беспокоить» policies with visual effects,
 * the name of a Person.
 */
final class SecCompat {
    private SecCompat() {
    }

    static final int IMPORTANCE_HIGH = 4;

    /** A channel (made if missing). bypassDnd — sounds even in «Не беспокоить» (needs the policy access). */
    static void channel(Context c, String id, String name, String description, int importance, boolean bypassDnd) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        try {
            Class<?> ch = Class.forName("android.app.NotificationChannel");
            Object existing = NotificationManager.class.getMethod("getNotificationChannel", String.class).invoke(nm, id);
            if (existing != null) return;
            Object channel = ch.getConstructor(String.class, CharSequence.class, int.class).newInstance(id, name, importance);
            ch.getMethod("setDescription", String.class).invoke(channel, description);
            if (bypassDnd) ch.getMethod("setBypassDnd", boolean.class).invoke(channel, true);
            ch.getMethod("enableVibration", boolean.class).invoke(channel, true);
            NotificationManager.class.getMethod("createNotificationChannel", ch).invoke(nm, channel);
        } catch (Exception e) {
            // older Android: no channels
        }
    }

    /** Whether the channel exists and may sound through «Не беспокоить». null — no such channel. */
    static Boolean canBypassDnd(Context c, String id) {
        if (Build.VERSION.SDK_INT < 26) return Boolean.TRUE;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        try {
            Object ch = NotificationManager.class.getMethod("getNotificationChannel", String.class).invoke(nm, id);
            if (ch == null) return null;
            return (Boolean) ch.getClass().getMethod("canBypassDnd").invoke(ch);
        } catch (Exception e) {
            return null;
        }
    }

    static void deleteChannel(Context c, String id) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        try {
            NotificationManager.class.getMethod("deleteNotificationChannel", String.class).invoke(nm, id);
        } catch (Exception ignored) {
        }
    }

    /** Ids of this app's channels. */
    static List<String> channelIds(Context c) {
        List<String> out = new ArrayList<String>();
        if (Build.VERSION.SDK_INT < 26) return out;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        try {
            List<?> l = (List<?>) NotificationManager.class.getMethod("getNotificationChannels").invoke(nm);
            for (Object ch : l) out.add((String) ch.getClass().getMethod("getId").invoke(ch));
        } catch (Exception ignored) {
        }
        return out;
    }

    static Notification.Builder builder(Context c, String channel) {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                return (Notification.Builder) Notification.Builder.class.getConstructor(Context.class, String.class).newInstance(c, channel);
            } catch (Exception ignored) {
            }
        }
        return new Notification.Builder(c);
    }

    /** NotificationManager.Policy with suppressed visual effects (Android 9+ has the 4-argument one). */
    static NotificationManager.Policy policy(int categories, int calls, int messages, int visual) {
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                return NotificationManager.Policy.class.getConstructor(int.class, int.class, int.class, int.class)
                        .newInstance(categories, calls, messages, visual);
            } catch (Exception ignored) {
            }
        }
        return new NotificationManager.Policy(categories, calls, messages);
    }

    static int suppressedVisualEffects(NotificationManager.Policy p) {
        try {
            return NotificationManager.Policy.class.getField("suppressedVisualEffects").getInt(p);
        } catch (Exception e) {
            return 0;
        }
    }

    /** A Person's name (Android 9+), or null. */
    static String personName(Object person) {
        if (person == null) return null;
        try {
            Object n = person.getClass().getMethod("getName").invoke(person);
            return n == null ? null : n.toString();
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    static String htmlText(String html) {
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                Object s = Html.class.getMethod("fromHtml", String.class, int.class).invoke(null, html, 0);
                return s.toString();
            } catch (Exception ignored) {
            }
        }
        return Html.fromHtml(html).toString();
    }

    /** registerReceiver, not exported where Android asks to say so. */
    static void register(Context c, BroadcastReceiver r, IntentFilter f) {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                Context.class.getMethod("registerReceiver", BroadcastReceiver.class, IntentFilter.class, int.class)
                        .invoke(c, r, f, 4 /* RECEIVER_NOT_EXPORTED */);
                return;
            } catch (Exception ignored) {
            }
        }
        c.registerReceiver(r, f);
    }
}
