package hits;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Soundbank;
import javax.sound.midi.Synthesizer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * {@code .sf2} loading through the JDK SoundFont reader ({@link MidiSystem#getSoundbank}).
 * The bank plays on the built-in Java synth. External MIDI outputs use their own sounds.
 */
public final class SoundFonts {
    public static final String PREF_FILE = "soundFont";
    public static final String NEEDS_BUILTIN =
        "SoundFonts play on the built-in synth. Choose Built-in synth under MIDI out.";
    public static final String UNSUPPORTED = "The built-in synth cannot play this SoundFont.";

    private SoundFonts() {}

    public static boolean isSoundFontFile(Path path) {
        if (path == null || path.getFileName() == null) {
            return false;
        }
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".sf2");
    }

    public static Soundbank read(Path path) throws IOException, InvalidMidiDataException {
        if (path == null || !Files.isRegularFile(path)) {
            throw new IOException("SoundFont file was not found");
        }
        if (!isSoundFontFile(path)) {
            throw new IllegalArgumentException("Choose a .sf2 SoundFont");
        }
        return MidiSystem.getSoundbank(path.toFile());
    }

    /**
     * Loads {@code bank} and then unloads {@code previous}, so a failed load leaves the current sounds.
     */
    public static void install(Synthesizer synthesizer, Soundbank bank, Soundbank previous) {
        if (synthesizer == null || !synthesizer.isOpen()) {
            throw new IllegalStateException(NEEDS_BUILTIN);
        }
        if (bank == null || !synthesizer.isSoundbankSupported(bank)) {
            throw new IllegalArgumentException(UNSUPPORTED);
        }
        if (!synthesizer.loadAllInstruments(bank)) {
            throw new IllegalArgumentException("The built-in synth did not load this SoundFont.");
        }
        if (previous != null && previous != bank) {
            synthesizer.unloadAllInstruments(previous);
        }
    }

    public static String displayName(Soundbank bank, Path path) {
        if (bank != null && bank.getName() != null && !bank.getName().isBlank()) {
            return bank.getName().trim();
        }
        if (path != null && path.getFileName() != null) {
            return path.getFileName().toString();
        }
        return "SoundFont";
    }
}
