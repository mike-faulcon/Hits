package hits;

import javax.sound.midi.Soundbank;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns the sample tracks in a beat into one in-memory SoundFont.
 * Each distinct sample on a row is its own preset in bank {@link SampleBank#SF2_BANK},
 * so pattern A and pattern B can play different files on the same row. Decay, gain, and
 * polarity are part of that preset. A row whose file cannot be read, or that does not fit
 * in the bank, falls back to its General MIDI drum and is named in {@link Ready#warning()}.
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
                key.append('|').append(row).append('=').append(track.sample().cacheToken(beatFile))
                    .append(",d").append(track.decay());
            }
            key.append(';');
        }
        return key.toString();
    }

    private static Ready prepare(Beat beat, Path beatFile, List<Character> slots, boolean build) {
        List<SampleUse.Play> plays = new ArrayList<>();
        List<SampleBank.Voice> voices = new ArrayList<>();
        StringBuilder warnings = new StringBuilder();
        if (beat != null && slots != null) {
            List<List<Pending>> pending = new ArrayList<>();
            for (int row = 0; row < Beat.TRACKS; row++) {
                pending.add(collect(beat, beatFile, slots, row, warnings, build));
            }
            if (build) {
                assign(pending, plays, voices, warnings);
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
            return new Ready(new SampleUse(plays, bank), sf2, warning, key);
        } catch (IOException | javax.sound.midi.InvalidMidiDataException exception) {
            String failed = exception.getMessage() == null ? "The sample bank did not load." : exception.getMessage();
            if (!warning.isEmpty()) {
                failed = warning + " " + failed;
            }
            return new Ready(SampleUse.none(), null, failed, key);
        }
    }

    private record Pending(SampleRef sample, int decay, SampleBank.Voice voice) {}

    private static List<Pending> collect(
        Beat beat,
        Path beatFile,
        List<Character> slots,
        int row,
        StringBuilder warnings,
        boolean build
    ) {
        List<Pending> found = new ArrayList<>();
        for (char slot : slots) {
            Track track = beat.slot(slot)[row];
            if (track.mode() != TrackMode.SAMPLE || track.sample() == null) {
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
            if (already(found, track)) {
                continue;
            }
            SampleRef sample = track.sample();
            try {
                SampleAudio.Clip clip = read(sample, beatFile);
                short[] pcm = sample.invert() ? invert(clip.samples()) : clip.samples();
                int audible = Math.max(1, pcm.length - Math.min(sample.start(), Math.max(0, pcm.length - 1)));
                SampleBank.Envelope envelope = SampleBank.envelope(sample.oneShot(), track.decay(), audible, clip.rate());
                found.add(new Pending(sample, track.decay(), new SampleBank.Voice(
                    row,
                    sample.label(),
                    clip.rate(),
                    pcm,
                    sample.root(),
                    sample.start(),
                    sample.oneShot(),
                    envelope.decay(),
                    envelope.sustain(),
                    envelope.release(),
                    SampleBank.attenuation(sample.gain())
                )));
            } catch (IOException exception) {
                append(warnings, track.name() + ": Could not read this sample. This row plays " + Notes.name(track.note()) + ".");
            }
        }
        return found;
    }

    private static boolean already(List<Pending> found, Track track) {
        for (Pending pending : found) {
            if (pending.decay == track.decay() && pending.sample.same(track.sample())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The first sample on a row keeps program = row, which is what a single-sample beat already used.
     * Further samples on that row take the next free program in the bank. A voice that does not fit
     * is left out and the pattern falls back to the drum note.
     */
    private static void assign(
        List<List<Pending>> pending,
        List<SampleUse.Play> plays,
        List<SampleBank.Voice> voices,
        StringBuilder warnings
    ) {
        boolean[] taken = new boolean[128];
        int[] firstProgram = new int[pending.size()];
        for (int row = 0; row < pending.size(); row++) {
            firstProgram[row] = -1;
            if (pending.get(row).isEmpty()) {
                continue;
            }
            if (!taken[row]) {
                firstProgram[row] = row;
                taken[row] = true;
            }
        }
        int next = 0;
        for (int row = 0; row < pending.size(); row++) {
            List<Pending> rowVoices = pending.get(row);
            for (int index = 0; index < rowVoices.size(); index++) {
                Pending pendingVoice = rowVoices.get(index);
                int program = index == 0 ? firstProgram[row] : -1;
                if (program < 0) {
                    while (next < taken.length && taken[next]) {
                        next++;
                    }
                    if (next >= taken.length) {
                        append(warnings, pendingVoice.sample.label()
                            + ": The sample bank is full. This row plays its drum note.");
                        continue;
                    }
                    program = next;
                    taken[next] = true;
                    next++;
                }
                SampleBank.Voice voice = pendingVoice.voice;
                voices.add(new SampleBank.Voice(
                    program,
                    voice.name(),
                    voice.rate(),
                    voice.samples(),
                    voice.root(),
                    voice.start(),
                    voice.oneShot(),
                    voice.decay(),
                    voice.sustain(),
                    voice.release(),
                    voice.attenuation()
                ));
                plays.add(new SampleUse.Play(row, pendingVoice.sample, pendingVoice.decay, program));
            }
        }
    }

    private static short[] invert(short[] samples) {
        short[] out = new short[samples.length];
        for (int i = 0; i < samples.length; i++) {
            out[i] = samples[i] == Short.MIN_VALUE ? Short.MAX_VALUE : (short) -samples[i];
        }
        return out;
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
