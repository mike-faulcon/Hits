package hits;

/** One row: a drum voice or a single pitched instrument, 32 steps stored. */
public final class Track {
    public static final int CAPACITY = 32;
    /** Mixer fader at unity. Hit velocity is unchanged. */
    public static final int LEVEL = 100;
    /** MIDI pan center. */
    public static final int PAN_CENTER = 64;
    /** Tune, in cents. Zero is the instrument's own pitch and is left out of the file. */
    public static final int TUNE_MIN = -2400;
    public static final int TUNE_MAX = 2400;
    /** Decay, as a percent of the instrument's own release. 100 is left out of the file. */
    public static final int DECAY_MIN = 10;
    public static final int DECAY = 100;
    public static final int DECAY_MAX = 200;
    /**
     * An effect controller that is not sent. Distinct from 0, which is a real send:
     * reverb 0 is dry, and cutoff 0 is a closed filter.
     */
    public static final int UNSET = -1;
    /** GM2 default for the reverb send. Sending this puts a channel back after an earlier send. */
    public static final int REVERB_NEUTRAL = 40;
    /** GM2 default for the chorus send. */
    public static final int CHORUS_NEUTRAL = 0;
    /** GM2 default for cutoff and resonance. 64 is no change on the built-in synth. */
    public static final int FILTER_NEUTRAL = 64;

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
    /** MSB×128+LSB. Zero is the General MIDI bank and is left out of the file. */
    private int bank;
    /** Drum kit program. Zero is the current kit and is left out of the file. */
    private int kit;
    /** Pitched percussion on a drum row. Null keeps the drum key. */
    private PitchedPreset preset;
    /** Cents, from {@link #TUNE_MIN} to {@link #TUNE_MAX}. */
    private int tune;
    /** Percent, from {@link #DECAY_MIN} to {@link #DECAY_MAX}. */
    private int decay = DECAY;
    private int reverb = UNSET;
    private int chorus = UNSET;
    private int cutoff = UNSET;
    private int resonance = UNSET;
    /** Kept while the row is a drum or a note, and written only in sample mode. */
    private SampleRef sample;
    private final Step[] steps = new Step[CAPACITY];

