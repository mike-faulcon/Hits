package hits;

import java.util.function.Consumer;

/** Mixer math shared by playback, export, and the mixer view. */
public final class Mix {
    private Mix() {}

    /**
     * Sets one row's level. {@code allPatterns} writes every pattern A–H.
     * Otherwise only {@code slot} changes, which is how a section can have its own mix.
     */
    public static void setLevel(Beat beat, char slot, int row, int level, boolean allPatterns) {
        apply(beat, slot, row, allPatterns, track -> track.setLevel(level));
    }

    /**
     * Sets one row's pan. {@code allPatterns} writes every pattern A–H.
     * Otherwise only {@code slot} changes.
     */
    public static void setPan(Beat beat, char slot, int row, int pan, boolean allPatterns) {
        apply(beat, slot, row, allPatterns, track -> track.setPan(pan));
    }

    private static void apply(Beat beat, char slot, int row, boolean allPatterns, Consumer<Track> edit) {
        if (beat == null || row < 0 || row >= Beat.TRACKS) {
            return;
        }
        if (allPatterns) {
            for (int i = 0; i < Beat.SLOT_COUNT; i++) {
                edit.accept(beat.slot(Beat.slotLetter(i))[row]);
            }
            return;
        }
        edit.accept(beat.slot(slot)[row]);
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
     * Drums at center pan share the General MIDI drum channel.
     * A panned drum takes the track's own channel so its pan is not stuck to the rest of the kit.
     */
    public static int channel(Track track, int row) {
        if (track.mode() == TrackMode.DRUM && track.pan() == Track.PAN_CENTER) {
            return Gm.DRUM_CHANNEL;
        }
        return row;
    }

    /** This drum is panned, so it needs the drum kit on its own channel. */
    public static boolean usesDrumBank(Track track) {
        return track.mode() == TrackMode.DRUM && track.pan() != Track.PAN_CENTER;
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
