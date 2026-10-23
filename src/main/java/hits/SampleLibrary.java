package hits;

import javax.sound.midi.Soundbank;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns sample tracks, and any row with low cut, presence, or EQ, into one in-memory SoundFont.
 * Each distinct sample on a row is its own preset in bank {@link SampleBank#SF2_BANK},
 * so pattern A and pattern B can play different files on the same row. Decay, gain, and
 * polarity are part of that preset. Low cut, EQ, and presence are baked into its PCM when
 * {@code bakeTone} is set. A row whose file cannot be read, or that does not fit in the
 * bank, falls back to its General MIDI drum and is named in {@link Ready#warning()}.
 * <p>
 * {@code bakeTone} is false for MIDI export. The file cannot carry the filters, so the
 * companion SoundFont keeps gain, invert, and the envelope, and leaves the PCM unfiltered.
 * A drum stays on its ordinary note.
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
     * {@code bakeTone} records low cut, EQ, and presence. MIDI export passes false.
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
            return "s|" + sample.cacheToken(beatFile) + "|d" + track.decay() + "|" + toneToken(track, bakeTone);
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

    /** {@code 0} when low cut, EQ, and presence are off, or when they are not being baked. */
    static String toneToken(Track track, boolean bakeTone) {
        if (!bakeTone || track == null || !track.hasTone()) {
            return "0";
        }
        StringBuilder text = new StringBuilder();
        text.append(track.lowCutHz()).append(':')
            .append(track.lowCutSlope()).append(':')
            .append(Db.number(track.presence()));
        for (EqBand band : track.eqBands()) {
            text.append(':').append(band.hz())
                .append(',').append(Db.number(band.gain()))
                .append(',').append(Db.number(band.q()));
        }
        text.append(":h").append(track.highCutHz());
        return text.toString();
    }

    private static Ready prepare(
        Beat beat,
        Path beatFile,
        List<Character> slots,
        Soundbank kits,
        boolean bakeTone,
        boolean build
    ) {
        List<SampleUse.Play> plays = new ArrayList<>();
        List<SampleBank.Voice> voices = new ArrayList<>();
        Map<String, Integer> rendered = new LinkedHashMap<>();
        StringBuilder warnings = new StringBuilder();
        if (beat != null && slots != null) {
            List<List<Pending>> pending = new ArrayList<>();
            List<Rendered> extras = new ArrayList<>();
            for (int row = 0; row < Beat.TRACKS; row++) {
                pending.add(collect(beat, beatFile, slots, row, bakeTone, warnings, build));
                if (build && bakeTone) {
                    collectRendered(beat, slots, row, kits, beatFile, extras, rendered, warnings);
                }
            }
            if (build) {
                boolean[] taken = assign(pending, plays, voices, warnings);
                assignRendered(extras, taken, voices, rendered, warnings);
            }
        }
        String warning = warnings.toString().trim();
        String key = cacheKey(beat, beatFile, slots, kits, bakeTone);
        if (!build || voices.isEmpty()) {
            return new Ready(SampleUse.none(), null, warning, key);
        }
        byte[] sf2 = SampleBank.write(voices);
        try {
            Soundbank bank = SampleBank.load(sf2);
            SampleUse use = new SampleUse(plays, bank, rendered, beatFile, bakeTone);
            return new Ready(use, sf2, warning, key);
        } catch (IOException | javax.sound.midi.InvalidMidiDataException exception) {
            String failed = exception.getMessage() == null ? "The sample bank did not load." : exception.getMessage();
            if (!warning.isEmpty()) {
                failed = warning + " " + failed;
            }
            return new Ready(SampleUse.none(), null, failed, key);
        }
    }

    private record Pending(SampleRef sample, int decay, String tone, SampleBank.Voice voice) {}

    private record Rendered(int row, String key, SampleBank.Voice voice) {}

    private static List<Pending> collect(
        Beat beat,
        Path beatFile,
        List<Character> slots,
        int row,
        boolean bakeTone,
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
            String tone = toneToken(track, bakeTone);
            if (already(found, track, tone)) {
                continue;
            }
            SampleRef sample = track.sample();
            try {
                SampleAudio.Clip clip = read(sample, beatFile);
                // Invert, then low cut, EQ bands, high cut, presence, then the 16-bit fit.
                short[] pcm = sample.invert() ? invert(clip.samples()) : clip.samples();
                double makeup = 0;
                if (bakeTone && track.hasTone()) {
                    double[] filtered = ToneFilter.process(pcm, clip.rate(), track);
                    ToneFilter.Fitted fitted = ToneFilter.fit(filtered);
                    pcm = fitted.samples();
                    makeup = fitted.makeupDb();
                }
                int audible = Math.max(1, pcm.length - Math.min(sample.start(), Math.max(0, pcm.length - 1)));
                SampleBank.Envelope envelope = SampleBank.envelope(sample.oneShot(), track.decay(), audible, clip.rate());
                found.add(new Pending(sample, track.decay(), tone, new SampleBank.Voice(
                    row,
                    sample.label(),
                    clip.rate(),
                    pcm,
                    sample.root(),
                    sample.start(),
                    sample.oneShot(),
                    false,
                    envelope.decay(),
                    envelope.sustain(),
                    envelope.release(),
                    SampleBank.attenuation(sample.gain() + makeup)
                )));
            } catch (IOException exception) {
                append(warnings, track.name() + ": Could not read this sample. This row plays " + Notes.name(track.note()) + ".");
            }
        }
        return found;
    }

    private static void collectRendered(
        Beat beat,
        List<Character> slots,
        int row,
        Soundbank kits,
        Path beatFile,
        List<Rendered> extras,
        Map<String, Integer> claimed,
        StringBuilder warnings
    ) {
        for (char slot : slots) {
            Track track = beat.slot(slot)[row];
            if (track.mode() == TrackMode.SAMPLE || !track.hasTone()) {
                continue;
            }
            String key = voiceKey(track, beatFile, true);
            if (key == null || claimed.containsKey(row + "|" + key)) {
                continue;
            }
            claimed.put(row + "|" + key, -1);
            try {
                ToneRender.Rendered rendered = ToneRender.render(kits, track);
                boolean loop = rendered.loop();
                extras.add(new Rendered(row, key, new SampleBank.Voice(
                    row,
                    track.soundName(),
                    WavAudio.SAMPLE_RATE,
                    rendered.samples(),
                    track.playedNote(),
                    0,
                    !loop,
                    loop,
                    SampleBank.DECAY_NONE,
                    SampleBank.SUSTAIN_FULL,
                    SampleBank.RELEASE_ONE_SHOT,
                    rendered.attenuation(),
                    rendered.loopStart(),
                    rendered.loopEnd(),
                    rendered.sampleMode()
                )));
            } catch (IOException exception) {
                claimed.remove(row + "|" + key);
                String reason = exception.getMessage() == null ? "Low cut did not render." : exception.getMessage();
                append(warnings, track.name() + ": " + reason + " This row plays its built-in sound.");
            }
        }
    }

    private static boolean already(List<Pending> found, Track track, String tone) {
        for (Pending pending : found) {
            if (pending.decay == track.decay() && pending.tone.equals(tone) && pending.sample.same(track.sample())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The first sample on a row keeps program = row, which is what a single-sample beat already used.
     * Further samples on that row take the next free program in the bank. A voice that does not fit
     * is left out and the pattern falls back to the drum note.
     * @return which programs were taken
     */
    private static boolean[] assign(
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
                    program = nextFree(taken, next);
                    if (program < 0) {
                        append(warnings, pendingVoice.sample.label()
                            + ": The sample bank is full. This row plays its drum note.");
                        continue;
                    }
                    next = program + 1;
                }
                voices.add(copy(pendingVoice.voice, program));
                plays.add(new SampleUse.Play(
                    row,
                    pendingVoice.sample,
                    pendingVoice.decay,
                    pendingVoice.tone,
                    program
                ));
            }
        }
        return taken;
    }

    private static void assignRendered(
        List<Rendered> extras,
        boolean[] taken,
        List<SampleBank.Voice> voices,
        Map<String, Integer> rendered,
        StringBuilder warnings
    ) {
        int next = 0;
        for (Rendered extra : extras) {
            int program = extra.row < taken.length && !taken[extra.row] ? extra.row : -1;
            if (program < 0) {
                program = nextFree(taken, next);
            }
            if (program < 0) {
                rendered.remove(extra.row + "|" + extra.key);
                append(warnings, extra.voice.name() + ": The sample bank is full. This row plays its built-in sound.");
                continue;
            }
            next = program + 1;
            taken[program] = true;
            voices.add(copy(extra.voice, program));
            rendered.put(extra.row + "|" + extra.key, program);
        }
    }

    private static int nextFree(boolean[] taken, int start) {
        for (int program = Math.max(0, start); program < taken.length; program++) {
            if (!taken[program]) {
                taken[program] = true;
                return program;
            }
        }
        return -1;
    }

    private static SampleBank.Voice copy(SampleBank.Voice voice, int program) {
        return new SampleBank.Voice(
            program,
            voice.name(),
            voice.rate(),
            voice.samples(),
            voice.root(),
            voice.start(),
            voice.oneShot(),
            voice.loop(),
            voice.decay(),
            voice.sustain(),
            voice.release(),
            voice.attenuation(),
            voice.loopStart(),
            voice.loopEnd(),
            voice.sampleMode()
        );
    }

    private static short[] invert(short[] samples) {
        short[] out = new short[samples.length];
        for (int i = 0; i < samples.length; i++) {
            out[i] = samples[i] == Short.MIN_VALUE ? Short.MAX_VALUE : (short) -samples[i];
        }
        return out;
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
