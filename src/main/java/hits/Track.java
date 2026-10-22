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
    /** Lowest high-pass corner the Low cut slider offers, in Hz. */
    public static final int LOW_CUT_MIN = 20;
    /** Highest high-pass corner the Low cut slider offers, in Hz. */
    public static final int LOW_CUT_MAX = 2000;
    /** Gentle high-pass, one Butterworth section. */
    public static final int LOW_CUT_SLOPE = 12;
    /** Steep high-pass, two Butterworth sections. */
    public static final int LOW_CUT_STEEP = 24;
    /** Slider steps across the log low-cut range. 0 is {@link #LOW_CUT_MIN}, this is {@link #LOW_CUT_MAX}. */
    public static final int LOW_CUT_SLIDER = 1000;
    /** High-shelf boost, in dB. 0 is off and is left out of the file. */
    public static final int PRESENCE_MAX = 12;
    /** High-shelf midpoint, in Hz. The boost is above this. */
    public static final int PRESENCE_HZ = 3000;

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
    /** High-pass corner in Hz. 0 is off and is left out of the file. */
    private int lowCutHz;
    /** 12 or 24 dB per octave. Used while {@link #lowCutHz} is on. */
    private int lowCutSlope = LOW_CUT_SLOPE;
    /** High-shelf gain in dB. 0 is off and is left out of the file. */
    private int presence;
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
            || resonance != other.resonance
            || lowCutHz != other.lowCutHz
            || lowCutSlope != other.lowCutSlope
            || presence != other.presence) {
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
        copy.lowCutHz = lowCutHz;
        copy.lowCutSlope = lowCutSlope;
        copy.presence = presence;
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

    /**
     * Whole semitones of {@link #tune()}, rounded so {@link #tuneCents()} stays inside ±50.
     * 275 is 3 semitones minus 25 cents, and one more semitone is 375, not 350.
     */
    public int tuneSemitones() {
        return (int) Math.round(tune / 100.0);
    }

    /** Cents left after {@link #tuneSemitones()}, from −50 to +50. */
    public int tuneCents() {
        return tune - tuneSemitones() * 100;
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
     * CC 72 for {@code percent}. 100% is 64, the release the built-in synth already uses,
     * and a row at 100% does not send it.
     * <p>
     * Gervill adds about {@code ((cc / 64) - 1) * 6000} timecents, so release time is
     * exponential in the controller. Measured −20 dB times on the rim, the open hat, Syn Bass,
     * and a saw stay on the floor until about CC 52–58 (the note is already that short) and
     * then rise through CC 127. Mapping 10% to 0 therefore leaves most of the slider silent.
     * 10% is CC 56, just off that floor. 100% is 64. 200% is 127. Each side is linear in the
     * controller, which is what spread those measured times.
     * CC 75 is not used. On this synth it turns an open hat into a click for every value under 64,
     * and a kick, tom, saw, and xylophone ignore it.
     */
    public static int decayController(int percent) {
        int clamped = Math.max(DECAY_MIN, Math.min(DECAY_MAX, percent));
        if (clamped >= DECAY) {
            return 64 + (int) Math.round((clamped - DECAY) * 63.0 / (DECAY_MAX - DECAY));
        }
        return 56 + (int) Math.round((clamped - DECAY_MIN) * 8.0 / (DECAY - DECAY_MIN));
    }

    /**
     * Whether CC 72 changes this sound on the built-in synth.
     * Measured at controller 0, 64, and 127: kick, snare, closed hat, toms, clap, side stick,
     * Woodblock click, and Snare crack (and the timpani and woodblock programs) do not move.
     * Open hat, crash, ride, rimshot, and the other measured notes do.
     * <p>
     * A sample row does. Its SoundFont voice maps the percent onto the volume-envelope
     * decay, sustain, and release, so one-shot and choke both follow the control.
     * CC 72 is not sent for a sample: on a one-shot it cannot move a release of 8400 timecents,
     * and on a choke it cannot shorten the short release. A one-shot above 100% matches 100%,
     * because the file is as long as the sound can be. A missing file falls back to a drum note,
     * which may ignore decay, but the row stays a sample and the control stays on.
     * The stored percent is kept either way.
     */
    public boolean decayApplies() {
        if (mode == TrackMode.SAMPLE) {
            return true;
        }
        if (mode == TrackMode.NOTE) {
            return !releaseIgnoredProgram(program);
        }
        if (preset == PitchedPreset.CLICK || preset == PitchedPreset.CRACK) {
            return false;
        }
        if (preset != null) {
            return true;
        }
        return !releaseIgnoredDrum(note);
    }

    /**
     * Why {@link #decayApplies()} is false, or null when decay works.
     * Snare crack's tail is reverb, so the note points at that control.
     */
    public String decayNote() {
        if (decayApplies()) {
            return null;
        }
        if (preset == PitchedPreset.CRACK || (mode == TrackMode.NOTE && program == 47)) {
            return "This sound ignores decay. The tail is reverb, so Reverb is what shortens it.";
        }
        return "This sound ignores decay on the built-in synth.";
    }

    /**
     * Why part of the decay slider does nothing, or null when the whole slider changes the sound.
     * A one-shot at 100% already plays the file, so 101–200% cannot make it longer.
     */
    public String decayLimit() {
        if (mode == TrackMode.SAMPLE && sample != null && sample.oneShot() && decay > DECAY) {
            return "A one-shot cannot ring longer than the file. Above 100% plays the whole recording.";
        }
        return null;
    }

    /**
     * Lowest cutoff controller the slider offers for {@code track}.
     * The file still stores the controller. A saved value outside the slider is kept and sent;
     * the slider sits at the nearest end until it is moved.
     * Kit drums move from 0 up to about 56. Most notes move from about 32 to 80.
     * Bass programs 32–39 stay closed through about 58.
     * A sample row starts at 0. Its voice has no filter of its own, so the dark end of
     * Gervill's brightness controller is where the sample changes.
     */
    public static int cutoffSliderMin(Track track) {
        if (track != null && track.mode() == TrackMode.SAMPLE) {
            return 0;
        }
        if (track != null && track.mode() == TrackMode.NOTE && bassProgram(track.program())) {
            return 58;
        }
        if (track != null && track.mode() == TrackMode.NOTE) {
            return 32;
        }
        return 0;
    }

    /**
     * Highest cutoff controller the slider offers. Above this the centroid stops rising:
     * about 56 for kit drums, 80 for most notes, and 86 for the bass programs.
     * On Syn Bass the centroid peaks near 86 and turns down by 90.
     * A sample row stops at {@link #FILTER_NEUTRAL}. Gervill's default filter is already
     * open there (about 20 kHz), and a higher controller does not brighten the sample.
     * Turning the filter on therefore starts at the sample's own tone.
     */
    public static int cutoffSliderMax(Track track) {
        if (track != null && track.mode() == TrackMode.SAMPLE) {
            return FILTER_NEUTRAL;
        }
        if (track != null && track.mode() == TrackMode.NOTE && bassProgram(track.program())) {
            return 86;
        }
        if (track != null && track.mode() == TrackMode.NOTE) {
            return 80;
        }
        return 56;
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

    /** High-pass corner in Hz, or 0 when Low cut is off. */
    public int lowCutHz() {
        return lowCutHz;
    }

    /** 12 or 24. Meaningful while {@link #lowCutHz()} is on. */
    public int lowCutSlope() {
        return lowCutSlope;
    }

    public boolean hasLowCut() {
        return lowCutHz > 0;
    }

    /**
     * Stores a high-pass. {@code hz} of 0 or less clears it.
     * The corner stays inside {@link #LOW_CUT_MIN}–{@link #LOW_CUT_MAX}.
     * {@code slope} of {@link #LOW_CUT_STEEP} is 24 dB/oct. Anything else is 12.
     */
    public void setLowCut(int hz, int slope) {
        if (hz <= 0) {
            clearLowCut();
            return;
        }
        lowCutHz = Math.max(LOW_CUT_MIN, Math.min(LOW_CUT_MAX, hz));
        lowCutSlope = slope == LOW_CUT_STEEP ? LOW_CUT_STEEP : LOW_CUT_SLOPE;
    }

    public void clearLowCut() {
        lowCutHz = 0;
        lowCutSlope = LOW_CUT_SLOPE;
    }

    /**
     * Slider position for {@code hz} on a log scale from {@link #LOW_CUT_MIN} to {@link #LOW_CUT_MAX}.
     * 0 is 20 Hz. {@link #LOW_CUT_SLIDER} is 2 kHz.
     */
    public static int lowCutSlider(int hz) {
        double clamped = Math.max(LOW_CUT_MIN, Math.min(LOW_CUT_MAX, hz));
        double t = Math.log(clamped / LOW_CUT_MIN) / Math.log(LOW_CUT_MAX / (double) LOW_CUT_MIN);
        return (int) Math.round(t * LOW_CUT_SLIDER);
    }

    /** Hertz for a {@link #lowCutSlider(int)} position. Rounded to the nearest Hz. */
    public static int lowCutHz(int slider) {
        double t = Math.max(0, Math.min(LOW_CUT_SLIDER, slider)) / (double) LOW_CUT_SLIDER;
        double hz = LOW_CUT_MIN * Math.pow(LOW_CUT_MAX / (double) LOW_CUT_MIN, t);
        return (int) Math.round(hz);
    }

    /** High-shelf gain in dB, 0 to {@link #PRESENCE_MAX}. 0 is off. */
    public int presence() {
        return presence;
    }

    public boolean hasPresence() {
        return presence > 0;
    }

    public void setPresence(int db) {
        presence = Math.max(0, Math.min(PRESENCE_MAX, db));
    }

    /** Low cut or presence. Playback bakes this into PCM. It is not a MIDI controller. */
    public boolean hasTone() {
        return hasLowCut() || hasPresence();
    }

    /**
     * Tune, decay, or an effect differs from a new row.
     * A center drum that returns true needs its own channel, on the GM drum bank.
     * Low cut and presence are not included. They are not MIDI controllers, so a drum
     * that only uses them still shares channel 9 in a MIDI file. Playback gives that
     * drum a channel only when the rendered voice is actually loaded.
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
        if (lowCutHz > 0) {
            join(text, "low cut " + lowCutHz + " Hz " + lowCutSlope + " dB/oct");
        }
        if (presence > 0) {
            join(text, "presence +" + presence + " dB");
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

    /** Acoustic bass through synth bass 2. Their energy sits under a closed filter until about CC 58. */
    private static boolean bassProgram(int program) {
        return program >= 32 && program <= 39;
    }

    /** Timpani and woodblock. The sample length does not follow CC 72. */
    private static boolean releaseIgnoredProgram(int program) {
        return program == 47 || program == 115;
    }

    /**
     * Kit keys that ignored CC 72 at 0, 64, and 127.
     * 46 open hat, 49 crash, and 51 ride are not in this list.
     */
    private static boolean releaseIgnoredDrum(int note) {
        return switch (note) {
            case 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 47, 48, 50 -> true;
            default -> false;
        };
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
