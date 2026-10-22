package hits;

import javax.sound.midi.Soundbank;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns the sample tracks in a beat into one in-memory SoundFont.
 * One row has one preset. When two patterns name different files on that row,
 * the first pattern in the given slot list that can be read wins and the other
 * plays its General MIDI note.
 */
public final class SampleLibrary {
    private SampleLibrary() {}

    public record Ready(SampleUse use, byte[] sf2, String warning, String key) {
        public Soundbank bank() {
            return use == null ? null : use.bank();
        }
    }

    public static Ready prepare(Beat beat, Path beatFile, List<Character> slots) {
        return prepare(beat, beatFile, slots, true);
    }

    /** Warnings only. External outputs do not build a bank. */
    public static Ready warningsOnly(Beat beat, Path beatFile, List<Character> slots) {
        return prepare(beat, beatFile, slots, false);
    }

    public static String cacheKey(Beat beat, Path beatFile, List<Character> slots) {
        StringBuilder key = new StringBuilder();
        if (beat == null || slots == null) {
            return "";
        }
        for (char slot : slots) {
            key.append(slot);
            Track[] tracks = beat.slot(slot);
            for (int row = 0; row < tracks.length; row++) {
                Track track = tracks[row];
                if (track.mode() != TrackMode.SAMPLE || track.sample() == null) {
                    continue;
                }
                key.append('|').append(row).append('=').append(track.sample().cacheToken(beatFile));
            }
            key.append(';');
        }
        return key.toString();
    }

    private static Ready prepare(Beat beat, Path beatFile, List<Character> slots, boolean build) {
        SampleRef[] loaded = new SampleRef[Beat.TRACKS];
        List<SampleBank.Voice> voices = new ArrayList<>();
        StringBuilder warnings = new StringBuilder();
        if (beat != null && slots != null) {
            for (int row = 0; row < Beat.TRACKS; row++) {
                pick(beat, beatFile, slots, row, loaded, voices, warnings, build);
            }
        }
        String warning = warnings.toString().trim();
        String key = cacheKey(beat, beatFile, slots);
        if (!build || voices.isEmpty()) {
            return new Ready(SampleUse.none(), null, warning, key);
        }
        byte[] sf2 = SampleBank.write(voices);
        try {
            Soundbank bank = SampleBank.load(sf2);
            return new Ready(new SampleUse(loaded, bank), sf2, warning, key);
        } catch (IOException | javax.sound.midi.InvalidMidiDataException exception) {
            String failed = exception.getMessage() == null ? "The sample bank did not load." : exception.getMessage();
            if (!warning.isEmpty()) {
                failed = warning + " " + failed;
            }
            return new Ready(SampleUse.none(), null, failed, key);
        }
    }

    private static void pick(
        Beat beat,
        Path beatFile,
        List<Character> slots,
        int row,
        SampleRef[] loaded,
        List<SampleBank.Voice> voices,
        StringBuilder warnings,
        boolean build
    ) {
        for (char slot : slots) {
            Track track = beat.slot(slot)[row];
            if (track.mode() != TrackMode.SAMPLE) {
                continue;
            }
            String problem = SamplePaths.problem(track, beatFile);
            if (problem != null) {
                append(warnings, track.name() + ": " + problem);
                continue;
            }
            if (!build) {
                continue;
            }
            SampleRef sample = track.sample();
            try {
                SampleAudio.Clip clip = read(sample, beatFile);
                loaded[row] = sample;
                voices.add(new SampleBank.Voice(
                    row,
                    sample.label(),
                    clip.rate(),
                    clip.samples(),
                    sample.root(),
                    sample.start(),
                    sample.oneShot()
                ));
                return;
            } catch (IOException exception) {
                append(warnings, track.name() + ": Could not read this sample. This row plays " + Notes.name(track.note()) + ".");
            }
        }
    }

    private static SampleAudio.Clip read(SampleRef sample, Path beatFile) throws IOException {
        if (BuiltinSamples.isBuiltin(sample.path())) {
            BuiltinSamples.Pcm pcm = BuiltinSamples.pcm(sample.path());
            if (pcm == null) {
                throw new IOException("Unknown sample");
            }
            return new SampleAudio.Clip(pcm.rate(), pcm.samples());
        }
        java.nio.file.Path path = SamplePaths.resolve(sample.path(), beatFile);
        return SampleAudio.read(path);
    }

    private static void append(StringBuilder warnings, String text) {
        if (warnings.indexOf(text) >= 0) {
            return;
        }
        if (warnings.length() > 0) {
            warnings.append(' ');
        }
        warnings.append(text);
    }
}
