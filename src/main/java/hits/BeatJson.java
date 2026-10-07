package hits;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Versioned beat document.
 * Version 1 is the original document. Version 2 adds an optional {@code pitch} on a step.
 * A beat that does not use that field is still written as version 1.
 */
public final class BeatJson {
    private BeatJson() {}

    /** 1 unless a step has its own pitch, in which case 2. */
    public static int documentVersion(Beat beat) {
        return beat.hasStepPitch() ? Beat.FORMAT_V2 : Beat.VERSION;
    }

    public static String write(Beat beat) {
        StringBuilder out = new StringBuilder();
        out.append("{\n");
        field(out, 1, "version", documentVersion(beat), true);
        field(out, 1, "name", beat.name(), true);
        field(out, 1, "bpm", beat.bpm(), true);
        field(out, 1, "swing", beat.swing(), true);
        field(out, 1, "steps", beat.stepCount(), true);
        field(out, 1, "active", beat.activeSlot() == 'b' ? "b" : "a", true);
        out.append("  \"slots\": {\n");
        writeSlot(out, "a", beat.slot('a'), true);
        writeSlot(out, "b", beat.slot('b'), false);
        out.append("  }\n");
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
        beat.setActiveSlot(text(root.get("active")).equals("b") ? 'b' : 'a');
        Map<String, Object> slots = asMap(root.get("slots"));
        readSlot(beat, 'a', asMap(slots.get("a")));
        readSlot(beat, 'b', asMap(slots.get("b")));
        return beat;
    }

    private static void writeSlot(StringBuilder out, String name, Track[] tracks, boolean comma) {
        out.append("    \"").append(name).append("\": {\n");
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
            out.append("}");
        }
        out.append("]\n");
        out.append("        }");
        out.append(comma ? ",\n" : "\n");
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
        List<?> tracks = asList(object.get("tracks"));
        if (tracks.size() != Beat.TRACKS) {
            throw new IllegalArgumentException("A slot needs " + Beat.TRACKS + " tracks");
        }
        Track[] parsed = new Track[Beat.TRACKS];
        for (int i = 0; i < Beat.TRACKS; i++) {
            parsed[i] = readTrack(asMap(tracks.get(i)));
        }
        beat.replaceSlot(slot, parsed);
    }

    private static Track readTrack(Map<String, Object> object) {
        Track track = new Track(
            text(object.get("name")),
            TrackMode.parse(text(object.get("mode"))),
            number(object.get("program")),
            number(object.get("note"))
        );
        track.setMute(bool(object.get("mute")));
        track.setSolo(bool(object.get("solo")));
        track.setGate(number(object.get("gate")));
        track.setVelocity(number(object.get("velocity")));
        track.setAccent(number(object.get("accent")));
        List<?> steps = asList(object.get("steps"));
        int count = Math.min(Track.CAPACITY, steps.size());
        for (int i = 0; i < count; i++) {
            Map<String, Object> step = asMap(steps.get(i));
            track.step(i).setOn(bool(step.get("on")));
            track.step(i).setVelocity(number(step.get("velocity")));
            if (step.containsKey("pitch") && step.get("pitch") != null) {
                track.step(i).setPitch(number(step.get("pitch")));
            }
        }
        return track;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IllegalArgumentException("Expected an object");
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
