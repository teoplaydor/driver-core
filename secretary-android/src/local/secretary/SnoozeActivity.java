package local.secretary;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;

/** «Отложить»: маленький выбор, когда спросить снова. */
public class SnoozeActivity extends Activity {
    private static final String[] LABELS = {"Через 30 минут", "Через 2 часа", "Вечером", "Завтра утром", "Не нужно"};
    private static final String[] CHOICES = {"30m", "2h", "evening", "tomorrow", ""};

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        long id = getIntent().getLongExtra(Tasks.EXTRA_ID, 0);
        String title = getIntent().getStringExtra(Tasks.EXTRA_TITLE);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Когда спросить снова?")
                .setMessage(null)
                .setItems(LABELS, (d, which) -> {
                    if (CHOICES[which].isEmpty()) Tasks.answer(this, id, "drop", "");
                    else Tasks.answer(this, id, "snooze", CHOICES[which]);
                    finish();
                })
                .setOnCancelListener(d -> finish())
                .show()
                .setTitle(title == null ? "Когда спросить снова?" : "Когда спросить про «" + title + "»?");
    }
}