    public Track(String name, TrackMode mode, int program, int note) {
        this.name = name;
        this.mode = mode;
        this.program = clampProgram(program);
        this.note = Notes.clamp(note);
        this.gate = mode == TrackMode.NOTE ? 80 : 45;
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
            || shift != other.shift
            || bank != other.bank
            || kit != other.kit
            || preset != other.preset
            || tune != other.tune
            || decay != other.decay
            || reverb != other.reverb
            || chorus != other.chorus
            || cutoff != other.cutoff
            || resonance != other.resonance) {
            return false;
        }
        if (mode == TrackMode.SAMPLE && !sameSample(sample, other.sample)) {
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
        copy.bank = bank;
        copy.kit = kit;
        copy.preset = preset;
        copy.tune = tune;
        copy.decay = decay;
        copy.reverb = reverb;
        copy.chorus = chorus;
        copy.cutoff = cutoff;
        copy.resonance = resonance;
        copy.sample = sample;
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
        if (this.mode != TrackMode.DRUM) {
            preset = null;
        }
        if (this.mode == TrackMode.SAMPLE && sample == null) {
            sample = SampleRef.builtin(BuiltinSamples.CLICK, note);
        }
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

    /** Soundbank bank, MSB×128+LSB. 0 is General MIDI. */
    public int bank() {
        return bank;
    }

    public void setBank(int bank) {
        this.bank = Math.max(0, Math.min(16383, bank));
    }

    /** Drum kit program. 0 is the kit already on the channel. */
    public int kit() {
        return kit;
    }

    public void setKit(int kit) {
        this.kit = Math.max(0, Math.min(127, kit));
    }

    /** Pitched percussion preset, or null when this row plays its drum key. */
    public PitchedPreset preset() {
        return preset;
    }

    public void setPreset(PitchedPreset preset) {
        if (mode != TrackMode.DRUM) {
            this.preset = null;
            return;
        }
        this.preset = preset;
    }

    /** The one-shot this row plays in sample mode. Null until that mode is chosen. */
    public SampleRef sample() {
        return sample;
    }

    public void setSample(SampleRef sample) {
        this.sample = sample;
    }

    /** Cents added to this row. 0 keeps the instrument's own pitch. */
    public int tune() {
        return tune;
    }

    public void setTune(int cents) {
        this.tune = Math.max(TUNE_MIN, Math.min(TUNE_MAX, cents));
    }

    /** Whole semitones of {@link #tune()}, toward zero. The rest is {@link #tuneCents()}. */
    public int tuneSemitones() {
        return tune / 100;
    }

    /** Cents within the current semitone, from −50 to +50 except at the ±24 semitone ends. */
    public int tuneCents() {
        return tune % 100;
    }

    /**
     * Stores semitones plus a fine offset. The total stays inside {@link #TUNE_MIN} and {@link #TUNE_MAX}.
     */
    public void setTuneParts(int semitones, int cents) {
        int semi = Math.max(-24, Math.min(24, semitones));
        int fine = Math.max(-50, Math.min(50, cents));
        setTune(semi * 100 + fine);
    }

    /** How long the built-in synth lets a hit ring, as a percent. 100 is the instrument's own release. */
    public int decay() {
        return decay;
    }

    public void setDecay(int decay) {
        this.decay = Math.max(DECAY_MIN, Math.min(DECAY_MAX, decay));
    }

    /**
     * CC 72 for {@code percent}. 100% is 64, which is the release the built-in synth already uses.
     * 10% is 0 and 200% is 127.
     * CC 75 is not used. On this synth it turns an open hat into a click for every value under 64,
     * and a kick, tom, saw, and xylophone ignore it.
     */
    public static int decayController(int percent) {
        int clamped = Math.max(DECAY_MIN, Math.min(DECAY_MAX, percent));
        if (clamped <= DECAY) {
            return (clamped - DECAY_MIN) * 64 / (DECAY - DECAY_MIN);
        }
        return 64 + (clamped - DECAY) * 63 / (DECAY_MAX - DECAY);
    }

    /**
     * Pitch bend for {@code cents} when the bend range is 24 semitones.
     * 0 cents is 8192, the center. ±2400 cents is the bottom or the top of the bend.
     */
    public static int pitchBend(int cents) {
        int clamped = Math.max(TUNE_MIN, Math.min(TUNE_MAX, cents));
        long bend = 8192L + Math.round(clamped * 8192.0 / TUNE_MAX);
        return (int) Math.max(0, Math.min(16383, bend));
    }

    /**
     * GS per-key pitch for {@code cents}, used only when a drum cannot have its own channel.
     * 64 is the drum's own pitch. The built-in synth moves that by a few semitones, not by 24.
     */
    public static int tuneKey(int cents) {
        int clamped = Math.max(TUNE_MIN, Math.min(TUNE_MAX, cents));
        int value = 64 + (int) Math.round(clamped * 64.0 / TUNE_MAX);
        return Math.max(0, Math.min(127, value));
    }

    /** Reverb send, or {@link #UNSET} when the controller is left alone. */
    public int reverb() {
        return reverb;
    }

    public boolean hasReverb() {
        return reverb != UNSET;
    }

    public void setReverb(int reverb) {
        this.reverb = clampController(reverb);
    }

    public void clearReverb() {
        reverb = UNSET;
    }

    /** Chorus send, or {@link #UNSET}. */
    public int chorus() {
        return chorus;
    }

    public boolean hasChorus() {
        return chorus != UNSET;
    }

    public void setChorus(int chorus) {
        this.chorus = clampController(chorus);
    }

    public void clearChorus() {
        chorus = UNSET;
    }

    /** Low-pass cutoff, or {@link #UNSET}. */
    public int cutoff() {
        return cutoff;
    }

    public boolean hasCutoff() {
        return cutoff != UNSET;
    }

    public void setCutoff(int cutoff) {
        this.cutoff = clampController(cutoff);
    }

    public void clearCutoff() {
        cutoff = UNSET;
    }

    /** Filter resonance, or {@link #UNSET}. */
    public int resonance() {
        return resonance;
    }

    public boolean hasResonance() {
        return resonance != UNSET;
    }

    public void setResonance(int resonance) {
        this.resonance = clampController(resonance);
    }

    public void clearResonance() {
        resonance = UNSET;
    }

    /**
     * Tune, decay, or an effect differs from a new row.
     * A center drum that returns true needs its own channel, on the GM drum bank.
     */
    public boolean shapesSound() {
        return tune != 0
            || decay != DECAY
            || reverb != UNSET
            || chorus != UNSET
            || cutoff != UNSET
            || resonance != UNSET;
    }

    /** Non-default tune, decay, and effects, for {@code hits info}. Empty when this row is plain. */
    public String shapeText() {
        StringBuilder text = new StringBuilder();
        if (tune != 0) {
            text.append("tune ").append(tune > 0 ? "+" : "").append(tune).append(" cents");
        }
        if (decay != DECAY) {
            join(text, "decay " + decay + "%");
        }
        if (reverb != UNSET) {
            join(text, "reverb " + reverb);
        }
        if (chorus != UNSET) {
            join(text, "chorus " + chorus);
        }
        if (cutoff != UNSET) {
            join(text, "cutoff " + cutoff);
        }
        if (resonance != UNSET) {
            join(text, "resonance " + resonance);
        }
        return text.toString();
    }

    /**
     * The pitch a hit uses when the step has none of its own.
     * A pitched preset supplies that pitch. A step pitch still replaces it.
     */
    public int playedNote() {
        if (mode == TrackMode.DRUM && preset != null) {
            return preset.note();
        }
        return note;
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
        if (mode == TrackMode.SAMPLE) {
            return sample == null ? "Sample" : sample.label();
        }
        if (mode == TrackMode.DRUM) {
            if (preset != null) {
                return preset.label();
            }
            String drum = Gm.drumName(note);
            if (kit != 0) {
                return drum + ", kit " + kit;
            }
            return drum;
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

    private static int clampController(int value) {
        return Math.max(0, Math.min(127, value));
    }

    private static boolean sameSample(SampleRef left, SampleRef right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return left.same(right);
    }

    private static void join(StringBuilder text, String part) {
        if (text.length() > 0) {
            text.append(", ");
        }
        text.append(part);
    }
}
