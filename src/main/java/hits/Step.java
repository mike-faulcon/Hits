package hits;

/**
 * One cell in a track. Velocity, play probability, note length, and nudge are kept when the cell is turned off.
 * Pitch is optional. A new hit takes the track's normal or loud-hit velocity; probability starts at 100.
 * Length starts at one step. Nudge starts on the grid.
 */
public final class Step {
    /** One step. Omitted from the beat file. */
    public static final int MIN_LENGTH = 1;
    /** A note can cover the whole stored pattern. */
    public static final int MAX_LENGTH = Track.CAPACITY;
    /** Half a step early. */
    public static final int MIN_NUDGE = -50;
    /** Half a step late. */
    public static final int MAX_NUDGE = 50;

    private boolean on;
    private int velocity;
    /** 0 never plays. 100 always plays. */
    private int probability = 100;
    /** {@code -1} plays the track note. Any other value is a MIDI note for this step only. */
    private int pitch = -1;
    /** How many steps this hit lasts. 1 is a single step. */
    private int length = MIN_LENGTH;
    /** Timing offset as a percent of one straight step, from {@link #MIN_NUDGE} to {@link #MAX_NUDGE}. */
    private int nudge;

    public Step() {
        this(false, 100);
    }

    public Step(boolean on, int velocity) {
        this.on = on;
        this.velocity = clamp(velocity);
    }

    public Step copy() {
        Step copy = new Step(on, velocity);
        copy.pitch = pitch;
        copy.probability = probability;
        copy.length = length;
        copy.nudge = nudge;
        return copy;
    }

    public void copyFrom(Step other) {
        this.on = other.on;
        this.velocity = other.velocity;
        this.pitch = other.pitch;
        this.probability = other.probability;
        this.length = other.length;
        this.nudge = other.nudge;
    }

    public boolean on() {
        return on;
    }

    public int velocity() {
        return velocity;
    }

    public void setOn(boolean on) {
        this.on = on;
    }

    public void setVelocity(int velocity) {
        this.velocity = clamp(velocity);
    }

    public int probability() {
        return probability;
    }

    public void setProbability(int probability) {
        this.probability = clampProbability(probability);
    }

    /** Stored chance is anything other than always-play. */
    public boolean uncertain() {
        return probability < 100;
    }

    public boolean hasPitch() {
        return pitch >= 0;
    }

    /** The step's own MIDI note, or {@code -1} when the step uses the track note. */
    public int pitch() {
        return pitch;
    }

    public void setPitch(int pitch) {
        this.pitch = Notes.clamp(pitch);
    }

    public void clearPitch() {
        this.pitch = -1;
    }

    /** Steps this hit holds. 1 is the grid default. */
    public int length() {
        return length;
    }

    public void setLength(int length) {
        this.length = clampLength(length);
    }

    /** True when the hit is stored longer than one step. */
    public boolean extended() {
        return length > MIN_LENGTH;
    }

    /**
     * Percent of one straight step, negative early and positive late.
     * 0 sits on the grid.
     */
    public int nudge() {
        return nudge;
    }

    public void setNudge(int nudge) {
        this.nudge = clampNudge(nudge);
    }

    /** True when this step is stored off the grid. */
    public boolean nudged() {
        return nudge != 0;
    }

    /**
     * Every stored field. Pattern C–H is omitted from the file when each track
     * {@linkplain Track#sameAs(Track) matches} the empty kit, so a new field belongs here.
     */
    public boolean sameAs(Step other) {
        return other != null
            && on == other.on
            && velocity == other.velocity
            && probability == other.probability
            && pitch == other.pitch
            && length == other.length
            && nudge == other.nudge;
    }

    public int soundingNote(int trackNote) {
        return pitch >= 0 ? pitch : Notes.clamp(trackNote);
    }

    /**
     * Grid tint for this cell. A hit at or above the track's loud-hit value is an accent.
     * Quieter hits, under 80 and below that value, draw dimmer than a normal hit.
     */
    public Shade shade(int accent) {
        if (!on) {
            return Shade.OFF;
        }
        if (velocity >= accent) {
            return Shade.ACCENT;
        }
        if (velocity < 80) {
            return Shade.QUIET;
        }
        return Shade.NORMAL;
    }

    public enum Shade {
        OFF, QUIET, NORMAL, ACCENT
    }

    private static int clamp(int velocity) {
        return Math.max(1, Math.min(127, velocity));
    }

    private static int clampProbability(int probability) {
        return Math.max(0, Math.min(100, probability));
    }

    /** Package-visible so the track shift can share the same limit. */
    public static int clampLength(int length) {
        return Math.max(MIN_LENGTH, Math.min(MAX_LENGTH, length));
    }

    /** Package-visible so a track shift uses the same half-step limit. */
    public static int clampNudge(int nudge) {
        return Math.max(MIN_NUDGE, Math.min(MAX_NUDGE, nudge));
    }
}
