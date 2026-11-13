package io.github.teoplaydor.semsearch.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.IntentFilter;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import io.github.teoplaydor.semsearch.R;
import io.github.teoplaydor.semsearch.core.MiniJson;

/**
 * The secretary's eyes (the server version's Android client, Listener.java, now with the server inside): while the
 * app has notification access Android keeps this running. Every notification is read whole (a messenger's messages
 * with their senders) and handed to the secretary; in «Тишина» it also says at once what to do with it: the important
 * shown again by the secretary with sound (a copy with the original's icon and buttons), noise removed, the rest
 * hidden by «Не беспокоить» until Тишина ends. A notification that disappears is evidence too: tapped (the chat
 * opened), cleared by the messenger (read there), swiped away. The secretary's loop runs while this lives.
 */
public class SecListener extends NotificationListenerService {
    static final String TAG = SecHub.TAG;
    static volatile SecListener instance;
    /** Android's reasons a notification went (API 26 constants). */
    static final int REASON_CLICK = 1, REASON_CANCEL = 2, REASON_APP_CANCEL = 8;
    private static final Pattern OTP = Pattern.compile(
            "(код|code|пароль|password)\\D{0,25}\\d{4,8}|никому\\s+не\\s+(сообщайте|говорите|называйте)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    // Android 15+ hides the text of notifications with codes from other apps — known by the stub
    private static final Pattern REDACTED = Pattern.compile("(content hidden|содержимое скрыто|скрыт[оа]? систем)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    static final class Info {
        String pkg, app, title, text, conversation;
        PendingIntent open;
        Notification orig; // the original: its icons and buttons («Ответить», «Прочитано»…)
        boolean alerted, initial;
    }

    private SecHub hub;
    private HandlerThread thread;
    Handler handler; // the tests idle its looper
    private final Map<String, Boolean> recent = new LinkedHashMap<String, Boolean>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) {
            return size() > 500;
        }
    };
    private final Map<String, Info> infos = new LinkedHashMap<String, Info>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Info> e) {
            return size() > 300;
        }
    };
    /** Notifications gathered for one pass (several at once — one look by the model). */
    private final List<Object> pending = new ArrayList<Object>();
    private SecAsk.Unlock unlock;

    @Override
    public void onListenerConnected() {
        instance = this;
        hub = SecHub.get(this);
        hub.ensureChannels();
        thread = new HandlerThread("sec-listen");
        thread.start();
        handler = new Handler(thread.getLooper());
        // what already hangs in the shade (also from before the install) — read too; repeats are dropped
        handler.post(new Runnable() {
            public void run() {
                try {
                    StatusBarNotification[] active = getActiveNotifications();
                    if (active != null) for (StatusBarNotification sbn : active) handle(sbn, true);
                } catch (Exception e) {
                    Log.w(TAG, "active notifications", e);
                }
            }
        });
        hub.startLoop();
        // the screen unlocked — ask «Сделал?» about the tasks it is time to
        unlock = new SecAsk.Unlock();
        SecCompat.register(this, unlock, new IntentFilter(Intent.ACTION_USER_PRESENT));
    }

    // Android 7's (the jar compiled against is Android 6's)
    public void onListenerDisconnected() {
        stop();
    }

    @Override
    public void onDestroy() {
        stop();
        super.onDestroy();
    }

    private void stop() {
        if (instance == this) instance = null;
        if (unlock != null) {
            try {
                unregisterReceiver(unlock);
            } catch (Exception ignored) {
            }
            unlock = null;
        }
        if (thread != null) {
            thread.quitSafely();
            thread = null;
            if (hub != null) hub.stopLoop();
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        handle(sbn, false);
    }

    private void handle(StatusBarNotification sbn, boolean initial) {
        try {
            Map<String, Object> item = describe(sbn);
            if (item == null) return;
            Map<String, Object> copy = new LinkedHashMap<String, Object>(item);
            copy.remove("posted");
            String fingerprint = io.github.teoplaydor.semsearch.secretary.SecUtil.sha1Hex(MiniJson.write(copy));
            synchronized (recent) {
                if (recent.put(fingerprint, Boolean.TRUE) != null) return; // the same notification again
            }
            Info info = new Info();
            info.pkg = sbn.getPackageName();
            info.conversation = (String) item.get("conversation");
            info.app = (String) item.get("app");
            info.title = (String) item.get("title");
            info.text = lastText(item);
            info.open = sbn.getNotification().contentIntent;
            info.orig = sbn.getNotification();
            info.initial = initial;
            synchronized (infos) {
                infos.put(sbn.getKey(), info);
            }
            // a confirmation code is awaited right now — shown at once, before any reading
            String all = info.title + " " + info.text;
            if (hub.focus() && !initial && (OTP.matcher(all).find() || REDACTED.matcher(all).find())) {
                if (!OTP.matcher(all).find()) info.text = "Код подтверждения — нажми, чтобы открыть";
                info.alerted = true;
                postAlert(this, hub, sbn.getKey(), info);
            }
            enqueue(item);
        } catch (Exception e) {
            Log.w(TAG, "notification", e);
        }
    }

    private void enqueue(Map<String, Object> item) {
        Handler h = handler;
        if (h == null) return;
        synchronized (pending) {
            pending.add(item);
            if (pending.size() > 1) return; // a pass is already coming
        }
        h.postDelayed(pass, 300);
    }

    /** One pass: the gathered notifications into the secretary; in Тишина — the verdicts, applied. */
    private final Runnable pass = new Runnable() {
        public void run() {
            List<Object> items;
            synchronized (pending) {
                items = new ArrayList<Object>(pending);
                pending.clear();
            }
            if (items.isEmpty()) return;
            try {
                hub.sec.phone.ingest(items);
                if (hub.focus()) {
                    Map<String, String> verdicts = hub.sec.phone.verdicts(items);
                    for (Map.Entry<String, String> v : verdicts.entrySet()) applyVerdict(v.getKey(), v.getValue());
                }
            } catch (Exception e) {
                Log.w(TAG, "pass", e);
            }
        }
    };

    // onNotificationRemoved(sbn, rankingMap, reason) is Android 8's; the jar compiled against has only the older ones
    public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap, int reason) {
        Info info;
        synchronized (infos) {
            info = infos.get(sbn.getKey());
        }
        if (info == null) return;
        // the original read or swiped — our copy goes too
        if (info.alerted) ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(sbn.getKey().hashCode());
        // why it went — evidence of «did the owner do it»; our own clean-ups are not counted
        String why = reason == REASON_CLICK ? "click" : reason == REASON_APP_CANCEL ? "app_cancel" : reason == REASON_CANCEL ? "cancel" : null;
        if (why == null) return;
        Map<String, Object> o = new LinkedHashMap<String, Object>();
        o.put("type", "removed");
        o.put("package", info.pkg);
        o.put("app", info.app);
        o.put("title", info.title);
        o.put("conversation", info.conversation);
        o.put("key", sbn.getKey());
        o.put("reason", why);
        o.put("posted", System.currentTimeMillis());
        enqueue(o);
    }

    static String lastText(Map<String, Object> item) {
        List<Object> m = MiniJson.arr(item.get("messages"));
        if (m != null && !m.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = Math.max(0, m.size() - 3); i < m.size(); i++) {
                Map<String, Object> x = MiniJson.obj(m.get(i));
                if (x == null) continue;
                if (sb.length() > 0) sb.append('\n');
                String s = String.valueOf(x.get("sender"));
                if (Boolean.TRUE.equals(item.get("is_group")) && !s.isEmpty()) sb.append(s).append(": ");
                sb.append(x.get("text"));
            }
            return sb.toString();
        }
        String big = (String) item.get("big_text");
        return big == null || big.isEmpty() ? (String) item.get("text") : big;
    }

    void applyVerdict(String key, String level) {
        Info info;
        synchronized (infos) {
            info = infos.get(key);
        }
        if (info == null) return;
        if ("urgent".equals(level)) {
            if (!info.alerted && !info.initial) {
                info.alerted = true;
                postAlert(this, hub, key, info);
            }
        } else if ("junk".equals(level)) {
            cancel(key); // ads and noise go for good (the secretary has them written)
        } else if ("normal".equals(level)) {
            // usually «Не беспокоить» hides it until Тишина ends; if nothing hides it — it goes: only the important
            // on the screen, and the digest keeps it anyway
            if (!hiddenByDnd(this)) cancel(key);
        }
    }

    private void cancel(String key) {
        try {
            cancelNotification(key);
        } catch (Exception e) {
            Log.w(TAG, "cancel", e);
        }
    }

    // ---------------- «Тишина» ----------------

    /** All of a notification's visual effects (screen off/on, peek, status bar, badge, ambient, the list). */
    static final int HIDE_ALL = 1 | 2 | 4 | 8 | 16 | 32 | 64 | 128 | 256;
    static final int PRIORITY_CATEGORY_REMINDERS = 1, PRIORITY_CATEGORY_EVENTS = 2, PRIORITY_CATEGORY_CALLS = 8,
            PRIORITY_CATEGORY_REPEAT_CALLERS = 16, PRIORITY_CATEGORY_ALARMS = 32, PRIORITY_CATEGORY_MEDIA = 64,
            PRIORITY_CATEGORY_SYSTEM = 128, PRIORITY_SENDERS_ANY = 0, PRIORITY_SENDERS_STARRED = 2,
            SUPPRESSED_EFFECT_NOTIFICATION_LIST = 256;

    /** «Не беспокоить» hides the usual notifications whole now (not only their sound)? */
    static boolean hiddenByDnd(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT < 28 || nm.getCurrentInterruptionFilter() == NotificationManager.INTERRUPTION_FILTER_ALL)
            return false;
        NotificationManager.Policy p = nm.getNotificationPolicy();
        return p != null && (SecCompat.suppressedVisualEffects(p) & SUPPRESSED_EFFECT_NOTIFICATION_LIST) != 0;
    }

    /**
     * Тишина on/off. On: «Не беспокоить» letting calls, alarms and the calendar through, all other notifications hidden
     * whole; the important the secretary shows itself (its channel sounds through). The former «Не беспокоить» settings
     * are kept and given back when it goes off. Returns the text for the owner, or null without the policy access.
     */
    static String setFocus(Context c, boolean on) {
        SecHub hub = SecHub.get(c);
        NotificationManager nm = (NotificationManager) c.getSystemService(NOTIFICATION_SERVICE);
        android.content.SharedPreferences p = hub.prefs;
        if (on) {
            if (!nm.isNotificationPolicyAccessGranted()) return null;
            hub.ensureChannels();
            if (!p.getBoolean("saved_policy", false)) {
                NotificationManager.Policy old = nm.getNotificationPolicy(); // null where none was ever set
                p.edit().putBoolean("saved_policy", true)
                        .putInt("old_cat", old == null ? 0 : old.priorityCategories)
                        .putInt("old_calls", old == null ? 0 : old.priorityCallSenders)
                        .putInt("old_msgs", old == null ? 0 : old.priorityMessageSenders)
                        .putInt("old_vis", old == null ? 0 : SecCompat.suppressedVisualEffects(old))
                        .putInt("old_filter", nm.getCurrentInterruptionFilter()).apply();
            }
            int cats = PRIORITY_CATEGORY_CALLS | PRIORITY_CATEGORY_REPEAT_CALLERS | PRIORITY_CATEGORY_REMINDERS | PRIORITY_CATEGORY_EVENTS;
            if (Build.VERSION.SDK_INT >= 28) cats |= PRIORITY_CATEGORY_ALARMS | PRIORITY_CATEGORY_MEDIA | PRIORITY_CATEGORY_SYSTEM;
            nm.setNotificationPolicy(SecCompat.policy(cats, PRIORITY_SENDERS_ANY, PRIORITY_SENDERS_STARRED, HIDE_ALL));
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY);
            p.edit().putBoolean("focus", true).apply();
            SecListener l = instance;
            if (l != null) l.sweep(); // what already lies in the shade goes to be weighed too
            return "Тишина включена: на экране только важное. Звонки и будильники проходят как обычно.";
        }
        p.edit().putBoolean("focus", false).apply();
        if (nm.isNotificationPolicyAccessGranted()) {
            if (p.getBoolean("saved_policy", false)) {
                nm.setNotificationPolicy(SecCompat.policy(p.getInt("old_cat", 0), p.getInt("old_calls", 0), p.getInt("old_msgs", 0),
                        p.getInt("old_vis", 0)));
                p.edit().putBoolean("saved_policy", false).apply();
            }
            int old = p.getInt("old_filter", NotificationManager.INTERRUPTION_FILTER_ALL);
            nm.setInterruptionFilter(old == NotificationManager.INTERRUPTION_FILTER_UNKNOWN ? NotificationManager.INTERRUPTION_FILTER_ALL : old);
        }
        return "Тишина выключена: скрытые уведомления вернулись в шторку.";
    }

    /** Everything in the shade now — to be weighed (after Тишина is turned on). */
    void sweep() {
        Handler h = handler;
        if (h == null) return;
        h.post(new Runnable() {
            public void run() {
                try {
                    synchronized (recent) {
                        recent.clear();
                    }
                    // the important among what came already «Не беспокоить» now hides — the secretary shows it again
                    StatusBarNotification[] active = getActiveNotifications();
                    if (active != null) for (StatusBarNotification sbn : active) handle(sbn, false);
                } catch (Exception e) {
                    Log.w(TAG, "sweep", e);
                }
            }
        });
    }

    /**
     * The important — a copy of the original: the app's icon, the person's picture, the same buttons («Ответить» from
     * the notification goes into the messenger itself). Above it — the app's name, as with usual notifications.
     */
    static void postAlert(Context c, SecHub hub, String key, Info info) {
        hub.ensureChannels();
        NotificationManager nm = (NotificationManager) c.getSystemService(NOTIFICATION_SERVICE);
        String title = info.conversation != null && !info.conversation.isEmpty() ? info.conversation
                : info.title == null || info.title.isEmpty() ? info.app : info.title;
        Notification.Builder b = SecCompat.builder(c, hub.alertChannel())
                .setContentTitle(title)
                .setContentText(info.text)
                .setSubText(info.app)
                .setStyle(new Notification.BigTextStyle().bigText(info.text))
                .setContentIntent(info.open != null ? info.open : hub.openApp()) // a tap — straight into the chat
                .setAutoCancel(true)
                .setShowWhen(true)
                .setCategory(Notification.CATEGORY_MESSAGE);
        Icon large = info.orig != null ? info.orig.getLargeIcon() : null;
        if (large == null) large = appIcon(c, info.pkg);
        if (large != null) b.setLargeIcon(large);
        if (info.orig != null) {
            b.setWhen(info.orig.when);
            if (info.orig.actions != null)
                for (int i = 0; i < Math.min(3, info.orig.actions.length); i++) b.addAction(info.orig.actions[i]);
        }
        // the app's own icon in the status bar; if it fails (system apps) — ours
        Icon small = info.orig != null ? info.orig.getSmallIcon() : null;
        if (small != null) {
            try {
                nm.notify(key.hashCode(), b.setSmallIcon(small).build());
                return;
            } catch (Exception e) {
                Log.w(TAG, "foreign small icon", e);
            }
        }
        try {
            nm.notify(key.hashCode(), b.setSmallIcon(R.drawable.ic_secretary_small).build());
        } catch (SecurityException ignored) {
        }
    }

    /** An app's icon by its package — for tasks and copies of notifications. */
    static Icon appIcon(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty()) return null;
        try {
            Drawable d = c.getPackageManager().getApplicationIcon(pkg);
            int size = 128;
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            d.setBounds(0, 0, size, size);
            d.draw(canvas);
            return Icon.createWithBitmap(bmp);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> describe(StatusBarNotification sbn) {
        String pkg = sbn.getPackageName();
        if (getPackageName().equals(pkg)) return null;
        Notification n = sbn.getNotification();
        int skip = Notification.FLAG_GROUP_SUMMARY | Notification.FLAG_ONGOING_EVENT | Notification.FLAG_FOREGROUND_SERVICE;
        if ((n.flags & skip) != 0) return null; // «5 новых сообщений», music, downloads, VPN
        String cat = n.category;
        if (Notification.CATEGORY_PROGRESS.equals(cat) || Notification.CATEGORY_TRANSPORT.equals(cat)
                || Notification.CATEGORY_SERVICE.equals(cat) || Notification.CATEGORY_SYSTEM.equals(cat)) return null;

        Bundle x = n.extras;
        Map<String, Object> o = new LinkedHashMap<String, Object>();
        o.put("package", pkg);
        o.put("app", appLabel(pkg));
        o.put("key", sbn.getKey());
        o.put("posted", sbn.getPostTime());
        o.put("category", cat == null ? "" : cat);
        o.put("title", str(x.getCharSequence("android.title.big"), x.getCharSequence(Notification.EXTRA_TITLE)));
        o.put("text", str(x.getCharSequence(Notification.EXTRA_TEXT)));
        o.put("big_text", str(x.getCharSequence(Notification.EXTRA_BIG_TEXT)));
        o.put("sub_text", str(x.getCharSequence(Notification.EXTRA_SUB_TEXT)));
        o.put("conversation", str(x.getCharSequence("android.conversationTitle")));
        o.put("is_group", x.getBoolean("android.isGroupConversation", false));

        CharSequence[] lines = x.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        List<Object> jl = new ArrayList<Object>();
        if (lines != null) for (CharSequence l : lines) if (l != null) jl.add(l.toString());
        o.put("lines", jl);

        // messengers put the chat's latest messages here — each whole, with its sender
        List<Object> jm = new ArrayList<Object>();
        Parcelable[] msgs = x.getParcelableArray("android.messages");
        String me = selfName(x);
        if (msgs != null) {
            for (Parcelable p : msgs) {
                if (!(p instanceof Bundle)) continue;
                Bundle b = (Bundle) p;
                CharSequence text = b.getCharSequence("text");
                if (text == null) continue;
                String sender = str(b.getCharSequence("sender"));
                if (sender.isEmpty() && Build.VERSION.SDK_INT >= 28) {
                    String name = SecCompat.personName(b.getParcelable("sender_person"));
                    if (name != null) sender = name;
                }
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("text", text.toString());
                m.put("time", b.getLong("time"));
                m.put("sender", sender);
                m.put("self", sender.isEmpty() || sender.equals(me));
                jm.add(m);
            }
        }
        o.put("messages", jm);
        if (((String) o.get("title")).isEmpty() && ((String) o.get("text")).isEmpty() && jm.isEmpty() && jl.isEmpty()) return null;
        return o;
    }

    private String selfName(Bundle x) {
        if (Build.VERSION.SDK_INT >= 28) {
            String name = SecCompat.personName(x.getParcelable("android.messagingUser"));
            if (name != null) return name;
        }
        return str(x.getCharSequence("android.selfDisplayName"));
    }

    private String appLabel(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    private static String str(CharSequence... values) {
        for (CharSequence v : values) if (v != null && v.length() > 0) return v.toString();
        return "";
    }

    /** Whether the app may read notifications. */
    static boolean hasAccess(Context c) {
        String flat = android.provider.Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(c.getPackageName() + "/");
    }
}
