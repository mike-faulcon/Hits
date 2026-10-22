package hits;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Soundbank;
import java.util.ArrayList;
import java.util.List;

/**
 * Which MIDI channel a row uses, stable for every pattern in an export.
 * Center drums with no kit, bank, pitched preset, tune, decay, or effect share channel 9.
 * Every other row gets a channel of its own from {@link #POOL}. Channel 15 is last
 * in that pool because the metronome already uses it. Notes, sample tracks, and pitched presets
 * are placed before drums, so a drum is the row that falls back to channel 9
 * when the pool runs out. A sample track never shares channel 9.
 */
public final class ChannelPlan {
    /**
     * Melodic channels, then the metronome channel. Channel 9 is the shared drum channel
     * and is not in the pool.
     */
    public static final int[] POOL = {0, 1, 2, 3, 4, 5, 6, 7, 8, 10, 11, 12, 13, 14, 15};

    /** GM2 release time. 64 is the instrument's own release. */
    public static final int RELEASE = 72;
    /** GM2 reverb send. */
    public static final int REVERB = 91;
    /** GM2 chorus send. */
    public static final int CHORUS = 93;
    /** GM2 brightness, the low-pass cutoff. */
    public static final int CUTOFF = 74;
    /** GM2 harmonic content, the filter resonance. */
    public static final int RESONANCE = 71;
    /** RPN 0, pitch-bend range, in semitones. Wide enough for {@link Track#TUNE_MAX}. */
    public static final int BEND_RANGE = 24;
    /** GS drum NRPN: pitch, reverb, chorus. The key is the NRPN LSB. */
    private static final int KEY_PITCH = 0x18;
    private static final int KEY_REVERB = 0x1D;
    private static final int KEY_CHORUS = 0x1E;

    private static final int SHAPE_TUNE = 1;
    private static final int SHAPE_DECAY = 2;
    private static final int SHAPE_REVERB = 4;
    private static final int SHAPE_CHORUS = 8;
    private static final int SHAPE_CUTOFF = 16;
    private static final int SHAPE_RESONANCE = 32;

    private final int[] reserved;
    private final boolean[] overflow;
    /** A row that plays a pitched preset in at least one pattern this plan covers. */
    private final boolean[] presetRow;
    /** Which shape controllers a row uses in any pattern this plan covers. */
    private final int[] shapeRow;
    /** Bank used to decide whether a kit program really exists. Null keeps the stored kit. */
    private final Soundbank kits;
    /** Which sample rows actually play a sample. Null or {@link SampleUse#none()} falls back. */
    private final SampleUse samples;

    private ChannelPlan(
        int[] reserved,
        boolean[] overflow,
        boolean[] presetRow,
        int[] shapeRow,
        Soundbank kits,
        SampleUse samples
    ) {
        this.reserved = reserved;
        this.overflow = overflow;
        this.presetRow = presetRow;
        this.shapeRow = shapeRow;
        this.kits = kits;
        this.samples = samples == null ? SampleUse.none() : samples;
    }

    /**
     * A drum at center pan, kit 0, bank 0, no pitched preset, and no tune, decay, or effect.
     * It sends pan only, on the General MIDI drum channel.
     */
    public static boolean sharesDrumChannel(Track track) {
        return sharesDrumChannel(track, null);
    }

    /**
     * A drum at center pan, with no bank, pitched preset, tune, decay, or effect, whose kit
     * is Standard or is missing from {@code kits}. A null bank keeps a non-zero kit private,
     * so a MIDI file still names the kit the row asked for.
     * Tune, decay, a send, or a filter takes the drum off this shared channel and onto bank 120.
     */
    public static boolean sharesDrumChannel(Track track, Soundbank kits) {
        return track.mode() == TrackMode.DRUM
            && track.pan() == Track.PAN_CENTER
            && SoundCatalog.playedKit(kits, track.kit()) == 0
            && track.bank() == 0
            && track.preset() == null
            && !track.shapesSound();
    }

