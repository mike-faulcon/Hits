package hits.ui;

import hits.BeatFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SaveSessionTest {
    @TempDir
    Path directory;

    @Test
    void jsonLoadRemembersTheFileAndBtfDoesNot() throws Exception {
        Path json = directory.resolve("Album Track.json");
        Path btf = directory.resolve("legacy.BTF");
        Files.writeString(json, "{}");
        Files.writeString(btf, "x");
        SaveSession session = new SaveSession();

        session.loaded(json);
        assertEquals(json.toAbsolutePath().normalize(), session.file());

        session.loaded(btf);
        assertNull(session.file());

        session.rememberWritten(json);
        session.forget();
        assertNull(session.file());
    }

    @Test
    void saveAsCaptionDescribesTheFileName() throws Exception {
        assertEquals(
            "Add a letter or number. This name cannot be a file.",
            SaveSession.saveAsCaption("   ", directory)
        );
        assertEquals("File: Take1.json.", SaveSession.saveAsCaption("Take1", directory));
        assertEquals(
            "File: AlbumTrackv3.json.  Name in the file stays \"Album Track – v3\".",
            SaveSession.saveAsCaption(" Album Track – v3 ", directory)
        );
        Files.writeString(directory.resolve("Take1.json"), "{}");
        Files.writeString(directory.resolve("AlbumTrackv3.json"), "{}");
        assertEquals("Replaces Take1.json.", SaveSession.saveAsCaption("Take1", directory));
        assertEquals(
            "Replaces AlbumTrackv3.json.  Name in the file stays \"Album Track – v3\".",
            SaveSession.saveAsCaption("Album Track – v3", directory)
        );
    }

    @Test
    void promptsAndTitleKeepTheDirtyMarker() {
        assertEquals(
            "Add a letter or number. This name cannot be a file yet.",
            SaveSession.nameTooltip("???")
        );
        assertEquals("Name stored in the file. Saves as AlbumTrackv3.json", SaveSession.nameTooltip("Album Track – v3"));

        assertEquals("Hits — untitled", SaveSession.windowTitle("untitled", false, true));
        assertEquals("Hits — untitled •", SaveSession.windowTitle("untitled", true, true));
        assertEquals("Hits — untitled — MIDI unavailable", SaveSession.windowTitle("untitled", false, false));
        assertEquals("Hits — untitled • — MIDI unavailable", SaveSession.windowTitle("untitled", true, false));

        assertEquals("Replace Take1.json?", SaveSession.confirmMessage(BeatFiles.SaveChoice.CONFIRM_REPLACE, "Take1", "Take1"));
        assertEquals("Save as Take1.json?", SaveSession.confirmMessage(BeatFiles.SaveChoice.CONFIRM_NAME, "Take1", "Take1"));
        assertEquals(
            "Save as AlbumTrackv3.json?\nThe name stored in the file stays \"Album Track – v3\".",
            SaveSession.confirmMessage(BeatFiles.SaveChoice.CONFIRM_NAME, "AlbumTrackv3", "Album Track – v3")
        );
        assertArrayEquals(new String[]{"Replace", "Cancel"}, SaveSession.confirmOptions(BeatFiles.SaveChoice.CONFIRM_REPLACE));
        assertArrayEquals(new String[]{"Save", "Cancel"}, SaveSession.confirmOptions(BeatFiles.SaveChoice.WRITE));
    }

    @Test
    void unsavedChoiceSaveDiscardOrCancel() {
        assertEquals(SaveSession.Discard.SAVE, SaveSession.choice(0));
        assertEquals(SaveSession.Discard.DISCARD, SaveSession.choice(1));
        assertEquals(SaveSession.Discard.CANCEL, SaveSession.choice(2));
        assertEquals(SaveSession.Discard.CANCEL, SaveSession.choice(-1));

        assertTrue(SaveSession.proceed(false, SaveSession.Discard.CANCEL, () -> false));
        assertFalse(SaveSession.proceed(true, SaveSession.Discard.CANCEL, () -> true));
        assertTrue(SaveSession.proceed(true, SaveSession.Discard.DISCARD, () -> false));
        assertTrue(SaveSession.proceed(true, SaveSession.Discard.SAVE, () -> true));
        assertFalse(SaveSession.proceed(true, SaveSession.Discard.SAVE, () -> false));
    }
}
