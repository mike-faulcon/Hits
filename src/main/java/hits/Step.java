package hits;

/** One cell in a track. Velocity is kept when the cell is turned off. */
public final class Step {
    private boolean on;
    private int velocity;

    public Step() {
        this(false, 100);
    }

    public Step(boolean on, int velocity) {
        this.on = on;
        this.velocity = clamp(velocity);
    }

    public Step copy() {
        return new Step(on, velocity);
    }

    public void copyFrom(Step other) {
        this.on = other.on;
        this.velocity = other.velocity;
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
