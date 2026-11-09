package hits.ui;

/**
 * Turns a message a beginner would not understand into one they can act on.
 * The exception text itself stays as it is, so the command line and the tests
 * still see the original sentence. Dialogs and the status line use this.
 */
final class PlainErrors {
    private PlainErrors() {}

    static String explain(Throwable exception) {
        if (exception == null) {
            return "Something went wrong. Try that again.";
        }
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "Something went wrong. Try that again.";
        }
        return explain(message);
    }

    static String explain(String message) {
        if (message == null || message.isBlank()) {
            return "Something went wrong. Try that again.";
        }
        String text = message.trim();
        if (text.startsWith("Unsupported beat version")) {
            return "This beat was saved by a newer Hits, so this version cannot open it.";
        }
        if (text.equals("Expected an object")
            || text.equals("Expected a list")
            || text.equals("Expected text")
            || text.equals("Expected a number")
            || text.equals("Expected true or false")
            || text.startsWith("Expected ','")
            || text.startsWith("Expected '")
            || text.equals("Unexpected trailing data in beat file")
            || text.equals("Beat file ended early")
            || text.contains("should be an object")) {
            return "This beat file is damaged and could not be read.";
        }
        if (text.startsWith("Beat file is missing")) {
            return "This beat file is incomplete and could not be read.";
        }
        if (text.equals("Bad escape in beat file") || text.equals("Unclosed string in beat file")) {
            return "A name in this beat file is broken, so it could not be read.";
        }
        if (text.startsWith("Beat file is too short")) {
            return "This file is too small to be a beat.";
        }
        if (text.equals("WAV export expects a tick-based MIDI sequence.")) {
            return "Hits could not make a WAV from this beat.";
        }
        if (text.equals("frame count")) {
            return "That recording is empty.";
        }
        if (text.equals("sequence")) {
            return "Hits could not read the notes for this export.";
        }
        if (text.equals("The built-in synth did not load this SoundFont.")
            || text.equals(hits.SoundFonts.UNSUPPORTED)) {
            return "Hits could not use this SoundFont. Choose a .sf2 file the built-in synth can play.";
        }
        if (text.startsWith("A slot needs ") && text.contains("tracks")) {
            return "A pattern needs " + text.substring("A slot needs ".length()).replace("tracks", "rows");
        }
        if (text.startsWith("Pattern ") && text.contains("track")) {
            return text.replace(" tracks", " rows").replace(" track", " row");
        }
        return text;
    }
}
