package androidx.test.espresso;

/** Minimal stand-in for the androidx.test API Robolectric 4 links against (Google Maven is not reachable here). */
public interface IdlingResource {
    String getName();

    boolean isIdleNow();

    void registerIdleTransitionCallback(ResourceCallback callback);

    interface ResourceCallback {
        void onTransitionToIdle();
    }
}
