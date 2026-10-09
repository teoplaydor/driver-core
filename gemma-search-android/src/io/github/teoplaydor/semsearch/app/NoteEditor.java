package io.github.teoplaydor.semsearch.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.Date;

import io.github.teoplaydor.semsearch.core.NoteText;

/**
 * A note written or changed, over the whole screen: its text (a list's items with boxes to tick: «Список» gives the
 * line under the cursor a box, and a new line after an item gets one too — an empty item ends the list), dictated
 * by voice into where the cursor is, pinned (first among the notes), with a reminder, made to a photo (it shows with
 * it). What is on the clipboard is offered for a new note. «Готово» and «Назад» both keep it; a new note left empty
 * is not kept, a note emptied is deleted.
 */
final class NoteEditor extends FrameLayout {
    private final MainActivity a;
    /** The note changed, or null for a new one; the photo a new one is made to. */
    final IndexStore.Item note;
    private final IndexStore.Item photo;
    final EditText text;
    private final ImageView pinView;
    private final TextView remindLine;
    private final LinearLayout remindRow;
    boolean pinned;
    long remind;
    private boolean closing, editing;

    NoteEditor(MainActivity activity, IndexStore.Item note, IndexStore.Item photo, String start) {
        super(activity);
        a = activity;
        this.note = note;
        this.photo = note == null ? photo : null;
        pinned = note != null && note.pinned;
        remind = note != null ? note.remind : 0;
        final Context c = activity;
        setBackgroundColor(Ui.BG);
        setClickable(true);
        setTranslationZ(Ui.dp(c, 22)); // over the viewer it may be opened from

        LinearLayout column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        addView(column, new LayoutParams(-1, -1));

        // the bar: back (keeps it), what this is, pin, delete
        LinearLayout bar = new LinearLayout(c);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(c, 8), Ui.dp(c, 10), Ui.dp(c, 8), Ui.dp(c, 6));
        ImageView back = Ui.icon(c, Icon.BACK, Ui.TEXT, 44);
        back.setContentDescription("Назад");
        back.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                done();
            }
        });
        bar.addView(back, new LinearLayout.LayoutParams(Ui.dp(c, 44), Ui.dp(c, 44)));
        TextView title = Ui.text(c, note == null ? (this.photo != null ? "Заметка к фото" : "Новая заметка") : "Заметка", 20, Ui.TEXT, Ui.SEMIBOLD);
        title.setPadding(Ui.dp(c, 6), 0, 0, 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        pinView = Ui.icon(c, Icon.PIN, Ui.TEXT2, 44);
        pinView.setContentDescription("Закрепить");
        pinView.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                pinned = !pinned;
                showPin();
                a.toast(pinned ? "Закреплена — первой среди заметок" : "Откреплена");
            }
        });
        bar.addView(pinView, new LinearLayout.LayoutParams(Ui.dp(c, 44), Ui.dp(c, 44)));
        if (note != null) {
            ImageView delete = Ui.icon(c, Icon.TRASH, Ui.TEXT2, 44);
            delete.setContentDescription("Удалить");
            delete.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    a.confirmDeleteNote(NoteEditor.this);
                }
            });
            bar.addView(delete, new LinearLayout.LayoutParams(Ui.dp(c, 44), Ui.dp(c, 44)));
        }
        column.addView(bar);

        // the photo it is made to
        IndexStore.Item shownPhoto = note != null ? a.linkedPhoto(note) : this.photo;
        if (shownPhoto != null) {
            LinearLayout to = new LinearLayout(c);
            to.setGravity(Gravity.CENTER_VERTICAL);
            to.setPadding(Ui.dp(c, 20), Ui.dp(c, 2), Ui.dp(c, 20), Ui.dp(c, 8));
            MasonryView.Thumb thumb = new MasonryView.Thumb(c);
            Bitmap b = a.thumb(shownPhoto);
            if (b != null) thumb.setImageBitmap(b);
            else a.thumbInto(thumb, shownPhoto, 256);
            to.addView(thumb, new LinearLayout.LayoutParams(Ui.dp(c, 52), Ui.dp(c, 52)));
            TextView tl = Ui.text(c, "К фото" + (shownPhoto.title != null ? " · " + shownPhoto.title : ""), 13, Ui.TEXT2, Ui.REGULAR);
            tl.setPadding(Ui.dp(c, 12), 0, 0, 0);
            tl.setSingleLine(true);
            to.addView(tl, new LinearLayout.LayoutParams(0, -2, 1));
            column.addView(to);
        }

        // the reminder set
        remindRow = new LinearLayout(c);
        remindRow.setGravity(Gravity.CENTER_VERTICAL);
        remindRow.setPadding(Ui.dp(c, 20), 0, Ui.dp(c, 12), Ui.dp(c, 6));
        remindRow.addView(Ui.icon(c, Icon.BELL, Ui.ACCENT, 20), new LinearLayout.LayoutParams(Ui.dp(c, 20), Ui.dp(c, 20)));
        remindLine = Ui.text(c, "", 14, Ui.TEXT, Ui.MEDIUM);
        remindLine.setPadding(Ui.dp(c, 10), 0, 0, 0);
        remindLine.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseReminder();
            }
        });
        remindRow.addView(remindLine, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView unremind = Ui.icon(c, Icon.CLOSE, Ui.TEXT3, 36);
        unremind.setContentDescription("Убрать напоминание");
        unremind.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                remind = 0;
                showReminder();
            }
        });
        remindRow.addView(unremind, new LinearLayout.LayoutParams(Ui.dp(c, 36), Ui.dp(c, 36)));
        column.addView(remindRow);

        // the text
        ScrollView scroll = new ScrollView(c);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        text = new EditText(c);
        text.setHint("Что запомнить? Списком — кнопка «Список» внизу");
        text.setTextColor(Ui.TEXT);
        text.setHintTextColor(Ui.TEXT3);
        text.setTextSize(17);
        text.setLineSpacing(0, 1.3f);
        text.setTypeface(Ui.font(c, Ui.REGULAR));
        text.setGravity(Gravity.TOP | Gravity.START);
        text.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        text.setBackground(null);
        text.setPadding(Ui.dp(c, 22), Ui.dp(c, 8), Ui.dp(c, 22), Ui.dp(c, 24));
        String initial = note != null ? note.body : start != null ? start : "";
        text.setText(initial);
        text.setSelection(text.length());
        text.addTextChangedListener(new ListContinuation());
        scroll.addView(text, new ScrollView.LayoutParams(-1, -1));
        column.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        // a new note: what was copied, offered
        if (note == null && initial.isEmpty()) {
            final String clip = clipboard();
            if (clip != null) {
                final TextView paste = Ui.text(c, "Вставить из буфера: «" + NoteText.title(clip, 40) + "»", 13.5f, Ui.TEXT, Ui.MEDIUM);
                paste.setSingleLine(true);
                paste.setBackground(Ui.round(c, Ui.SURFACE2, 16));
                paste.setPadding(Ui.dp(c, 14), Ui.dp(c, 10), Ui.dp(c, 14), Ui.dp(c, 10));
                paste.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        insert(clip);
                        paste.setVisibility(GONE);
                    }
                });
                Ui.pressable(paste);
                LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, -2);
                pl.setMargins(Ui.dp(c, 16), 0, Ui.dp(c, 16), Ui.dp(c, 8));
                column.addView(paste, pl);
            }
        }

        // the tools: a list, voice, a reminder; done
        LinearLayout tools = new LinearLayout(c);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        tools.setPadding(Ui.dp(c, 10), Ui.dp(c, 6), Ui.dp(c, 12), Ui.dp(c, 12));
        tools.addView(tool(Icon.LIST, "Список", new Runnable() {
            @Override
            public void run() {
                toggleList();
            }
        }));
        tools.addView(tool(Icon.MIC, "Голосом", new Runnable() {
            @Override
            public void run() {
                dictate();
            }
        }));
        tools.addView(tool(Icon.BELL, "Напомнить", new Runnable() {
            @Override
            public void run() {
                chooseReminder();
            }
        }));
        tools.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1));
        TextView ok = Sheet.button(c, "Готово", true);
        ok.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                done();
            }
        });
        tools.addView(ok, new LinearLayout.LayoutParams(-2, Ui.dp(c, 46)));
        column.addView(tools);
        showPin();
        showReminder();
    }

    private View tool(int icon, String label, final Runnable r) {
        Context c = getContext();
        LinearLayout b = new LinearLayout(c);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER);
        b.setPadding(Ui.dp(c, 10), Ui.dp(c, 4), Ui.dp(c, 10), Ui.dp(c, 4));
        b.addView(Ui.icon(c, icon, Ui.TEXT, 24), new LinearLayout.LayoutParams(Ui.dp(c, 24), Ui.dp(c, 24)));
        TextView t = Ui.text(c, label, 11, Ui.TEXT2, Ui.MEDIUM);
        t.setPadding(0, Ui.dp(c, 4), 0, 0);
        t.setMaxLines(1);
        b.addView(t, new LinearLayout.LayoutParams(-2, -2));
        b.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                r.run();
            }
        });
        Ui.pressable(b);
        return b;
    }

    private void showPin() {
        pinView.setImageDrawable(new Icon(Icon.PIN, pinned ? Ui.ACCENT : Ui.TEXT2, Ui.dp(getContext(), 1.6f)));
        pinView.setBackground(pinned ? Ui.round(getContext(), Ui.ACCENT_SOFT, 22) : null);
    }

    void showReminder() {
        remindRow.setVisibility(remind > 0 ? VISIBLE : GONE);
        if (remind > 0) remindLine.setText("Напомнит " + when(remind));
    }

    /** «12 октября, 9:00» (today and tomorrow by name). */
    static String when(long at) {
        java.util.Calendar now = java.util.Calendar.getInstance(), t = java.util.Calendar.getInstance();
        t.setTimeInMillis(at);
        String time = DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(at));
        int days = (t.get(java.util.Calendar.YEAR) - now.get(java.util.Calendar.YEAR)) * 366
                + t.get(java.util.Calendar.DAY_OF_YEAR) - now.get(java.util.Calendar.DAY_OF_YEAR);
        if (days == 0) return "сегодня в " + time;
        if (days == 1) return "завтра в " + time;
        return DateFormat.getDateInstance(DateFormat.LONG).format(new Date(at)) + ", " + time;
    }

    private void chooseReminder() {
        a.chooseReminder(remind, new Engine.Callback<Long>() {
            @Override
            public void done(Long at, Exception e) {
                if (at == null) return;
                remind = at;
                showReminder();
            }
        });
    }

    /** The cursor's line (each of the lines selected) given a box to tick, or its box taken away. */
    void toggleList() {
        String body = text.getText().toString();
        int s = Math.min(text.getSelectionStart(), text.getSelectionEnd()), e = Math.max(text.getSelectionStart(), text.getSelectionEnd());
        if (s < 0) s = e = body.length();
        int first = NoteText.lineAt(body, s), last = NoteText.lineAt(body, e);
        boolean add = NoteText.box(NoteText.lines(body)[first]) == 0;
        for (int i = first; i <= last; i++) {
            boolean has = NoteText.box(NoteText.lines(body)[i]) != 0;
            if (has == add) continue;
            body = NoteText.toggleList(body, i);
        }
        editing = true;
        text.setText(body);
        editing = false;
        // the cursor at the end of the last line changed
        int end = NoteText.lineStart(body, last) + NoteText.lines(body)[last].length();
        text.setSelection(Math.min(end, body.length()));
    }

    private void dictate() {
        a.dictate(new Engine.Callback<String>() {
            @Override
            public void done(String said, Exception e) {
                if (said != null && !said.trim().isEmpty()) insert(said.trim());
            }
        });
    }

    /** Text put where the cursor is (a space before it when it follows a word). */
    void insert(String s) {
        int at = Math.max(0, text.getSelectionStart());
        Editable ed = text.getText();
        if (at > 0 && !Character.isWhitespace(ed.charAt(at - 1))) s = " " + s;
        ed.insert(at, s);
        text.setSelection(at + s.length());
        text.requestFocus();
    }

    private String clipboard() {
        try {
            ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData d = cm == null ? null : cm.getPrimaryClip();
            if (d == null || d.getItemCount() == 0) return null;
            CharSequence t = d.getItemAt(0).coerceToText(getContext());
            String s = t == null ? null : t.toString().trim();
            return s == null || s.isEmpty() ? null : s;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * A new line after a list's item: an item too; after an empty item: the list ends there (its box gone, no new
     * line).
     */
    private final class ListContinuation implements TextWatcher {
        private int at = -1;

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            at = !editing && count == 1 && before == 0 && s.charAt(start) == '\n' ? start : -1;
        }

        @Override
        public void afterTextChanged(Editable ed) {
            if (at < 0) return;
            int nl = at;
            at = -1;
            String body = ed.toString();
            int line = NoteText.lineAt(body, nl);
            String prev = NoteText.lines(body)[line];
            if (NoteText.box(prev) == 0) return;
            editing = true;
            if (NoteText.rest(prev).trim().isEmpty()) {
                // an empty item and Enter: the list ends — no box, no new line
                int ls = NoteText.lineStart(body, line);
                ed.replace(ls, nl + 1, "");
            } else {
                ed.insert(nl + 1, NoteText.OPEN);
            }
            editing = false;
        }
    }

    void open(ViewGroup parent) {
        parent.addView(this, new ViewGroup.LayoutParams(-1, -1));
        setAlpha(0f);
        setTranslationY(Ui.dp(getContext(), 24));
        animate().alpha(1f).translationY(0).setDuration(220).setInterpolator(Ui.EASE).start();
        if (note == null) {
            text.requestFocus();
            text.post(new Runnable() {
                @Override
                public void run() {
                    android.view.inputmethod.InputMethodManager im = (android.view.inputmethod.InputMethodManager)
                            getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (im != null) im.showSoftInput(text, 0);
                }
            });
        }
    }

    boolean isClosing() {
        return closing;
    }

    /** Keeps it (see the class) and closes. */
    void done() {
        if (closing) return;
        a.saveNote(this, text.getText().toString().replaceAll("\\s+$", ""), photo);
        close();
    }

    void close() {
        if (closing) return;
        closing = true;
        android.view.inputmethod.InputMethodManager im = (android.view.inputmethod.InputMethodManager)
                getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(getWindowToken(), 0);
        animate().alpha(0f).translationY(Ui.dp(getContext(), 24)).setDuration(180).withEndAction(new Runnable() {
            @Override
            public void run() {
                ViewGroup p = (ViewGroup) getParent();
                if (p != null) p.removeView(NoteEditor.this);
                a.noteEditorClosed(NoteEditor.this);
            }
        }).start();
    }
}
