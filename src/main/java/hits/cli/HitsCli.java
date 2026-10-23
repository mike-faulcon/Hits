package hits.cli;

import hits.Beat;
import hits.BeatFiles;
import hits.Chain;
import hits.ExportOptions;
import hits.GrooveGenerator;
import hits.Notes;
import hits.SampleLibrary;
import hits.SamplePaths;
import hits.SampleRef;
import hits.SampleUse;
import hits.SequenceBuilder;
import hits.SoundCatalog;
import hits.Timeline;
import hits.Track;
import hits.TrackMode;
import hits.WavAudio;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.Sequence;
import javax.sound.midi.Soundbank;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Headless commands for rendering and describing a beat.
 * {@code render} and {@code info} do not open a window. They call the same
 * {@link BeatFiles} export methods as the desktop Export buttons. WAV uses the built-in
 * General MIDI synth plus the beat's sample bank. A user SoundFont is not loaded.
 */
public final class HitsCli {
    public static final int OK = 0;
    /** The beat could not be read, or the file could not be written. */
    public static final int FAIL = 1;
    /** The command line was missing a path, an option, or a known extension. */
    public static final int USAGE = 2;

    private static final String USAGE_TEXT = ""
        + "Usage:\n"
        + "  hits render <beat.json> --out <file.wav|file.mid> [--song] [--samples-sf2]\n"
        + "  hits info <beat.json>\n"
        + "\n"
        + "render writes the active pattern once, as heard. --song writes the whole chain.\n"
        + "The output type comes from the extension (.wav, .mid, or .midi).\n"
        + "WAV uses the built-in General MIDI synth plus the beat's sample bank, and does not open an audio device.\n"
        + "Low cut and presence are in the WAV. A MIDI file keeps the ordinary note and does not carry them, and says so on stderr.\n"
        + "A user SoundFont is not loaded. --samples-sf2 writes <name>-samples.sf2 beside a MIDI file.\n"
        + "Exit codes: 0 ok, 1 the beat or the render failed, 2 the command line was wrong.\n";

    private HitsCli() {}

    /**
     * True when {@code args} should open the desktop window.
     * A macOS launcher sometimes passes a single {@code -psn_} token to a double-clicked app.
     * That is still a launch with no command.
     */
    public static boolean launchesDesktop(String[] args) {
        return commandArgs(args).length == 0;
    }

    /** Arguments with a lone macOS process-serial token removed. */
    public static String[] commandArgs(String[] args) {
        if (args == null || args.length == 0) {
            return new String[0];
        }
        if (args.length == 1 && args[0] != null && args[0].startsWith("-psn_")) {
            return new String[0];
        }
        return args;
    }

