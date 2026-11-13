package io.github.teoplaydor.semsearch.app;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.Locale;

import io.github.teoplaydor.semsearch.secretary.LocalLlm;

/**
 * The secretary's settings: its model (downloaded from Hugging Face; how fast it is on this phone), the accesses it
 * needs (notifications; «Не беспокоить» for Тишина), Тишина itself, and what the server kept in config.toml: who the
 * owner is («О себе» — the model weighs everything by it), the signature, the digests' times.
 */
public class SecSetup extends Activity {
    private SecHub hub;
    private LinearLayout box;
    private TextView modelState, accessState, benchOut;
    private Switch focus;
    private CompoundButton.OnCheckedChangeListener focusListener;
    private final android.os.Handler main = new android.os.Handler();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        hub = SecHub.get(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#262624"));
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(18);
        box.setPadding(p, p, p, p);
        scroll.addView(box);
        setContentView(scroll);

        head("Секретарь");
        note("Свой секретарь на телефоне: сортирует уведомления, пишет сводки, следит за делами и расходами. "
                + "Всё считает модель на самом телефоне — без интернета и без сервера.");

        head("Модель");
        modelState = note("");
        row(button("Скачать 4B (≈1,1 ГБ)", new View.OnClickListener() {
            public void onClick(View v) {
                hub.startDownload(SecHub.REPO_4B, refresh);
                refresh.run();
            }
        }), button("8B (≈2,1 ГБ)", new View.OnClickListener() {
            public void onClick(View v) {
                hub.startDownload(SecHub.REPO_8B, refresh);
                refresh.run();
            }
        }));
        row(button("Остановить", new View.OnClickListener() {
            public void onClick(View v) {
                hub.download.cancel = true;
            }
        }), button("Проверить скорость", new View.OnClickListener() {
            public void onClick(View v) {
                bench();
            }
        }));
        note("Ternary-Bonsai от PrismML: Qwen3 в 1,58 бита. 4B — быстрее, 8B — чуть умнее и вдвое больше. "
                + "Качать лучше по Wi-Fi; загрузка продолжается с места обрыва.");
        benchOut = note("");

        head("Доступ");
        accessState = note("");
        row(button("Уведомления", new View.OnClickListener() {
            public void onClick(View v) {
                open(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
            }
        }), button("«Не беспокоить»", new View.OnClickListener() {
            public void onClick(View v) {
                open(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
            }
        }));

        head("Тишина");
        focus = new Switch(this);
        focus.setText("На экране только важное");
        focus.setTextColor(Color.parseColor("#F5F4EF"));
        focus.setTextSize(16);
        focus.setChecked(hub.focus());
        focusListener = new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean on) {
                String msg = SecListener.setFocus(SecSetup.this, on);
                if (msg == null) {
                    b.setOnCheckedChangeListener(null);
                    b.setChecked(false);
                    b.setOnCheckedChangeListener(this);
                    Toast.makeText(SecSetup.this, "Нужен доступ к «Не беспокоить»", Toast.LENGTH_LONG).show();
                    open(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
                } else {
                    Toast.makeText(SecSetup.this, msg, Toast.LENGTH_LONG).show();
                }
            }
        };
        focus.setOnCheckedChangeListener(focusListener);
        box.addView(focus);
        note("Включается «Не беспокоить»: звонки и будильники проходят, остальное прячется. Важное (близкие, коды, "
                + "срочные дела) секретарь показывает сам — со звуком. Реклама убирается, обычное ждёт сводки.");
        row(button("Проверить звук", new View.OnClickListener() {
            public void onClick(View v) {
                hub.testAlert();
            }
        }));

        head("О себе");
        final EditText about = edit(hub.prefs.getString("about_me", ""), true);
        about.setHint("Кто я и что для меня важно: работа, учёба (занятия по вторникам), важные люди, что не интересно");
        final EditText name = edit(hub.prefs.getString("my_name", ""), false);
        name.setHint("Подпись в ответах: Имя Фамилия");
        note("Сводки (время через запятую):");
        final EditText times = edit(hub.prefs.getString("digest_times", "08:30, 19:00"), false);
        row(button("Сохранить", new View.OnClickListener() {
            public void onClick(View v) {
                hub.prefs.edit().putString("about_me", about.getText().toString().trim())
                        .putString("my_name", name.getText().toString().trim())
                        .putString("digest_times", times.getText().toString().trim()).apply();
                hub.reloadPrefs();
                Toast.makeText(SecSetup.this, "Сохранено", Toast.LENGTH_SHORT).show();
            }
        }));
        head("Журнал");
        note("Подробный журнал секретаря: страница и её ошибки, каждый запрос, очередь, модель (загрузка, строки самой "
                + "llama.cpp, скорость каждого ответа и сам ответ), уведомления (приложение, чат, сколько сообщений — без их текста), "
                + "«Тишина», «Сделал?», будильники, загрузка модели. Что-то не так — «Поделиться журналом» и прислать файл.");
        row(button("Поделиться журналом", new View.OnClickListener() {
            public void onClick(View v) {
                if (SecLog.share(SecSetup.this) == null)
                    Toast.makeText(SecSetup.this, "Не удалось сохранить журнал", Toast.LENGTH_LONG).show();
            }
        }), button("Скопировать", new View.OnClickListener() {
            public void onClick(View v) {
                SecLog.copy(SecSetup.this);
                Toast.makeText(SecSetup.this, "Последние 400 строк скопированы", Toast.LENGTH_SHORT).show();
            }
        }));
        final TextView tail = note("");
        tail.setTypeface(Typeface.MONOSPACE);
        tail.setTextSize(11);
        row(button("Показать последние строки", new View.OnClickListener() {
            public void onClick(View v) {
                tail.setText(SecLog.tail(80));
            }
        }));
        refresh.run();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh.run();
    }

    private final Runnable refresh = new Runnable() {
        public void run() {
            if (Thread.currentThread() != getMainLooper().getThread()) {
                main.post(this);
                return;
            }
            File f = hub.modelFile();
            SecHub.Download d = hub.download;
            String s;
            if (d.running) {
                s = String.format(Locale.ROOT, "Скачиваю %s: %.0f из %.0f МБ", d.file, d.done / 1e6, d.total / 1e6);
            } else if (f != null) {
                s = "Готова: " + f.getName() + String.format(Locale.ROOT, " (%.2f ГБ)", f.length() / 1e9)
                        + (hub.brain.loaded() ? " · загружена" : "") + "\nПотоков: " + LocalLlm.defaultThreads();
            } else {
                s = "Не скачана.";
            }
            if (!d.error.isEmpty()) s += "\nОшибка: " + d.error;
            modelState.setText(s);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            accessState.setText((SecListener.hasAccess(SecSetup.this) ? "✓" : "✗") + " доступ к уведомлениям\n"
                    + (nm.isNotificationPolicyAccessGranted() ? "✓" : "✗") + " доступ к «Не беспокоить» (для Тишины)");
            if (focus != null && focus.isChecked() != hub.focus()) { // shown as it is, without switching it again
                focus.setOnCheckedChangeListener(null);
                focus.setChecked(hub.focus());
                focus.setOnCheckedChangeListener(focusListener);
            }
        }
    };

    private void bench() {
        if (hub.modelFile() == null) {
            Toast.makeText(this, "Сначала скачай модель", Toast.LENGTH_SHORT).show();
            return;
        }
        benchOut.setText("Меряю… (первый раз модель загружается и читает свои инструкции)");
        new Thread(new Runnable() {
            public void run() {
                final String r = hub.benchmark();
                main.post(new Runnable() {
                    public void run() {
                        benchOut.setText(r);
                        refresh.run();
                    }
                });
            }
        }, "sec-bench").start();
    }

    private void open(Intent i) {
        try {
            startActivity(i);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void head(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.parseColor("#D97757"));
        t.setTextSize(18);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(18), 0, dp(6));
        box.addView(t);
    }

    private TextView note(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.parseColor("#A6A39B"));
        t.setTextSize(14);
        t.setPadding(0, dp(4), 0, dp(4));
        t.setTextIsSelectable(true);
        box.addView(t);
        return t;
    }

    private EditText edit(String value, boolean multi) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setTextColor(Color.parseColor("#F5F4EF"));
        e.setHintTextColor(Color.parseColor("#75736C"));
        e.setTextSize(15);
        if (multi) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            e.setMinLines(3);
            e.setGravity(Gravity.TOP);
        }
        box.addView(e);
        return e;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private void row(View... views) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (View v : views) r.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(r);
    }
}
