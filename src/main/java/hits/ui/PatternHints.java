package hits.ui;

import hits.Beat;
import hits.Track;

/** Copy for an empty pattern. The grid paints it, and the status line keeps a shorter form. */
final class PatternHints {
    static final String EMPTY = "Click a square to add a hit. Press Space to play.";
    static final String STATUS = EMPTY + "  Help lists every shortcut.";

    private PatternHints() {}

    /** True when the pattern on screen has no hit inside its length. */
    static boolean quiet(Beat beat, char slot) {
        if (beat == null) {
            return false;
        }
        int length = beat.stepCount(slot);
        for (Track track : beat.slot(slot)) {
            for (int step = 0; step < length; step++) {
                if (track.step(step).on()) {
                    return false;
                }
            }
        }
        return true;
    }
}
