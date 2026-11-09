package hits.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Dialog text for messages that used to name the parser. */
class PlainErrorsTest {
    @Test
    void damagedBeatsAndExportsArePlainLanguage() {
        assertEquals(
            "This beat file is damaged and could not be read.",
            PlainErrors.explain("Expected an object")
        );
        assertEquals(
            "This beat file is damaged and could not be read.",
            PlainErrors.explain("Beat file ended early")
        );
        assertEquals(
            "This beat file is incomplete and could not be read.",
            PlainErrors.explain("Beat file is missing \"bpm\"")
        );
        assertEquals(
            "This beat was saved by a newer Hits, so this version cannot open it.",
            PlainErrors.explain("Unsupported beat version 9")
        );
        assertEquals(
            "A name in this beat file is broken, so it could not be read.",
            PlainErrors.explain("Unclosed string in beat file")
        );
        assertEquals(
            "This file is too small to be a beat.",
            PlainErrors.explain("Beat file is too short (4 bytes)")
        );
        assertEquals(
            "Hits could not make a WAV from this beat.",
            PlainErrors.explain("WAV export expects a tick-based MIDI sequence.")
        );
        assertEquals("That recording is empty.", PlainErrors.explain("frame count"));
        assertEquals(
            "Hits could not use this SoundFont. Choose a .sf2 file the built-in synth can play.",
            PlainErrors.explain("The built-in synth did not load this SoundFont.")
        );
        assertEquals(
            "Pattern B has 4 rows, not 8.",
            PlainErrors.explain("Pattern B has 4 tracks, not 8.")
        );
        assertEquals(
            "Add a letter or number to the name before saving",
            PlainErrors.explain("Add a letter or number to the name before saving")
        );
        assertEquals(
            "Something went wrong. Try that again.",
            PlainErrors.explain(new IllegalArgumentException())
        );
    }
}
