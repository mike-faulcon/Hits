package hits;

import java.util.function.Consumer;

/** Mixer math shared by playback, export, and the mixer view. */
public final class Mix {
    private Mix() {}

    /**
     * Sets one row's level. {@code allPatterns} writes every pattern whose row is the same
     * instrument (name, mode, program, and note). A row that is a different sound is left alone.
     * Otherwise only {@code slot} changes, which is how a section can have its own mix.
     */
    public static void setLevel(Beat beat, char slot, int row, int level, boolean allPatterns) {
        apply(beat, slot, row, allPatterns, track -> track.setLevel(level));
    }

    /**
     * Sets one row's pan. {@code allPatterns} writes every pattern whose row is the same
     * instrument. Otherwise only {@code slot} changes.
     */
    public static void setPan(Beat beat, char slot, int row, int pan, boolean allPatterns) {
        apply(beat, slot, row, allPatterns, track -> track.setPan(pan));
    }

    private static void apply(Beat beat, char slot, int row, boolean allPatterns, Consumer<Track> edit) {
        if (beat == null || row < 0 || row >= Beat.TRACKS) {
            return;
        }
        char current = Beat.normalizeSlot(slot);
        Track source = beat.slot(current)[row];
        if (allPatterns) {
            for (int i = 0; i < Beat.SLOT_COUNT; i++) {
                Track track = beat.slot(Beat.slotLetter(i))[row];
                if (track == source || sameInstrument(source, track)) {
                    edit.accept(track);
                }
            }
            return;
        }
        edit.accept(source);
    }

    /** Name plus the sound. Two rows that only share a number are not the same instrument. */
    static boolean sameInstrument(Track left, Track right) {
        return left.name().equals(right.name())
            && left.mode() == right.mode()
            && left.program() == right.program()
            && left.note() == right.note()
            && left.bank() == right.bank()
            && left.kit() == right.kit()
            && left.preset() == right.preset();
    }

    /**
     * Hit velocity after the track fader and the master fader.
     * {@link Track#LEVEL} and {@link Beat#MASTER} leave the programmed velocity alone.
     * Above unity the hit can get louder, up to 127. Zero on either fader is silent.
     */
    public static int velocity(int velocity, int level, int master) {
        if (velocity <= 0 || level <= 0 || master <= 0) {
            return 0;
        }
        double scaled = velocity * (level / (double) Track.LEVEL) * (master / (double) Beat.MASTER);
        return Math.max(0, Math.min(127, (int) Math.round(scaled)));
    }

    /**
     * Channel for one row looked at on its own.
     * A song or export uses {@link ChannelPlan} so the number stays put across patterns.
     * Center drums with no kit, bank, or pitched preset share the General MIDI drum channel.
     */
    public static int channel(Track track, int row) {
        if (ChannelPlan.sharesDrumChannel(track)) {
            return Gm.DRUM_CHANNEL;
        }
        if (row >= 0 && row <= 15 && row != Gm.DRUM_CHANNEL) {
            return row;
        }
        return Gm.DRUM_CHANNEL;
    }

    /**
     * This drum needs the drum kit selected on its own channel.
     * A pitched preset uses a melodic program instead, and a non-zero bank uses that bank.
     */
    public static boolean usesDrumBank(Track track) {
        return track.mode() == TrackMode.DRUM
            && track.preset() == null
            && track.bank() == 0
            && !ChannelPlan.sharesDrumChannel(track);
    }

    /** {@code C} at center, otherwise {@code L} or {@code R} and how far from the middle. */
    public static String panText(int pan) {
        int value = Math.max(0, Math.min(127, pan));
        if (value == Track.PAN_CENTER) {
            return "C";
        }
        if (value < Track.PAN_CENTER) {
            return "L" + (Track.PAN_CENTER - value);
        }
        return "R" + (value - Track.PAN_CENTER);
    }
}
