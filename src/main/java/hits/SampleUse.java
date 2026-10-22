package hits;

import javax.sound.midi.Soundbank;
import java.nio.file.Path;
import java.util.Map;

/**
 * Which rows of this playback or export actually play from the sample bank.
 * A sample row that is not listed falls back to its General MIDI note.
 * A drum or note is listed only when low cut or presence was baked into a voice.
 * External MIDI outputs use {@link #none()} so every row plays the ordinary note.
 */
public final class SampleUse {
    private static final SampleUse NONE = new SampleUse(new SampleRef[Beat.TRACKS], null, Map.of(), null, false);

    private final SampleRef[] loaded;
    private final Soundbank bank;
    /** {@code row + "|" + voice key} to the SoundFont program. Empty uses the legacy sample match. */
    private final Map<String, Integer> programs;
    private final Path beatFile;
    private final boolean bakeTone;

    public SampleUse(SampleRef[] loaded, Soundbank bank) {
        this(loaded, bank, Map.of(), null, false);
    }

    SampleUse(SampleRef[] loaded, Soundbank bank, Map<String, Integer> programs, Path beatFile, boolean bakeTone) {
        this.loaded = loaded == null ? new SampleRef[Beat.TRACKS] : loaded;
        this.bank = bank;
        this.programs = programs == null ? Map.of() : programs;
        this.beatFile = beatFile;
        this.bakeTone = bakeTone;
    }

    public static SampleUse none() {
        return NONE;
    }

    public Soundbank bank() {
        return bank;
    }

    /** True when this track plays a voice from the sample bank. */
    public boolean plays(Track track, int row) {
        if (track == null || row < 0 || row >= loaded.length) {
            return false;
        }
        if (!programs.isEmpty()) {
            return programOf(track, row) != null;
        }
        if (track.mode() != TrackMode.SAMPLE || loaded[row] == null || track.sample() == null) {
            return false;
        }
        return loaded[row].same(track.sample());
    }

    /** SoundFont program for this row, or the row number when it is not in the bank. */
    public int program(Track track, int row) {
        Integer found = programOf(track, row);
        if (found != null) {
            return found;
        }
        return Math.max(0, row);
    }

    private Integer programOf(Track track, int row) {
        if (programs.isEmpty() || track == null) {
            return null;
        }
        String key = SampleLibrary.voiceKey(track, beatFile, bakeTone);
        if (key == null) {
            return null;
        }
        String binding = row + "|" + key;
        Integer exact = programs.get(binding);
        if (exact != null) {
            return exact;
        }
        // The bank is rebuilt when the slider settles. Until then the previous
        // filter for this same sample or instrument keeps playing.
        String stem = stem(binding);
        for (Map.Entry<String, Integer> entry : programs.entrySet()) {
            if (stem.equals(stem(entry.getKey()))) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** Binding without the trailing low-cut token. */
    private static String stem(String binding) {
        int cut = binding.lastIndexOf('|');
        return cut < 0 ? binding : binding.substring(0, cut);
    }
}
