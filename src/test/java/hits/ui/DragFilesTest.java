package hits.ui;

import hits.Beat;
import hits.Chain;
import hits.ExportOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DragFilesTest {
    @TempDir
    Path directory;

    @Test
    void uniqueNamesDoNotClobberAFileTheDawMayStillBeReading() {
        Set<String> used = new java.util.LinkedHashSet<>();
        assertEquals("Take-A.mid", DragFiles.uniqueName(used, "Take-A.mid"));
        assertEquals("Take-A-2.mid", DragFiles.uniqueName(used, "Take-A.mid"));
        assertEquals("Take-A-3.mid", DragFiles.uniqueName(used, "Take-A.mid"));
        assertEquals("notes", DragFiles.uniqueName(used, "notes"));
        assertEquals("notes-2", DragFiles.uniqueName(used, "notes"));
    }

    @Test
    void closeDeletesTheFolderEvenIfAPartialRenderIsLeftBehind() throws Exception {
        DragFiles files = DragFiles.in(directory.resolve("drag"));
        Path midi = files.place("Take-A.mid");
        Path again = files.place("Take-A.mid");
        Files.writeString(midi, "first");
        Files.writeString(again, "second");
        Files.writeString(files.directory().resolve("hits-wav-orphan.part"), "partial");
        assertEquals("first", Files.readString(midi));
        files.close();
        assertFalse(Files.exists(midi));
        assertFalse(Files.exists(again));
        assertFalse(Files.exists(files.directory()));
        files.close();
        assertThrows(IllegalStateException.class, () -> files.place("Take-A.mid"));
    }

    @Test
    void placeRejectsANameThatCouldLeaveTheTempFolder() throws Exception {
        DragFiles files = DragFiles.in(directory.resolve("drag"));
        assertThrows(IllegalArgumentException.class, () -> files.place("../Take.mid"));
        assertThrows(IllegalArgumentException.class, () -> files.place("a/Take.mid"));
        assertThrows(IllegalArgumentException.class, () -> files.place(".."));
        assertThrows(IllegalArgumentException.class, () -> files.place("  "));
        files.close();
    }

    @Test
    void openRemovesItsTempFolderOnClose() throws Exception {
        DragFiles files = DragFiles.open();
        assertTrue(files.directory().getFileName().toString().startsWith("hits-drag-"));
        assertEquals(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize(), files.directory().getParent());
        Path midi = files.place("Take-song.mid");
        Files.writeString(midi, "midi");
        files.close();
        assertFalse(Files.exists(files.directory()));
    }

    @Test
    void patternAndSongDragsWriteDifferentMidi() throws Exception {
        Beat beat = Beat.drumKit("Album Track – v3");
        beat.setActiveSlot('b');
        beat.track(0).tap(0, false);
        beat.setActiveSlot('a');
        beat.setChain(List.of(new Chain.Part('a', 1), new Chain.Part('b', 1)));

        ExportOptions pattern = ExportPlan.dragOptions(false, false, 1, false, true);
        ExportOptions song = ExportPlan.dragOptions(false, false, 1, true, true);
        assertEquals("AlbumTrackv3-A.mid", ExportPlan.dragFileName(beat.name(), pattern, beat.activeSlot(), "mid"));
        assertEquals("AlbumTrackv3-song.mid", ExportPlan.dragFileName(beat.name(), song, beat.activeSlot(), "mid"));

        DragFiles files = DragFiles.in(directory.resolve("drag"));
        Path patternFile = write(files, beat, pattern);
        Path songFile = write(files, beat, song);
        assertEquals(0, noteOns(MidiSystem.getSequence(patternFile.toFile())));
        assertEquals(1, noteOns(MidiSystem.getSequence(songFile.toFile())));
        assertTrue(MidiSystem.getSequence(songFile.toFile()).getTickLength()
            > MidiSystem.getSequence(patternFile.toFile()).getTickLength());
        files.close();
        assertFalse(Files.exists(patternFile));
        assertFalse(Files.exists(songFile));
    }

    @Test
    void rememberedChoicesFeedTheNextDrag() {
        ExportCommands.ExportMemory memory = new ExportCommands.ExportMemory();
        ExportOptions initial = memory.forDrag(false, true);
        assertTrue(initial.asHeard());
        assertFalse(initial.song());
        assertEquals(1, initial.repeats());
        assertTrue(ExportCommands.ExportMemory.asHeardFromIgnore(false));
        assertFalse(ExportCommands.ExportMemory.asHeardFromIgnore(true));

        memory.remember(true, true, 3);
        ExportOptions pattern = memory.forDrag(false, true);
        assertTrue(pattern.asHeard());
        assertTrue(pattern.bothSlots());
        assertEquals(3, pattern.repeats());
        assertFalse(pattern.song());

        ExportOptions song = memory.forDrag(true, true);
        assertTrue(song.song());
        assertTrue(song.asHeard());
        assertFalse(song.bothSlots());
        assertEquals(1, song.repeats());

        memory.remember(false, true, 100);
        assertEquals(32, memory.repeats());
        assertTrue(memory.forDrag(true, false).bothSlots());
        assertFalse(memory.forDrag(true, false).song());
    }

    @Test
    void defaultDragLeavesMutedTracksOut() throws Exception {
        Beat beat = Beat.drumKit("t");
        beat.track(0).tap(0, false);
        beat.track(1).tap(4, false);
        beat.track(0).setMute(true);

        ExportCommands.ExportMemory memory = new ExportCommands.ExportMemory();
        DragFiles files = DragFiles.in(directory.resolve("drag-mute"));
        Path heard = write(files, beat, memory.forDrag(false, false));
        assertFalse(notes(MidiSystem.getSequence(heard.toFile())).contains(36));
        assertTrue(notes(MidiSystem.getSequence(heard.toFile())).contains(38));

        memory.remember(ExportCommands.ExportMemory.asHeardFromIgnore(true), false, 1);
        Path all = write(files, beat, memory.forDrag(false, false));
        assertTrue(notes(MidiSystem.getSequence(all.toFile())).contains(36));
        assertTrue(notes(MidiSystem.getSequence(all.toFile())).contains(38));
        files.close();
    }

    private static Path write(DragFiles files, Beat beat, ExportOptions options) throws Exception {
        Path path = files.place(ExportPlan.dragFileName(beat.name(), options, beat.activeSlot(), "mid"));
        hits.BeatFiles.exportMidi(beat, path, options);
        return path;
    }

    private static Set<Integer> notes(Sequence sequence) {
        Set<Integer> notes = new HashSet<>();
        for (Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                if (track.get(i).getMessage() instanceof ShortMessage message
                    && message.getCommand() == ShortMessage.NOTE_ON
                    && message.getData2() > 0) {
                    notes.add(message.getData1());
                }
            }
        }
        return notes;
    }

    private static int noteOns(Sequence sequence) {
        int count = 0;
        for (Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                if (track.get(i).getMessage() instanceof ShortMessage message
                    && message.getCommand() == ShortMessage.NOTE_ON
                    && message.getData2() > 0) {
                    count++;
                }
            }
        }
        return count;
    }
}
