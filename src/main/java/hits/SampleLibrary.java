package hits;

import javax.sound.midi.Soundbank;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns sample tracks, and any row with low cut or presence, into one in-memory SoundFont.
 * One row has one preset for each distinct sample and filter. When two patterns name
 * different files on that row, the first pattern in the given slot list that can be
 * read wins and the other plays its General MIDI note.
 * <p>
 * {@code bakeTone} is false for MIDI export. The file cannot carry a high-pass, so the
 * companion SoundFont is unfiltered and a drum stays on its ordinary note.
 */
public final class SampleLibrary {
    private SampleLibrary() {}

    public record Ready(SampleUse use, byte[] sf2, String warning, String key) {
        public Soundbank bank() {
            return use == null ? null : use.bank();
        }
    }

    public static Ready prepare(Beat beat, Path beatFile, List<Character> slots) {
        return prepare(beat, beatFile, slots, null, true);
    }

    /**
     * {@code kits} is the SoundFont the built-in drums and notes are recorded from.
     * Null uses the JDK default, which is what a WAV export does when no SoundFont is loaded.
     * {@code bakeTone} records low cut and presence. MIDI export passes false.
     */
    public static Ready prepare(Beat beat, Path beatFile, List<Character> slots, Soundbank kits, boolean bakeTone) {
        return prepare(beat, beatFile, slots, kits, bakeTone, true);
    }

    /** Warnings only. External outputs do not build a bank. */
    public static Ready warningsOnly(Beat beat, Path beatFile, List<Character> slots) {
        return prepare(beat, beatFile, slots, null, false, false);
    }

    public static String cacheKey(Beat beat, Path beatFile, List<Character> slots) {
        return cacheKey(beat, beatFile, slots, null, true);
    }

    /** Includes the SoundFont and the low-cut settings, so a settled slider rebuilds once. */
    public static String cacheKey(Beat beat, Path beatFile, List<Character> slots, Soundbank kits, boolean bakeTone) {
        StringBuilder key = new StringBuilder();
        key.append(bakeTone ? '1' : '0').append('|').append(bankToken(kits)).append('|');
        if (beat == null || slots == null) {
            return key.toString();
        }
        for (char slot : slots) {
            key.append(slot);
            Track[] tracks = beat.slot(slot);
            for (int row = 0; row < tracks.length; row++) {
                String voice = voiceKey(tracks[row], beatFile, bakeTone);
                if (voice == null) {
                    continue;
                }
                key.append('|').append(row).append('=').append(voice);
            }
            key.append(';');
        }
        return key.toString();
    }

    /**
     * Identity of the voice this row would play.
     * Null when the row does not use the sample bank under {@code bakeTone}.
     */
    public static String voiceKey(Track track, Path beatFile, boolean bakeTone) {
        if (track == null) {
            return null;
        }
        if (track.mode() == TrackMode.SAMPLE) {
            SampleRef sample = track.sample();
            if (sample == null || sample.path().isBlank()) {
                return null;
            }
            return "s|" + sample.cacheToken(beatFile) + "|" + toneToken(track, bakeTone);
        }
        if (!bakeTone || !track.hasTone()) {
            return null;
        }
        String preset = track.preset() == null ? "-" : track.preset().json();
        return "i|" + track.mode().json()
            + "|" + track.program()
            + "|" + track.note()
            + "|" + track.playedNote()
            + "|" + track.bank()
            + "|" + track.kit()
            + "|" + preset
            + "|" + toneToken(track, true);
    }

    private static Ready prepare(
        Beat beat,
        Path beatFile,
        List<Character> slots,
        Soundbank kits,
        boolean bakeTone,
        boolean build
    ) {
        SampleRef[] loaded = new SampleRef[Beat.TRACKS];
        List<SampleBank.Voice> voices = new ArrayList<>();
        Map<String, Integer> programs = new LinkedHashMap<>();
        StringBuilder warnings = new StringBuilder();
        if (beat != null && slots != null) {
            collect(beat, beatFile, slots, kits, bakeTone, build, loaded, voices, programs, warnings);
        }
        String warning = warnings.toString().trim();
        String key = cacheKey(beat, beatFile, slots, kits, bakeTone);
        if (!build || voices.isEmpty()) {
            return new Ready(SampleUse.none(), null, warning, key);
        }
        byte[] sf2 = SampleBank.write(voices);
        try {
            Soundbank bank = SampleBank.load(sf2);
            SampleUse use = new SampleUse(loaded, bank, programs, beatFile, bakeTone);
            return new Ready(use, sf2, warning, key);
        } catch (IOException | javax.sound.midi.InvalidMidiDataException exception) {
            String failed = exception.getMessage() == null ? "The sample bank did not load." : exception.getMessage();
            if (!warning.isEmpty()) {
                failed = warning + " " + failed;
            }
            return new Ready(SampleUse.none(), null, failed, key);
        }
    }

