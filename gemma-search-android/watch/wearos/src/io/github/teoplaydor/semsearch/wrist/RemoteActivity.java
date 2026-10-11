package io.github.teoplaydor.semsearch.wrist;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * One screen, round-friendly: what the link is doing and a button to start or stop it. The work itself is
 * {@link StreamService}, which runs on with the screen off.
 */
public final class RemoteActivity extends Activity {
    private static final int REQ = 1;
    private TextView state, button;

    private final Runnable show = new Runnable() {
        @Override
        public void run() {
            show();
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pad = dp(26);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setPadding(pad, pad, pad, pad);
        col.setBackgroundColor(Color.BLACK);
        TextView title = text("Пульт кистью", 16, 0xFFE6EDF3, true);
        col.addView(title);
        TextView how = text("Вправо — дальше, влево — назад. На телефоне: «Смысловой поиск» → Настройки → Часы.", 12, 0xFF8B96A3, false);
        how.setPadding(0, dp(6), 0, dp(6));
        col.addView(how);
        state = text("", 13, 0xFF8DA6C0, false);
        col.addView(state);
        button = text("", 15, 0xFF0E1116, true);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF8DA6C0);
        bg.setCornerRadius(dp(22));
        button.setBackground(bg);
        button.setPadding(dp(18), dp(10), dp(18), dp(10));
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggle();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(10);
        col.addView(button, lp);
        setContentView(col);
    }

    @Override
    protected void onResume() {
        super.onResume();
        StreamService.listen(show);
        show();
    }

    @Override
    protected void onPause() {
        StreamService.listen(null);
        super.onPause();
    }

    private void show() {
        boolean on = StreamService.isRunning();
        String st = StreamService.status();
        state.setText(on ? (st.isEmpty() ? "запускается…" : st) : "выключено");
        button.setText(on ? "Выключить" : "Включить");
    }

    private void toggle() {
        if (StreamService.isRunning()) {
            stopService(new Intent(this, StreamService.class));
            show();
            return;
        }
        List<String> need = new ArrayList<String>();
        for (String p : permissions()) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), REQ);
            return;
        }
        start();
    }

    /** Wear OS 4+ (Android 12+): «Nearby devices»; Wear OS 3: location, which a Bluetooth LE scan needs there. */
    static String[] permissions() {
        List<String> p = new ArrayList<String>();
        if (Build.VERSION.SDK_INT >= 31) {
            p.add("android.permission.BLUETOOTH_SCAN");
            p.add("android.permission.BLUETOOTH_CONNECT");
        } else {
            p.add("android.permission.ACCESS_FINE_LOCATION");
        }
        if (Build.VERSION.SDK_INT >= 33) p.add("android.permission.POST_NOTIFICATIONS");
        return p.toArray(new String[0]);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code != REQ) return;
        for (String p : permissions()) {
            if (p.endsWith("POST_NOTIFICATIONS")) continue; // nice to have: the service runs without it
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                state.setText("без доступа к устройствам поблизости телефон не найти");
                return;
            }
        }
        start();
    }

    private void start() {
        try {
            Intent i = new Intent(this, StreamService.class);
            Activity.class.getMethod("startForegroundService", Intent.class).invoke(this, i);
        } catch (Exception e) {
            state.setText("не запустилось: " + e);
        }
        show();
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
