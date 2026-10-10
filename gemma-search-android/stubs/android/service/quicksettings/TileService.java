package android.service.quicksettings;

/**
 * Compile-only stand-in (the app compiles against Android 6's android.jar, which has no quick settings tiles): the
 * few members QuickTile uses, matching Android 7+'s. Not packed into the APK — the phone's own class is used.
 */
public class TileService extends android.app.Service {
    public void onClick() {
    }

    public void onStartListening() {
    }

    public void onTileAdded() {
    }

    public final Tile getQsTile() {
        return null;
    }

    public final void startActivityAndCollapse(android.content.Intent intent) {
    }

    public final boolean isLocked() {
        return false;
    }

    @Override
    public android.os.IBinder onBind(android.content.Intent intent) {
        return null;
    }
}
