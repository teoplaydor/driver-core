package android.service.quicksettings;

/** Compile-only stand-in for Android 7+'s Tile (see TileService here). */
public final class Tile {
    public static final int STATE_UNAVAILABLE = 0, STATE_INACTIVE = 1, STATE_ACTIVE = 2;

    public void setState(int state) {
    }

    public void setLabel(CharSequence label) {
    }

    public void updateTile() {
    }
}
