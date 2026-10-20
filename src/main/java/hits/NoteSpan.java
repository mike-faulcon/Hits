package hits;

/**
 * Which cells a hit covers on the grid. Playback may carry a note into the next section;
 * the grid only draws cells inside this pattern.
 */
public final class NoteSpan {
    private NoteSpan() {}

    /**
     * Exclusive column of the bar that starts at {@code step}, capped at {@code stepCount}.
     * A legato note track holds until the next lit cell. Any other row uses the step length.
     */
    public static int displayEnd(Track track, int step, int stepCount) {
        if (step < 0 || step >= stepCount || !track.step(step).on()) {
            return step;
        }
        if (track.legato() && track.mode() == TrackMode.NOTE) {
            for (int next = step + 1; next < stepCount; next++) {
                if (track.step(next).on()) {
                    return next;
                }
            }
            return stepCount;
        }
        return Math.min(stepCount, step + track.step(step).length());
    }

    /**
     * The lit step whose bar covers this off cell, or {@code -1}.
     * A later hit wins when two lengths overlap.
     */
    public static int holder(Track track, int column, int stepCount) {
        if (column < 0 || column >= stepCount || track.step(column).on()) {
            return -1;
        }
        int found = -1;
        for (int step = 0; step < column; step++) {
            if (track.step(step).on() && displayEnd(track, step, stepCount) > column) {
                found = step;
            }
        }
        return found;
    }

    /** Length written while dragging from {@code origin} to {@code column}. {@code -1} is left of the grid. */
    public static int lengthForDrag(int origin, int column, int stepCount) {
        if (column < origin) {
            return Step.MIN_LENGTH;
        }
        int last = Math.min(stepCount - 1, column);
        return Step.clampLength(last - origin + 1);
    }
}
