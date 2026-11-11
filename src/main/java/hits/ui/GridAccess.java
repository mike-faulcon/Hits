package hits.ui;

import hits.Slide;
import hits.Step;
import hits.Track;

/**
 * Names a screen reader speaks for the step grid. The focused cell reads like
 * {@code Kick, step 5 of 16, on, accent} or {@code Kick, step 5 of 16, off}.
 * The count is the number of drawn columns, the same range Home and End use.
 */
final class GridAccess {
    static final String GRID_NAME = "Step grid";

    private GridAccess() {}

    static String rowName(Track track) {
        return track.name();
    }

    static String cellName(Track track, int step, int count) {
        Step cell = track.step(step);
        StringBuilder text = new StringBuilder();
        text.append(track.name());
        text.append(", step ").append(step + 1).append(" of ").append(count);
        if (!cell.on()) {
            text.append(", off");
        } else {
            text.append(", on");
            switch (cell.shade(track.accent())) {
                case ACCENT -> text.append(", accent");
                case QUIET -> text.append(", quiet");
                default -> {
                }
            }
        }
        if (cell.slide() && Slide.applies(track)) {
            text.append(", slide");
        }
        if (track.mute()) {
            text.append(", muted");
        }
        if (track.solo()) {
            text.append(", solo");
        }
        return text.toString();
    }
}
