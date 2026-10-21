package hits;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Soundbank;
import java.util.ArrayList;
import java.util.List;

/**
 * Which MIDI channel a row uses, stable for every pattern in an export.
 * Center drums with no kit, bank, or pitched preset share channel 9.
 * Every other row gets a channel of its own from {@link #POOL}. Channel 15 is last
 * in that pool because the metronome already uses it. Notes and pitched presets
 * are placed before drums, so a drum is the row that falls back to channel 9
 * when the pool runs out.
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

    private final int[] reserved;
    private final boolean[] overflow;
    /** A row that plays a pitched preset in at least one pattern this plan covers. */
    private final boolean[] presetRow;
    /** Bank used to decide whether a kit program really exists. Null keeps the stored kit. */
    private final Soundbank kits;

    private ChannelPlan(int[] reserved, boolean[] overflow, boolean[] presetRow, Soundbank kits) {
        this.reserved = reserved;
        this.overflow = overflow;
        this.presetRow = presetRow;
        this.kits = kits;
    }

    /**
     * A drum at center pan, kit 0, bank 0, and no pitched preset.
     * It sends pan only, on the General MIDI drum channel.
     */
    public static boolean sharesDrumChannel(Track track) {
        return sharesDrumChannel(track, null);
    }

    /**
     * A drum at center pan, with no bank or pitched preset, whose kit is Standard
     * or is missing from {@code kits}. A null bank keeps a non-zero kit private,
     * so a MIDI file still names the kit the row asked for.
     */
    public static boolean sharesDrumChannel(Track track, Soundbank kits) {
        return track.mode() == TrackMode.DRUM
            && track.pan() == Track.PAN_CENTER
            && SoundCatalog.playedKit(kits, track.kit()) == 0
            && track.bank() == 0
            && track.preset() == null;
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
        boolean[] needsPrivate = new boolean[Beat.TRACKS];
        boolean[] melodic = new boolean[Beat.TRACKS];
        boolean[] presetRow = new boolean[Beat.TRACKS];
        if (beat != null && slots != null) {
            for (char slot : slots) {
                Track[] tracks = beat.slot(slot);
                for (int row = 0; row < tracks.length; row++) {
                    Track track = tracks[row];
                    if (track.mode() == TrackMode.DRUM && track.preset() != null) {
                        presetRow[row] = true;
                    }
                    if (sharesDrumChannel(track, kits)) {
                        continue;
                    }
                    needsPrivate[row] = true;
                    if (track.mode() == TrackMode.NOTE || (track.mode() == TrackMode.DRUM && track.preset() != null)) {
                        melodic[row] = true;
                    }
                }
            }
        }
        ChannelPlan assigned = assign(Beat.TRACKS, needsPrivate, melodic);
        return new ChannelPlan(assigned.reserved, assigned.overflow, presetRow, kits);
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
        return new ChannelPlan(reserved, overflow, new boolean[count], null);
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
     * Bank, program, and pan for one row.
     * {@code row} lets a later pattern clear the release and reverb a preset left on this channel.
     */
    public List<ShortMessage> setup(Track track, int channel, int row) throws InvalidMidiDataException {
        List<ShortMessage> messages = new ArrayList<>();
        if (!sharesDrumChannel(track, kits)) {
            if (track.preset() != null && track.mode() == TrackMode.DRUM) {
                bankSelect(messages, channel, track.bank());
                messages.add(program(channel, track.preset().program()));
                messages.add(control(channel, RELEASE, presetRelease(track.gate())));
                messages.add(control(channel, REVERB, presetReverb(track.gate())));
            } else if (track.mode() == TrackMode.NOTE) {
                bankSelect(messages, channel, track.bank());
                messages.add(program(channel, track.program()));
                clearPresetShape(messages, channel, row);
            } else if (track.bank() == 0) {
                messages.add(control(channel, 0, Gm.DRUM_BANK));
                messages.add(control(channel, 32, 0));
                messages.add(program(channel, SoundCatalog.playedKit(kits, track.kit())));
                clearPresetShape(messages, channel, row);
            } else {
                bankSelect(messages, channel, track.bank());
                messages.add(program(channel, SoundCatalog.playedKit(kits, track.kit())));
                clearPresetShape(messages, channel, row);
            }
        }
        messages.add(control(channel, 10, track.pan()));
        return messages;
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

    /** Puts release and reverb back to the GM defaults after a preset used this channel. */
    private void clearPresetShape(List<ShortMessage> messages, int channel, int row) throws InvalidMidiDataException {
        if (row < 0 || row >= presetRow.length || !presetRow[row]) {
            return;
        }
        messages.add(control(channel, RELEASE, 64));
        messages.add(control(channel, REVERB, 0));
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
