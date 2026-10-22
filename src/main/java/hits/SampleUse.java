package hits;

import javax.sound.midi.Soundbank;
import java.util.List;

/**
 * Which sample presets this playback or export actually loaded.
 * A row that is not listed falls back to its General MIDI drum, on channel 9.
 * External MIDI outputs use {@link #none()} so every sample track does that.
 * One row can load more than one preset when two patterns name different samples,
 * or the same sample at a different decay, gain, or polarity.
 */
public final class SampleUse {
    private static final SampleUse NONE = new SampleUse(List.of(), null);

    /** One loaded preset. {@code decay} is the track percent baked into the envelope. */
    public record Play(int row, SampleRef sample, int decay, int program) {}

    private final List<Play> plays;
    private final Soundbank bank;

    public SampleUse(List<Play> plays, Soundbank bank) {
        this.plays = plays == null ? List.of() : List.copyOf(plays);
        this.bank = bank;
    }

    public static SampleUse none() {
        return NONE;
    }

    public Soundbank bank() {
        return bank;
    }

    public List<Play> plays() {
        return plays;
    }

    /** True when this track's sample, decay, gain, and polarity were loaded. */
    public boolean plays(Track track, int row) {
        return program(track, row) >= 0;
    }

    /**
     * Program in bank {@link SampleBank#SF2_BANK} for this pattern's sample, or -1 when
     * the row falls back to its drum note.
     */
    public int program(Track track, int row) {
        if (track == null || track.mode() != TrackMode.SAMPLE || track.sample() == null) {
            return -1;
        }
        for (Play play : plays) {
            if (play.row == row && play.decay == track.decay() && play.sample.same(track.sample())) {
                return play.program;
            }
        }
        return -1;
    }
}
