package io.github.teoplaydor.semsearch.app;

import android.Manifest;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.teoplaydor.semsearch.core.MiniJson;
import io.github.teoplaydor.semsearch.core.Spoken;

/**
 * The places reminders come at — «Дом», «Работа», «Магазин»… — each a point and a radius, saved where the phone was
 * when «Я сейчас здесь» was pressed. A note at a place sets a proximity alert with the phone's own location service
 * (no Google services needed): coming there (or leaving, «когда выйду из дома») shows the reminder.
 */
final class Places {
    static final String PREF = "places";
    static final float RADIUS = 150f;

    static final class Place {
        String name;
        double lat, lon;
        float radius = RADIUS;
    }

    private Places() {
    }

    static List<Place> list(Context c) {
        List<Place> out = new ArrayList<Place>();
        try {
            for (Object o : MiniJson.arr(MiniJson.parse(Engine.get(c).prefs().getString(PREF, "[]")))) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) o;
                Place p = new Place();
                p.name = MiniJson.str(m, "name", "");
                p.lat = ((Number) m.get("lat")).doubleValue();
                p.lon = ((Number) m.get("lon")).doubleValue();
                p.radius = (float) MiniJson.num(m, "radius", (long) RADIUS);
                if (!p.name.isEmpty()) out.add(p);
            }
        } catch (Exception e) {
            // none kept, or unreadable: none
        }
        return out;
    }

    private static void save(Context c, List<Place> ps) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < ps.size(); i++) {
            Place p = ps.get(i);
            b.append(i > 0 ? "," : "").append("{\"name\":").append(MiniJson.write(p.name)).append(",\"lat\":")
                    .append(String.format(Locale.ROOT, "%.7f", p.lat)).append(",\"lon\":").append(String.format(Locale.ROOT, "%.7f", p.lon))
                    .append(",\"radius\":").append(Math.round(p.radius)).append('}');
        }
        Engine.get(c).prefs().edit().putString(PREF, b.append(']').toString()).apply();
    }

    /** The place by its name as said («магазин», «в магазине», «Магазин» alike), or null. */
    static Place find(Context c, String said) {
        if (said == null) return null;
        String want = Spoken.placeName(said).toLowerCase(Locale.ROOT);
        for (Place p : list(c)) {
            String n = Spoken.placeName(p.name).toLowerCase(Locale.ROOT);
            if (n.equals(want) || p.name.equalsIgnoreCase(said.trim())) return p;
        }
        return null;
    }

    /** A place kept (a place of that name moved there), its notes' alerts set. */
    static void add(Context c, String name, double lat, double lon) {
        List<Place> ps = list(c);
        Place found = null;
        for (Place p : ps) if (Spoken.placeName(p.name).equalsIgnoreCase(Spoken.placeName(name))) found = p;
        if (found == null) {
            found = new Place();
            ps.add(found);
        }
        found.name = Spoken.placeName(name);
        found.lat = lat;
        found.lon = lon;
        save(c, ps);
        restore(c);
    }

    static void remove(Context c, String name) {
        List<Place> ps = list(c);
        for (int i = ps.size() - 1; i >= 0; i--) if (ps.get(i).name.equalsIgnoreCase(name)) ps.remove(i);
        save(c, ps);
        IndexStore s = Engine.get(c).store();
        if (s != null) for (IndexStore.Item n : s.placed()) if (name.equalsIgnoreCase(n.place)) disarm(c, n.id);
    }

    // ------------------------------------------------------------------ alerts

    static boolean canLocate(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /** Android 10+: alerts while the app is not open need «Allow all the time». */
    static boolean inBackground(Context c) {
        return Build.VERSION.SDK_INT < 29
                || c.checkSelfPermission("android.permission.ACCESS_BACKGROUND_LOCATION") == PackageManager.PERMISSION_GRANTED;
    }

    private static PendingIntent pending(Context c, long id) {
        Intent i = new Intent(c, PlaceAlert.class).setAction(PlaceAlert.ACTION).putExtra(Reminders.EXTRA_NOTE, id);
        int mutable = Build.VERSION.SDK_INT >= 31 ? 0x02000000 /* MUTABLE: the system adds «entering» */ : 0;
        return PendingIntent.getBroadcast(c, (int) id, i, PendingIntent.FLAG_UPDATE_CURRENT | mutable);
    }

    /** The note's alert at its place set (nothing when the place is not known yet or location is not allowed). */
    static boolean arm(Context c, IndexStore.Item note) {
        if (note.place == null) return false;
        Place p = find(c, note.place);
        LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        if (p == null || lm == null || !canLocate(c)) return false;
        try {
            lm.addProximityAlert(p.lat, p.lon, p.radius, -1, pending(c, note.id));
            return true;
        } catch (SecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    static void disarm(Context c, long id) {
        LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return;
        try {
            lm.removeProximityAlert(pending(c, id));
        } catch (SecurityException e) {
            // never set
        }
    }

    /** Every note's alert set again (after a reboot, a place added or moved, location allowed). */
    static void restore(Context c) {
        IndexStore s = Engine.get(c).store();
        if (s == null) return;
        for (IndexStore.Item n : s.placed()) arm(c, n);
    }

    // ------------------------------------------------------------------ where the phone is

    /**
     * Where the phone is now: a fresh fix (up to 20 s), else the last one known (null: none). On the main thread.
     */
    static void here(Context c, final Engine.Callback<Location> cb) {
        final LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null || !canLocate(c)) {
            cb.done(null, null);
            return;
        }
        final Handler h = new Handler(Looper.getMainLooper());
        final boolean[] done = {false};
        final Location[] best = {null};
        try {
            for (String p : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best[0] == null || l.getTime() > best[0].getTime())) best[0] = l;
            }
        } catch (SecurityException e) {
            // none
        }
        if (best[0] != null && System.currentTimeMillis() - best[0].getTime() < 120_000L && best[0].getAccuracy() <= 100) {
            cb.done(best[0], null);
            return;
        }
        final LocationListener ll = new LocationListener() {
            @Override
            public void onLocationChanged(Location l) {
                if (done[0]) return;
                done[0] = true;
                try {
                    lm.removeUpdates(this);
                } catch (SecurityException ignored) {
                    // gone
                }
                cb.done(l, null);
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {
            }

            @Override
            public void onProviderEnabled(String provider) {
            }

            @Override
            public void onProviderDisabled(String provider) {
            }
        };
        try {
            for (String p : lm.getProviders(true)) {
                if (LocationManager.PASSIVE_PROVIDER.equals(p)) continue;
                lm.requestLocationUpdates(p, 0, 0, ll, Looper.getMainLooper());
            }
        } catch (SecurityException e) {
            done[0] = true;
            cb.done(best[0], null);
            return;
        }
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (done[0]) return;
                done[0] = true;
                try {
                    lm.removeUpdates(ll);
                } catch (SecurityException ignored) {
                    // gone
                }
                cb.done(best[0], null);
            }
        }, 20_000L);
    }
}
