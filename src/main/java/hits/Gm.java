package hits;

import java.util.LinkedHashMap;
import java.util.Map;

/** General MIDI program names, matching the list shipped with the original app, and drum labels. */
public final class Gm {
    public static final int DRUM_CHANNEL = 9;

    private static final String[] PROGRAMS = {
        "Piano", "Bright Piano", "Electric Grand", "Honky Tonk Piano",
        "Electric Piano 1", "Electric Piano 2", "Harpsichord", "Clavinet",
        "Celesta", "Glockenspiel", "Music Box", "Vibraphone",
        "Marimba", "Xylophone", "Tubular Bell", "Dulcimer",
        "Hammond Organ", "Perc Organ", "Rock Organ", "Church Organ",
        "Reed Organ", "Accordion", "Harmonica", "Tango Accordion",
        "Nylon Str Guitar", "Steel String Guitar", "Jazz Electric Gtr", "Clean Guitar",
        "Muted Guitar", "Overdrive Guitar", "Distortion Guitar", "Guitar Harmonics",
        "Acoustic Bass", "Fingered Bass", "Picked Bass", "Fretless Bass",
        "Slap Bass 1", "Slap Bass 2", "Syn Bass 1", "Syn Bass 2",
        "Violin", "Viola", "Cello", "Contrabass",
        "Tremolo Strings", "Pizzicato Strings", "Orchestral Harp", "Timpani",
        "Ensemble Strings", "Slow Strings", "Synth Strings 1", "Synth Strings 2",
        "Choir Aahs", "Voice Oohs", "Syn Choir", "Orchestra Hit",
        "Trumpet", "Trombone", "Tuba", "Muted Trumpet",
        "French Horn", "Brass Ensemble", "Syn Brass 1", "Syn Brass 2",
        "Soprano Sax", "Alto Sax", "Tenor Sax", "Baritone Sax",
        "Oboe", "English Horn", "Bassoon", "Clarinet",
        "Piccolo", "Flute", "Recorder", "Pan Flute",
        "Bottle Blow", "Shakuhachi", "Whistle", "Ocarina",
        "Syn Square Wave", "Syn Saw Wave", "Syn Calliope", "Syn Chiff",
        "Syn Charang", "Syn Voice", "Syn Fifths Saw", "Syn Brass and Lead",
        "Fantasia", "Warm Pad", "Polysynth", "Space Vox",
        "Bowed Glass", "Metal Pad", "Halo Pad", "Sweep Pad",
        "Ice Rain", "Soundtrack", "Crystal", "Atmosphere",
        "Brightness", "Goblins", "Echo Drops", "Sci Fi",
        "Sitar", "Banjo", "Shamisen", "Koto",
        "Kalimba", "Bag Pipe", "Fiddle", "Shanai",
        "Tinkle Bell", "Agogo", "Steel Drums", "Woodblock",
        "Taiko Drum", "Melodic Tom", "Syn Drum", "Reverse Cymbal",
        "Guitar Fret Noise", "Breath Noise", "Seashore", "Bird",
        "Telephone", "Helicopter", "Applause", "Gunshot"
    };

    /** Common kit notes, in the order shown in the inspector. */
    public static final int[] DRUM_NOTES = {
        36, 38, 37, 39, 42, 46, 45, 47, 48, 50, 49, 51, 54, 56
    };

    private static final Map<Integer, String> DRUMS = new LinkedHashMap<>();

    static {
        DRUMS.put(35, "Kick");
        DRUMS.put(36, "Kick");
        DRUMS.put(37, "Side Stick");
        DRUMS.put(38, "Snare");
        DRUMS.put(39, "Clap");
        DRUMS.put(40, "Snare");
        DRUMS.put(41, "Tom");
        DRUMS.put(42, "Hat C");
        DRUMS.put(43, "Tom");
        DRUMS.put(44, "Hat C");
        DRUMS.put(45, "Tom");
        DRUMS.put(46, "Hat O");
        DRUMS.put(47, "Tom");
        DRUMS.put(48, "Tom");
        DRUMS.put(49, "Crash");
        DRUMS.put(50, "Tom");
        DRUMS.put(51, "Ride");
        DRUMS.put(54, "Tambourine");
        DRUMS.put(56, "Cowbell");
        DRUMS.put(57, "Crash");
        DRUMS.put(59, "Ride");
    }

    private Gm() {}

    public static String programName(int program) {
        if (program < 0 || program >= PROGRAMS.length) {
            return "Program " + program;
        }
        return PROGRAMS[program];
    }

    public static String drumName(int note) {
        String name = DRUMS.get(note);
        return name == null ? Notes.name(note) : name;
    }

    public static String drumChoice(int note) {
        String specific = switch (note) {
            case 36 -> "Kick";
            case 38 -> "Snare";
            case 37 -> "Side Stick";
            case 39 -> "Clap";
            case 42 -> "Closed Hat";
            case 46 -> "Open Hat";
            case 45 -> "Low Tom";
            case 47 -> "Mid Tom";
            case 48 -> "High Tom";
            case 50 -> "High Tom 2";
            case 49 -> "Crash";
            case 51 -> "Ride";
            case 54 -> "Tambourine";
            case 56 -> "Cowbell";
            default -> null;
        };
        if (specific != null) {
            return specific + "  " + Notes.name(note);
        }
        return Notes.name(note);
    }
}