    public static int run(String[] args, PrintStream out) {
        return run(args, out, out);
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        System.setProperty("java.awt.headless", "true");
        PrintStream stdout = out == null ? System.out : out;
        PrintStream stderr = err == null ? System.err : err;
        String[] commands = commandArgs(args);
        if (commands.length == 0 || isHelp(commands[0])) {
            stdout.print(USAGE_TEXT);
            return commands.length == 0 ? USAGE : OK;
        }
        String verb = commands[0];
        if ("shot".equals(verb)) {
            stderr.println("hits shot is not available. It would open the studio window, which needs a display. render and info run with no display.");
            return USAGE;
        }
        if ("render".equals(verb)) {
            return render(commands, stdout, stderr);
        }
        if ("info".equals(verb)) {
            return info(commands, stdout, stderr);
        }
        stderr.println("Unknown command \"" + verb + "\".");
        stderr.print(USAGE_TEXT);
        return USAGE;
    }

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");
        System.exit(run(args, System.out, System.err));
    }

    private static int render(String[] args, PrintStream out, PrintStream err) {
        Parsed parsed = parse(args, true);
        if (parsed.error != null) {
            err.println(parsed.error);
            err.print(USAGE_TEXT);
            return USAGE;
        }
        String extension = extension(parsed.out);
        boolean wav = ".wav".equals(extension);
        boolean midi = ".mid".equals(extension) || ".midi".equals(extension);
        if (!wav && !midi) {
            String shown = extension.isEmpty() ? parsed.out.getFileName().toString() : extension;
            err.println("Unknown output extension \"" + shown + "\". Use .wav, .mid, or .midi.");
            return USAGE;
        }
        Beat beat;
        try {
            beat = load(parsed.beat, err);
        } catch (Fail fail) {
            return fail.code;
        }
        ExportOptions options = parsed.song
            ? new ExportOptions(true, false, 1, true)
            : ExportOptions.matchingPlayback();
        warnLoads(beat, err);
        warnSamples(beat, parsed.beat, SequenceBuilder.exportSlots(beat, options), err);
        if (midi || parsed.samplesSf2) {
            warnFilters(beat, err);
        }
        try {
            Path parent = parsed.out.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (wav) {
                String limiter = BeatFiles.exportWav(beat, parsed.out, options, null, null, () -> false, parsed.beat);
                if (limiter != null && !limiter.isBlank()) {
                    err.println(limiter);
                }
            } else {
                BeatFiles.exportMidi(beat, parsed.out, options, parsed.beat, parsed.samplesSf2);
            }
        } catch (IOException | InvalidMidiDataException | RuntimeException exception) {
            err.println("Could not render " + parsed.out + ": " + detail(exception));
            return FAIL;
        }
        try {
            if (!Files.isRegularFile(parsed.out) || Files.size(parsed.out) == 0) {
                err.println("Render wrote no data to " + parsed.out);
                return FAIL;
            }
        } catch (IOException exception) {
            err.println("Could not render " + parsed.out + ": " + detail(exception));
            return FAIL;
        }
        out.println("Wrote " + parsed.out.toAbsolutePath());
        if (parsed.samplesSf2 && midi) {
            Path companion = BeatFiles.samplesFont(parsed.out);
            if (Files.isRegularFile(companion)) {
                out.println("Wrote " + companion.toAbsolutePath());
            } else {
                out.println("No samples to write.");
            }
        }
        return OK;
    }

    private static int info(String[] args, PrintStream out, PrintStream err) {
        Parsed parsed = parse(args, false);
        if (parsed.error != null) {
            err.println(parsed.error);
            err.print(USAGE_TEXT);
            return USAGE;
        }
        Beat beat;
        try {
            beat = load(parsed.beat, err);
        } catch (Fail fail) {
            return fail.code;
        }
        warnLoads(beat, err);
        warnFilters(beat, err);
        try {
            List<Character> slots = usedSlots(beat);
            SampleLibrary.Ready ready = SampleLibrary.prepare(beat, parsed.beat, slots);
            warnSamples(ready, err);
            out.print(describe(beat, parsed.beat, ready.use()));
        } catch (InvalidMidiDataException exception) {
            err.println("Could not describe " + parsed.beat + ": " + detail(exception));
            return FAIL;
        }
        return OK;
    }

    /**
     * Tempo, swing, the patterns that are playing or named, each pattern's step count,
     * the chain, each of those patterns' tracks, and the length of one pattern and of the song.
     * Lengths use the same sequences as {@code render}.
     */
    static String describe(Beat beat) throws InvalidMidiDataException {
        return describe(beat, null, null);
    }

    /**
     * Same listing as {@link #describe(Beat)}. {@code beatFile} resolves sample paths.
     * {@code samples} says which of those paths were loaded. Null treats a readable sample as ready.
     */
    static String describe(Beat beat, Path beatFile, SampleUse samples) throws InvalidMidiDataException {
        Sequence pattern = SequenceBuilder.export(beat, ExportOptions.matchingPlayback());
        Sequence song = SequenceBuilder.export(beat, new ExportOptions(true, false, 1, true));
        List<Timeline.Section> patternSections = Timeline.pattern(beat);
        List<Timeline.Section> songSections = beat.hasChain() ? Timeline.song(beat) : patternSections;
        StringBuilder text = new StringBuilder();
        text.append(beat.name()).append('\n');
        text.append("tempo: ").append(beat.bpm()).append(" bpm\n");
        text.append("swing: ").append(beat.swing()).append('%');
        if (beat.swing() == 50) {
            text.append(" straight");
        }
        text.append('\n');
        text.append("patterns: ").append(patternsUsed(beat)).append('\n');
        text.append("lengths: ").append(lengthsText(beat)).append('\n');
        text.append("chain: ").append(chainText(beat)).append('\n');
        text.append("tracks:\n");
        Soundbank bank = SoundCatalog.builtin();
        for (char slot : usedSlots(beat)) {
            Track[] tracks = beat.slot(slot);
            for (int row = 0; row < tracks.length; row++) {
                Track track = tracks[row];
                String flags = "";
                if (track.mute()) {
                    flags = ", muted";
                } else if (track.solo()) {
                    flags = ", solo";
                }
                String sound = track.soundName();
                String fallback = SoundCatalog.kitFallback(bank, track);
                if (fallback != null) {
                    sound = sound + " — " + fallback;
                }
                String shape = track.shapeText();
                if (!shape.isEmpty()) {
                    sound = sound + ", " + shape;
                }
                if (track.mode() == TrackMode.SAMPLE) {
                    sound = sound + " — " + sampleStatus(track, row, beatFile, samples);
                }
                text.append(String.format(
                    Locale.ROOT,
                    "  %s  %-16s %-4s %4s  %s%s%n",
                    Beat.slotLabel(slot),
                    track.name(),
                    track.mode().json(),
                    Notes.name(track.playedNote()),
                    sound,
                    flags
                ));
            }
        }
        text.append("length: ").append(lengthText(pattern, patternSections)).append('\n');
        text.append("song: ").append(lengthText(song, songSections)).append('\n');
        text.append("sample gain: +").append(SampleRef.GAIN)
            .append(" dB is the default when a sample row does not set gain\n");
        return text.toString();
    }

    private static void warnLoads(Beat beat, PrintStream err) {
        if (beat == null) {
            return;
        }
        for (String warning : beat.loadWarnings()) {
            err.println(warning);
        }
    }

    private static void warnFilters(Beat beat, PrintStream err) {
        if (beat == null) {
            return;
        }
        String warning = beat.midiFilterWarning();
        if (warning != null) {
            err.println(warning);
        }
    }

    private static void warnSamples(Beat beat, Path beatFile, List<Character> slots, PrintStream err) {
        warnSamples(SampleLibrary.prepare(beat, beatFile, slots), err);
    }

    private static void warnSamples(SampleLibrary.Ready ready, PrintStream err) {
        if (ready != null && ready.warning() != null && !ready.warning().isBlank()) {
            err.println(ready.warning());
        }
    }

    /** Source path and whether that pattern's sample loaded. */
    private static String sampleStatus(Track track, int row, Path beatFile, SampleUse samples) {
        SampleRef sample = track.sample();
        String source = sample == null || sample.path().isBlank() ? "(none)" : sample.path();
        StringBuilder text = new StringBuilder(source);
        if (sample != null && sample.gain() != SampleRef.GAIN) {
            text.append(", gain ").append(SampleRef.gainText(sample.gain()));
        }
        if (sample != null && sample.invert()) {
            text.append(", invert");
        }
        String problem = SamplePaths.problem(track, beatFile);
        if (problem != null) {
            text.append(", missing, plays ").append(Notes.name(track.note()));
            return text.toString();
        }
        if (samples == null || samples.plays(track, row)) {
            text.append(", ready");
            return text.toString();
        }
        text.append(", plays ").append(Notes.name(track.note()));
        return text.toString();
    }

    private static String lengthsText(Beat beat) {
        StringBuilder text = new StringBuilder();
        for (char slot : usedSlots(beat)) {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(Beat.slotLabel(slot)).append(' ').append(beat.stepCount(slot));
        }
        return text.toString();
    }

    private static String lengthText(Sequence sequence, List<Timeline.Section> sections) {
        double seconds = WavAudio.musicalMicros(sequence) / 1_000_000.0;
        return barPhrase(sections) + ", " + String.format(Locale.ROOT, "%.3f", seconds) + " s";
    }

    /** Whole bars when every section is a multiple of 16. Otherwise the step total. */
    private static String barPhrase(List<Timeline.Section> sections) {
        int steps = 0;
        boolean whole = true;
        for (Timeline.Section section : sections) {
            steps += section.steps();
            if (section.steps() % 16 != 0) {
                whole = false;
            }
        }
        if (whole && steps > 0 && steps % 16 == 0) {
            int bars = steps / 16;
            return bars + (bars == 1 ? " bar" : " bars");
        }
        return steps + (steps == 1 ? " step" : " steps");
    }

    private static String patternsUsed(Beat beat) {
        StringBuilder text = new StringBuilder();
        for (char slot : usedSlots(beat)) {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(Beat.slotLabel(slot));
        }
        return text.toString();
    }

    /** Active pattern, any pattern with a hit, and any pattern named by the chain. */
    private static List<Character> usedSlots(Beat beat) {
        List<Character> slots = new ArrayList<>();
        for (char slot = 'a'; slot <= 'h'; slot++) {
            if (slot == beat.activeSlot() || GrooveGenerator.patternHasHits(beat, slot) || namedInChain(beat, slot)) {
                slots.add(slot);
            }
        }
        return slots;
    }

    private static boolean namedInChain(Beat beat, char slot) {
        for (Chain.Part part : beat.chain()) {
            if (part.slot() == slot) {
                return true;
            }
        }
        return false;
    }

    private static String chainText(Beat beat) {
        if (!beat.hasChain()) {
            return "(none)";
        }
        StringBuilder text = new StringBuilder();
        for (Chain.Part part : beat.chain()) {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(Beat.slotLabel(part.slot())).append(" x").append(part.repeats());
        }
        return text.toString();
    }

    private static Beat load(Path path, PrintStream err) throws Fail {
        if (path == null || path.getFileName() == null) {
            err.println("No such beat file: " + path);
            throw new Fail(FAIL);
        }
        if (!Files.exists(path)) {
            err.println("No such beat file: " + path);
            throw new Fail(FAIL);
        }
        if (Files.isDirectory(path)) {
            err.println("Not a beat file: " + path);
            throw new Fail(FAIL);
        }
        try {
            return BeatFiles.load(path);
        } catch (IllegalArgumentException exception) {
            err.println("Invalid beat file " + path + ": " + detail(exception));
            throw new Fail(FAIL);
        } catch (IOException exception) {
            err.println("Could not read " + path + ": " + detail(exception));
            throw new Fail(FAIL);
        }
    }

    private static Parsed parse(String[] args, boolean render) {
        Path beat = null;
        Path out = null;
        boolean song = false;
        boolean samplesSf2 = false;
        for (int i = 1; i < args.length; i++) {
            String token = args[i];
            if (token == null) {
                return Parsed.error("Missing argument.");
            }
            if ("--song".equals(token)) {
                song = true;
                continue;
            }
            if ("--samples-sf2".equals(token)) {
                samplesSf2 = true;
                continue;
            }
            if ("--out".equals(token)) {
                if (i + 1 >= args.length || args[i + 1] == null || args[i + 1].startsWith("--")) {
                    return Parsed.error("render needs a file after --out.");
                }
                if (out != null) {
                    return Parsed.error("render takes one --out file.");
                }
                out = Path.of(args[++i]);
                continue;
            }
            if (token.startsWith("-")) {
                return Parsed.error("Unknown option \"" + token + "\".");
            }
            if (beat != null) {
                return Parsed.error("Give one beat file.");
            }
            beat = Path.of(token);
        }
        if (beat == null) {
            return Parsed.error(render ? "render needs a beat file." : "info needs a beat file.");
        }
        if (!render && (song || out != null || samplesSf2)) {
            return Parsed.error("info takes only a beat file.");
        }
        if (render && out == null) {
            return Parsed.error("render needs --out <file.wav|file.mid>.");
        }
        return new Parsed(beat, out, song, samplesSf2, null);
    }

    private static String extension(Path path) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot).toLowerCase(Locale.ROOT);
    }

    private static boolean isHelp(String token) {
        return "help".equals(token) || "--help".equals(token) || "-h".equals(token);
    }

    private static String detail(Throwable exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message;
    }

    private record Parsed(Path beat, Path out, boolean song, boolean samplesSf2, String error) {
        static Parsed error(String message) {
            return new Parsed(null, null, false, false, message);
        }
    }

    private static final class Fail extends Exception {
        final int code;

        Fail(int code) {
            this.code = code;
        }
    }
}
