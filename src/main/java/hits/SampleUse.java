package hits;

import javax.sound.midi.Soundbank;

/**
 * Which rows of this playback or export actually play a sample.
 * A row that is not listed falls back to its General MIDI note.
 * External MIDI outputs use {@link #none()} so every sample track does that.
 */
public final class SampleUse {
    private static final SampleUse NONE = new SampleUse(new SampleRef[Beat.TRACKS], null);

    private final SampleRef[] loaded;
    private final Soundbank bank;

    public SampleUse(SampleRef[] loaded, Soundbank bank) {
        this.loaded = loaded == null ? new SampleRef[Beat.TRACKS] : loaded;
        this.bank = bank;
    }

    public static SampleUse none() {
        return NONE;
    }

    public Soundbank bank() {
        return bank;
    }

    /** True when this track's sample is the one loaded for its row. */
    public boolean plays(Track track, int row) {
        if (track == null || track.mode() != TrackMode.SAMPLE) {
            return false;
        }
        if (row < 0 || row >= loaded.length || loaded[row] == null) {
            return false;
        }
        return loaded[row].same(track.sample());
    }
}