    /** Channels for the patterns this playback or export will actually visit. */
    public static ChannelPlan of(Beat beat, List<Character> slots) {
        return of(beat, slots, null);
    }

    /**
     * Same plan as {@link #of(Beat, List)}, using {@code kits} when a drum kit program
     * is not actually in that bank. Null keeps every stored kit number.
     */
    public static ChannelPlan of(Beat beat, List<Character> slots, Soundbank kits) {
        return of(beat, slots, kits, SampleUse.none());
    }

    /**
     * Same plan as {@link #of(Beat, List, Soundbank)}. {@code samples} selects the sample bank
     * for rows whose sample loaded. Every other sample row plays its General MIDI note.
     */
    public static ChannelPlan of(Beat beat, List<Character> slots, Soundbank kits, SampleUse samples) {
        boolean[] needsPrivate = new boolean[Beat.TRACKS];
        boolean[] melodic = new boolean[Beat.TRACKS];
        boolean[] presetRow = new boolean[Beat.TRACKS];
        int[] shapeRow = new int[Beat.TRACKS];
        if (beat != null && slots != null) {
            for (char slot : slots) {
                Track[] tracks = beat.slot(slot);
                for (int row = 0; row < tracks.length; row++) {
                    Track track = tracks[row];
                    if (track.mode() == TrackMode.DRUM && track.preset() != null) {
                        presetRow[row] = true;
                    }
                    shapeRow[row] |= shapeFlags(track);
                    if (sharesDrumChannel(track, kits)) {
                        continue;
                    }
                    needsPrivate[row] = true;
                    if (track.mode() == TrackMode.NOTE
                        || track.mode() == TrackMode.SAMPLE
                        || (track.mode() == TrackMode.DRUM && track.preset() != null)) {
                        melodic[row] = true;
                    }
                }
            }
        }
        ChannelPlan assigned = assign(Beat.TRACKS, needsPrivate, melodic);
        return new ChannelPlan(assigned.reserved, assigned.overflow, presetRow, shapeRow, kits, samples);
    }

    private static int shapeFlags(Track track) {
        int flags = 0;
        if (track.tune() != 0) {
            flags |= SHAPE_TUNE;
        }
        if (track.decay() != Track.DECAY) {
            flags |= SHAPE_DECAY;
        }
        if (track.hasReverb()) {
            flags |= SHAPE_REVERB;
        }
        if (track.hasChorus()) {
            flags |= SHAPE_CHORUS;
        }
        if (track.hasCutoff()) {
            flags |= SHAPE_CUTOFF;
        }
        if (track.hasResonance()) {
            flags |= SHAPE_RESONANCE;
        }
        return flags;
    }

    /**
     * Assigns private channels. {@code melodic} rows are placed first so a note
     * keeps a channel when drums would otherwise fill the pool.
     * A row whose index is free in {@link #POOL} keeps that number, which is how
     * an eight-track beat stays on channel == row.
     */
    public static ChannelPlan assign(int rowCount, boolean[] needsPrivate, boolean[] melodic) {
        int count = Math.max(0, rowCount);
        int[] reserved = new int[count];
        boolean[] overflow = new boolean[count];
        for (int row = 0; row < count; row++) {
            reserved[row] = -1;
        }
        boolean[] taken = new boolean[16];
        place(reserved, overflow, taken, needsPrivate, melodic, true);
        place(reserved, overflow, taken, needsPrivate, melodic, false);
        return new ChannelPlan(reserved, overflow, new boolean[count], new int[count], null, SampleUse.none());
    }

    private static void place(
        int[] reserved,
        boolean[] overflow,
        boolean[] taken,
        boolean[] needsPrivate,
        boolean[] melodic,
        boolean wantMelodic
    ) {
        for (int row = 0; row < reserved.length; row++) {
            if (row >= needsPrivate.length || !needsPrivate[row] || reserved[row] >= 0) {
                continue;
            }
            boolean rowMelodic = row < melodic.length && melodic[row];
            if (rowMelodic != wantMelodic) {
                continue;
            }
            int channel = -1;
            if (inPool(row) && !taken[row]) {
                channel = row;
            } else {
                channel = nextFree(taken);
            }
            if (channel < 0) {
                reserved[row] = Gm.DRUM_CHANNEL;
                overflow[row] = true;
            } else {
                reserved[row] = channel;
                taken[channel] = true;
            }
        }
    }

