package io.github.teoplaydor.semsearch.app;

import android.Manifest;
import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.TimeZone;

import io.github.teoplaydor.semsearch.core.Spoken;

/**
 * A note by voice in one go, over whatever is on the screen (the lock screen too): opened by holding the power button
 * (the app as the phone's assistant), a double press of the side key or Quick Tap (the «Голосовая заметка» icon), the
 * tile in the quick settings, the widget or the icon's shortcut. It listens at once, shows the words as they come, and
 * what was said is kept as a note at once — with a reminder when a time was said («напомни завтра в 9…», «каждый
 * понедельник…»), a list when items were («купить молоко, хлеб и сыр»); «найди…» opens the search instead. Then it
 * closes by itself; «Отменить» takes the note back, «Изменить» opens it.
 * The words are recognized by the phone's recognizer, on the phone itself where it can.
 */
public final class QuickNoteActivity extends Activity {
    static final String ACTION = "io.github.teoplaydor.semsearch.QUICK_NOTE";
    /** Start with the keyboard rather than the microphone. */
    static final String EXTRA_TYPE = "type";
    private static final int REQ_MIC = 1, REQ_VOICE = 2, REQ_NOTIFY = 3;
    private static final long CLOSE_AFTER_MS = 5000;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private LinearLayout card, chips, buttons;
    private TextView label, heard, when;
    private EditText typed;
    private Pulse mic;
    private SpeechRecognizer sr;
    private boolean onDevice, listening, closing, triedOther;
    private String partial = "";
    /** The note kept (null before). */
    IndexStore.Item saved;
    private final Runnable closeLater = new Runnable() {
        @Override
        public void run() {
            close();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        showOverLockScreen();
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        build();
        Engine.get(this); // the index opens meanwhile
        start(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // pressed again: a new note
        start(intent);
    }

    private void start(Intent intent) {
        ui.removeCallbacks(closeLater);
        saved = null;
        if (intent != null && intent.getBooleanExtra(EXTRA_TYPE, false)) type();
        else listen();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // gone from the screen while listening (a call, the home button): the microphone off
        if (listening) {
            stopRecognizer();
            hint("Нажмите на микрофон, чтобы продолжить");
        }
    }

    @Override
    protected void onDestroy() {
        stopRecognizer();
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        close();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        // touched: it stays until closed by hand
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) ui.removeCallbacks(closeLater);
        return super.dispatchTouchEvent(ev);
    }

    // ------------------------------------------------------------------ the card

    private void build() {
        final Context c = this;
        root = new FrameLayout(c);
        root.setBackgroundColor(0x99050709);
        root.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                close();
            }
        });
        card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.round(c, Ui.SURFACE, 28));
        card.setPadding(Ui.dp(c, 20), Ui.dp(c, 14), Ui.dp(c, 20), Ui.dp(c, 18));
        card.setClickable(true); // a tap on it is not a tap outside
        card.setElevation(Ui.dp(c, 12));

        LinearLayout top = new LinearLayout(c);
        top.setGravity(Gravity.CENTER_VERTICAL);
        label = Ui.text(c, "Быстрая заметка", 13, Ui.TEXT3, Ui.MEDIUM);
        top.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView x = Ui.icon(c, Icon.CLOSE, Ui.TEXT3, 40);
        x.setContentDescription("Закрыть");
        x.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                close();
            }
        });
        top.addView(x, new LinearLayout.LayoutParams(Ui.dp(c, 40), Ui.dp(c, 40)));
        card.addView(top);

        heard = Ui.text(c, "", 20, Ui.TEXT, Ui.MEDIUM);
        heard.setMinLines(2);
        heard.setMaxLines(9);
        heard.setPadding(0, Ui.dp(c, 2), 0, Ui.dp(c, 6));
        card.addView(heard);

        typed = new EditText(c);
        typed.setTextSize(20);
        typed.setTextColor(Ui.TEXT);
        typed.setHintTextColor(Ui.TEXT3);
        typed.setTypeface(Ui.font(c, Ui.MEDIUM));
        typed.setHint("Например: завтра в 9 позвонить маме");
        typed.setBackground(Ui.round(c, Ui.SURFACE2, 16));
        typed.setPadding(Ui.dp(c, 14), Ui.dp(c, 12), Ui.dp(c, 14), Ui.dp(c, 12));
        typed.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        typed.setImeOptions(EditorInfo.IME_ACTION_DONE);
        typed.setHorizontallyScrolling(false);
        typed.setMaxLines(6);
        typed.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int action, KeyEvent ev) {
                if (ev != null && ev.getAction() != KeyEvent.ACTION_DOWN) return true;
                submitTyped();
                return true;
            }
        });
        typed.setVisibility(View.GONE);
        card.addView(typed, new LinearLayout.LayoutParams(-1, -2));

        when = Ui.text(c, "", 14, Ui.ACCENT, Ui.MEDIUM);
        when.setCompoundDrawablePadding(Ui.dp(c, 8));
        when.setPadding(0, Ui.dp(c, 6), 0, Ui.dp(c, 4));
        when.setVisibility(View.GONE);
        when.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                askWhen("Когда напомнить?");
            }
        });
        card.addView(when);

        HorizontalScrollView hs = new HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        chips = new LinearLayout(c);
        chips.setPadding(0, Ui.dp(c, 6), 0, Ui.dp(c, 2));
        hs.addView(chips);
        card.addView(hs, new LinearLayout.LayoutParams(-1, -2));

        buttons = new LinearLayout(c);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        buttons.setPadding(0, Ui.dp(c, 14), 0, 0);
        card.addView(buttons, new LinearLayout.LayoutParams(-1, -2));

        mic = new Pulse(c);
        mic.setContentDescription("Говорить");

        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        final int m = Ui.dp(c, 10);
        cl.setMargins(m, 0, m, m);
        root.addView(card, cl);
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) card.getLayoutParams();
                lp.bottomMargin = m + in.getSystemWindowInsetBottom();
                lp.topMargin = m + in.getSystemWindowInsetTop();
                card.setLayoutParams(lp);
                return in.consumeSystemWindowInsets();
            }
        });
        setContentView(root);
        root.setAlpha(0f);
        root.animate().alpha(1f).setDuration(160).start();
        card.setTranslationY(Ui.dp(c, 60));
        card.animate().translationY(0).setDuration(260).setInterpolator(Ui.EASE).start();
    }

    private TextView button(String text, boolean primary, final Runnable r) {
        TextView b = Sheet.button(this, text, primary);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                r.run();
            }
        });
        return b;
    }

    private void setButtons(View... vs) {
        buttons.removeAllViews();
        for (int i = 0; i < vs.length; i++) {
            View v = vs[i];
            if (v == null) {
                buttons.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
                continue;
            }
            LinearLayout.LayoutParams lp = v == mic ? new LinearLayout.LayoutParams(Ui.dp(this, 72), Ui.dp(this, 72))
                    : new LinearLayout.LayoutParams(-2, Ui.dp(this, 46));
            if (i > 0 && vs[i - 1] != null) lp.leftMargin = Ui.dp(this, 8);
            buttons.addView(v, lp);
        }
    }

    // ------------------------------------------------------------------ listening

    /** The microphone on: the words as they come, kept when the speaking stops. */
    void listen() {
        stopRecognizer();
        typed.setVisibility(View.GONE);
        hideKeyboard();
        chips.removeAllViews();
        when.setVisibility(View.GONE);
        label.setText("Быстрая заметка");
        heard.setMinLines(2);
        partial = "";
        hint("Говорите — например: «напомни завтра в 9 позвонить маме»");
        mic.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listening) finishListening();
                else listen();
            }
        });
        setButtons(button("Напечатать", false, new Runnable() {
            @Override
            public void run() {
                type();
            }
        }), null, mic, null, button("Готово", true, new Runnable() {
            @Override
            public void run() {
                finishListening();
            }
        }));
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            hint("Нужен доступ к микрофону — разрешите, и можно говорить");
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        triedOther = false;
        startRecognizer(onDeviceAvailable());
    }

    private boolean onDeviceAvailable() {
        if (Build.VERSION.SDK_INT < 31) return false;
        try {
            return (Boolean) SpeechRecognizer.class.getMethod("isOnDeviceRecognitionAvailable", Context.class).invoke(null, this);
        } catch (Exception e) {
            return false;
        }
    }

    /** On the phone itself when it can (nothing leaves it), else the phone's usual recognizer. */
    private void startRecognizer(boolean local) {
        stopRecognizer();
        SpeechRecognizer r = null;
        onDevice = false;
        if (local) {
            try {
                r = (SpeechRecognizer) SpeechRecognizer.class.getMethod("createOnDeviceSpeechRecognizer", Context.class).invoke(null, this);
                onDevice = r != null;
            } catch (Exception e) {
                r = null;
            }
        }
        if (r == null) {
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                recognizerActivity();
                return;
            }
            r = SpeechRecognizer.createSpeechRecognizer(this);
        }
        sr = r;
        sr.setRecognitionListener(new Listener());
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault().toLanguageTag())
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName())
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L);
        listening = true;
        mic.setListening(true);
        try {
            sr.startListening(i);
        } catch (RuntimeException e) {
            listening = false;
            recognizerActivity();
        }
    }

    private void stopRecognizer() {
        listening = false;
        if (mic != null) mic.setListening(false);
        if (sr != null) {
            try {
                sr.cancel();
                sr.destroy();
            } catch (RuntimeException ignored) {
                // already gone
            }
            sr = null;
        }
    }

    /** «Готово» while listening: what was said so far is the note. */
    private void finishListening() {
        if (sr != null && listening) {
            try {
                sr.stopListening();
                return;
            } catch (RuntimeException ignored) {
                // fall through: what was heard is kept
            }
        }
        stopRecognizer();
        if (!partial.trim().isEmpty()) heard(partial);
    }

    /** No recognizer to talk to here: the phone's own dialog (Google's or the maker's), else the keyboard. */
    private void recognizerActivity() {
        stopRecognizer();
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Говорите — это станет заметкой");
        try {
            startActivityForResult(i, REQ_VOICE);
        } catch (Exception e) {
            type();
            hint("На телефоне нет распознавания речи — напечатайте или продиктуйте с клавиатуры (значок микрофона)");
        }
    }

    private final class Listener implements RecognitionListener {
        @Override
        public void onReadyForSpeech(Bundle params) {
            hint("Слушаю…");
        }

        @Override
        public void onBeginningOfSpeech() {
        }

        @Override
        public void onRmsChanged(float db) {
            mic.level(db);
        }

        @Override
        public void onBufferReceived(byte[] buffer) {
        }

        @Override
        public void onEndOfSpeech() {
            mic.setListening(false);
        }

        @Override
        public void onError(int error) {
            listening = false;
            mic.setListening(false);
            if (!partial.trim().isEmpty() && error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                // it stopped after words were heard: those are the note
                String p = partial;
                stopRecognizer();
                heard(p);
                return;
            }
            if (onDevice && !triedOther && error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                // the phone's own recognition has no Russian (yet) or is busy: the usual recognizer then
                triedOther = true;
                startRecognizer(false);
                return;
            }
            stopRecognizer();
            switch (error) {
                case SpeechRecognizer.ERROR_NO_MATCH:
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                    hint("Ничего не расслышал — нажмите на микрофон и скажите ещё раз");
                    break;
                case SpeechRecognizer.ERROR_NETWORK:
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                    hint("Распознаванию нужна сеть: скачайте русский язык для распознавания без сети (настройки "
                            + "голосового ввода) или напечатайте");
                    break;
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                    hint("Нет доступа к микрофону");
                    break;
                default:
                    recognizerActivity();
                    break;
            }
        }

        @Override
        public void onResults(Bundle results) {
            listening = false;
            ArrayList<String> said = results == null ? null : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            String text = said != null && !said.isEmpty() && said.get(0) != null ? said.get(0) : partial;
            stopRecognizer();
            if (text.trim().isEmpty()) {
                hint("Ничего не расслышал — нажмите на микрофон и скажите ещё раз");
                return;
            }
            heard(text);
        }

        @Override
        public void onPartialResults(Bundle results) {
            ArrayList<String> said = results == null ? null : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (said == null || said.isEmpty() || said.get(0) == null || said.get(0).trim().isEmpty()) return;
            partial = said.get(0);
            heard.setTextColor(Ui.TEXT);
            heard.setText(partial);
        }

        @Override
        public void onEvent(int type, Bundle params) {
        }
    }

    // ------------------------------------------------------------------ typing

    /** The keyboard instead (or after the recognizer failed). */
    void type() {
        stopRecognizer();
        label.setText("Быстрая заметка");
        heard.setText("");
        heard.setVisibility(View.GONE);
        chips.removeAllViews();
        when.setVisibility(View.GONE);
        typed.setVisibility(View.VISIBLE);
        if (!partial.trim().isEmpty() && typed.getText().length() == 0) typed.setText(partial);
        typed.setSelection(typed.getText().length());
        typed.requestFocus();
        ImageView voice = Ui.icon(this, Icon.MIC, Ui.TEXT, 46);
        voice.setContentDescription("Голосом");
        voice.setBackground(Ui.round(this, Ui.SURFACE3, 16));
        voice.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                heard.setVisibility(View.VISIBLE);
                listen();
            }
        });
        Ui.pressable(voice);
        setButtons(voice, null, button("Сохранить", true, new Runnable() {
            @Override
            public void run() {
                submitTyped();
            }
        }));
        ((LinearLayout.LayoutParams) voice.getLayoutParams()).width = Ui.dp(this, 46);
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null && typed.getVisibility() == View.VISIBLE) imm.showSoftInput(typed, InputMethodManager.SHOW_IMPLICIT);
            }
        }, 120);
    }

    private void submitTyped() {
        String s = typed.getText().toString();
        if (s.trim().isEmpty()) return;
        hideKeyboard();
        typed.setVisibility(View.GONE);
        typed.setText("");
        heard.setVisibility(View.VISIBLE);
        heard(s);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(root.getWindowToken(), 0);
    }

    private void hint(String s) {
        heard.setVisibility(View.VISIBLE);
        if (!partial.trim().isEmpty() && listening) return;
        heard.setTextColor(Ui.TEXT3);
        heard.setText(s);
    }

    // ------------------------------------------------------------------ what was said

    /** What was said (or typed): a search opened, or the note kept — with its reminder, if a time was said. */
    void heard(String said) {
        if (saved != null || closing) return;
        partial = "";
        final Spoken.Result r = Spoken.parse(said, System.currentTimeMillis(), TimeZone.getDefault());
        if (r.query != null && !r.query.isEmpty()) {
            Intent i = new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_SEARCH)
                    .putExtra(MainActivity.EXTRA_QUERY, r.query).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            openApp(i);
            return;
        }
        if (r.text.trim().isEmpty()) {
            hint("Ничего не расслышал — нажмите на микрофон и скажите ещё раз");
            return;
        }
        label.setText("Сохраняю…");
        heard.setTextColor(Ui.TEXT);
        heard.setText(r.text);
        setButtons();
        Engine.get(this).addNote(r.text, null, false, r.remindAt, r.repeat, new Engine.Callback<IndexStore.Item>() {
            @Override
            public void done(IndexStore.Item it, Exception e) {
                if (e != null || it == null) {
                    label.setText("Не сохранилось" + (e != null && e.getMessage() != null ? ": " + e.getMessage() : ""));
                    setButtons(null, button("Закрыть", false, new Runnable() {
                        @Override
                        public void run() {
                            close();
                        }
                    }));
                    return;
                }
                saved = it;
                showSaved(r.wantsReminder && r.remindAt == 0);
            }
        });
    }

    /** «Сохранено»: the note, its reminder; «Отменить», «Изменить», «Ещё»; closed by itself soon unless asked when. */
    private void showSaved(boolean ask) {
        label.setText("Сохранено в заметки");
        heard.setMinLines(1);
        heard.setTextColor(Ui.TEXT);
        heard.setText(saved.body);
        showWhen();
        setButtons(button("Отменить", false, new Runnable() {
            @Override
            public void run() {
                undo();
            }
        }), button("Изменить", false, new Runnable() {
            @Override
            public void run() {
                edit();
            }
        }), null, button("Ещё", true, new Runnable() {
            @Override
            public void run() {
                saved = null;
                listen();
            }
        }));
        if (ask) {
            askWhen("Когда напомнить?");
            return;
        }
        if (saved.remind > 0 && needsNotificationPermission()) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIFY);
            return;
        }
        ui.postDelayed(closeLater, CLOSE_AFTER_MS);
    }

    private void showWhen() {
        chips.removeAllViews();
        if (saved == null || saved.remind <= 0) {
            when.setVisibility(View.GONE);
            return;
        }
        when.setVisibility(View.VISIBLE);
        when.setCompoundDrawablesRelativeWithIntrinsicBounds(new Icon(Icon.BELL, Ui.ACCENT, Ui.dp(this, 1.6f)), null, null, null);
        when.setText("Напомнит " + NoteEditor.when(saved.remind)
                + (saved.repeat != Spoken.ONCE ? ", " + Spoken.repeatLabel(saved.repeat) : ""));
    }

    /** «Напомни…» without a time, or the time tapped: a few times to choose from. */
    private void askWhen(String question) {
        if (saved == null) return;
        ui.removeCallbacks(closeLater);
        label.setText(question);
        chips.removeAllViews();
        Calendar now = Calendar.getInstance();
        addChip("Через час", System.currentTimeMillis() + 3_600_000L);
        if (now.get(Calendar.HOUR_OF_DAY) < 18) addChip("Сегодня в 19:00", at(0, 19));
        addChip("Завтра в 9:00", at(1, 9));
        addChip("Через неделю", at(7, 9));
        if (saved.remind > 0) addChip("Без напоминания", 0);
        else addChip("Не надо", -1);
    }

    private void addChip(String text, final long t) {
        TextView chip = Ui.text(this, text, 14, Ui.TEXT, Ui.MEDIUM);
        chip.setGravity(Gravity.CENTER);
        chip.setBackground(Ui.round(this, Ui.SURFACE3, 18));
        chip.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        Ui.pressable(chip);
        chip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chosen(t);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, Ui.dp(this, 38));
        if (chips.getChildCount() > 0) lp.leftMargin = Ui.dp(this, 8);
        chips.addView(chip, lp);
    }

    private void chosen(long t) {
        if (saved == null) return;
        if (t >= 0) Engine.get(this).setReminder(saved, t, t > 0 ? saved.repeat : Spoken.ONCE);
        label.setText("Сохранено в заметки");
        showWhen();
        if (t > 0 && needsNotificationPermission()) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIFY);
            return;
        }
        ui.postDelayed(closeLater, CLOSE_AFTER_MS);
    }

    /** Today (+{@code days}) at {@code hour}:00. */
    private static long at(int days, int hour) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_YEAR, days);
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private void undo() {
        if (saved != null) Engine.get(this).deleteItem(saved);
        saved = null;
        label.setText("Заметка удалена");
        close();
    }

    private void edit() {
        if (saved == null) return;
        Intent i = new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_OPEN_NOTE)
                .putExtra(Reminders.EXTRA_NOTE, saved.id).putExtra(MainActivity.EXTRA_EDIT, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        openApp(i);
    }

    /** The app opened (asking to unlock first on the lock screen: the gallery is not shown over it). */
    private void openApp(Intent i) {
        stopRecognizer();
        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (km != null && km.isKeyguardLocked() && Build.VERSION.SDK_INT >= 26) {
            try {
                Class<?> cb = Class.forName("android.app.KeyguardManager$KeyguardDismissCallback");
                KeyguardManager.class.getMethod("requestDismissKeyguard", Activity.class, cb).invoke(km, this, null);
            } catch (Exception ignored) {
                // the system asks for the unlock as the app opens
            }
        }
        startActivity(i);
        closing = true;
        finish();
        overridePendingTransition(0, 0);
    }

    private boolean needsNotificationPermission() {
        return Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        boolean ok = res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED;
        if (req == REQ_MIC) {
            if (ok) listen();
            else recognizerActivity(); // the phone's own dialog needs no access of ours
        } else if (req == REQ_NOTIFY) {
            if (!ok) when.setText(when.getText() + " — уведомления выключены, напоминание не появится");
            ui.postDelayed(closeLater, CLOSE_AFTER_MS);
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_VOICE) return;
        ArrayList<String> said = data == null || res != RESULT_OK ? null : data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (said != null && !said.isEmpty() && said.get(0) != null && !said.get(0).trim().isEmpty()) heard(said.get(0));
        else type();
    }

    // ------------------------------------------------------------------ the window

    private void showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= 27) {
            try {
                Activity.class.getMethod("setShowWhenLocked", boolean.class).invoke(this, true);
                return;
            } catch (Exception ignored) {
                // the flag below then
            }
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
    }

    void close() {
        if (closing) return;
        closing = true;
        stopRecognizer();
        hideKeyboard();
        ui.removeCallbacks(closeLater);
        card.animate().translationY(card.getHeight() + Ui.dp(this, 40)).setDuration(200).setInterpolator(Ui.EASE_OUT).start();
        root.animate().alpha(0f).setDuration(200).withEndAction(new Runnable() {
            @Override
            public void run() {
                finish();
                overridePendingTransition(0, 0);
            }
        }).start();
    }

    /** The microphone: a round button that breathes with the voice while listening. */
    static final class Pulse extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Icon glyph;
        private boolean on;
        private float level, shown;

        Pulse(Context c) {
            super(c);
            glyph = new Icon(Icon.MIC, Ui.ON_ACCENT, Ui.dp(c, 2.2f));
            setClickable(true);
            Ui.pressable(this);
        }

        void setListening(boolean on) {
            this.on = on;
            if (!on) level = 0;
            invalidate();
        }

        /** The recognizer's loudness (dB, about −2…10). */
        void level(float db) {
            level = Math.max(0f, Math.min(1f, (db + 2f) / 12f));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy) * 0.74f;
            shown += (level - shown) * 0.35f;
            if (on) {
                p.setColor(Ui.ACCENT);
                p.setAlpha(60);
                c.drawCircle(cx, cy, r * (1.04f + 0.3f * shown), p);
                if (Math.abs(level - shown) > 0.01f) postInvalidateOnAnimation();
            }
            p.setColor(on ? Ui.ACCENT : Ui.SURFACE3);
            p.setAlpha(255);
            c.drawCircle(cx, cy, r, p);
            glyph.setColor(on ? Ui.ON_ACCENT : Ui.TEXT);
            int s = Math.round(r * 0.9f);
            glyph.setBounds(Math.round(cx - s / 2f), Math.round(cy - s / 2f), Math.round(cx + s / 2f), Math.round(cy + s / 2f));
            glyph.draw(c);
        }
    }
}
