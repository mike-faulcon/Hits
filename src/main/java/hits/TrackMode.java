package hits;

public enum TrackMode {
    DRUM,
    NOTE;

    public static TrackMode parse(String value) {
        if (value != null && value.equalsIgnoreCase("note")) {
            return NOTE;
        }
        return DRUM;
    }

    public String json() {
        return this == NOTE ? "note" : "drum";
    }
}
