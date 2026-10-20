package hits;

/** One row: a drum voice or a single pitched instrument, 32 steps stored. */
public final class Track {
    public static final int CAPACITY = 32;
    /** Mixer fader at unity. Hit velocity is unchanged. */
    public static final int LEVEL = 100;
    /** MIDI pan center. */
    public static final int PAN_CENTER = 64;

    private String name;
    private TrackMode mode;
    private int program;
    private int note;
    private boolean mute;
    private boolean solo;
    private int level = LEVEL;
    private int pan = PAN_CENTER;
    private int gate;
    private int velocity;
    private int accent;
    /** Note tracks: each sounding hit holds until the next hit. */
    private boolean legato;
    /** Added to every step's nudge, as a percent of one straight step. */
    private int shift;
    private final Step[] steps = new Step[CAPACITY];

    public Track(String name, TrackMode mode, int program, int note) {
        this.name = name;
        this.mode = mode;
        this.program = clampProgram(program);
        this.note = Notes.clamp(note);
        this.gate = mode == TrackMode.DRUM ? 45 : 80;
        this.velocity = 100;
        this.accent = 120;
        for (int i = 0; i < steps.length; i++) {
            steps[i] = new Step();
        }
    }

    /**
     * Every field on the row, including each step. A pattern C–H that differs only
     * in one of these is still written. Add a comparison here when adding a field.
     */
    public boolean sameAs(Track other) {
        if (other == null
            || !name.equals(other.name)
            || mode != other.mode
            || program != other.program
            || note != other.note
            || mute != other.mute
            || solo != other.solo
            || level != other.level
            || pan != other.pan
            || gate != other.gate
            || velocity != other.velocity
            || accent != other.accent
            || legato != other.legato
            || shift != other.shift) {
            return false;
        }
        for (int i = 0; i < CAPACITY; i++) {
            if (!steps[i].sameAs(other.steps[i])) {
                return false;
            }
        }
        return true;
    }

    public Track copy() {
        Track copy = new Track(name, mode, program, note);
        copy.mute = mute;
        copy.solo = solo;
        copy.level = level;
        copy.pan = pan;
        copy.gate = gate;
        copy.velocity = velocity;
        copy.accent = accent;
        copy.legato = legato;
        copy.shift = shift;
        for (int i = 0; i < steps.length; i++) {
            copy.steps[i] = steps[i].copy();
        }
        return copy;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        if (name != null && !name.isBlank()) {
            this.name = name.trim();
        }
    }

    public TrackMode mode() {
        return mode;
    }

    public void setMode(TrackMode mode) {
        this.mode = mode == null ? TrackMode.DRUM : mode;
    }

    public int program() {
        return program;
    }

    public void setProgram(int program) {
        this.program = clampProgram(program);
    }

    public int note() {
        return note;
    }

    public void setNote(int note) {
        this.note = Notes.clamp(note);
    }

    public boolean mute() {
        return mute;
    }

    public void setMute(boolean mute) {
        this.mute = mute;
    }

    public boolean solo() {
        return solo;
    }

    public void setSolo(boolean solo) {
        this.solo = solo;
    }

    /** Mixer level, 0 to 127. {@link #LEVEL} leaves hit velocity as programmed. */
    public int level() {
        return level;
    }

    public void setLevel(int level) {
        this.level = Math.max(0, Math.min(127, level));
    }

    /** MIDI pan, 0 to 127. {@link #PAN_CENTER} is the middle. */
    public int pan() {
        return pan;
    }

    public void setPan(int pan) {
        this.pan = Math.max(0, Math.min(127, pan));
    }

    public int gate() {
        return gate;
    }

    public void setGate(int gate) {
        this.gate = Math.max(5, Math.min(100, gate));
    }

    public int velocity() {
        return velocity;
    }

    public void setVelocity(int velocity) {
        this.velocity = Math.max(1, Math.min(127, velocity));
    }

    public int accent() {
        return accent;
    }

    public void setAccent(int accent) {
        this.accent = Math.max(1, Math.min(127, accent));
    }

    /**
     * When this is a note track, each hit holds until the next hit on the row.
     * Drum tracks remember the flag and ignore it while they stay drums.
     */
    public boolean legato() {
        return legato;
    }

    public void setLegato(boolean legato) {
        this.legato = legato;
    }

    /** Percent of a step added to every step nudge. 0 leaves each step where it is. */
    public int shift() {
        return shift;
    }

    public void setShift(int shift) {
        this.shift = Step.clampNudge(shift);
    }

    /**
     * The offset that playback uses. Step nudge and track shift add together,
     * then stay inside half a step. Swing is applied as well, and the sounding tick
     * is kept off the next grid point so two hits cannot land together.
     */
    public int playedNudge(int index) {
        return Step.clampNudge(steps[index].nudge() + shift);
    }

    public Step step(int index) {
        return steps[index];
    }

    public String soundName() {
        if (mode == TrackMode.DRUM) {
            return Gm.drumName(note);
        }
        return Gm.programName(program);
    }

    /**
     * Click toggles the step at the track velocity.
     * Shift-click turns it on at the accent velocity, or swaps accent and normal.
     */
    public void tap(int index, boolean accented) {
        Step step = steps[index];
        if (accented) {
            if (!step.on()) {
                step.setOn(true);
                step.setVelocity(accent);
            } else if (step.velocity() >= accent) {
                step.setVelocity(velocity);
            } else {
                step.setVelocity(accent);
            }
            return;
        }
        if (step.on()) {
            step.setOn(false);
        } else {
            step.setOn(true);
            step.setVelocity(velocity);
        }
    }

    public void clearSteps() {
        for (Step step : steps) {
            step.setOn(false);
            step.setVelocity(velocity);
            step.setProbability(100);
            step.setLength(Step.MIN_LENGTH);
            step.setNudge(0);
        }
    }

    private static int clampProgram(int program) {
        return Math.max(0, Math.min(127, program));
    }
}