    private static void collect(
        Beat beat,
        Path beatFile,
        List<Character> slots,
        Soundbank kits,
        boolean bakeTone,
        boolean build,
        SampleRef[] loaded,
        List<SampleBank.Voice> voices,
        Map<String, Integer> programs,
        StringBuilder warnings
    ) {
        SampleRef[] firstFile = new SampleRef[Beat.TRACKS];
        SampleAudio.Clip[] clips = new SampleAudio.Clip[Beat.TRACKS];
        int[] variants = new int[Beat.TRACKS];
        for (char slot : slots) {
            Track[] tracks = beat.slot(slot);
            for (int row = 0; row < tracks.length; row++) {
                Track track = tracks[row];
                if (track.mode() == TrackMode.SAMPLE) {
                    addSample(track, row, beatFile, bakeTone, build, loaded, firstFile, clips, variants, voices, programs, warnings);
                } else if (build && bakeTone && track.hasTone()) {
                    addRendered(track, row, kits, beatFile, variants, voices, programs, warnings);
                }
            }
        }
    }

    private static void addSample(
        Track track,
        int row,
        Path beatFile,
        boolean bakeTone,
        boolean build,
        SampleRef[] loaded,
        SampleRef[] firstFile,
        SampleAudio.Clip[] clips,
        int[] variants,
        List<SampleBank.Voice> voices,
        Map<String, Integer> programs,
        StringBuilder warnings
    ) {
        String problem = SamplePaths.problem(track, beatFile);
        if (problem != null) {
            append(warnings, track.name() + ": " + problem);
            return;
        }
        if (!build) {
            return;
        }
        SampleRef sample = track.sample();
        if (firstFile[row] != null && !firstFile[row].same(sample)) {
            return;
        }
        String key = voiceKey(track, beatFile, bakeTone);
        if (key == null || programs.containsKey(binding(row, key))) {
            return;
        }
        if (clips[row] == null) {
            try {
                clips[row] = read(sample, beatFile);
                firstFile[row] = sample;
                loaded[row] = sample;
            } catch (IOException exception) {
                append(warnings, track.name() + ": Could not read this sample. This row plays " + Notes.name(track.note()) + ".");
                return;
            }
        }
        SampleAudio.Clip clip = clips[row];
        short[] pcm = clip.samples();
        if (bakeTone && track.hasTone()) {
            pcm = ToneFilter.apply(pcm, clip.rate(), track.lowCutHz(), track.lowCutSlope(), track.presence());
        }
        int program = programFor(row, variants);
        programs.put(binding(row, key), program);
        voices.add(new SampleBank.Voice(
            program,
            sample.label(),
            clip.rate(),
            pcm,
            sample.root(),
            sample.start(),
            sample.oneShot()
        ));
    }

    private static void addRendered(
        Track track,
        int row,
        Soundbank kits,
        Path beatFile,
        int[] variants,
        List<SampleBank.Voice> voices,
        Map<String, Integer> programs,
        StringBuilder warnings
    ) {
        String key = voiceKey(track, beatFile, true);
        if (key == null || programs.containsKey(binding(row, key))) {
            return;
        }
        try {
            ToneRender.Rendered rendered = ToneRender.render(kits, track);
            int program = programFor(row, variants);
            programs.put(binding(row, key), program);
            voices.add(new SampleBank.Voice(
                program,
                track.soundName(),
                WavAudio.SAMPLE_RATE,
                rendered.samples(),
                track.playedNote(),
                0,
                !rendered.loop(),
                rendered.loop()
            ));
        } catch (IOException exception) {
            String reason = exception.getMessage() == null ? "Low cut did not render." : exception.getMessage();
            append(warnings, track.name() + ": " + reason + " This row plays its built-in sound.");
        }
    }

    private static int programFor(int row, int[] variants) {
        int program = row + 8 * variants[row];
        variants[row]++;
        if (program > 127) {
            return row;
        }
        return program;
    }

    private static String binding(int row, String key) {
        return row + "|" + key;
    }

    private static String toneToken(Track track, boolean bakeTone) {
        if (!bakeTone || !track.hasTone()) {
            return "0";
        }
        return track.lowCutHz() + ":" + track.lowCutSlope() + ":" + track.presence();
    }

    private static String bankToken(Soundbank kits) {
        if (kits == null) {
            return "-";
        }
        String name = kits.getName() == null ? "" : kits.getName();
        return name + "#" + System.identityHashCode(kits);
    }

    private static SampleAudio.Clip read(SampleRef sample, Path beatFile) throws IOException {
        if (BuiltinSamples.isBuiltin(sample.path())) {
            BuiltinSamples.Pcm pcm = BuiltinSamples.pcm(sample.path());
            if (pcm == null) {
                throw new IOException("Unknown sample");
            }
            return new SampleAudio.Clip(pcm.rate(), pcm.samples());
        }
        Path path = SamplePaths.resolve(sample.path(), beatFile);
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
