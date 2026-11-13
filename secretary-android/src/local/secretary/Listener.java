package local.secretary;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
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
import android.text.Html;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Живёт, пока у приложения есть доступ к уведомлениям (Android сам держит эту службу запущенной).
 * 1) Читает все уведомления телефона целиком и отправляет на сервер (через очередь — без связи не теряются).
 * 2) Держит связь с сервером и показывает уведомления от секретаря.
 */
public class Listener extends NotificationListenerService {
    static final String TAG = "Secretary";
    static final String CHANNEL = "secretary";
    private static final int BATCH = 50;

    private HandlerThread senderThread;
    private Handler sender;
    private Thread syncThread;
    private volatile boolean running;
    private long retryDelay = 30_000;
    private final Map<String, Boolean> recent = new LinkedHashMap<String, Boolean>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) { return size() > 500; }
    };

    // ---- режим «Тишина»: на экране только важное, остальное спрятано до выключения режима ----
    static final String CH_ALERT = "important";
    static volatile Listener instance;
    private static final Pattern OTP = Pattern.compile(
            "(код|code|пароль|password)\\D{0,25}\\d{4,8}|никому\\s+не\\s+(сообщайте|говорите|называйте)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    // Android 15+ скрывает от сторонних приложений текст уведомлений с кодами — узнаём их по заглушке.
    private static final Pattern REDACTED = Pattern.compile("(content hidden|содержимое скрыто|скрыт[оа]? систем)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    static final class Info {
        String pkg, app, title, text, conversation;
        PendingIntent open;
        Notification orig; // оригинал — берём из него иконки и кнопки («Ответить», «Прочитано»…)
        boolean alerted, initial;
    }

    private final Map<String, Info> infos = new LinkedHashMap<String, Info>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Info> e) { return size() > 300; }
    };

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("secretary", MODE_PRIVATE);
    }

    static boolean focus(Context c) {
        return prefs(c).getBoolean("focus", false);
    }

    // ---------------- сбор уведомлений ----------------

    @Override public void onListenerConnected() {
        instance = this;
        // секретарь живёт в приложении: его циклы (чаты, расписание) крутятся, пока жива эта служба
        Core.get(this).startLoop();
        coreStarted = true;
        Core.Journal.line("listener: connected");
        ensureChannels(this);
        running = true;
        senderThread = new HandlerThread("sender");
        senderThread.start();
        sender = new Handler(senderThread.getLooper());
        // То, что уже висит в шторке (в том числе пришедшее до установки), — тоже забираем.
        // Смахнутые раньше уведомления Android приложениям не отдаёт. Повторы сервер отсеет сам.
        // Напоминания о них второй раз не показываем — только уберём лишнее.
        sender.post(() -> {
            try {
                StatusBarNotification[] active = getActiveNotifications();
                if (active != null) for (StatusBarNotification sbn : active) handle(sbn, true);
            } catch (Exception e) {
                Log.w(TAG, "active notifications", e);
            }
        });
        kick();
        syncThread = new Thread(this::syncLoop, "sync");
        syncThread.start();
        // Разблокировали телефон — спросить «сделал?» о делах, о которых пора.
        unlock = new Tasks.UnlockReceiver();
        IntentFilter f = new IntentFilter(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(unlock, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(unlock, f);
    }

    private BroadcastReceiver unlock;

    @Override public void onListenerDisconnected() {
        stop();
    }

    @Override public void onDestroy() {
        stop();
        super.onDestroy();
    }

    private boolean coreStarted;

    /** Есть ли у приложения доступ к уведомлениям. */
    static boolean hasAccess(Context c) {
        String enabled = android.provider.Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        return enabled != null && enabled.contains(new android.content.ComponentName(c, Listener.class).flattenToString());
    }

    private void stop() {
        if (coreStarted) {
            coreStarted = false;
            Core.get(this).stopLoop();
            Core.Journal.line("listener: disconnected");
        }
        running = false;
        if (instance == this) instance = null;
        if (unlock != null) {
            try {
                unregisterReceiver(unlock);
            } catch (Exception ignored) {
                // уже снят
            }
            unlock = null;
        }
        if (syncThread != null) syncThread.interrupt();
        if (senderThread != null) senderThread.quitSafely();
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        handle(sbn, false);
    }

    private void handle(StatusBarNotification sbn, boolean initial) {
        try {
            JSONObject item = describe(sbn);
            if (item == null) return;
            String fingerprint = Net.sha256(item.toString().replaceAll("\"posted\":\\d+", "").getBytes("UTF-8"));
            synchronized (recent) {
                if (recent.put(fingerprint, Boolean.TRUE) != null) return; // то же уведомление ещё раз
            }
            Info info = new Info();
            info.pkg = sbn.getPackageName();
            info.conversation = item.optString("conversation");
            info.app = item.optString("app");
            info.title = item.optString("title");
            info.text = lastText(item);
            info.open = sbn.getNotification().contentIntent;
            Core.Journal.line("listener: " + (initial ? "in the shade " : "posted ") + info.pkg + " «" + info.app + "» chat «"
                    + (info.conversation.isEmpty() ? info.title : info.conversation) + "»" + (item.optBoolean("is_group") ? " (group)" : "")
                    + ", " + (item.optJSONArray("messages") == null ? 0 : item.optJSONArray("messages").length()) + " message(s), "
                    + (info.text == null ? 0 : info.text.length()) + " chars");
            info.orig = sbn.getNotification();
            info.initial = initial;
            synchronized (infos) {
                infos.put(sbn.getKey(), info);
            }
            // Код подтверждения ждут прямо сейчас — показываем сразу, без сервера.
            String all = info.title + " " + info.text;
            if (focus(this) && !initial && (OTP.matcher(all).find() || REDACTED.matcher(all).find())) {
                if (!OTP.matcher(all).find()) info.text = "Код подтверждения — нажми, чтобы открыть";
                info.alerted = true;
                postAlert(this, sbn.getKey(), info);
            }
            Queue.get(this).add(item.toString());
            kick();
        } catch (Exception e) {
            Log.w(TAG, "notification", e);
        }
    }

    @Override public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap, int reason) {
        Info info;
        synchronized (infos) {
            info = infos.get(sbn.getKey());
        }
        if (info == null) return;
        // Оригинал прочитан или смахнут — наше напоминание о нём тоже убираем.
        if (info.alerted) getSystemService(NotificationManager.class).cancel(sbn.getKey().hashCode());
        // Почему исчезло — улика для «сделал ли владелец дело»: нажал (открыл чат), мессенджер сам убрал
        // (чат прочитан в приложении) или смахнул. Наши собственные уборки мусора не считаем.
        String why = reason == REASON_CLICK ? "click" : reason == REASON_APP_CANCEL ? "app_cancel"
                : reason == REASON_CANCEL ? "cancel" : null;
        if (why == null) return;
        try {
            JSONObject o = new JSONObject()
                    .put("type", "removed").put("package", info.pkg).put("app", info.app)
                    .put("title", info.title).put("conversation", info.conversation)
                    .put("key", sbn.getKey()).put("reason", why).put("posted", System.currentTimeMillis());
            Queue.get(this).add(o.toString());
            kick();
        } catch (Exception e) {
            Log.w(TAG, "removed", e);
        }
    }

    private static String lastText(JSONObject item) {
        JSONArray m = item.optJSONArray("messages");
        if (m != null && m.length() > 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = Math.max(0, m.length() - 3); i < m.length(); i++) {
                JSONObject x = m.optJSONObject(i);
                if (x == null) continue;
                if (sb.length() > 0) sb.append('\n');
                String s = x.optString("sender");
                if (item.optBoolean("is_group") && !s.isEmpty()) sb.append(s).append(": ");
                sb.append(x.optString("text"));
            }
            return sb.toString();
        }
        String big = item.optString("big_text");
        return big.isEmpty() ? item.optString("text") : big;
    }

    void applyVerdict(String key, String level) {
        Info info;
        synchronized (infos) {
            info = infos.get(key);
        }
        if (info == null) {
            Core.Journal.line("listener: verdict " + level + " for a notification no longer known");
            return;
        }
        Core.Journal.line("listener: verdict " + level + " → " + info.pkg + " «" + info.title + "»"
                + ("urgent".equals(level) ? (info.alerted || info.initial ? " (not shown again)" : " shown again with sound")
                : "junk".equals(level) ? " removed" : "normal".equals(level) ? (hiddenByDnd(this) ? " hidden by «Не беспокоить»" : " removed") : ""));
        switch (level) {
            case "urgent":
                if (!info.alerted && !info.initial) {
                    info.alerted = true;
                    postAlert(this, key, info);
                }
                break;
            case "junk":
                cancel(key); // реклама и шум — убираем насовсем (секретарь их уже записал)
                break;
            case "normal":
                // Обычно его прячет «Не беспокоить» до выключения Тишины. Если прятать нечем — убираем:
                // на экране должно быть только важное, а в сводке у секретаря оно всё равно останется.
                if (!hiddenByDnd(this)) cancel(key);
                break;
            default:
                break;
        }
    }

    private void cancel(String key) {
        try {
            cancelNotification(key);
        } catch (Exception e) {
            Log.w(TAG, "cancel", e);
        }
    }

    // ---------------- режим «Тишина» ----------------

    static final int HIDE_ALL = NotificationManager.Policy.SUPPRESSED_EFFECT_SCREEN_OFF
            | NotificationManager.Policy.SUPPRESSED_EFFECT_SCREEN_ON
            | NotificationManager.Policy.SUPPRESSED_EFFECT_FULL_SCREEN_INTENT
            | NotificationManager.Policy.SUPPRESSED_EFFECT_LIGHTS
            | NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK
            | NotificationManager.Policy.SUPPRESSED_EFFECT_STATUS_BAR
            | NotificationManager.Policy.SUPPRESSED_EFFECT_BADGE
            | NotificationManager.Policy.SUPPRESSED_EFFECT_AMBIENT
            | NotificationManager.Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST;

    /** Сейчас «Не беспокоить» прячет обычные уведомления целиком (не только звук)? */
    static boolean hiddenByDnd(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT < 28 || nm.getCurrentInterruptionFilter() == NotificationManager.INTERRUPTION_FILTER_ALL)
            return false;
        NotificationManager.Policy policy = nm.getNotificationPolicy();
        return policy != null && (policy.suppressedVisualEffects
                & NotificationManager.Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST) != 0;
    }

    /**
     * Включить/выключить Тишину. Включаем «Не беспокоить», где проходят звонки, будильники и календарь,
     * а все прочие уведомления скрыты полностью. Важное показывает сам секретарь (его канал пробивает режим).
     * Прежние настройки «Не беспокоить» запоминаем и при выключении возвращаем. Вернёт текст для пользователя.
     */
    static String setFocus(Context c, boolean on) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        SharedPreferences p = prefs(c);
        if (on) {
            if (!nm.isNotificationPolicyAccessGranted()) return null;
            ensureChannels(c);
            if (!p.getBoolean("saved_policy", false)) {
                NotificationManager.Policy old = nm.getNotificationPolicy(); // бывает null, если её никто не задавал
                p.edit().putBoolean("saved_policy", true)
                        .putInt("old_cat", old == null ? 0 : old.priorityCategories)
                        .putInt("old_calls", old == null ? 0 : old.priorityCallSenders)
                        .putInt("old_msgs", old == null ? 0 : old.priorityMessageSenders)
                        .putInt("old_vis", old == null ? 0 : old.suppressedVisualEffects)
                        .putInt("old_filter", nm.getCurrentInterruptionFilter()).apply();
            }
            int cats = NotificationManager.Policy.PRIORITY_CATEGORY_CALLS
                    | NotificationManager.Policy.PRIORITY_CATEGORY_REPEAT_CALLERS
                    | NotificationManager.Policy.PRIORITY_CATEGORY_REMINDERS
                    | NotificationManager.Policy.PRIORITY_CATEGORY_EVENTS;
            if (Build.VERSION.SDK_INT >= 28) {
                cats |= NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS
                        | NotificationManager.Policy.PRIORITY_CATEGORY_MEDIA
                        | NotificationManager.Policy.PRIORITY_CATEGORY_SYSTEM;
                nm.setNotificationPolicy(new NotificationManager.Policy(cats,
                        NotificationManager.Policy.PRIORITY_SENDERS_ANY, NotificationManager.Policy.PRIORITY_SENDERS_STARRED,
                        HIDE_ALL));
            } else {
                nm.setNotificationPolicy(new NotificationManager.Policy(cats,
                        NotificationManager.Policy.PRIORITY_SENDERS_ANY, NotificationManager.Policy.PRIORITY_SENDERS_STARRED));
            }
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY);
            p.edit().putBoolean("focus", true).apply();
            Listener l = instance;
            if (l != null) l.sweep(); // то, что уже лежит в шторке, тоже отправим на оценку
            return "Тишина включена: на экране только важное. Звонки и будильники проходят как обычно.";
        }
        p.edit().putBoolean("focus", false).apply();
        if (nm.isNotificationPolicyAccessGranted()) {
            if (p.getBoolean("saved_policy", false)) {
                if (Build.VERSION.SDK_INT >= 28) {
                    nm.setNotificationPolicy(new NotificationManager.Policy(p.getInt("old_cat", 0),
                            p.getInt("old_calls", 0), p.getInt("old_msgs", 0), p.getInt("old_vis", 0)));
                } else {
                    nm.setNotificationPolicy(new NotificationManager.Policy(p.getInt("old_cat", 0),
                            p.getInt("old_calls", 0), p.getInt("old_msgs", 0)));
                }
                p.edit().putBoolean("saved_policy", false).apply();
            }
            int old = p.getInt("old_filter", NotificationManager.INTERRUPTION_FILTER_ALL);
            nm.setInterruptionFilter(old == NotificationManager.INTERRUPTION_FILTER_UNKNOWN
                    ? NotificationManager.INTERRUPTION_FILTER_ALL : old);
        }
        return "Тишина выключена: скрытые уведомления вернулись в шторку.";
    }

    /** Отправить на оценку всё, что сейчас лежит в шторке (после включения Тишины). */
    void sweep() {
        Handler h = sender;
        if (h == null) return;
        h.post(() -> {
            try {
                synchronized (recent) {
                    recent.clear();
                }
                // Важное из уже пришедшего «Не беспокоить» сейчас спрячет — покажем его заново от секретаря.
                StatusBarNotification[] active = getActiveNotifications();
                if (active != null) for (StatusBarNotification sbn : active) handle(sbn, false);
            } catch (Exception e) {
                Log.w(TAG, "sweep", e);
            }
        });
    }

    static PendingIntent openApp(Context c) {
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /**
     * Важное — копия оригинала: значок приложения, аватарка собеседника, его же кнопки («Ответить» прямо
     * из уведомления уходит в сам мессенджер). Сверху — название приложения, как у обычных уведомлений.
     */
    static void postAlert(Context c, String key, Info info) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        String title = !info.conversation.isEmpty() ? info.conversation : info.title.isEmpty() ? info.app : info.title;
        Notification.Builder b = new Notification.Builder(c, alertChannel(c))
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(title)
                .setContentText(info.text)
                .setSubText(info.app)
                .setStyle(new Notification.BigTextStyle().bigText(info.text))
                .setContentIntent(info.open != null ? info.open : openApp(c)) // нажатие — сразу в нужный чат
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
        // Значок самого приложения в строке состояния; не вышло (бывает у системных) — наш.
        Icon small = info.orig != null ? info.orig.getSmallIcon() : null;
        if (small != null) {
            try {
                nm.notify(key.hashCode(), b.setSmallIcon(small).build());
                return;
            } catch (Exception e) {
                Log.w(TAG, "foreign small icon", e);
            }
        }
        nm.notify(key.hashCode(), b.setSmallIcon(R.drawable.ic_launcher).build());
    }

    /** Иконка приложения по пакету — для дел и копий уведомлений. */
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

    private JSONObject describe(StatusBarNotification sbn) throws Exception {
        String pkg = sbn.getPackageName();
        if (getPackageName().equals(pkg)) return null;
        Notification n = sbn.getNotification();
        int skip = Notification.FLAG_GROUP_SUMMARY | Notification.FLAG_ONGOING_EVENT | Notification.FLAG_FOREGROUND_SERVICE;
        if ((n.flags & skip) != 0) return null; // «5 новых сообщений», музыка, загрузки, VPN
        String cat = n.category;
        if (Notification.CATEGORY_PROGRESS.equals(cat) || Notification.CATEGORY_TRANSPORT.equals(cat)
                || Notification.CATEGORY_SERVICE.equals(cat) || Notification.CATEGORY_SYSTEM.equals(cat)) return null;

        Bundle x = n.extras;
        JSONObject o = new JSONObject();
        o.put("package", pkg);
        o.put("app", appLabel(pkg));
        o.put("key", sbn.getKey());
        o.put("posted", sbn.getPostTime());
        o.put("category", cat == null ? "" : cat);
        o.put("title", str(x.getCharSequence(Notification.EXTRA_TITLE_BIG), x.getCharSequence(Notification.EXTRA_TITLE)));
        o.put("text", str(x.getCharSequence(Notification.EXTRA_TEXT)));
        o.put("big_text", str(x.getCharSequence(Notification.EXTRA_BIG_TEXT)));
        o.put("sub_text", str(x.getCharSequence(Notification.EXTRA_SUB_TEXT)));
        o.put("conversation", str(x.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)));
        o.put("is_group", x.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false));

        CharSequence[] lines = x.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        JSONArray jl = new JSONArray();
        if (lines != null) for (CharSequence l : lines) if (l != null) jl.put(l.toString());
        o.put("lines", jl);

        // Мессенджеры кладут сюда последние сообщения чата — каждое целиком и с отправителем.
        JSONArray jm = new JSONArray();
        Parcelable[] msgs = x.getParcelableArray(Notification.EXTRA_MESSAGES);
        String me = selfName(x);
        if (msgs != null) {
            for (Parcelable p : msgs) {
                if (!(p instanceof Bundle)) continue;
                Bundle b = (Bundle) p;
                CharSequence text = b.getCharSequence("text");
                if (text == null) continue;
                String sender = str(b.getCharSequence("sender"));
                if (sender.isEmpty() && Build.VERSION.SDK_INT >= 28) {
                    Parcelable person = b.getParcelable("sender_person");
                    if (person instanceof Person && ((Person) person).getName() != null)
                        sender = ((Person) person).getName().toString();
                }
                JSONObject m = new JSONObject();
                m.put("text", text.toString());
                m.put("time", b.getLong("time"));
                m.put("sender", sender);
                m.put("self", sender.isEmpty() || sender.equals(me));
                jm.put(m);
            }
        }
        o.put("messages", jm);
        if (o.getString("title").isEmpty() && o.getString("text").isEmpty() && jm.length() == 0 && jl.length() == 0)
            return null;
        return o;
    }

    private String selfName(Bundle x) {
        if (Build.VERSION.SDK_INT >= 28) {
            Parcelable p = x.getParcelable(Notification.EXTRA_MESSAGING_PERSON);
            if (p instanceof Person && ((Person) p).getName() != null) return ((Person) p).getName().toString();
        }
        return str(x.getCharSequence(Notification.EXTRA_SELF_DISPLAY_NAME));
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

    // ---------------- отправка очереди ----------------

    private final Runnable flushTask = this::flush;

    void kick() {
        Handler h = sender;
        if (h != null) {
            h.removeCallbacks(flushTask);
            h.post(flushTask);
        }
    }

    private void flush() {
        Queue q = Queue.get(this);
        List<Queue.Item> items = q.peek(BATCH);
        if (items.isEmpty()) return;
        try {
            StringBuilder sb = new StringBuilder("{\"items\":[");
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(items.get(i).json);
            }
            sb.append("]}");
            boolean focus = focus(this);
            String resp = Net.postJson("/hook/phone" + (focus ? "?verdicts=1" : ""), sb.toString(), 30_000);
            Core.Journal.line("listener: " + items.size() + " item(s) → " + resp);
            q.delete(items);
            JSONObject verdicts = focus ? new JSONObject(resp).optJSONObject("verdicts") : null;
            if (verdicts != null) {
                for (java.util.Iterator<String> it = verdicts.keys(); it.hasNext(); ) {
                    String key = it.next();
                    applyVerdict(key, verdicts.optString(key));
                }
            }
            retryDelay = 30_000;
            prefs(this).edit().putLong("last_sent", System.currentTimeMillis()).apply();
            if (items.size() == BATCH) sender.post(flushTask);
        } catch (Exception e) {
            Log.w(TAG, "send failed, retry in " + retryDelay / 1000 + "s: " + e);
            sender.postDelayed(flushTask, retryDelay);
            retryDelay = Math.min(retryDelay * 2, 300_000);
        }
    }

    // ---------------- сообщения от секретаря ----------------

    private void syncLoop() {
        SharedPreferences p = prefs(this);
        long backoff = 5_000;
        while (running) {
            try {
                long last = p.getLong("last_id", -1);
                String body = Net.get("/api/messages?after=" + Math.max(last, 0) + "&wait=25", 40_000);
                JSONArray msgs = new JSONObject(body).getJSONArray("messages");
                long max = last;
                for (int i = 0; i < msgs.length(); i++) {
                    JSONObject m = msgs.getJSONObject(i);
                    long id = m.getLong("id");
                    max = Math.max(max, id);
                    // Первый запуск — старое не показываем. Открытый чат — тоже не дёргаем.
                    if (last >= 0 && m.optBoolean("notify") && "bot".equals(m.optString("role"))
                            && !MainActivity.visible && id > p.getLong("seen_id", 0)) {
                        show(this, id, m.optString("html"));
                    }
                }
                if (max != last) p.edit().putLong("last_id", Math.max(max, 0)).apply();
                backoff = 5_000;
                kick(); // связь есть — заодно отправим накопленное
            } catch (Exception e) {
                if (!running) return;
                Log.w(TAG, "sync: " + e);
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    return;
                }
                backoff = Math.min(backoff * 2, 60_000);
            }
        }
    }

    /** Текущий канал «Важное». Имя с номером: см. ensureChannels. */
    static String alertChannel(Context c) {
        return CH_ALERT + "_" + prefs(c).getInt("alert_ch", 1);
    }

    static void ensureChannels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        String id = alertChannel(c);
        NotificationChannel ch = nm.getNotificationChannel(id);
        // Пробивать «Не беспокоить» (со звуком) канал может, только если создан, когда доступ к режиму уже был.
        // Пересоздать с тем же именем нельзя — Android вернёт старые настройки, поэтому берём новое имя.
        if (ch != null && !ch.canBypassDnd() && nm.isNotificationPolicyAccessGranted()) {
            int next = prefs(c).getInt("alert_ch", 1) + 1;
            prefs(c).edit().putInt("alert_ch", next).apply();
            nm.deleteNotificationChannel(id);
            id = alertChannel(c);
            ch = null;
        }
        if (ch == null) {
            ch = new NotificationChannel(id, "Важное", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Важное от секретаря — со звуком, даже в режиме «Тишина»");
            ch.setBypassDnd(true);
            ch.enableVibration(true);
            nm.createNotificationChannel(ch);
        }
        for (NotificationChannel old : nm.getNotificationChannels()) // каналы прошлых версий
            if (!old.getId().equals(id)) nm.deleteNotificationChannel(old.getId());
    }

    /** Кнопка «Проверить звук»: так придёт важное. */
    static void testAlert(Context c) {
        ensureChannels(c);
        Notification n = new Notification.Builder(c, alertChannel(c))
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Проверка")
                .setContentText("Так будет приходить важное — со звуком, даже в Тишине")
                .setContentIntent(openApp(c))
                .setAutoCancel(true)
                .build();
        c.getSystemService(NotificationManager.class).notify(3, n);
    }

    static void show(Context c, long id, String html) {
        ensureChannels(c);
        // строки сообщения сохраняем: первая — заголовок (иначе HTML склеил бы их в одну)
        String text = Html.fromHtml(html.replace("\n", "<br>"), Html.FROM_HTML_MODE_LEGACY).toString().trim();
        // Коротко: первая строка — заголовок (без служебных «#12»), остальное — текст.
        // Однострочное сообщение целиком идёт в текст, заголовок — просто «Секретарь».
        String title = "Секретарь", body = text;
        int nl = text.indexOf('\n');
        if (nl > 0) {
            title = text.substring(0, nl).replaceAll("\\s*·\\s*#\\d+", "").trim();
            body = text.substring(nl).trim();
        }
        PendingIntent pi = openApp(c);
        Notification n = new Notification.Builder(c, alertChannel(c))
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .build();
        c.getSystemService(NotificationManager.class).notify(SERVER_TAG, (int) id, n);
    }

    static final String SERVER_TAG = "srv"; // сообщения секретаря — убираем, когда открыли чат с ним
}
