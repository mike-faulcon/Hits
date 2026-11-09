package hits;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.midi.MidiSystem;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UI copy does not change a saved beat. The sparse fixture still loads, saves,
 * and renders the same bytes, and grid and row edits still undo.
 */
class UiPolishLockTest {
    private static final Path FIXTURE = Path.of("src/test/resources/fixtures/sparse-808-groove.json");

    @TempDir
    Path temp;

    @Test
    void sparseFixtureSavesAndRendersTheSameBytes() throws Exception {
        String golden = Files.readString(FIXTURE);
        Beat beat = BeatJson.read(golden);
        assertEquals(golden, BeatJson.write(beat));
        byte[] midi = midi(beat);
        byte[] again = midi(BeatJson.read(BeatJson.write(beat)));
        assertArrayEquals(midi, again);

        Path first = temp.resolve("first.wav");
        Path second = temp.resolve("second.wav");
        try {
            BeatFiles.exportWav(beat, first, ExportOptions.allTracks(), null, null);
            BeatFiles.exportWav(BeatJson.read(BeatJson.write(beat)), second, ExportOptions.allTracks(), null, null);
        } catch (Exception exception) {
            if (WavRenderer.unavailable(exception)) {
                Assumptions.abort("offline render is not available: " + exception.getMessage());
            }
            throw exception;
        }
        assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second));
    }

    @Test
    void gridEditsAndRowChangesUndo() {
        Editor editor = new Editor(Beat.drumKit("undo"));
        String first = editor.beat().track(0).name();
        String second = editor.beat().track(1).name();
        editor.tap(0, 0, false);
        assertTrue(editor.beat().track(0).step(0).on());
        editor.undo();
        assertFalse(editor.beat().track(0).step(0).on());
        editor.redo();
        assertTrue(editor.beat().track(0).step(0).on());

        int count = editor.beat().trackCount();
        editor.addRow();
        assertEquals(count + 1, editor.beat().trackCount());
        editor.undo();
        assertEquals(count, editor.beat().trackCount());
        editor.redo();
        assertEquals(count + 1, editor.beat().trackCount());
        editor.removeRow();
        assertEquals(count, editor.beat().trackCount());
        editor.undo();
        assertEquals(count + 1, editor.beat().trackCount());

        editor.selectTrack(0);
        editor.moveRow(1);
        assertEquals(second, editor.beat().track(0).name());
        assertEquals(first, editor.beat().track(1).name());
        editor.undo();
        assertEquals(first, editor.beat().track(0).name());
        assertEquals(second, editor.beat().track(1).name());
        editor.redo();
        assertEquals(second, editor.beat().track(0).name());
    }

    private static byte[] midi(Beat beat) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MidiSystem.write(SequenceBuilder.export(beat, ExportOptions.allTracks()), 1, out);
        return out.toByteArray();
    }
}