    private static boolean inPool(int channel) {
        for (int candidate : POOL) {
            if (candidate == channel) {
                return true;
            }
        }
        return false;
    }

    private static int nextFree(boolean[] taken) {
        for (int candidate : POOL) {
            if (!taken[candidate]) {
                return candidate;
            }
        }
        return -1;
    }

    /**
     * Channel for this pattern's row. A shared drum stays on channel 9 even when
     * another pattern on the same row reserved a private channel.
     */
    public int channel(Track track, int row) {
        if (track != null && sharesDrumChannel(track, kits)) {
            return Gm.DRUM_CHANNEL;
        }
        if (row >= 0 && row < reserved.length && reserved[row] >= 0) {
            return reserved[row];
        }
        if (inPool(row)) {
            return row;
        }
        return Gm.DRUM_CHANNEL;
    }

    /** The private channel reserved for {@code row}, or -1 when the row shares channel 9. */
    public int reserved(int row) {
        if (row < 0 || row >= reserved.length) {
            return -1;
        }
        return reserved[row];
    }

    /** True when {@code row} needed its own channel and the pool was already full. */
    public boolean overflow(int row) {
        return row >= 0 && row < overflow.length && overflow[row];
    }

    /**
     * Bank, program, and pan for one row, in the order a synth needs them.
     * A shared drum sends pan only. A pitched preset sends its melodic program
     * and ignores the kit. A private drum with no bank of its own uses the GM drum bank.
     */
    public List<ShortMessage> setup(Track track, int channel) throws InvalidMidiDataException {
        return setup(track, channel, -1);
    }

    /**
     * Bank, program, pan, and any tune or effect for one row.
     * {@code row} lets a later pattern clear a controller a previous pattern on this channel set.
     * <p>
     * An explicit decay replaces the release a pitched preset's gate would send, and an explicit
     * reverb replaces that gate's reverb. At decay 100 and with reverb unset, the gate is left
     * in charge, so a preset at gate 45 still asks for release 64 and reverb 40. One value is
     * chosen either way, so the two never fight. The sequence writer then skips a controller
     * the channel already has, including those neutrals when nothing has changed them.
     * Unset cutoff, resonance, chorus, and reverb send nothing on a row that never uses them.
     * CC 75 is not sent: the built-in synth's decay controller does not lengthen a kick and it
     * collapses an open hat. Decay is CC 72.
     * Tune is a pitch bend after RPN 0 is set to {@link #BEND_RANGE} semitones. It is not RPN 2,
     * which can pick a different drum key. A row that ran out of channels uses GS per-key NRPN
     * instead, and only for tune, reverb, and chorus. Filter and decay have no per-key message.
     */
    public List<ShortMessage> setup(Track track, int channel, int row) throws InvalidMidiDataException {
        List<ShortMessage> messages = new ArrayList<>();
        if (!sharesDrumChannel(track, kits)) {
            boolean preset = track.preset() != null && track.mode() == TrackMode.DRUM;
            if (track.mode() == TrackMode.SAMPLE && samples.plays(track, row)) {
                bankSelect(messages, channel, SampleBank.MIDI_BANK);
                messages.add(program(channel, Math.max(0, row)));
            } else if (track.mode() == TrackMode.SAMPLE) {
                messages.add(control(channel, 0, Gm.DRUM_BANK));
                messages.add(control(channel, 32, 0));
                messages.add(program(channel, SoundCatalog.playedKit(kits, track.kit())));
            } else if (preset) {
                bankSelect(messages, channel, track.bank());
                messages.add(program(channel, track.preset().program()));
            } else if (track.mode() == TrackMode.NOTE) {
                bankSelect(messages, channel, track.bank());
                messages.add(program(channel, track.program()));
            } else if (track.bank() == 0) {
                messages.add(control(channel, 0, Gm.DRUM_BANK));
                messages.add(control(channel, 32, 0));
                messages.add(program(channel, SoundCatalog.playedKit(kits, track.kit())));
            } else {
                bankSelect(messages, channel, track.bank());
                messages.add(program(channel, SoundCatalog.playedKit(kits, track.kit())));
            }
            if (overflow(row)) {
                addKeyShape(messages, track, channel, row);
            } else {
                addChannelShape(messages, track, channel, row, preset);
            }
        }
        messages.add(control(channel, 10, track.pan()));
        return messages;
    }

