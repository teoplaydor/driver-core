package io.github.teoplaydor.semsearch.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

import io.github.teoplaydor.semsearch.core.HfRepo;

/**
 * Everything that is not searching, on one calm page: model, indexing (incl. the background mode),
 * speed and search options. Values open bottom sheets; explanations are one quiet line at most.
 */
final class SettingsPanel extends FrameLayout implements Engine.Listener {
    private final MainActivity a;
    private final Engine e;
    private final LinearLayout list;
    private boolean closing;

    // live parts
    private TextView modelValue, modelHint, indexValue, indexStatus, accelValue, photosValue, videosValue, detailValue,
            threadsValue, bridgeValue, dimsValue, photoModelValue, gemmaValue, sourceValue, sideValue;
    private TextView modelButton, indexButton, errorButton, deleteButton, fp32Button, reportLink, compareButton, fp32Delete,
            speedButton, liteRtSwitch, liteRtLeave, fp16Delete, liteRtDelete, qnnButton, qnnDelete;
    private ProgressLine modelProgress, indexProgress;
    private Toggle autoToggle, idleToggle, batteryToggle;
    private View batteryRow;
    private TextView unrestrictedValue;

    SettingsPanel(MainActivity activity) {
        super(activity);
        a = activity;
        e = Engine.get(activity);
        setBackgroundColor(Ui.BG);
        setClickable(true);
        Context c = activity;

        LinearLayout column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        addView(column, new LayoutParams(-1, -1));

        LinearLayout bar = new LinearLayout(c);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(c, 8), Ui.dp(c, 10), Ui.dp(c, 16), Ui.dp(c, 6));
        ImageView back = Ui.icon(c, Icon.BACK, Ui.TEXT, 44);
        back.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                close();
            }
        });
        bar.addView(back, new LinearLayout.LayoutParams(Ui.dp(c, 44), Ui.dp(c, 44)));
        TextView title = Ui.text(c, "Настройки", 20, Ui.TEXT, Ui.SEMIBOLD);
        title.setPadding(Ui.dp(c, 6), 0, 0, 0);
        bar.addView(title);
        column.addView(bar);

        ScrollView sv = new ScrollView(c);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(OVER_SCROLL_NEVER);
        list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(Ui.dp(c, 16), Ui.dp(c, 4), Ui.dp(c, 16), Ui.dp(c, 40));
        sv.addView(list);
        column.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        buildModel();
        buildIndex();
        buildSpeed();
        buildSearch();
        buildLook();
        TextView about = Ui.text(c, "EmbeddingGemma 2 (Google DeepMind, Apache 2.0) · версия " + BuildInfo.version(c)
                + "\nВсё считается на телефоне, файлы никуда не отправляются.", 12, Ui.TEXT3, Ui.REGULAR);
        about.setPadding(Ui.dp(c, 6), Ui.dp(c, 18), Ui.dp(c, 6), 0);
        list.addView(about);
        onEngineChanged();
    }

    // ------------------------------------------------------------------ building blocks

    /** "NPU (NNAPI, fp32)" → "NPU, fp32", "Видеокарта (WebGPU)" → "Видеокарта". */
    private void confirmSwitchToLiteRt() {
        IndexStore s = e.store();
        int n = s == null ? 0 : s.count(IndexStore.KIND_PHOTO) + s.count(IndexStore.KIND_VIDEO);
        int ms = e.prefs().getInt("litert_offer_ms", 0);
        String eta = ms > 0 && n > 0 ? String.format(Locale.ROOT, " (≈%d мин при %.2f с на фото)", Math.max(1, Math.round(n * ms / 60000.0)),
                ms / 1000.0) : "";
        Sheet.confirm(root(), "Перейти на LiteRT-LM?", "Векторы LiteRT-LM немного отличаются от текущих, поэтому индекс "
                + "будет построен заново: " + n + " фото и видео" + eta + ", заметки тоже. Пока идёт переиндексация, поиск "
                + "находит только уже готовое. Вернуться можно в любой момент, тоже с переиндексацией.", "Перейти",
                new Runnable() {
                    @Override
                    public void run() {
                        e.switchToLiteRt();
                    }
                });
    }

    private void confirmLeaveLiteRt() {
        Sheet.confirm(root(), "Вернуться на ONNX Runtime?", "Индекс построен LiteRT-LM; для ONNX-версии модели он будет "
                + "построен заново, затем подбор снова выберет самое быстрое ускорение.", "Вернуться", new Runnable() {
                    @Override
                    public void run() {
                        e.leaveLiteRt();
                    }
                });
    }

    private static String shortAccel(String name) {
        return name.replace(" (NNAPI, fp32)", ", fp32").replace(" (NNAPI)", "").replace(" (WebGPU)", "");
    }

    private static View rowOf(TextView value) {
        return (View) value.getParent();
    }

    private LinearLayout section(String name) {
        Context c = getContext();
        TextView h = Ui.text(c, name, 13, Ui.TEXT3, Ui.MEDIUM);
        h.setPadding(Ui.dp(c, 6), Ui.dp(c, 22), 0, Ui.dp(c, 8));
        list.addView(h);
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.round(c, Ui.SURFACE, 22));
        card.setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 4));
        list.addView(card, new LinearLayout.LayoutParams(-1, -2));
        return card;
    }

    /** "Title ........ value >" row; returns the value view. */
    private TextView row(LinearLayout card, String title, String hint, final Runnable onClick) {
        Context c = getContext();
        LinearLayout r = new LinearLayout(c);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(c, 18), Ui.dp(c, 14), Ui.dp(c, 12), Ui.dp(c, 14));
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(Ui.text(c, title, 15, Ui.TEXT, Ui.MEDIUM));
        if (hint != null) {
            TextView h = Ui.text(c, hint, 12.5f, Ui.TEXT3, Ui.REGULAR);
            h.setPadding(0, Ui.dp(c, 4), 0, 0);
            texts.addView(h);
        }
        r.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        TextView value = Ui.text(c, "", 14, Ui.TEXT2, Ui.REGULAR);
        value.setGravity(Gravity.END);
        value.setMaxLines(2);
        LinearLayout.LayoutParams vl = new LinearLayout.LayoutParams(-2, -2);
        vl.leftMargin = Ui.dp(c, 12);
        r.addView(value, vl);
        if (onClick != null) {
            r.addView(Ui.icon(c, Icon.CHEVRON, Ui.TEXT3, 22), new LinearLayout.LayoutParams(Ui.dp(c, 22), Ui.dp(c, 22)));
            r.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    onClick.run();
                }
            });
            r.setBackground(Ui.round(c, 0x00000000, 22));
        }
        card.addView(r, new LinearLayout.LayoutParams(-1, -2));
        return value;
    }

    /** Title + hint on the left, a switch on the right; returns the row. */
    private View toggleRow(LinearLayout card, String title, String hint, Toggle toggle) {
        Context c = getContext();
        LinearLayout r = new LinearLayout(c);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(c, 18), Ui.dp(c, 12), Ui.dp(c, 16), Ui.dp(c, 12));
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(Ui.text(c, title, 15, Ui.TEXT, Ui.MEDIUM));
        TextView h = Ui.text(c, hint, 12.5f, Ui.TEXT3, Ui.REGULAR);
        h.setPadding(0, Ui.dp(c, 4), 0, 0);
        texts.addView(h);
        r.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        r.addView(toggle);
        card.addView(r);
        return r;
    }

    private TextView action(LinearLayout card, String label, boolean primary, final Runnable r) {
        Context c = getContext();
        TextView b = Sheet.button(c, label, primary);
        b.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                r.run();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(c, 48));
        lp.setMargins(Ui.dp(c, 14), Ui.dp(c, 6), Ui.dp(c, 14), Ui.dp(c, 8));
        card.addView(b, lp);
        return b;
    }

    private TextView quiet(LinearLayout card, String label, int color, final Runnable r) {
        Context c = getContext();
        TextView t = Ui.text(c, label, 14, color, Ui.MEDIUM);
        t.setPadding(Ui.dp(c, 18), Ui.dp(c, 12), Ui.dp(c, 18), Ui.dp(c, 12));
        t.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                r.run();
            }
        });
        card.addView(t);
        return t;
    }

    private ProgressLine progress(LinearLayout card) {
        Context c = getContext();
        ProgressLine p = new ProgressLine(c);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(c, 3));
        lp.setMargins(Ui.dp(c, 18), Ui.dp(c, 2), Ui.dp(c, 18), Ui.dp(c, 8));
        card.addView(p, lp);
        return p;
    }

    private TextView note(LinearLayout card) {
        Context c = getContext();
        TextView t = Ui.text(c, "", 12.5f, Ui.TEXT2, Ui.REGULAR);
        t.setPadding(Ui.dp(c, 18), 0, Ui.dp(c, 18), Ui.dp(c, 8));
        card.addView(t);
        return t;
    }

    private ViewGroup root() {
        return (ViewGroup) getParent();
    }

    // ------------------------------------------------------------------ sections

    private void buildModel() {
        LinearLayout card = section("Модель");
        photoModelValue = row(card, "Модель для фото", null, new Runnable() {
            @Override
            public void run() {
                choosePhotoModel();
            }
        });
        modelValue = row(card, "Состояние", null, null);
        modelProgress = progress(card);
        modelHint = note(card);
        modelButton = action(card, "Скачать", true, new Runnable() {
            @Override
            public void run() {
                if (e.state == Engine.State.DOWNLOADING) e.cancelDownload();
                else if (e.state == Engine.State.ERROR && e.hasModelFiles()) e.retryLoad();
                else a.downloadModel();
            }
        });
        errorButton = quiet(card, "Скопировать подробности ошибки", Ui.ACCENT, new Runnable() {
            @Override
            public void run() {
                ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("SemSearch error", e.errorDetails));
                a.toast("Скопировано — вставьте в чат");
            }
        });
        gemmaValue = row(card, "EmbeddingGemma 2 для заметок", "точнее ищет по смыслу в заметках", new Runnable() {
            @Override
            public void run() {
                gemmaSheet();
            }
        });
        sourceValue = row(card, "Источник EmbeddingGemma 2", "Hugging Face, токен, фото и видео", new Runnable() {
            @Override
            public void run() {
                sourceSheet();
            }
        });
        deleteButton = quiet(card, "Удалить модель с телефона", Ui.DANGER, new Runnable() {
            @Override
            public void run() {
                Sheet.confirm(root(), "Удалить модель?", "Индекс сохранится, модель можно скачать снова.", "Удалить", new Runnable() {
                    @Override
                    public void run() {
                        e.deleteModel();
                    }
                });
            }
        });
    }

    private void choosePhotoModel() {
        final int cur = e.photoModel();
        Sheet.choose(root(), "Модель для фото", FastModel.NAMES, FastModel.HINTS, cur, new Sheet.Choice() {
            @Override
            public void chosen(final int i) {
                if (i == cur) return;
                IndexStore s = e.store();
                boolean hasIndex = s != null && s.count(IndexStore.KIND_PHOTO) + s.count(IndexStore.KIND_VIDEO) > 0;
                Runnable apply = new Runnable() {
                    @Override
                    public void run() {
                        e.setPhotoModel(i);
                        onEngineChanged();
                    }
                };
                if (!hasIndex) {
                    apply.run();
                    return;
                }
                Sheet.confirm(root(), "Сменить модель?", "Фото и видео проиндексируются заново новой моделью"
                        + (i == FastModel.GEMMA ? " — это долго." : " — это быстро.") + " Заметки не тронутся.", "Сменить", apply);
            }
        });
    }

    private void gemmaSheet() {
        if (!e.gemmaDownloaded()) {
            Sheet.confirm(root(), "Скачать EmbeddingGemma 2?", "Несколько сотен МБ. Заметки будут искаться точнее, "
                    + "и появится сравнение моделей на ваших фото.", "Скачать", new Runnable() {
                @Override
                public void run() {
                    e.download(e.repo(), e.prefs().getString("token", ""), true);
                }
            });
        } else {
            Sheet.confirm(root(), "Удалить EmbeddingGemma 2?", "Заметки будут искаться быстрой моделью — проще, зато меньше памяти.",
                    "Удалить", new Runnable() {
                        @Override
                        public void run() {
                            e.deleteGemma();
                        }
                    });
        }
    }

    private void sourceSheet() {
        Context c = getContext();
        final Sheet s = new Sheet(c, "Источник модели");
        final EditText repo = field(c, HfRepo.DEFAULT_REPO);
        repo.setText(e.repo());
        final EditText token = field(c, "Токен — только если доступ закрыт");
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setText(e.prefs().getString("token", ""));
        s.body().addView(repo, new LinearLayout.LayoutParams(-1, Ui.dp(c, 50)));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, Ui.dp(c, 50));
        tl.topMargin = Ui.dp(c, 8);
        s.body().addView(token, tl);
        LinearLayout vr = new LinearLayout(c);
        vr.setGravity(Gravity.CENTER_VERTICAL);
        vr.setPadding(Ui.dp(c, 4), Ui.dp(c, 14), 0, Ui.dp(c, 4));
        vr.addView(Ui.text(c, "Фото и видео (визуальный энкодер)", 14.5f, Ui.TEXT, Ui.MEDIUM), new LinearLayout.LayoutParams(0, -2, 1));
        final Toggle vision = new Toggle(c, e.prefs().getBoolean("vision", true));
        vr.addView(vision);
        s.body().addView(vr);
        final TextView plan = Ui.text(c, "", 13, Ui.TEXT2, Ui.REGULAR);
        plan.setPadding(Ui.dp(c, 4), Ui.dp(c, 10), 0, 0);
        s.body().addView(plan);
        LinearLayout buttons = new LinearLayout(c);
        buttons.setPadding(0, Ui.dp(c, 16), 0, 0);
        TextView check = Sheet.button(c, "Проверить", false);
        check.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                plan.setText("Проверяю…");
                e.checkRepo(repo.getText().toString(), token.getText().toString(), vision.isOn(), new Engine.Callback<HfRepo.Plan>() {
                    @Override
                    public void done(HfRepo.Plan p, Exception err) {
                        plan.setText(err != null ? "Ошибка: " + err.getMessage()
                                : String.format(Locale.ROOT, "Будет скачано %.0f МБ, %d файлов", p.totalBytes / 1048576.0, p.files.size()));
                    }
                });
            }
        });
        TextView dl = Sheet.button(c, "Скачать", true);
        dl.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                e.download(repo.getText().toString(), token.getText().toString(), vision.isOn());
                s.dismiss();
            }
        });
        LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(0, Ui.dp(c, 50), 1);
        LinearLayout.LayoutParams b2 = new LinearLayout.LayoutParams(0, Ui.dp(c, 50), 1);
        b2.leftMargin = Ui.dp(c, 10);
        buttons.addView(check, b1);
        buttons.addView(dl, b2);
        s.body().addView(buttons);
        s.show(root());
    }

    static EditText field(Context c, String hint) {
        EditText t = new EditText(c);
        t.setHint(hint);
        t.setSingleLine(true);
        t.setTextColor(Ui.TEXT);
        t.setHintTextColor(Ui.TEXT3);
        t.setTextSize(15);
        t.setTypeface(Ui.font(c, Ui.REGULAR));
        t.setBackground(Ui.round(c, Ui.SURFACE2, 16));
        t.setPadding(Ui.dp(c, 16), 0, Ui.dp(c, 16), 0);
        return t;
    }

    private void buildIndex() {
        LinearLayout card = section("Индексация");
        indexValue = row(card, "В индексе", null, null);
        indexProgress = progress(card);
        indexStatus = note(card);
        indexButton = action(card, "Индексировать сейчас", true, new Runnable() {
            @Override
            public void run() {
                if (e.indexing) e.stopIndex();
                else a.requestMediaAndIndex();
            }
        });
        Context c = getContext();
        LinearLayout r = new LinearLayout(c);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(c, 18), Ui.dp(c, 12), Ui.dp(c, 16), Ui.dp(c, 12));
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(Ui.text(c, "Новые фото — автоматически", 15, Ui.TEXT, Ui.MEDIUM));
        TextView h = Ui.text(c, "в фоне, когда появляются фото, скриншоты и видео", 12.5f, Ui.TEXT3, Ui.REGULAR);
        h.setPadding(0, Ui.dp(c, 4), 0, 0);
        texts.addView(h);
        r.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        autoToggle = new Toggle(c, AutoIndex.enabled(c));
        autoToggle.setListener(new Toggle.Listener() {
            @Override
            public void changed(boolean on) {
                AutoIndex.setEnabled(getContext(), on);
            }
        });
        r.addView(autoToggle);
        card.addView(r);

        idleToggle = new Toggle(c, IdleIndex.enabled(c));
        idleToggle.setListener(new Toggle.Listener() {
            @Override
            public void changed(boolean on) {
                if (on) a.enableIdleIndex();
                else IdleIndex.setEnabled(getContext(), false);
                onEngineChanged();
            }
        });
        toggleRow(card, "Пока телефон не используется", "экран погас — индексирую, взяли телефон — пауза", idleToggle);
        batteryToggle = new Toggle(c, IdleIndex.onBattery(c));
        batteryToggle.setListener(new Toggle.Listener() {
            @Override
            public void changed(boolean on) {
                IdleIndex.prefs(getContext()).edit().putBoolean("idle_battery", on).apply();
            }
        });
        batteryRow = toggleRow(card, "И от батареи", "при заряде выше " + IdleIndex.MIN_BATTERY + "%, иначе только на зарядке",
                batteryToggle);
        unrestrictedValue = row(card, "Не прерывать ночью", "снять ограничения батареи для приложения", new Runnable() {
            @Override
            public void run() {
                try {
                    getContext().startActivity(IdleIndex.unrestricted(getContext())
                            ? new android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:" + getContext().getPackageName()))
                            : IdleIndex.exemptionRequest(getContext()));
                } catch (Exception ignored) {
                    a.toast("На этом телефоне такого экрана нет");
                }
            }
        });

        photosValue = row(card, "Фото", null, new Runnable() {
            @Override
            public void run() {
                Sheet.choose(root(), "Сколько последних фото", new String[]{"100", "300", "1000", "3000", "Все"}, null,
                        e.prefs().getInt("photo_limit", 4), new Sheet.Choice() {
                            @Override
                            public void chosen(int i) {
                                e.prefs().edit().putInt("photo_limit", i).apply();
                                onEngineChanged();
                            }
                        });
            }
        });
        videosValue = row(card, "Видео", null, new Runnable() {
            @Override
            public void run() {
                Sheet.choose(root(), "Видео", new String[]{"Не индексировать", "Последние 10", "Последние 30", "Последние 100"},
                        new String[]{null, "по " + Engine.VIDEO_FRAMES + " кадра из каждого", null, null},
                        e.prefs().getInt("video_limit", 1), new Sheet.Choice() {
                            @Override
                            public void chosen(int i) {
                                e.prefs().edit().putInt("video_limit", i).apply();
                                onEngineChanged();
                            }
                        });
            }
        });
        detailValue = row(card, "Детализация", null, new Runnable() {
            @Override
            public void run() {
                // shown order: auto first; stored values: 0..2 = budgets, 3 = auto
                final int[] stored = {Engine.DETAIL_AUTO, 0, 1, 2};
                int cur = 0;
                for (int i = 0; i < stored.length; i++) if (stored[i] == e.prefs().getInt("photo_detail", Engine.DETAIL_AUTO)) cur = i;
                Sheet.choose(root(), "Детализация фото", new String[]{"Авто", "Быстрая", "Средняя", "Максимальная"},
                        new String[]{"70 токенов для обычных фото, 280 для скриншотов и документов", "70 токенов — для обычных фото",
                                "140 — мелкие детали", "280 — текст на скриншотах, медленно"},
                        cur, new Sheet.Choice() {
                            @Override
                            public void chosen(int i) {
                                e.prefs().edit().putInt("photo_detail", stored[i]).apply();
                                // the NPU graph is compiled for one budget
                                if (Engine.isNpu(e.loadedAccel) && e.ready() && !e.indexing) e.loadModel();
                                onEngineChanged();
                            }
                        });
            }
        });
        quiet(card, "Очистить индекс фото и видео", Ui.DANGER, new Runnable() {
            @Override
            public void run() {
                Sheet.confirm(root(), "Очистить индекс?", "Сами файлы не трогаются, заметки останутся.", "Очистить", new Runnable() {
                    @Override
                    public void run() {
                        e.clearMediaIndex();
                    }
                });
            }
        });
    }

    private void buildSpeed() {
        LinearLayout card = section("Скорость");
        accelValue = row(card, "Ускорение", null, new Runnable() {
            @Override
            public void run() {
                if (e.photoModel() != FastModel.GEMMA) {
                    String rep = e.fastReport();
                    Sheet.message(root(), "Ускорение", rep != null ? rep : "Автопроверка ещё не проводилась — она начнётся "
                            + "сама после загрузки модели.", "Проверить снова", new Runnable() {
                        @Override
                        public void run() {
                            a.runBenchmark();
                        }
                    });
                    return;
                }
                Sheet.choose(root(), "Где считать", Engine.ACCEL_NAMES, null, e.accel(), new Sheet.Choice() {
                    @Override
                    public void chosen(int i) {
                        if (i == e.accel()) return;
                        if (Engine.isGpu(i) && e.gpuBroken()) {
                            a.toast("Видеокарта на этом телефоне уже приводила к сбою — оставляю процессор");
                            return;
                        }
                        if (Engine.isNpu(i) && e.npuBroken(i)) {
                            a.toast("Этот вариант NPU уже приводил к сбою — оставляю процессор");
                            return;
                        }
                        if (Engine.isNpu(i) && e.gemmaNeedsFp32()) {
                            a.toast("Сначала «Проверить NPU»: нужна полная версия визуального энкодера");
                            return;
                        }
                        if ((i == Engine.ACCEL_GPU_FP16 && e.gemmaFp16Vision() == null) || (Engine.isLiteRt(i) && !e.liteRtInstalled())) {
                            a.toast("Сначала «Проверить LiteRT-LM и fp16»: этой версии ещё нет на телефоне");
                            return;
                        }
                        if (Engine.isQnn(i) && !e.qnnUsable()) {
                            a.toast(e.prefs().getBoolean("qnn_broken", false) ? "NPU Snapdragon уже приводил к сбою — не включаю"
                                    : "Сначала «Проверить NPU Snapdragon»");
                            return;
                        }
                        if (Engine.isLiteRt(i) && e.liteRtBroken(i)) {
                            a.toast("Этот вариант LiteRT-LM уже приводил к сбою — не включаю");
                            return;
                        }
                        if (e.liteRtSpace() && !Engine.isLiteRt(i)) {
                            confirmLeaveLiteRt();
                            return;
                        }
                        if (Engine.isLiteRt(i) && !e.liteRtSpace()) {
                            float cos = e.prefs().getFloat("litert_cos_" + i, -1);
                            if (cos < 0) {
                                a.toast("Сначала «Подобрать самое быстрое»: проверю, совпадают ли векторы LiteRT-LM с индексом");
                                return;
                            }
                            if (cos < 0.98f) {
                                e.prefs().edit().putInt("litert_offer", i).apply();
                                confirmSwitchToLiteRt();
                                return;
                            }
                        }
                        e.prefs().edit().putInt("accel", i).apply();
                        if (e.ready() && !e.indexing) e.loadModel();
                        onEngineChanged();
                    }
                });
            }
        });
        fp32Button = action(card, "Проверить NPU", false, new Runnable() {
            @Override
            public void run() {
                final boolean gemma = e.photoModel() == FastModel.GEMMA;
                long mb = (gemma ? e.gemmaFp32EstimateBytes() : e.fp32EstimateBytes()) >> 20;
                Sheet.confirm(root(), "Проверить NPU?", String.format(Locale.ROOT, "NPU работает с полной версией "
                        + "визуальной части модели — докачаю ≈%d МБ. Потом сравню скорость и точность всех вариантов "
                        + "(процессор, видеокарта, NPU) и оставлю самый быстрый из точных.", mb), "Докачать и проверить",
                        new Runnable() {
                            @Override
                            public void run() {
                                if (gemma) e.downloadGemmaFp32();
                                else e.downloadFast(true);
                            }
                        });
            }
        });
        action(card, "Подобрать самое быстрое", false, new Runnable() {
            @Override
            public void run() {
                a.runBenchmark();
            }
        });
        reportLink = quiet(card, "Отчёт последнего подбора", Ui.ACCENT, new Runnable() {
            @Override
            public void run() {
                String r = e.photoModel() == FastModel.GEMMA ? e.gemmaReport() : e.fastReport();
                if (r != null) a.showReport("Скорость на этом телефоне", r);
                else Sheet.message(root(), "Скорость на этом телефоне", "Подбора ещё не было.", null, null);
            }
        });
        fp32Delete = quiet(card, "Удалить версию для NPU", Ui.DANGER, new Runnable() {
            @Override
            public void run() {
                Sheet.confirm(root(), "Удалить версию для NPU?", String.format(Locale.ROOT, "Освободится ≈%d МБ. NPU сейчас "
                        + "не используется; если понадобится, «Проверить NPU» скачает её снова.", e.gemmaFp32Bytes() >> 20),
                        "Удалить", new Runnable() {
                            @Override
                            public void run() {
                                e.deleteGemmaFp32();
                            }
                        });
            }
        });
        qnnButton = action(card, "Проверить NPU Snapdragon", true, new Runnable() {
            @Override
            public void run() {
                long fp32 = e.gemmaFp32Vision() == null ? e.gemmaFp32EstimateBytes() >> 20 : 0;
                Sheet.confirm(root(), "Проверить NPU Snapdragon?", "Визуальная часть модели — основная работа на каждое фото — "
                        + "пойдёт на нейропроцессор Snapdragon напрямую, через Qualcomm QNN, а не через NNAPI. Докачаю движок "
                        + "QNN и сборку ONNX Runtime для него (≈72 МБ с Maven Central)"
                        + (fp32 > 0 ? String.format(Locale.ROOT, " и полную версию визуальной части (≈%d МБ)", fp32) : "")
                        + ".\n\nПервый запуск скомпилирует модель под NPU — это может занять несколько минут на каждую "
                        + "детализацию; потом подбор сравнит NPU с видеокартой и оставит самое быстрое из точного. NPU работает "
                        + "в отдельном процессе: если драйвер упадёт, приложение останется работать.", "Докачать и проверить",
                        new Runnable() {
                            @Override
                            public void run() {
                                e.downloadQnn();
                            }
                        });
            }
        });
        qnnDelete = quiet(card, "Удалить NPU Snapdragon", Ui.DANGER, new Runnable() {
            @Override
            public void run() {
                Sheet.confirm(root(), "Удалить NPU Snapdragon?", String.format(Locale.ROOT, "Освободится ≈%d МБ (движок QNN и "
                        + "скомпилированные под NPU графы). «Проверить NPU Snapdragon» скачает его снова.", e.qnnBytes() >> 20),
                        "Удалить", new Runnable() {
                            @Override
                            public void run() {
                                e.deleteQnn();
                            }
                        });
            }
        });
        speedButton = action(card, "Проверить LiteRT-LM и fp16", false, new Runnable() {
            @Override
            public void run() {
                boolean fp16 = e.gemmaFp16Vision() == null;
                long mb = e.gemmaFp32EstimateBytes() / 2 >> 20; // fp16 weights: half the fp32 estimate
                boolean lrt = !e.liteRtInstalled();
                Sheet.confirm(root(), "Проверить LiteRT-LM и fp16?", (fp16 ? "• fp16-версия визуальной части для видеокарты"
                        + (mb > 0 ? String.format(Locale.ROOT, " (≈%d МБ)", mb) : "") + ": мобильные видеокарты считают fp16 "
                        + "быстрее, а памяти она гоняет вдвое меньше.\n" : "")
                        + (lrt ? "• LiteRT-LM — движок Google со своей сборкой EmbeddingGemma 2 и своими ядрами для "
                        + "видеокарты (≈0,4–0,5 ГБ: модель и библиотеки движка с серверов Google).\n" : "")
                        + "\nПотом подбор сравнит скорость и точность всех вариантов и оставит самый быстрый из точных.",
                        "Докачать и проверить", new Runnable() {
                            @Override
                            public void run() {
                                e.downloadSpeedups();
                            }
                        });
            }
        });
        liteRtSwitch = action(card, "Перейти на LiteRT-LM", true, new Runnable() {
            @Override
            public void run() {
                confirmSwitchToLiteRt();
            }
        });
        liteRtLeave = quiet(card, "Вернуться на ONNX Runtime", Ui.ACCENT, new Runnable() {
            @Override
            public void run() {
                confirmLeaveLiteRt();
            }
        });
        fp16Delete = quiet(card, "Удалить fp16-версию", Ui.DANGER, new Runnable() {
            @Override
            public void run() {
                Sheet.confirm(root(), "Удалить fp16-версию?", String.format(Locale.ROOT, "Освободится ≈%d МБ. Сейчас она не "
                        + "используется; «Проверить LiteRT-LM и fp16» скачает её снова.", e.gemmaFp16Bytes() >> 20), "Удалить",
                        new Runnable() {
                            @Override
                            public void run() {
                                e.deleteGemmaFp16();
                            }
                        });
            }
        });
        liteRtDelete = quiet(card, "Удалить LiteRT-LM", Ui.DANGER, new Runnable() {
            @Override
            public void run() {
                Sheet.confirm(root(), "Удалить LiteRT-LM?", String.format(Locale.ROOT, "Освободится ≈%d МБ. Сейчас он не "
                        + "используется; «Проверить LiteRT-LM и fp16» скачает его снова.", e.liteRtBytes() >> 20), "Удалить",
                        new Runnable() {
                            @Override
                            public void run() {
                                e.deleteLiteRt();
                            }
                        });
            }
        });
        threadsValue = row(card, "Потоки процессора", null, new Runnable() {
            @Override
            public void run() {
                final int[] opts = {0, 2, 3, 4, 6, 8};
                int cur = 0;
                for (int i = 0; i < opts.length; i++) if (opts[i] == e.prefs().getInt("threads", 0)) cur = i;
                Sheet.choose(root(), "Потоки", new String[]{"Авто (" + Engine.autoThreads() + ")", "2", "3", "4", "6", "8"},
                        new String[]{"быстрые ядра", null, null, null, null, null}, cur, new Sheet.Choice() {
                            @Override
                            public void chosen(int i) {
                                if (opts[i] == e.prefs().getInt("threads", 0)) return;
                                e.prefs().edit().putInt("threads", opts[i]).apply();
                                if (e.ready() && !e.indexing) e.loadModel();
                                onEngineChanged();
                            }
                        });
            }
        });
    }

    private void buildSearch() {
        LinearLayout card = section("Поиск");
        bridgeValue = row(card, "Русские запросы к фото", null, new Runnable() {
            @Override
            public void run() {
                Sheet.choose(root(), "Русские запросы к фото", new String[]{"Русский + перевод", "Только перевод", "Без перевода"},
                        new String[]{"рекомендуется: модель лучше понимает английский", null, null}, e.bridgeMode(),
                        new Sheet.Choice() {
                            @Override
                            public void chosen(int i) {
                                e.prefs().edit().putInt("bridge_mode", i).apply();
                                onEngineChanged();
                            }
                        });
            }
        });
        dimsValue = row(card, "Длина вектора", null, new Runnable() {
            @Override
            public void run() {
                final int[] dims = {768, 512, 256, 128};
                int cur = 0;
                for (int i = 0; i < dims.length; i++) if (dims[i] == e.searchDims()) cur = i;
                Sheet.choose(root(), "Длина вектора", new String[]{"768", "512", "256", "128"},
                        new String[]{"полная точность", null, null, "меньше памяти, чуть ниже точность"}, cur, new Sheet.Choice() {
                            @Override
                            public void chosen(int i) {
                                e.prefs().edit().putInt("dims", dims[i]).apply();
                                onEngineChanged();
                            }
                        });
            }
        });
        action(card, "Проверить качество поиска", false, new Runnable() {
            @Override
            public void run() {
                a.runDiagnostics();
            }
        });
        compareButton = action(card, "Сравнить модели на моих фото", false, new Runnable() {
            @Override
            public void run() {
                a.openCompare();
            }
        });
    }

    private void buildLook() {
        LinearLayout card = section("Вид");
        sideValue = row(card, "Панель поиска", "фильтры и поиск сбоку, под большим пальцем", new Runnable() {
            @Override
            public void run() {
                Sheet.choose(root(), "Панель поиска", new String[]{"Справа", "Слева"},
                        new String[]{"под правым большим пальцем", "под левым"}, a.railSide(), new Sheet.Choice() {
                            @Override
                            public void chosen(int i) {
                                a.setRailSide(i);
                                onEngineChanged();
                            }
                        });
            }
        });
    }

    // ------------------------------------------------------------------ state

    @Override
    public void onEngineChanged() {
        Engine.State st = e.state;
        boolean busy = st == Engine.State.DOWNLOADING || st == Engine.State.LOADING;
        String mv;
        switch (st) {
            case READY: mv = "готова"; break;
            case DOWNLOADING: mv = e.dlTotal > 0 ? String.format(Locale.ROOT, "%d%%", 100 * e.dlDone / Math.max(1, e.dlTotal)) : "загрузка"; break;
            case LOADING: mv = "загружается"; break;
            case ERROR: mv = "ошибка"; break;
            default: mv = e.hasModelFiles() ? "не загружена" : "не скачана";
        }
        modelValue.setText(mv);
        modelValue.setTextColor(st == Engine.State.ERROR ? Ui.DANGER : st == Engine.State.READY ? Ui.ACCENT : Ui.TEXT2);
        modelProgress.setVisibility(busy ? VISIBLE : GONE);
        modelProgress.setIndeterminate(st == Engine.State.LOADING || e.dlTotal <= 0);
        if (e.dlTotal > 0) modelProgress.setProgress((float) e.dlDone / e.dlTotal);
        boolean fast = e.photoModel() != FastModel.GEMMA;
        String hint = e.dlError != null && !busy ? e.dlError
                : st == Engine.State.READY ? (fast ? e.accelLabel : Engine.ACCEL_NAMES[e.loadedAccel] + ", потоков " + e.threads)
                : st == Engine.State.DOWNLOADING && e.dlTotal > 0
                ? String.format(Locale.ROOT, "%.0f из %.0f МБ", e.dlDone / 1048576.0, e.dlTotal / 1048576.0)
                : st == Engine.State.ERROR ? e.status : "";
        modelHint.setText(hint);
        modelHint.setVisibility(hint.isEmpty() ? GONE : VISIBLE);
        modelButton.setText(st == Engine.State.DOWNLOADING ? "Остановить загрузку"
                : st == Engine.State.READY ? "Скачать заново" : st == Engine.State.ERROR && e.hasModelFiles() ? "Повторить" : "Скачать");
        modelButton.setVisibility(st == Engine.State.LOADING ? GONE : VISIBLE);
        modelButton.setBackground(Ui.round(getContext(), st == Engine.State.READY ? Ui.SURFACE3 : Ui.ACCENT, 16));
        modelButton.setTextColor(st == Engine.State.READY ? Ui.TEXT : Ui.ON_ACCENT);
        errorButton.setVisibility(st == Engine.State.ERROR && e.errorDetails != null ? VISIBLE : GONE);
        deleteButton.setVisibility(e.hasModelFiles() && !busy ? VISIBLE : GONE);
        photoModelValue.setText(FastModel.NAMES[e.photoModel()]);
        // EmbeddingGemma 2 is the photo model; the SigLIP option stays hidden unless it is in use
        rowOf(photoModelValue).setVisibility(fast ? VISIBLE : GONE);
        compareButton.setVisibility(fast ? VISIBLE : GONE);
        String report = fast ? e.fastReport() : e.gemmaReport();
        reportLink.setVisibility(report != null ? VISIBLE : GONE);
        rowOf(gemmaValue).setVisibility(fast ? VISIBLE : GONE);
        gemmaValue.setText(e.gemmaDownloaded() ? "скачана" : "не скачана");
        rowOf(sourceValue).setVisibility(!fast || e.gemmaDownloaded() ? VISIBLE : GONE);
        fp32Button.setVisibility((fast ? e.fastNeedsFp32() : e.gemmaNeedsFp32() && !Engine.isSnapdragon()) && !busy
                && FastModel.acceleratorLikely() ? VISIBLE : GONE);
        fp32Delete.setVisibility(!fast && !busy && e.gemmaFp32Vision() != null && !Engine.isNpu(e.accel()) && !Engine.isQnn(e.accel())
                ? VISIBLE : GONE);
        speedButton.setVisibility(!fast && !busy && e.speedupsMissing() ? VISIBLE : GONE);
        qnnButton.setVisibility(!fast && !busy && e.qnnMissing() ? VISIBLE : GONE);
        boolean qnnUnused = !fast && !busy && e.qnnInstalled() && !Engine.isQnn(e.accel());
        qnnDelete.setVisibility(qnnUnused ? VISIBLE : GONE);
        if (qnnUnused) qnnDelete.setText(String.format(Locale.ROOT, "Удалить NPU Snapdragon (%d МБ)", e.qnnBytes() >> 20));
        liteRtSwitch.setVisibility(!fast && !busy && e.liteRtOffer() >= 0 ? VISIBLE : GONE);
        liteRtLeave.setVisibility(!fast && !busy && e.liteRtSpace() ? VISIBLE : GONE);
        fp16Delete.setVisibility(!fast && !busy && e.gemmaFp16Vision() != null && e.accel() != Engine.ACCEL_GPU_FP16 ? VISIBLE : GONE);
        if (fp16Delete.getVisibility() == VISIBLE) {
            fp16Delete.setText(String.format(Locale.ROOT, "Удалить fp16-версию (%d МБ)", e.gemmaFp16Bytes() >> 20));
        }
        // also a download that stopped halfway (hundreds of MB) can go
        boolean lrtUnused = !fast && !busy && !e.liteRtSpace() && !Engine.isLiteRt(e.accel()) && e.liteRtBytes() > 0;
        liteRtDelete.setVisibility(lrtUnused ? VISIBLE : GONE);
        if (lrtUnused) liteRtDelete.setText(String.format(Locale.ROOT, "Удалить LiteRT-LM (%d МБ)", e.liteRtBytes() >> 20));
        if (fp32Delete.getVisibility() == VISIBLE) {
            fp32Delete.setText(String.format(Locale.ROOT, "Удалить версию для NPU (%d МБ)", e.gemmaFp32Bytes() >> 20));
        }
        rowOf(detailValue).setVisibility(fast ? GONE : VISIBLE);
        rowOf(threadsValue).setVisibility(fast ? GONE : VISIBLE);
        rowOf(dimsValue).setVisibility(fast ? GONE : VISIBLE);
        sideValue.setText(a.railSide() == 0 ? "справа" : "слева");

        IndexStore s = e.store();
        if (s != null) {
            indexValue.setText(String.format(Locale.ROOT, "%d фото · %d видео\n%d заметок", s.count(IndexStore.KIND_PHOTO),
                    s.count(IndexStore.KIND_VIDEO), s.count(IndexStore.KIND_NOTE)));
        }
        indexProgress.setVisibility(e.indexing ? VISIBLE : GONE);
        indexProgress.setIndeterminate(e.idxTotal == 0);
        if (e.idxTotal > 0) indexProgress.setProgress((float) e.idxDone / e.idxTotal);
        String status = e.idxStatus == null ? "" : e.idxStatus.split("\n")[0];
        indexStatus.setText(status);
        indexStatus.setVisibility(status.isEmpty() ? GONE : VISIBLE);
        indexButton.setText(e.indexing ? "Остановить" : "Индексировать сейчас");
        autoToggle.setOn(AutoIndex.enabled(getContext()), false);
        boolean idle = IdleIndex.enabled(getContext());
        idleToggle.setOn(idle, false);
        batteryRow.setVisibility(idle ? VISIBLE : GONE);
        rowOf(unrestrictedValue).setVisibility(idle ? VISIBLE : GONE);
        unrestrictedValue.setText(IdleIndex.unrestricted(getContext()) ? "снято" : "разрешить");
        unrestrictedValue.setTextColor(IdleIndex.unrestricted(getContext()) ? Ui.TEXT2 : Ui.ACCENT);
        int pl = e.prefs().getInt("photo_limit", 4);
        photosValue.setText(pl >= 4 ? "все" : Engine.PHOTO_LIMITS[pl] + " последних");
        int vl = e.prefs().getInt("video_limit", 1);
        videosValue.setText(vl == 0 ? "нет" : Engine.VIDEO_LIMITS[vl] + " последних");
        detailValue.setText(new String[]{"быстрая", "средняя", "максимальная", "авто"}[Math.max(0, Math.min(3,
                e.prefs().getInt("photo_detail", Engine.DETAIL_AUTO)))]);
        accelValue.setText(shortAccel(fast ? (e.accelLabel.isEmpty() ? "проверяю…" : e.accelLabel) : Engine.ACCEL_NAMES[e.accel()]));
        int t = e.prefs().getInt("threads", 0);
        threadsValue.setText(t == 0 ? "авто" : String.valueOf(t));
        bridgeValue.setText(new String[]{"русский + перевод", "только перевод", "без перевода"}[e.bridgeMode()]);
        dimsValue.setText(String.valueOf(e.searchDims()));
    }

    // ------------------------------------------------------------------ open / close

    void open(ViewGroup parent) {
        parent.addView(this, new ViewGroup.LayoutParams(-1, -1));
        e.addListener(this);
        setTranslationY(Ui.dp(getContext(), 60));
        setAlpha(0f);
        animate().translationY(0).alpha(1f).setDuration(280).setInterpolator(Ui.EASE).start();
    }

    boolean isClosing() {
        return closing;
    }

    void close() {
        if (closing) return;
        closing = true;
        e.removeListener(this);
        animate().translationY(Ui.dp(getContext(), 60)).alpha(0f).setDuration(200).setInterpolator(Ui.EASE).withEndAction(new Runnable() {
            @Override
            public void run() {
                ViewGroup p = (ViewGroup) getParent();
                if (p != null) p.removeView(SettingsPanel.this);
                a.settingsClosed();
            }
        }).start();
    }
}
