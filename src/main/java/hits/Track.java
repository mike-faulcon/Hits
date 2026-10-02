package hits;

/** One row: a drum voice or a single pitched instrument, 32 steps stored. */
public final class Track {
    public static final int CAPACITY = 32;

    private String name;
    private TrackMode mode;
    private int program;
    private int note;
    private boolean mute;
    private boolean solo;
    private int gate;
    private int velocity;
    private int accent;
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

    public Track copy() {
        Track copy = new Track(name, mode, program, note);
        copy.mute = mute;
        copy.solo = solo;
        copy.gate = gate;
        copy.velocity = velocity;
        copy.accent = accent;
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
        }
    }

    private static int clampProgram(int program) {
        return Math.max(0, Math.min(127, program));
    }
}
