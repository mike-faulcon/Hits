package hits;

import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Sequencer;
import javax.sound.midi.Synthesizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * MIDI outputs the user can send notes to. The built-in Java synth is always listed
 * and is the fallback when a saved device is unplugged.
 */
public final class MidiOutputs {
    public static final String PREF_OUTPUT = "midiOutput";
    public static final String BUILTIN_ID = "builtin";
    public static final String BUILTIN_LABEL = "Built-in synth";

    private MidiOutputs() {}

    public record Choice(String id, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    /** Built-in synth first, then hardware, IAC, and other ports that can receive notes. */
    public static List<Choice> list() {
        List<Choice> choices = new ArrayList<>();
        choices.add(new Choice(BUILTIN_ID, BUILTIN_LABEL));
        for (MidiDevice.Info info : MidiSystem.getMidiDeviceInfo()) {
            try {
                MidiDevice device = MidiSystem.getMidiDevice(info);
                if (!isExternalOutput(device)) {
                    continue;
                }
                choices.add(new Choice(idFor(info), labelFor(info.getName(), info.getVendor())));
            } catch (MidiUnavailableException ignored) {
                // A device that cannot be queried is not a choice.
            }
        }
        return choices;
    }

    /**
     * External ports only. The Java synth is the built-in row, and the sequencer is not an output.
     */
    public static boolean isExternalOutput(MidiDevice device) {
        if (device instanceof Synthesizer || device instanceof Sequencer) {
            return false;
        }
        return device.getMaxReceivers() != 0;
    }

    public static MidiDevice.Info find(String id) {
        if (id == null || BUILTIN_ID.equals(id)) {
            return null;
        }
        for (MidiDevice.Info info : MidiSystem.getMidiDeviceInfo()) {
            if (idFor(info).equals(id)) {
                return info;
            }
        }
        return null;
    }

    public static String idFor(MidiDevice.Info info) {
        return idFor(info.getName(), info.getVendor(), info.getDescription());
    }

    /** Stable preference value. {@code |} inside a name is escaped so the id round-trips. */
    public static String idFor(String name, String vendor, String description) {
        return encode(name) + "|" + encode(vendor) + "|" + encode(description);
    }

    public static String labelFor(String name, String vendor) {
        String shown = text(name);
        if (shown.isEmpty()) {
            shown = "MIDI output";
        }
        String who = text(vendor);
        if (who.isEmpty() || shown.toLowerCase(Locale.ROOT).contains(who.toLowerCase(Locale.ROOT))) {
            return shown;
        }
        return shown + " (" + who + ")";
    }

    public static String labelFor(MidiDevice.Info info) {
        return labelFor(info.getName(), info.getVendor());
    }

    /** Saved id when it is still connected, otherwise the built-in synth. */
    public static String resolve(String saved, List<String> availableIds) {
        if (saved != null && availableIds.contains(saved)) {
            return saved;
        }
        return BUILTIN_ID;
    }

    private static String encode(String value) {
        return text(value).replace("%", "%25").replace("|", "%7C");
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
