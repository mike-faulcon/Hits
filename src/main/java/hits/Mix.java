package hits;

/** Mixer math shared by playback, export, and the mixer view. */
public final class Mix {
    private Mix() {}

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
