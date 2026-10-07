package hits;

/** One cell in a track. Velocity is kept when the cell is turned off. Pitch is optional. */
public final class Step {
    private boolean on;
    private int velocity;
    /** {@code -1} plays the track note. Any other value is a MIDI note for this step only. */
    private int pitch = -1;

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
        return copy;
    }

    public void copyFrom(Step other) {
        this.on = other.on;
        this.velocity = other.velocity;
        this.pitch = other.pitch;
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
}
