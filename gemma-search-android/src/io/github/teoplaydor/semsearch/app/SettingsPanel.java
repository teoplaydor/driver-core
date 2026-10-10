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

import java.util.List;
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
    private Toggle autoToggle, idleToggle, batteryToggle, adultToggle;
    private TextView adultLevelValue, hiddenValue, adultNote;
    private TextView facesValue, facesNote, facesButton, facesDelete, faceLevelValue, hiddenFacesValue;
    private ProgressLine facesProgress;
    private View batteryRow;
    private TextView unrestrictedValue;
    private TextView assistantValue;
    private Toggle voiceIconToggle;

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
        buildQuick();
        buildIndex();
        buildSoundAndFiles();
        buildSpeed();
        buildSearch();
        buildPeople();
        buildAdult();
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
        modelHint.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                io.github.teoplaydor.semsearch.core.StageProgress b = e.bench;
                if (b != null && !b.finished()) a.showBenchProgress(b.startedMs());
            }
        });
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
                Sheet.choose(root(), "Видео", new String[]{"Не индексировать", "Последние 10", "Последние 30", "Последние 100",
                                "Последние 300", "Последние 1000", "Все"},
                        new String[]{null, "по " + Engine.VIDEO_FRAMES + " кадра из каждого", null, null, null, null,
                                "видео, которое не открывается за минуту, пропускается"},
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

    private TextView foldersValue, soundValue, soundNote, soundButton, soundDelete;
    private LinearLayout foldersBox;
    private View soundIndexRow, videoSoundRow;
    private Toggle soundIndexToggle, videoSoundToggle;
    private String foldersShown;
    private double soundMb;

    /** Documents from the folders given, and sound (EmbeddingGemma's audio part, downloaded on request). */
    private void buildQuick() {
        final Context c = getContext();
        LinearLayout card = section("Быстрые заметки");
        TextView how = Ui.text(c, "Скажите, например: «напомни завтра в 9 позвонить маме», «каждый понедельник в 10 планёрка», "
                + "«купить молоко, хлеб и сыр» — заметка сохранится сразу, с напоминанием или списком. «Найди…» откроет поиск.",
                13, Ui.TEXT2, Ui.REGULAR);
        how.setPadding(Ui.dp(c, 18), Ui.dp(c, 12), Ui.dp(c, 18), Ui.dp(c, 4));
        card.addView(how);
        action(card, "Попробовать", true, new Runnable() {
            @Override
            public void run() {
                a.startActivity(QuickNotes.intent(c, false));
            }
        });
        assistantValue = row(card, "Удержание кнопки питания", "сделайте приложение «цифровым помощником» — удержание "
                + "питания (или «Домой») откроет заметку голосом", new Runnable() {
            @Override
            public void run() {
                QuickNotes.openAssistantSettings(a);
            }
        });
        voiceIconToggle = new Toggle(c, QuickNotes.iconShown(c));
        voiceIconToggle.setListener(new Toggle.Listener() {
            @Override
            public void changed(boolean on) {
                QuickNotes.showIcon(c, on);
                if (on) a.toast("Значок «Голосовая заметка» появится в списке приложений");
            }
        });
        toggleRow(card, "Значок «Голосовая заметка»", "его можно повесить на жест: Samsung — «Боковая клавиша → Двойное "
                + "нажатие → Открыть приложение», Pixel — Quick Tap (двойное касание задней панели), другие — быстрый запуск "
                + "и жесты в настройках", voiceIconToggle);
        row(card, "Плитка в шторке", "«Заметка голосом» в быстрых настройках — и на заблокированном экране", new Runnable() {
            @Override
            public void run() {
                if (!QuickNotes.askForTile(a, new Runnable() {
                    @Override
                    public void run() {
                        a.toast("Плитка «Заметка голосом» в шторке");
                    }
                })) {
                    a.toast("Опустите шторку, нажмите на карандаш и перетащите плитку «Заметка голосом»");
                }
            }
        });
        row(card, "Виджет на главный экран", "«Сказать заметку» в одно касание", new Runnable() {
            @Override
            public void run() {
                if (!QuickNotes.askForWidget(c)) a.toast("Долгое нажатие на главном экране → «Виджеты» → «Смысловой поиск»");
            }
        });
    }

    private void showQuick() {
        if (assistantValue == null) return;
        Boolean on = QuickNotes.isAssistant(getContext());
        assistantValue.setText(Boolean.TRUE.equals(on) ? "включено" : "настроить");
        assistantValue.setTextColor(Boolean.TRUE.equals(on) ? Ui.TEXT2 : Ui.ACCENT);
        boolean icon = QuickNotes.iconShown(getContext());
        if (voiceIconToggle.isOn() != icon) voiceIconToggle.setOn(icon, false);
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        // back from the phone's settings: the assistant may be chosen now
        if (focus) showQuick();
    }

    private void buildSoundAndFiles() {
        Context c = getContext();
        LinearLayout card = section("Документы и звук");
        foldersValue = row(card, "Папки с документами", "PDF, Word, Excel, PowerPoint, OpenDocument, RTF, текст, книги; "
                + "приложение читает только выбранные папки", null);
        foldersBox = new LinearLayout(c);
        foldersBox.setOrientation(LinearLayout.VERTICAL);
        card.addView(foldersBox);
        action(card, "Добавить папку", false, new Runnable() {
            @Override
            public void run() {
                a.pickFolder();
            }
        });
        soundValue = row(card, "Поиск по звуку", "записи, голосовые, музыка — по тому, что в них звучит; звуковая часть "
                + "EmbeddingGemma 2, на телефоне", null);
        soundNote = note(card);
        soundButton = action(card, "Скачать звуковую часть", true, new Runnable() {
            @Override
            public void run() {
                a.requestAudioAccess();
                e.downloadAudio();
                onEngineChanged();
            }
        });
        soundIndexToggle = new Toggle(c, e.soundIndexOn());
        soundIndexToggle.setListener(new Toggle.Listener() {
            @Override
            public void changed(boolean on) {
                e.setSoundIndex(on);
                if (on) a.requestAudioAccess();
            }
        });
        soundIndexRow = toggleRow(card, "Записи, голосовые, музыка", "звуки телефона и из выбранных папок", soundIndexToggle);
        videoSoundToggle = new Toggle(c, e.videoSoundOn());
        videoSoundToggle.setListener(new Toggle.Listener() {
            @Override
            public void changed(boolean on) {
                e.setVideoSound(on);
            }
        });
        videoSoundRow = toggleRow(card, "Звук в видео", "видео находится и по тому, что в нём говорят и звучит; при смене "
                + "видео индексируются заново", videoSoundToggle);
        soundDelete = quiet(card, "Удалить звуковую часть", Ui.TEXT2, new Runnable() {
            @Override
            public void run() {
                Sheet.confirm(root(), "Удалить звуковую часть?", "Найденные звуки останутся в поиске; новые не будут "
                        + "индексироваться, пока она не скачана снова.", "Удалить", new Runnable() {
                    @Override
                    public void run() {
                        e.deleteAudio();
                    }
                });
            }
        });
        if (!e.audioDownloaded() && e.hasModelFiles()) {
            e.audioSize(new Engine.Callback<Long>() {
                @Override
                public void done(Long n, Exception err) {
                    if (n != null && n > 0) {
                        soundMb = n / 1048576.0;
                        onEngineChanged();
                    }
                }
            });
        }
    }

    /** The folders' rows (each with ✕), rebuilt when they changed. */
    private void showFolders() {
        List<android.net.Uri> fs = e.folders();
        String key = fs.toString();
        if (key.equals(foldersShown)) return;
        foldersShown = key;
        foldersBox.removeAllViews();
        Context c = getContext();
        for (final android.net.Uri u : fs) {
            LinearLayout r = new LinearLayout(c);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(Ui.dp(c, 18), Ui.dp(c, 6), Ui.dp(c, 8), Ui.dp(c, 6));
            r.addView(Ui.icon(c, Icon.FOLDER, Ui.TEXT2, 22), new LinearLayout.LayoutParams(Ui.dp(c, 22), Ui.dp(c, 22)));
            TextView t = Ui.text(c, Folders.label(u), 14, Ui.TEXT, Ui.REGULAR);
            t.setPadding(Ui.dp(c, 10), 0, 0, 0);
            r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
            ImageView x = Ui.icon(c, Icon.CLOSE, Ui.TEXT3, 36);
            x.setContentDescription("Убрать папку " + Folders.label(u));
            x.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    Sheet.confirm(root(), "Убрать папку «" + Folders.label(u) + "»?", "Её документы уйдут из поиска; сами файлы "
                            + "останутся на месте.", "Убрать", new Runnable() {
                        @Override
                        public void run() {
                            e.removeFolder(u);
                            onEngineChanged();
                        }
                    });
                }
            });
            Ui.pressable(x);
            r.addView(x, new LinearLayout.LayoutParams(Ui.dp(c, 36), Ui.dp(c, 36)));
            foldersBox.addView(r);
        }
    }

    private void buildPeople() {
        LinearLayout card = section("Люди и питомцы");
        facesValue = row(card, "Узнавание людей по лицам", "лица находит YuNet, сравнивает SFace (модели OpenCV); всё на телефоне", null);
        facesProgress = progress(card);
        facesNote = note(card);
        facesButton = action(card, "Скачать модели лиц (≈40 МБ)", true, new Runnable() {
            @Override
            public void run() {
                a.downloadFaces();
                onEngineChanged();
            }
        });
        faceLevelValue = row(card, "Строгость узнавания", "строже — меньше чужих в одной группе, но один человек чаще в двух", new Runnable() {
            @Override
            public void run() {
                Sheet.choose(root(), "Строгость узнавания", new String[]{"Мягко", "Обычно", "Строго"},
                        new String[]{"больше фото человека, иногда и чужие", "рекомендуется",
                                "только явное сходство; человек может оказаться в двух группах — назовите обе одним именем"},
                        e.faceLevel(), new Sheet.Choice() {
                            @Override
                            public void chosen(int i) {
                                e.setFaceLevel(i);
                                onEngineChanged();
                            }
                        });
            }
        });
        hiddenFacesValue = row(card, "Скрытые лица", "лица, которые не нужно узнавать", new Runnable() {
            @Override
            public void run() {
                if (e.facesHidden() == 0) return;
                Sheet.confirm(root(), "Вернуть скрытые лица?", "Они снова появятся на фото и в группах «Кто это?».", "Вернуть",
                        new Runnable() {
                            @Override
                            public void run() {
                                e.showHiddenFaces(null);
                            }
                        });
            }
        });
        facesDelete = quiet(card, "Удалить модели лиц", Ui.TEXT2, new Runnable() {
            @Override
            public void run() {
                Sheet.confirm(root(), "Удалить модели лиц?", "Найденные лица и имена останутся; новые фото не будут "
                        + "проверяться на лица, пока модели не скачаны снова.", "Удалить", new Runnable() {
                    @Override
                    public void run() {
                        e.deleteFaces();
                    }
                });
            }
        });
        TextView how = Ui.text(getContext(), "Лица видны прямо на фото в просмотре: нажмите — назвать, значок глаза — скрыть. Питомца — «Отметить». "
                + "Люди и питомцы — в «Альбомах» на боковой панели.", 12.5f, Ui.TEXT3, Ui.REGULAR);
        how.setPadding(Ui.dp(getContext(), 18), Ui.dp(getContext(), 4), Ui.dp(getContext(), 18), Ui.dp(getContext(), 10));
        card.addView(how);
    }

    private void buildAdult() {
        Context c = getContext();
        LinearLayout card = section("18+");
        adultToggle = new Toggle(c, e.hideAdult());
        adultToggle.setListener(new Toggle.Listener() {
            @Override
            public void changed(boolean on) {
                adultNote.setText(!on ? "" : e.ready() ? "Проверяю галерею… (в первый раз — до полуминуты)"
                        : "Проверю галерею, когда загрузится модель");
                e.setHideAdult(on, adultDone(on));
                onEngineChanged();
            }
        });
        toggleRow(card, "Скрывать 18+", "откровенные фото и видео не видны в галерее, поиске и альбомах; файлы остаются на телефоне",
                adultToggle);
        adultLevelValue = row(card, "Строгость", null, new Runnable() {
            @Override
            public void run() {
                Sheet.choose(root(), "Строгость", new String[]{"Мягко", "Обычно", "Строго"},
                        new String[]{"только явное", "рекомендуется", "больше скрытого, в том числе обычные фото с открытым телом"},
                        e.adultLevel(), new Sheet.Choice() {
                            @Override
                            public void chosen(int i) {
                                adultNote.setText(e.ready() ? "Проверяю галерею…" : "Проверю галерею, когда загрузится модель");
                                e.setAdultLevel(i, adultDone(true));
                                onEngineChanged();
                            }
                        });
            }
        });
        hiddenValue = row(card, "Скрытое", "откроется вместо галереи; вернуть файл — «Вернуть» в просмотре", new Runnable() {
            @Override
            public void run() {
                a.leave(MainActivity.OVER_SETTINGS); // «Назад» from the folder: the settings again
                close();
                a.showHidden();
            }
        });
        adultNote = note(card);
    }

    private Engine.Callback<Integer> adultDone(final boolean on) {
        return new Engine.Callback<Integer>() {
            @Override
            public void done(Integer n, Exception err) {
                if (!on) return;
                if (err != null) adultNote.setText("Не получилось: " + err.getMessage());
                else if (e.ready()) adultNote.setText("Скрыто: " + n + ". Модель узнаёт откровенное по смыслу и может ошибаться: лишнее "
                        + "верните кнопкой «Вернуть» в просмотре, пропущенное скройте кнопкой «Скрыть».");
                onEngineChanged();
            }
        };
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
        showQuick();
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
        io.github.teoplaydor.semsearch.core.StageProgress bench = e.bench;
        boolean checking = bench != null && !bench.finished();
        long now = System.currentTimeMillis();
        modelProgress.setVisibility(busy || checking ? VISIBLE : GONE);
        modelProgress.setIndeterminate(!checking && (st == Engine.State.LOADING || e.dlTotal <= 0));
        if (checking) modelProgress.setProgress((float) bench.fraction(now));
        else if (e.dlTotal > 0) modelProgress.setProgress((float) e.dlDone / e.dlTotal);
        boolean fast = e.photoModel() != FastModel.GEMMA;
        String hint = checking ? "Подбор ускорения · " + bench.line(now) + " — подробнее"
                : e.dlError != null && !busy ? e.dlError
                : st == Engine.State.READY ? (fast ? e.accelLabel : Engine.ACCEL_NAMES[e.loadedAccel] + ", потоков " + e.threads)
                : st == Engine.State.DOWNLOADING && e.dlTotal > 0
                ? String.format(Locale.ROOT, "%.0f из %.0f МБ", e.dlDone / 1048576.0, e.dlTotal / 1048576.0)
                : st == Engine.State.ERROR ? e.status : "";
        modelHint.setText(hint);
        modelHint.setVisibility(hint.isEmpty() ? GONE : VISIBLE);
        modelHint.setTextColor(checking ? Ui.ACCENT : Ui.TEXT2);
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
        boolean faces = e.facesInstalled();
        IndexStore st0 = e.store();
        int docs = st0 == null ? 0 : st0.count(IndexStore.KIND_FILE), sounds = st0 == null ? 0 : st0.count(IndexStore.KIND_AUDIO);
        int nf = e.folders().size();
        foldersValue.setText(nf == 0 ? "нет" : nf + " " + MainActivity.plural(nf, "папка", "папки", "папок") + " · " + docs + " "
                + MainActivity.plural(docs, "документ", "документа", "документов"));
        showFolders();
        boolean audio = e.audioDownloaded() || e.audioReady();
        soundValue.setText(e.audioReady() ? "включён · " + sounds + " " + MainActivity.plural(sounds, "звук", "звука", "звуков") : audio ? (e.audioError() != null ? "ошибка" : "скачан")
                : st == Engine.State.DOWNLOADING ? "скачиваю" : "не скачан");
        soundValue.setTextColor(e.audioReady() ? Ui.ACCENT : e.audioError() != null ? Ui.DANGER : Ui.TEXT2);
        soundNote.setText(e.audioError() != null ? "Звуковая часть не загрузилась: " + e.audioError()
                : audio && !AutoIndex.hasAudioAccess(getContext()) ? "Нет доступа к аудио — звуки из выбранных папок найдутся, "
                + "записи и голосовые телефона нет" : "");
        soundNote.setVisibility(soundNote.getText().length() > 0 ? VISIBLE : GONE);
        soundButton.setText(soundMb > 0 ? String.format(Locale.ROOT, "Скачать звуковую часть (%.0f МБ)", soundMb) : "Скачать звуковую часть");
        soundButton.setVisibility(!audio && !e.audioReady() && !busy && e.photoModel() == FastModel.GEMMA ? VISIBLE : GONE);
        soundIndexRow.setVisibility(audio ? VISIBLE : GONE);
        videoSoundRow.setVisibility(audio && e.photoModel() == FastModel.GEMMA ? VISIBLE : GONE);
        soundDelete.setVisibility(audio && !busy ? VISIBLE : GONE);
        facesValue.setText(e.faceDownloading ? "скачиваю" : faces ? "включено" : "не скачано");
        facesValue.setTextColor(faces ? Ui.ACCENT : Ui.TEXT2);
        facesProgress.setVisibility(e.faceDownloading || e.faceScanning ? VISIBLE : GONE);
        if (e.faceDownloading) {
            facesProgress.setIndeterminate(e.faceDlTotal <= 0);
            if (e.faceDlTotal > 0) facesProgress.setProgress((float) e.faceDlDone / e.faceDlTotal);
        } else if (e.faceScanning) {
            facesProgress.setIndeterminate(e.faceTotal <= 0);
            if (e.faceTotal > 0) facesProgress.setProgress((float) e.faceDone / e.faceTotal);
        }
        String fn = e.faceDlError != null && !faces ? e.faceDlError
                : e.faceDownloading ? (e.faceDlTotal > 0 ? String.format(Locale.ROOT, "%.0f из %.0f МБ", e.faceDlDone / 1048576.0,
                e.faceDlTotal / 1048576.0) : "начинаю…")
                : e.faceScanning && e.faceTotal > 0 ? String.format(Locale.ROOT, "Ищу лица · %d из %d фото", e.faceDone, e.faceTotal)
                : faces ? String.format(Locale.ROOT, "Найдено лиц: %d на %d фото", e.facesFound(), e.photosScanned()) : "";
        facesNote.setText(fn);
        facesNote.setVisibility(fn.isEmpty() ? GONE : VISIBLE);
        facesNote.setTextColor(e.faceDlError != null && !faces ? Ui.DANGER : Ui.TEXT2);
        facesButton.setVisibility(!faces && !e.faceDownloading ? VISIBLE : GONE);
        facesDelete.setVisibility(faces && !e.faceDownloading && Engine.facesForTest == null ? VISIBLE : GONE);
        rowOf(faceLevelValue).setVisibility(faces ? VISIBLE : GONE);
        faceLevelValue.setText(Engine.FACE_LEVELS[e.faceLevel()]);
        int hiddenFaces = e.facesHidden();
        rowOf(hiddenFacesValue).setVisibility(hiddenFaces > 0 ? VISIBLE : GONE);
        hiddenFacesValue.setText(hiddenFaces + " · вернуть");
        if (facesDelete.getVisibility() == VISIBLE) facesDelete.setText(String.format(Locale.ROOT, "Удалить модели лиц (%d МБ)", e.facesBytes() >> 20));
        boolean adult = e.hideAdult();
        if (adultToggle.isOn() != adult) adultToggle.setOn(adult, false);
        rowOf(adultLevelValue).setVisibility(adult ? VISIBLE : GONE);
        rowOf(hiddenValue).setVisibility(adult ? VISIBLE : GONE);
        adultNote.setVisibility(adult && adultNote.length() > 0 ? VISIBLE : GONE);
        adultLevelValue.setText(Engine.ADULT_LEVELS[e.adultLevel()]);
        if (adult) hiddenValue.setText(String.valueOf(e.hiddenItems().size()));

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
        videosValue.setText(vl == 0 ? "нет" : vl >= Engine.VIDEO_LIMITS.length - 1 ? "все" : Engine.VIDEO_LIMITS[vl] + " последних");
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
