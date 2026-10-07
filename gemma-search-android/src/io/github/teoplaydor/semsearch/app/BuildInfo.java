package io.github.teoplaydor.semsearch.app;

import android.content.Context;

/** App version string for error reports. */
final class BuildInfo {
    private BuildInfo() {}

    static String version(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }
}
