package hits.ui;

import java.util.prefs.Preferences;

/** Preferences stored on the {@link HitsApp} node. Failures leave the in-memory choice in place. */
final class AppPreferences {
    static final String COACH_DISMISSED = "coachDismissed";

    private AppPreferences() {}

    static String get(String key) {
        try {
            return Preferences.userNodeForPackage(HitsApp.class).get(key, "");
        } catch (Exception exception) {
            return "";
        }
    }

    static void put(String key, String value) {
        try {
            Preferences.userNodeForPackage(HitsApp.class).put(key, value);
        } catch (Exception ignored) {
            // The choice still applies until the app closes.
        }
    }

    static void remove(String key) {
        try {
            Preferences.userNodeForPackage(HitsApp.class).remove(key);
        } catch (Exception ignored) {
            // The loaded bank is already cleared.
        }
    }

    /** True when the preference store cannot be read, so the first-run hint stays hidden. */
    static boolean coachDismissed() {
        try {
            return Preferences.userNodeForPackage(HitsApp.class).getBoolean(COACH_DISMISSED, false);
        } catch (Exception exception) {
            return true;
        }
    }

    static void dismissCoach() {
        try {
            Preferences.userNodeForPackage(HitsApp.class).putBoolean(COACH_DISMISSED, true);
        } catch (Exception ignored) {
            // The hint can come back next launch if preferences cannot be stored.
        }
    }
}
