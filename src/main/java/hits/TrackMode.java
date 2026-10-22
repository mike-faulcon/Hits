package hits;

public enum TrackMode {
    DRUM,
    NOTE,
    SAMPLE;

    /**
     * {@code note} and {@code sample} are the named modes. Anything else, including a mode
     * this version does not know, is a drum. An older Hits only recognized {@code note},
     * so a {@code sample} track opened there is a drum too.
     */
    public static TrackMode parse(String value) {
        if (value == null) {
            return DRUM;
        }
        if (value.equalsIgnoreCase("note")) {
            return NOTE;
        }
        if (value.equalsIgnoreCase("sample")) {
            return SAMPLE;
        }
        return DRUM;
    }

    public String json() {
        return switch (this) {
            case NOTE -> "note";
            case SAMPLE -> "sample";
            case DRUM -> "drum";
        };
    }
}