    /**
     * Channel controllers. Explicit decay and reverb win over the preset gate.
     * A row that used a controller in another pattern sends the neutral value here when this
     * pattern leaves it unset, so the earlier value does not stick. A shared drum never reaches
     * this, so those neutrals are not written onto channel 9.
     */
    private void addChannelShape(
        List<ShortMessage> messages,
        Track track,
        int channel,
        int row,
        boolean preset
    ) throws InvalidMidiDataException {
        if (track.tune() != 0 || shaped(row, SHAPE_TUNE)) {
            bendRange(messages, channel);
            messages.add(pitchBend(channel, track.tune()));
        }
        Integer release = releaseValue(track, row, preset);
        if (release != null) {
            messages.add(control(channel, RELEASE, release));
        }
        Integer reverb = reverbValue(track, row, preset);
        if (reverb != null) {
            messages.add(control(channel, REVERB, reverb));
        }
        Integer chorus = held(track.hasChorus(), track.chorus(), row, SHAPE_CHORUS, Track.CHORUS_NEUTRAL);
        if (chorus != null) {
            messages.add(control(channel, CHORUS, chorus));
        }
        Integer cutoff = held(track.hasCutoff(), track.cutoff(), row, SHAPE_CUTOFF, Track.FILTER_NEUTRAL);
        if (cutoff != null) {
            messages.add(control(channel, CUTOFF, cutoff));
        }
        Integer resonance = held(track.hasResonance(), track.resonance(), row, SHAPE_RESONANCE, Track.FILTER_NEUTRAL);
        if (resonance != null) {
            messages.add(control(channel, RESONANCE, resonance));
        }
    }

    /**
     * Per-key fallback for a drum that needed its own channel and did not get one.
     * Channel-wide bend, filter, and release would move every drum on channel 9, so they are not sent.
     */
    private void addKeyShape(List<ShortMessage> messages, Track track, int channel, int row) throws InvalidMidiDataException {
        int key = track.playedNote();
        if (track.tune() != 0 || shaped(row, SHAPE_TUNE)) {
            nrpn(messages, channel, KEY_PITCH, key, Track.tuneKey(track.tune()));
        }
        if (track.hasReverb() || shaped(row, SHAPE_REVERB)) {
            int value = track.hasReverb() ? track.reverb() : Track.REVERB_NEUTRAL;
            nrpn(messages, channel, KEY_REVERB, key, value);
        }
        if (track.hasChorus() || shaped(row, SHAPE_CHORUS)) {
            int value = track.hasChorus() ? track.chorus() : Track.CHORUS_NEUTRAL;
            nrpn(messages, channel, KEY_CHORUS, key, value);
        }
    }

    /**
     * Release for this pattern. An explicit decay wins. Otherwise a preset keeps the gate's
     * release, and a later pattern on that row puts the controller back to 64.
     */
    private Integer releaseValue(Track track, int row, boolean preset) {
        if (track.decay() != Track.DECAY) {
            return Track.decayController(track.decay());
        }
        if (preset) {
            return presetRelease(track.gate());
        }
        if (presetRow(row) || shaped(row, SHAPE_DECAY)) {
            return Track.FILTER_NEUTRAL;
        }
        return null;
    }

