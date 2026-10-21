package hits;

import javax.sound.midi.Instrument;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Patch;
import javax.sound.midi.Soundbank;
import javax.sound.midi.Synthesizer;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Instruments in a loaded soundbank. The JDK's emergency bank has one percussion kit
 * and 128 melodic programs, all on bank 0. Kit names that are not in the bank are
 * not offered: on that bank, GM2 kit programs are the same Standard kit, or silent.
 */
public final class SoundCatalog {
    /** One melodic program. {@code bank} is MSB×128+LSB, matching {@link Track#bank()}. */
    public record Voice(int bank, int program, String name) {}

    /** One percussion kit. {@code program} is the kit number stored on a drum row. */
    public record Kit(int program, String name) {}

    private SoundCatalog() {}

    /** The synthesizer's built-in bank, or null when MIDI cannot be opened. */
    public static Soundbank builtin() {
        try {
            Synthesizer synthesizer = MidiSystem.getSynthesizer();
            return synthesizer.getDefaultSoundbank();
        } catch (MidiUnavailableException exception) {
            return null;
        }
    }

    /**
     * Melodic instruments, bank then program. A null bank is the General MIDI names
     * at bank 0, which is what an external output is assumed to have.
     * Percussion patches are left out, including the kit that shares bank 0 program 0
     * with acoustic piano.
     */
    public static List<Voice> melodic(Soundbank bank) {
        if (bank == null) {
            List<Voice> general = new ArrayList<>();
            for (int program = 0; program < 128; program++) {
                general.add(new Voice(0, program, Gm.programName(program)));
            }
            return general;
        }
        List<Voice> voices = new ArrayList<>();
        for (Instrument instrument : bank.getInstruments()) {
            Patch patch = instrument.getPatch();
            if (patch == null || percussion(patch)) {
                continue;
            }
            voices.add(new Voice(patch.getBank(), patch.getProgram(), clean(instrument.getName())));
        }
        voices.sort(Comparator.comparingInt(Voice::bank).thenComparingInt(Voice::program).thenComparing(Voice::name));
        return voices;
    }

    /**
     * Percussion kits that are actually in {@code bank}. A null bank offers Standard only.
     * An empty percussion list also falls back to Standard, so the combo is never blank.
     */
    public static List<Kit> kits(Soundbank bank) {
        List<Kit> kits = new ArrayList<>();
        if (bank != null) {
            for (Instrument instrument : bank.getInstruments()) {
                Patch patch = instrument.getPatch();
                if (patch == null || !percussion(patch)) {
                    continue;
                }
                kits.add(new Kit(patch.getProgram(), clean(instrument.getName())));
            }
            kits.sort(Comparator.comparingInt(Kit::program).thenComparing(Kit::name));
        }
        if (kits.isEmpty()) {
            kits.add(new Kit(0, "Standard"));
        }
        return kits;
    }

    /** {@code 0:0  Piano}, or {@code 1:2:33  Fingered Bass} when the LSB is not zero. */
    public static String voiceLabel(Voice voice) {
        int msb = Math.floorDiv(voice.bank(), 128);
        int lsb = Math.floorMod(voice.bank(), 128);
        String numbers = lsb == 0
            ? msb + ":" + voice.program()
            : msb + ":" + lsb + ":" + voice.program();
        return numbers + "  " + voice.name();
    }

    public static String kitLabel(Kit kit) {
        return kit.program() + "  " + kit.name();
    }

    /** True when {@code program} is a percussion kit actually in {@code bank}. A null bank has Standard only. */
    public static boolean hasKit(Soundbank bank, int program) {
        for (Kit kit : kits(bank)) {
            if (kit.program() == program) {
                return true;
            }
        }
        return false;
    }

    /**
     * Kit program to send. A null bank keeps the requested kit, which is what a MIDI file
     * and an external output do. A loaded bank that lacks the kit plays Standard.
     */
    public static int playedKit(Soundbank bank, int kit) {
        int program = Math.max(0, Math.min(127, kit));
        if (program == 0 || bank == null || hasKit(bank, program)) {
            return program;
        }
        return 0;
    }

    /**
     * Warning when a drum row's kit is not in {@code bank}, or null when the kit will play.
     * A null bank keeps the stored kit (a MIDI file, or an external output), so there is no warning.
     * A pitched preset does not use the kit.
     */
    public static String kitFallback(Soundbank bank, Track track) {
        if (bank == null || track == null || track.mode() != TrackMode.DRUM || track.preset() != null || track.kit() == 0) {
            return null;
        }
        if (hasKit(bank, track.kit())) {
            return null;
        }
        return "kit " + track.kit() + " is not in this bank, plays Standard";
    }

    private static String clean(String name) {
        if (name == null || name.isBlank()) {
            return "Instrument";
        }
        return name.trim();
    }

    /**
     * {@code ModelPatch.isPercussion()} is public, but the package is not exported
     * unless the app is launched with {@code --add-exports}. A failure treats the
     * patch as melodic, which is the safe side for the program list.
     */
    private static boolean percussion(Patch patch) {
        try {
            Method method = patch.getClass().getMethod("isPercussion");
            if (method.getReturnType() != boolean.class) {
                return false;
            }
            return Boolean.TRUE.equals(method.invoke(patch));
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return false;
        }
    }
}
