package hits;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Versioned beat document.
 * Version 1 is the original document. Version 2 adds optional fields: {@code pitch} on a step,
 * {@code probability} on a step (0–100, omitted when it is 100), a {@code chain} of pattern entries,
 * a track {@code pan} or mixer {@code level}, a beat {@code master}, pattern slots {@code c} through {@code h},
 * a per-pattern {@code steps} count, a step {@code len} (note length in steps, omitted at 1),
 * a track {@code legato} flag (omitted when false), a step {@code nudge} (timing percent, omitted at 0),
 * a track {@code shift} (omitted at 0), a track {@code tune} (cents, omitted at 0),
 * a track {@code decay} (percent, omitted at 100), and track {@code reverb}, {@code chorus},
 * {@code cutoff}, and {@code resonance} (each omitted when unset, so no controller is sent),
 * a track {@code lowCut} object ({@code hz}, {@code slope}) when the high-pass is on,
 * a track {@code presence} (dB, omitted at 0),
 * and a track {@code sample} object when {@code mode} is {@code sample}
 * ({@code path}, {@code root}, {@code start}, {@code oneShot},
 * optional {@code gain} in decibels omitted at {@link SampleRef#GAIN},
 * and optional {@code invert} omitted when false).
 * Mute and solo are original track fields.
 * Slots C–H are omitted unless that pattern differs from the empty kit or its length differs from the root.
 * A slot {@code steps} key is written only when that pattern's length differs from the root {@code steps},
 * which stays 16 or 32. A beat that uses none of the version 2 fields is still written as version 1.
 * Keys this version does not know are ignored. An older Hits drops the new keys the next time it saves.
 */
public final class BeatJson {
    private BeatJson() {}

    /** 1 unless a version 2 field is in use. See {@link Beat#usesFormatV2()}. */
    public static int documentVersion(Beat beat) {
        return beat.usesFormatV2() ? Beat.FORMAT_V2 : Beat.VERSION;
    }

    public static String write(Beat beat) {
        StringBuilder out = new StringBuilder();
        out.append("{\n");
        field(out, 1, "version", documentVersion(beat), true);
        field(out, 1, "name", beat.name(), true);
        field(out, 1, "bpm", beat.bpm(), true);
        field(out, 1, "swing", beat.swing(), true);
        field(out, 1, "steps", beat.stepCount(), true);
        field(out, 1, "active", String.valueOf(beat.activeSlot()), true);
        if (beat.master() != Beat.MASTER) {
            field(out, 1, "master", beat.master(), true);
        }
        List<Character> extra = beat.extraSlots();
        out.append("  \"slots\": {\n");
        writeSlot(out, beat, 'a', true);
        writeSlot(out, beat, 'b', !extra.isEmpty());
        for (int i = 0; i < extra.size(); i++) {
            char slot = extra.get(i);
            writeSlot(out, beat, slot, i + 1 < extra.size());
        }
        out.append("  }");
        if (beat.hasChain()) {
            out.append(",\n");
            writeChain(out, beat.chain());
        } else {
            out.append("\n");
        }
        out.append("}\n");
        return out.toString();
    }

    public static Beat read(String json) {
        Object parsed = new Parser(json).parse();
        Map<String, Object> root = asMap(parsed);
        int version = number(root.get("version"));
        if (version < Beat.VERSION || version > Beat.FORMAT_V2) {
            throw new IllegalArgumentException("Unsupported beat version " + version);
        }
        Beat beat = Beat.drumKit(text(root.get("name")));
        beat.setBpm(number(root.get("bpm")));
        beat.setSwing(number(root.get("swing")));
        beat.setStepCount(number(root.get("steps")));
        beat.setActiveSlot(Beat.parseSlot(text(root.get("active"))));
        if (root.containsKey("master") && root.get("master") != null) {
            beat.setMaster(number(root.get("master")));
        }
        Map<String, Object> slots = requireObject(root, "slots");
        readSlot(beat, 'a', requireObject(slots, "a"));
        readSlot(beat, 'b', requireObject(slots, "b"));
        for (char slot = 'c'; slot <= 'h'; slot++) {
            Object value = slots.get(String.valueOf(slot));
            if (value != null) {
                readSlot(beat, slot, asMap(value));
            }
        }
        if (root.containsKey("chain") && root.get("chain") != null) {
            beat.setChain(readChain(root.get("chain")));
        }
        return beat;
    }

    private static void writeChain(StringBuilder out, List<Chain.Part> chain) {
        out.append("  \"chain\": [\n");
        for (int i = 0; i < chain.size(); i++) {
            Chain.Part part = chain.get(i);
            out.append("    {\"slot\": \"").append(part.slot()).append("\", \"repeats\": ").append(part.repeats()).append("}");
            out.append(i + 1 < chain.size() ? ",\n" : "\n");
        }
        out.append("  ]\n");
    }

    private static List<Chain.Part> readChain(Object value) {
        List<?> list = asList(value);
        List<Chain.Part> parts = new ArrayList<>();
        int count = Math.min(Chain.MAX_PARTS, list.size());
        for (int i = 0; i < count; i++) {
            Map<String, Object> part = asMap(list.get(i));
            char slot = Beat.parseSlot(text(part.get("slot")));
            parts.add(new Chain.Part(slot, number(part.get("repeats"))));
        }
        return parts;
    }

    private static void writeSlot(StringBuilder out, Beat beat, char slot, boolean comma) {
        Track[] tracks = beat.slot(slot);
        int steps = beat.stepCount(slot);
        out.append("    \"").append(slot).append("\": {\n");
        if (steps != beat.stepCount()) {
            field(out, 3, "steps", steps, true);
        }
        out.append("      \"tracks\": [\n");
        for (int i = 0; i < tracks.length; i++) {
            writeTrack(out, tracks[i], i < tracks.length - 1);
        }
        out.append("      ]\n");
        out.append("    }");
        out.append(comma ? ",\n" : "\n");
    }

    private static void writeTrack(StringBuilder out, Track track, boolean comma) {
        out.append("        {\n");
        field(out, 5, "name", track.name(), true);
        field(out, 5, "mode", track.mode().json(), true);
        field(out, 5, "program", track.program(), true);
        field(out, 5, "note", track.note(), true);
        field(out, 5, "mute", track.mute(), true);
        field(out, 5, "solo", track.solo(), true);
        field(out, 5, "gate", track.gate(), true);
        field(out, 5, "velocity", track.velocity(), true);
        field(out, 5, "accent", track.accent(), true);
        if (track.pan() != Track.PAN_CENTER) {
            field(out, 5, "pan", track.pan(), true);
        }
        if (track.level() != Track.LEVEL) {
            field(out, 5, "level", track.level(), true);
        }
        if (track.legato()) {
            field(out, 5, "legato", true, true);
        }
        if (track.shift() != 0) {
            field(out, 5, "shift", track.shift(), true);
        }
        if (track.bank() != 0) {
            field(out, 5, "bank", track.bank(), true);
        }
        if (track.kit() != 0) {
            field(out, 5, "kit", track.kit(), true);
        }
        if (track.preset() != null) {
            field(out, 5, "preset", track.preset().json(), true);
        }
        if (track.tune() != 0) {
            field(out, 5, "tune", track.tune(), true);
        }
        if (track.decay() != Track.DECAY) {
            field(out, 5, "decay", track.decay(), true);
        }
        if (track.hasReverb()) {
            field(out, 5, "reverb", track.reverb(), true);
        }
        if (track.hasChorus()) {
            field(out, 5, "chorus", track.chorus(), true);
        }
        if (track.hasCutoff()) {
            field(out, 5, "cutoff", track.cutoff(), true);
        }
        if (track.hasResonance()) {
            field(out, 5, "resonance", track.resonance(), true);
        }
        if (track.hasLowCut()) {
            writeLowCut(out, track);
        }
        if (track.hasPresence()) {
            field(out, 5, "presence", track.presence(), true);
        }
        if (track.mode() == TrackMode.SAMPLE && track.sample() != null) {
            writeSample(out, track.sample());
        }
        out.append("          \"steps\": [");
        for (int i = 0; i < Track.CAPACITY; i++) {
            if (i > 0) {
                out.append(", ");
            }
            Step step = track.step(i);
            out.append("{\"on\":").append(step.on()).append(",\"velocity\":").append(step.velocity());
            if (step.hasPitch()) {
                out.append(",\"pitch\":").append(step.pitch());
            }
            if (step.probability() != 100) {
                out.append(",\"probability\":").append(step.probability());
            }
            if (step.length() != Step.MIN_LENGTH) {
                out.append(",\"len\":").append(step.length());
            }
            if (step.nudge() != 0) {
                out.append(",\"nudge\":").append(step.nudge());
            }
            out.append("}");
        }
        out.append("]\n");
        out.append("        }");
        out.append(comma ? ",\n" : "\n");
    }

    private static void writeLowCut(StringBuilder out, Track track) {
        indent(out, 5);
        out.append("\"lowCut\": {\"hz\": ").append(track.lowCutHz())
            .append(", \"slope\": ").append(track.lowCutSlope())
            .append("},\n");
    }

    private static void writeSample(StringBuilder out, SampleRef sample) {
        indent(out, 5);
        out.append("\"sample\": {\"path\": \"").append(escape(sample.path()))
            .append("\", \"root\": ").append(sample.root())
            .append(", \"start\": ").append(sample.start())
            .append(", \"oneShot\": ").append(sample.oneShot());
        if (sample.gain() != SampleRef.GAIN) {
            out.append(", \"gain\": ").append(sample.gain());
        }
        if (sample.invert()) {
            out.append(", \"invert\": true");
        }
        out.append("},\n");
    }

    private static void field(StringBuilder out, int indent, String key, String value, boolean comma) {
        indent(out, indent);
        out.append("\"").append(key).append("\": \"").append(escape(value)).append("\"");
        out.append(comma ? ",\n" : "\n");
    }

    private static void field(StringBuilder out, int indent, String key, int value, boolean comma) {
        indent(out, indent);
        out.append("\"").append(key).append("\": ").append(value);
        out.append(comma ? ",\n" : "\n");
    }

    private static void field(StringBuilder out, int indent, String key, boolean value, boolean comma) {
        indent(out, indent);
        out.append("\"").append(key).append("\": ").append(value);
        out.append(comma ? ",\n" : "\n");
    }

    private static void indent(StringBuilder out, int n) {
        out.append("  ".repeat(n));
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static void readSlot(Beat beat, char slot, Map<String, Object> object) {
        if (object.containsKey("steps") && object.get("steps") != null) {
            beat.setSlotSteps(slot, number(object.get("steps")));
        }
        List<?> tracks = asList(object.get("tracks"));
        if (tracks.size() != Beat.TRACKS) {
            throw new IllegalArgumentException("A slot needs " + Beat.TRACKS + " tracks");
        }
        Track[] parsed = new Track[Beat.TRACKS];
        for (int i = 0; i < Beat.TRACKS; i++) {
            parsed[i] = readTrack(beat, slot, asMap(tracks.get(i)));
        }
        beat.replaceSlot(slot, parsed);
    }

    private static Track readTrack(Beat beat, char slot, Map<String, Object> object) {
        Track track = new Track(
            text(object.get("name")),
            TrackMode.parse(text(object.get("mode"))),
            number(object.get("program")),
            number(object.get("note"))
        );
        track.setMute(bool(object.get("mute")));
        track.setSolo(bool(object.get("solo")));
        if (object.containsKey("pan") && object.get("pan") != null) {
            track.setPan(number(object.get("pan")));
        }
        if (object.containsKey("level") && object.get("level") != null) {
            track.setLevel(number(object.get("level")));
        }
        track.setGate(number(object.get("gate")));
        track.setVelocity(number(object.get("velocity")));
        track.setAccent(number(object.get("accent")));
        if (object.containsKey("legato") && object.get("legato") != null) {
            track.setLegato(bool(object.get("legato")));
        }
        if (object.containsKey("shift") && object.get("shift") != null) {
            track.setShift(number(object.get("shift")));
        }
        if (object.containsKey("bank") && object.get("bank") != null) {
            track.setBank(number(object.get("bank")));
        }
        if (object.containsKey("kit") && object.get("kit") != null) {
            track.setKit(number(object.get("kit")));
        }
        if (object.get("preset") instanceof String preset) {
            track.setPreset(PitchedPreset.parse(preset));
        }
        if (object.containsKey("tune") && object.get("tune") != null) {
            track.setTune(number(object.get("tune")));
        }
        if (object.containsKey("decay") && object.get("decay") != null) {
            track.setDecay(number(object.get("decay")));
        }
        if (object.containsKey("reverb") && object.get("reverb") != null) {
            track.setReverb(number(object.get("reverb")));
        }
        if (object.containsKey("chorus") && object.get("chorus") != null) {
            track.setChorus(number(object.get("chorus")));
        }
        if (object.containsKey("cutoff") && object.get("cutoff") != null) {
            track.setCutoff(number(object.get("cutoff")));
        }
        if (object.containsKey("resonance") && object.get("resonance") != null) {
            track.setResonance(number(object.get("resonance")));
        }
        if (object.get("lowCut") instanceof Map<?, ?> rawCut) {
            Map<String, Object> cut = asMap(rawCut);
            int hz = cut.get("hz") == null ? 0 : number(cut.get("hz"));
            int slope = cut.get("slope") == null ? Track.LOW_CUT_SLOPE : number(cut.get("slope"));
            if (hz > 0) {
                track.setLowCut(hz, slope);
            }
        }
        if (object.containsKey("presence") && object.get("presence") != null) {
            track.setPresence(number(object.get("presence")));
        }
        if (object.get("sample") instanceof Map<?, ?> raw) {
            Map<String, Object> sample = asMap(raw);
            track.setSample(readSample(sample));
            warnGain(beat, slot, track, sample);
        } else if (track.mode() == TrackMode.SAMPLE) {
            track.setSample(new SampleRef("", SampleRef.DEFAULT_ROOT, 0, true));
        }
        List<?> steps = asList(object.get("steps"));
        int count = Math.min(Track.CAPACITY, steps.size());
        for (int i = 0; i < count; i++) {
            Map<String, Object> step = asMap(steps.get(i));
            track.step(i).setOn(bool(step.get("on")));
            track.step(i).setVelocity(number(step.get("velocity")));
            if (step.containsKey("pitch") && step.get("pitch") != null) {
                track.step(i).setPitch(number(step.get("pitch")));
            }
            if (step.containsKey("probability") && step.get("probability") != null) {
                track.step(i).setProbability(number(step.get("probability")));
            }
            if (step.containsKey("len") && step.get("len") != null) {
                track.step(i).setLength(number(step.get("len")));
            }
            if (step.containsKey("nudge") && step.get("nudge") != null) {
                track.step(i).setNudge(number(step.get("nudge")));
            }
        }
        return track;
    }

    private static void warnGain(Beat beat, char slot, Track track, Map<String, Object> sample) {
        if (!sample.containsKey("gain") || sample.get("gain") == null || track.sample() == null) {
            return;
        }
        int requested = number(sample.get("gain"));
        int stored = track.sample().gain();
        if (requested == stored) {
            return;
        }
        beat.addLoadWarning(
            Beat.slotLabel(slot) + " " + track.name()
                + ": sample gain " + requested + " dB was clamped to " + stored + " dB."
        );
    }

    private static SampleRef readSample(Map<String, Object> object) {
        String path = object.get("path") instanceof String text ? text : "";
        int root = object.containsKey("root") && object.get("root") != null
            ? number(object.get("root"))
            : SampleRef.DEFAULT_ROOT;
        int start = object.containsKey("start") && object.get("start") != null
            ? number(object.get("start"))
            : 0;
        boolean oneShot = !object.containsKey("oneShot") || object.get("oneShot") == null || bool(object.get("oneShot"));
        int gain = object.containsKey("gain") && object.get("gain") != null
            ? number(object.get("gain"))
            : SampleRef.GAIN;
        boolean invert = object.containsKey("invert") && object.get("invert") != null && bool(object.get("invert"));
        return new SampleRef(path, root, start, oneShot, gain, invert);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IllegalArgumentException("Expected an object");
    }

    /** A required object field. The message names the missing key. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> requireObject(Map<String, Object> parent, String key) {
        if (!parent.containsKey(key) || parent.get(key) == null) {
            throw new IllegalArgumentException("Beat file is missing \"" + key + "\"");
        }
        Object value = parent.get(key);
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("\"" + key + "\" should be an object");
        }
        return (Map<String, Object>) map;
    }

    private static List<?> asList(Object value) {
        if (value instanceof List<?> list) {
            return list;
        }
        throw new IllegalArgumentException("Expected a list");
    }

    private static String text(Object value) {
        if (value instanceof String string) {
            return string;
        }
        throw new IllegalArgumentException("Expected text");
    }

    private static int number(Object value) {
        if (value instanceof Integer integer) {
            return integer;
        }
        throw new IllegalArgumentException("Expected a number");
    }

    private static boolean bool(Object value) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        throw new IllegalArgumentException("Expected true or false");
    }

    private static final class Parser {
        private final String source;
        private int index;

        Parser(String source) {
            this.source = source;
        }

        Object parse() {
            Object value = parseValue();
            skip();
            if (index != source.length()) {
                throw new IllegalArgumentException("Unexpected trailing data in beat file");
            }
            return value;
        }

        private Object parseValue() {
            skip();
            if (index >= source.length()) {
                throw new IllegalArgumentException("Beat file ended early");
            }
            char c = source.charAt(index);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> parseLiteral("true", Boolean.TRUE);
                case 'f' -> parseLiteral("false", Boolean.FALSE);
                case 'n' -> parseLiteral("null", null);
                default -> parseNumber();
            };
        }

        private Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> object = new LinkedHashMap<>();
            skip();
            if (peek() == '}') {
                index++;
                return object;
            }
            while (true) {
                String key = parseString();
                expect(':');
                object.put(key, parseValue());
                char next = next("Expected ',' or '}'");
                if (next == '}') {
                    return object;
                }
                if (next != ',') {
                    throw new IllegalArgumentException("Expected ',' or '}'");
                }
            }
        }

        private List<Object> parseArray() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skip();
            if (peek() == ']') {
                index++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                char next = next("Expected ',' or ']'");
                if (next == ']') {
                    return list;
                }
                if (next != ',') {
                    throw new IllegalArgumentException("Expected ',' or ']'");
                }
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (index < source.length()) {
                char c = source.charAt(index++);
                if (c == '"') {
                    return out.toString();
                }
                if (c == '\\') {
                    if (index >= source.length()) {
                        throw new IllegalArgumentException("Bad escape in beat file");
                    }
                    char escaped = source.charAt(index++);
                    switch (escaped) {
                        case '"', '\\', '/' -> out.append(escaped);
                        case 'n' -> out.append('\n');
                        case 'r' -> out.append('\r');
                        case 't' -> out.append('\t');
                        default -> out.append(escaped);
                    }
                } else {
                    out.append(c);
                }
            }
            throw new IllegalArgumentException("Unclosed string in beat file");
        }

        private Object parseLiteral(String literal, Object value) {
            if (!source.startsWith(literal, index)) {
                throw new IllegalArgumentException("Expected " + literal);
            }
            index += literal.length();
            return value;
        }

        private Integer parseNumber() {
            int start = index;
            if (peek() == '-') {
                index++;
            }
            if (index >= source.length() || !Character.isDigit(source.charAt(index))) {
                throw new IllegalArgumentException("Expected a number");
            }
            while (index < source.length() && (Character.isDigit(source.charAt(index)) || source.charAt(index) == '.')) {
                index++;
            }
            String number = source.substring(start, index);
            if (number.contains(".")) {
                return (int) Math.round(Double.parseDouble(number));
            }
            return Integer.parseInt(number);
        }

        private void expect(char wanted) {
            char found = next("Expected '" + wanted + "'");
            if (found != wanted) {
                throw new IllegalArgumentException("Expected '" + wanted + "'");
            }
        }

        private char next(String error) {
            skip();
            if (index >= source.length()) {
                throw new IllegalArgumentException(error);
            }
            return source.charAt(index++);
        }

        private char peek() {
            skip();
            if (index >= source.length()) {
                throw new IllegalArgumentException("Beat file ended early");
            }
            return source.charAt(index);
        }

        private void skip() {
            while (index < source.length() && Character.isWhitespace(source.charAt(index))) {
                index++;
            }
        }
    }
}
