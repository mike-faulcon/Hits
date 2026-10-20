package hits.ui;

/** Maps a pad drag or a velocity-lane pointer into a MIDI velocity. */
final class VelocityGesture {
    static final int DRAG_SLOP = 6;

    private VelocityGesture() {}

    /** Dragging up raises velocity. Two pixels change it by one. */
    static int fromDrag(int startVelocity, int deltaY) {
        return clamp(startVelocity - deltaY / 2);
    }

    /** The top of the bar is 127. The bottom is 1. */
    static int fromLane(int y, int top, int height) {
        int span = Math.max(1, height - 1);
        int offset = Math.max(0, Math.min(span, y - top));
        return clamp(127 - (offset * 126) / span);
    }

    /** Height of the filled part of a pad. */
    static int fillHeight(int velocity, int cellHeight) {
        int span = Math.max(1, cellHeight);
        return Math.max(3, (span * clamp(velocity)) / 127);
    }

    private static int clamp(int velocity) {
        return Math.max(1, Math.min(127, velocity));
    }
}
