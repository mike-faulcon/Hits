package hits;

import javax.sound.midi.Soundbank;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Which presets this playback or export actually loaded.
 * A sample row that is not listed falls back to its General MIDI drum, on channel 9.
 * A drum or note is listed only when low cut or presence was baked into a voice.
 * One row can load more than one preset when two patterns name different samples,
 * or the same sample at a different decay, gain, polarity, or low cut.
 * External MIDI outputs use {@link #none()} so every row plays the ordinary note.
 */
public final class SampleUse {
    private static final SampleUse NONE = new SampleUse(List.of(), null);

    /**
     * One loaded sample preset. {@code decay} is the track percent baked into the envelope.
     * {@code tone} is the low-cut token, {@code 0} when the preset is unfiltered.
     */
    public record Play(int row, SampleRef sample, int decay, String tone, int program) {
        public Play {
            tone = tone == null ? "0" : tone;
        }
    }

    private final List<Play> plays;
    private final Soundbank bank;
    /** {@code row + "|" + voice key} for a drum or note recorded into the bank. */
    private final Map<String, Integer> rendered;
    private final Path beatFile;
    private final boolean bakeTone;

    public SampleUse(List<Play> plays, Soundbank bank) {
        this(plays, bank, Map.of(), null, false);
    }

    SampleUse(
        List<Play> plays,
        Soundbank bank,
        Map<String, Integer> rendered,
        Path beatFile,
        boolean bakeTone
    ) {
        this.plays = plays == null ? List.of() : List.copyOf(plays);
        this.bank = bank;
        this.rendered = rendered == null ? Map.of() : Map.copyOf(rendered);
        this.beatFile = beatFile;
        this.bakeTone = bakeTone;
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

    /** True when this track plays a voice from the sample bank. */
    public boolean plays(Track track, int row) {
        return program(track, row) >= 0;
    }

    /**
     * Program in bank {@link SampleBank#SF2_BANK} for this pattern, or -1 when the row
     * falls back to its built-in note.
     */
    public int program(Track track, int row) {
        Integer found = programOf(track, row);
        return found == null ? -1 : found;
    }

    private Integer programOf(Track track, int row) {
        if (track == null || row < 0) {
            return null;
        }
        if (track.mode() != TrackMode.SAMPLE) {
            return renderedProgram(track, row);
        }
        SampleRef sample = track.sample();
        if (sample == null) {
            return null;
        }
        String tone = SampleLibrary.toneToken(track, bakeTone);
        Play fallback = null;
        for (Play play : plays) {
            if (play.row != row || play.sample == null || !sameFile(play.sample, sample)) {
                continue;
            }
            if (play.decay == track.decay() && play.sample.same(sample) && play.tone.equals(tone)) {
                return play.program;
            }
            if (fallback == null) {
                fallback = play;
            }
        }
        // The bank is rebuilt when a slider settles. Until then the previous
        // preset for this file keeps playing.
        return fallback == null ? null : fallback.program;
    }

    private Integer renderedProgram(Track track, int row) {
        if (rendered.isEmpty()) {
            return null;
        }
        String key = SampleLibrary.voiceKey(track, beatFile, bakeTone);
        if (key == null) {
            return null;
        }
        String binding = row + "|" + key;
        Integer exact = rendered.get(binding);
        if (exact != null) {
            return exact;
        }
        String stem = stem(binding);
        for (Map.Entry<String, Integer> entry : rendered.entrySet()) {
            if (stem.equals(stem(entry.getKey()))) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** Same file and playback settings, ignoring gain, polarity, and the filter. */
    private static boolean sameFile(SampleRef left, SampleRef right) {
        return left.path().equals(right.path())
            && left.root() == right.root()
            && left.start() == right.start()
            && left.oneShot() == right.oneShot();
    }

    /** Binding without the trailing low-cut token. */
    private static String stem(String binding) {
        int cut = binding.lastIndexOf('|');
        return cut < 0 ? binding : binding.substring(0, cut);
    }
}