    /**
     * Reverb for this pattern. An explicit send wins, including over a preset's gate.
     * A later pattern on a preset row sends 0, which is the value that row used to clear the gate.
     * A later pattern on a row that only used an explicit send sends {@link Track#REVERB_NEUTRAL}.
     */
    private Integer reverbValue(Track track, int row, boolean preset) {
        if (track.hasReverb()) {
            return track.reverb();
        }
        if (preset) {
            return presetReverb(track.gate());
        }
        if (presetRow(row)) {
            return 0;
        }
        if (shaped(row, SHAPE_REVERB)) {
            return Track.REVERB_NEUTRAL;
        }
        return null;
    }

    private Integer held(boolean set, int value, int row, int flag, int neutral) {
        if (set) {
            return value;
        }
        if (shaped(row, flag)) {
            return neutral;
        }
        return null;
    }

    private boolean shaped(int row, int flag) {
        return row >= 0 && row < shapeRow.length && (shapeRow[row] & flag) != 0;
    }

    private boolean presetRow(int row) {
        return row >= 0 && row < presetRow.length && presetRow[row];
    }

    private static void bendRange(List<ShortMessage> messages, int channel) throws InvalidMidiDataException {
        messages.add(control(channel, 101, 0));
        messages.add(control(channel, 100, 0));
        messages.add(control(channel, 6, BEND_RANGE));
        messages.add(control(channel, 38, 0));
        messages.add(control(channel, 101, 127));
        messages.add(control(channel, 100, 127));
    }

    private static ShortMessage pitchBend(int channel, int cents) throws InvalidMidiDataException {
        int value = Track.pitchBend(cents);
        ShortMessage message = new ShortMessage();
        message.setMessage(ShortMessage.PITCH_BEND, channel, value & 0x7f, (value >> 7) & 0x7f);
        return message;
    }

    /** One GS NRPN, then the null NRPN so a later data entry does not keep writing it. */
    private static void nrpn(List<ShortMessage> messages, int channel, int msb, int key, int value) throws InvalidMidiDataException {
        messages.add(control(channel, 99, msb));
        messages.add(control(channel, 98, key));
        messages.add(control(channel, 6, value));
        messages.add(control(channel, 38, 0));
        messages.add(control(channel, 99, 127));
        messages.add(control(channel, 98, 127));
    }

    /**
     * Release for a pitched preset, scaled to the drum gate.
     * Gervill adds about {@code (value - 64) / 64 * 6000} ms to the release.
     * Below 54, a one-tick hit (gate 5 of a 16th at 120 bpm) is silent, because
     * note-on and note-off land in one control period. 64 is the built-in release.
     * 80 lets a full gate ring longer than that.
     */
    public static int presetRelease(int gate) {
        int clamped = Math.max(5, Math.min(100, gate));
        if (clamped <= 45) {
            return 54 + (clamped - 5) / 4;
        }
        return 64 + (clamped - 45) * 16 / 55;
    }

    /**
     * Reverb send for a pitched preset, scaled to the drum gate.
     * On the built-in bank the timpani crack's tail is reverb: 0 cuts it to the sample,
     * 40 matches the tail when this controller is left unset, and 80 lets a long gate bloom.
     * The xylophone rim barely moves.
     */
    public static int presetReverb(int gate) {
        int clamped = Math.max(5, Math.min(100, gate));
        if (clamped <= 45) {
            return clamped - 5;
        }
        return 40 + (clamped - 45) * 40 / 55;
    }

    private static void bankSelect(List<ShortMessage> messages, int channel, int bank) throws InvalidMidiDataException {
        int value = Math.max(0, Math.min(16383, bank));
        messages.add(control(channel, 0, value / 128));
        messages.add(control(channel, 32, value % 128));
    }

    private static ShortMessage control(int channel, int controller, int value) throws InvalidMidiDataException {
        ShortMessage message = new ShortMessage();
        message.setMessage(ShortMessage.CONTROL_CHANGE, channel, controller, Math.max(0, Math.min(127, value)));
        return message;
    }

    private static ShortMessage program(int channel, int program) throws InvalidMidiDataException {
        ShortMessage message = new ShortMessage();
        message.setMessage(ShortMessage.PROGRAM_CHANGE, channel, Math.max(0, Math.min(127, program)), 0);
        return message;
    }
}
