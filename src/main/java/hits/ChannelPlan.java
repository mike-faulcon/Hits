package hits;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.ShortMessage;
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

    private final int[] reserved;
    private final boolean[] overflow;

    private ChannelPlan(int[] reserved, boolean[] overflow) {
        this.reserved = reserved;
        this.overflow = overflow;
    }

    /**
     * A drum at center pan, kit 0, bank 0, and no pitched preset.
     * It sends pan only, on the General MIDI drum channel.
     */
    public static boolean sharesDrumChannel(Track track) {
        return track.mode() == TrackMode.DRUM
            && track.pan() == Track.PAN_CENTER
            && track.kit() == 0
            && track.bank() == 0
            && track.preset() == null;
    }

    /** Channels for the patterns this playback or export will actually visit. */
    public static ChannelPlan of(Beat beat, List<Character> slots) {
        boolean[] needsPrivate = new boolean[Beat.TRACKS];
        boolean[] melodic = new boolean[Beat.TRACKS];
        if (beat != null && slots != null) {
            for (char slot : slots) {
                Track[] tracks = beat.slot(slot);
                for (int row = 0; row < tracks.length; row++) {
                    Track track = tracks[row];
                    if (sharesDrumChannel(track)) {
                        continue;
                    }
                    needsPrivate[row] = true;
                    if (track.mode() == TrackMode.NOTE || track.preset() != null) {
                        melodic[row] = true;
                    }
                }
            }
        }
        return assign(Beat.TRACKS, needsPrivate, melodic);
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
        return new ChannelPlan(reserved, overflow);
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
        if (track != null && sharesDrumChannel(track)) {
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
        List<ShortMessage> messages = new ArrayList<>();
        if (!sharesDrumChannel(track)) {
            if (track.preset() != null && track.mode() == TrackMode.DRUM) {
                bankSelect(messages, channel, track.bank());
                messages.add(program(channel, track.preset().program()));
            } else if (track.mode() == TrackMode.NOTE) {
                bankSelect(messages, channel, track.bank());
                messages.add(program(channel, track.program()));
            } else if (track.bank() == 0) {
                messages.add(control(channel, 0, Gm.DRUM_BANK));
                messages.add(control(channel, 32, 0));
                messages.add(program(channel, track.kit()));
            } else {
                bankSelect(messages, channel, track.bank());
                messages.add(program(channel, track.kit()));
            }
        }
        messages.add(control(channel, 10, track.pan()));
        return messages;
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
