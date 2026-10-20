package hits.cli;

import hits.Beat;
import hits.BeatFiles;
import hits.Chain;
import hits.ExportOptions;
import hits.GrooveGenerator;
import hits.Notes;
import hits.SequenceBuilder;
import hits.Track;
import hits.WavAudio;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.Sequence;
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
 * {@link BeatFiles} export methods as the desktop Export buttons, with no SoundFont loaded.
 */
public final class HitsCli {
    public static final int OK = 0;
    /** The beat could not be read, or the file could not be written. */
    public static final int FAIL = 1;
    /** The command line was missing a path, an option, or a known extension. */
    public static final int USAGE = 2;

    private static final String USAGE_TEXT = ""
        + "Usage:\n"
        + "  hits render <beat.json> --out <file.wav|file.mid> [--song]\n"
        + "  hits info <beat.json>\n"
        + "\n"
        + "render writes the active pattern once, as heard. --song writes the whole chain.\n"
        + "The output type comes from the extension (.wav, .mid, or .midi).\n"
        + "WAV uses the built-in General MIDI synth and does not open an audio device.\n"
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
        try {
            Path parent = parsed.out.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (wav) {
                BeatFiles.exportWav(beat, parsed.out, options, null, null);
            } else {
                BeatFiles.exportMidi(beat, parsed.out, options);
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
        try {
            out.print(describe(beat));
        } catch (InvalidMidiDataException exception) {
            err.println("Could not describe " + parsed.beat + ": " + detail(exception));
            return FAIL;
        }
        return OK;
    }

    /**
     * Tempo, swing, the patterns that are playing or named, the chain,
     * each of those patterns' tracks, and the length of one pattern and of the song.
     * Lengths use the same sequences as {@code render}.
     */
    static String describe(Beat beat) throws InvalidMidiDataException {
        Sequence pattern = SequenceBuilder.export(beat, ExportOptions.matchingPlayback());
        Sequence song = SequenceBuilder.export(beat, new ExportOptions(true, false, 1, true));
        int patternBars = bars(beat.stepCount(), 1);
        int songSections = beat.hasChain() ? Math.max(1, Chain.sections(beat.chain())) : 1;
        int songBars = bars(beat.stepCount(), songSections);
        StringBuilder text = new StringBuilder();
        text.append(beat.name()).append('\n');
        text.append("tempo: ").append(beat.bpm()).append(" bpm\n");
        text.append("swing: ").append(beat.swing()).append('%');
        if (beat.swing() == 50) {
            text.append(" straight");
        }
        text.append('\n');
        text.append("patterns: ").append(patternsUsed(beat)).append('\n');
        text.append("chain: ").append(chainText(beat)).append('\n');
        text.append("tracks:\n");
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
                text.append(String.format(
                    Locale.ROOT,
                    "  %s  %-16s %-4s %4s  %s%s%n",
                    Beat.slotLabel(slot),
                    track.name(),
                    track.mode().json(),
                    Notes.name(track.note()),
                    track.soundName(),
                    flags
                ));
            }
        }
        text.append("length: ").append(lengthText(pattern, patternBars)).append('\n');
        text.append("song: ").append(lengthText(song, songBars)).append('\n');
        return text.toString();
    }

    private static int bars(int steps, int sections) {
        int perSection = Math.max(1, steps / 16);
        return perSection * Math.max(1, sections);
    }

    private static String lengthText(Sequence sequence, int barCount) {
        double seconds = WavAudio.musicalMicros(sequence) / 1_000_000.0;
        String noun = barCount == 1 ? "bar" : "bars";
        return barCount + " " + noun + ", " + String.format(Locale.ROOT, "%.3f", seconds) + " s";
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
        for (int i = 1; i < args.length; i++) {
            String token = args[i];
            if (token == null) {
                return Parsed.error("Missing argument.");
            }
            if ("--song".equals(token)) {
                song = true;
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
        if (!render && (song || out != null)) {
            return Parsed.error("info takes only a beat file.");
        }
        if (render && out == null) {
            return Parsed.error("render needs --out <file.wav|file.mid>.");
        }
        return new Parsed(beat, out, song, null);
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

    private record Parsed(Path beat, Path out, boolean song, String error) {
        static Parsed error(String message) {
            return new Parsed(null, null, false, message);
        }
    }

    private static final class Fail extends Exception {
        final int code;

        Fail(int code) {
            this.code = code;
        }
    }
}
