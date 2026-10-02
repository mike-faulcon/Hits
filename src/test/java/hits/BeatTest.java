package hits;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BeatTest {
    @Test
    void noteNamesUseC1ForTheKick() {
        assertEquals("C1", Notes.name(36));
        assertEquals("D1", Notes.name(38));
        assertEquals("F#1", Notes.name(42));
        assertEquals("Gunshot", Gm.programName(127));
        assertEquals("Glockenspiel", Gm.programName(9));
        assertEquals("Syn Brass 2", Gm.programName(63));
    }

    @Test
    void legacyRawBeatKeepsTheGlockPattern() throws Exception {
        Beat beat = BtfFormat.read(Path.of("beats/raw.btf"));
        Track hats = beat.track(0);
        assertEquals("Glockenspiel", hats.name());
        assertEquals(9, hats.program());
        assertEquals(71, hats.note());
        assertEquals(TrackMode.NOTE, hats.mode());
        assertEquals(100, hats.gate());
        assertTrue(hats.step(0).on());
        assertFalse(hats.step(1).on());
        assertTrue(hats.step(2).on());
        assertFalse(hats.step(13).on());
        assertFalse(hats.step(14).on());
        assertTrue(beat.track(1).step(14).on());
        assertEquals(70, beat.track(1).note());
        assertEquals(63, beat.track(2).program());
        assertEquals(60, beat.track(2).note());
        assertEquals(120, beat.bpm());
        assertTrue(beat.slot('b')[0].step(0).on());
    }

    @Test
    void legacyReggaeBeatKeepsTheOffbeats() throws Exception {
        Beat beat = BtfFormat.read(Path.of("beats/reggae1.btf"));
        Track skank = beat.track(0);
        assertEquals(45, skank.program());
        assertEquals(60, skank.note());
        assertFalse(skank.step(0).on());
        assertTrue(skank.step(2).on());
        assertTrue(skank.step(3).on());
        assertTrue(skank.step(15).on());
        assertEquals(10, beat.track(1).program());
        assertTrue(beat.track(1).step(0).on());
        assertEquals(13, beat.track(2).program());
        assertTrue(beat.track(2).step(4).on());
        assertTrue(beat.track(2).step(12).on());
        assertEquals(67, beat.track(3).program());
        assertEquals(48, beat.track(3).note());
    }

    @Test
    void everyLegacyFileLoads() throws Exception {
        try (var stream = Files.list(Path.of("beats"))) {
            List<Path> files = stream.filter(path -> path.getFileName().toString().endsWith(".btf")).toList();
            assertEquals(11, files.size());
            for (Path file : files) {
                Beat beat = BtfFormat.read(file);
                assertEquals(16, beat.stepCount());
                assertEquals(8, beat.activeTracks().length);
            }
        }
        assertTrue(BeatFiles.list(Path.of("beats")).stream().anyMatch(entry -> entry.label().equals("raw")));
    }

    @Test
    void jsonRoundTripKeepsBothSlots() {
        Beat beat = Beat.drumKit("Take \"one\"");
        beat.setBpm(100);
        beat.setSwing(66);
        beat.setStepCount(32);
        beat.track(0).tap(0, true);
        beat.track(0).setMute(true);
        beat.track(3).setSolo(true);
        beat.track(3).setGate(25);
        beat.setActiveSlot('b');
        beat.track(1).tap(4, false);
        beat.track(1).setName("Rim");
        beat.setActiveSlot('a');

        Beat loaded = BeatJson.read(BeatJson.write(beat));
        assertEquals(Beat.VERSION, 1);
        assertEquals("Take \"one\"", loaded.name());
        assertEquals(100, loaded.bpm());
        assertEquals(66, loaded.swing());
        assertEquals(32, loaded.stepCount());
        assertEquals('a', loaded.activeSlot());
        assertTrue(loaded.track(0).step(0).on());
        assertEquals(120, loaded.track(0).step(0).velocity());
        assertTrue(loaded.track(0).mute());
        assertTrue(loaded.track(3).solo());
        assertEquals(25, loaded.track(3).gate());
        assertFalse(loaded.slot('b')[0].step(0).on());
        assertTrue(loaded.slot('b')[1].step(4).on());
        assertEquals("Rim", loaded.slot('b')[1].name());
    }

    @Test
    void saveAndExportRoundTrip(@TempDir Path directory) throws Exception {
        Beat beat = Beat.drumKit("Take 1");
        Kits.boomBap(beat);
        Path saved = BeatFiles.save(beat, directory);
        assertEquals("Take1.json", saved.getFileName().toString());
        Beat loaded = BeatFiles.load(saved);
        assertEquals(96, loaded.bpm());
        assertTrue(loaded.track(0).step(0).on());

        Path midi = directory.resolve("Take1.mid");
        BeatFiles.exportMidi(loaded, midi);
        Sequence sequence = MidiSystem.getSequence(midi.toFile());
        assertEquals(Sequence.PPQ, sequence.getDivisionType());
        assertEquals(SequenceBuilder.PPQ, sequence.getResolution());
        List<ShortMessage> notes = noteOns(sequence);
        assertFalse(notes.isEmpty());
        assertTrue(notes.stream().anyMatch(message -> message.getChannel() == Gm.DRUM_CHANNEL && message.getData1() == 36));
    }

    @Test
    void straightAndSwungClocks() {
        assertEquals(0, SequenceBuilder.tickForStep(0, 50));
        assertEquals(24, SequenceBuilder.tickForStep(1, 50));
        assertEquals(48, SequenceBuilder.tickForStep(2, 50));
        assertEquals(36, SequenceBuilder.tickForStep(1, 75));
        assertEquals(84, SequenceBuilder.tickForStep(3, 75));
        assertEquals(0, SequenceBuilder.stepForTick(23, 16, 50));
        assertEquals(1, SequenceBuilder.stepForTick(24, 16, 50));
        assertEquals(384, SequenceBuilder.loopTicks(16));
    }

    @Test
    void sequenceUsesChannelsGateAndMute() throws Exception {
        Beat beat = Beat.drumKit("t");
        beat.setBpm(96);
        beat.track(0).setGate(50);
        beat.track(0).tap(0, false);
        beat.track(0).tap(1, false);
        Sequence sequence = SequenceBuilder.build(beat);
        List<MidiEvent> ons = noteOnEvents(sequence);
        assertEquals(0, ons.get(0).getTick());
        assertEquals(24, ons.get(1).getTick());
        ShortMessage first = (ShortMessage) ons.get(0).getMessage();
        assertEquals(Gm.DRUM_CHANNEL, first.getChannel());
        assertEquals(36, first.getData1());
        assertEquals(100, first.getData2());
        assertTrue(noteOffTicks(sequence).contains(12L));
        assertEquals(625_000, tempoMicros(sequence));

        beat.track(0).setMute(true);
        assertTrue(noteOns(SequenceBuilder.build(beat)).isEmpty());

        beat.track(0).setMute(false);
        beat.track(1).tap(0, false);
        beat.track(1).setSolo(true);
        List<ShortMessage> solo = noteOns(SequenceBuilder.build(beat));
        assertEquals(1, solo.size());
        assertEquals(38, solo.get(0).getData1());

        Beat melodic = Beat.drumKit("m");
        melodic.track(6).setMode(TrackMode.NOTE);
        melodic.track(6).setProgram(33);
        melodic.track(6).setNote(36);
        melodic.track(6).tap(0, false);
        ShortMessage bass = noteOns(SequenceBuilder.build(melodic)).get(0);
        assertEquals(6, bass.getChannel());
        assertEquals(36, bass.getData1());
    }

    @Test
    void copyBarDoublesThePattern() {
        Beat beat = Beat.drumKit("t");
        beat.track(0).tap(0, false);
        beat.track(0).tap(3, true);
        int accent = beat.track(0).step(3).velocity();
        beat.copyBar();
        assertEquals(32, beat.stepCount());
        assertTrue(beat.track(0).step(16).on());
        assertTrue(beat.track(0).step(19).on());
        assertEquals(accent, beat.track(0).step(19).velocity());
        assertFalse(beat.slot('b')[0].step(16).on());
    }

    @Test
    void kitsLeaveTheOtherSlotAlone() {
        Beat beat = Beat.drumKit("t");
        Kits.boomBap(beat);
        assertEquals(96, beat.bpm());
        assertEquals(54, beat.swing());
        assertTrue(beat.track(0).step(0).on());
        assertTrue(beat.track(1).step(4).on());
        assertTrue(beat.track(2).step(0).on());
        assertFalse(beat.track(2).step(6).on());
        assertTrue(beat.track(3).step(6).on());
        assertEquals(TrackMode.NOTE, beat.track(6).mode());
        assertEquals(33, beat.track(6).program());
        assertFalse(beat.slot('b')[0].step(0).on());

        Beat reggae = Beat.drumKit("t");
        Kits.reggae(reggae);
        assertEquals(86, reggae.bpm());
        assertTrue(reggae.track(0).step(8).on());
        assertFalse(reggae.track(0).step(0).on());
        assertTrue(reggae.track(2).step(2).on());
        assertTrue(reggae.track(2).step(14).on());
        assertEquals(TrackMode.NOTE, reggae.track(5).mode());
        assertTrue(reggae.track(5).step(2).on());
        assertEquals(18, reggae.track(5).program());
    }

    @Test
    void undoRestoresAStepAndATempoDrag() {
        Editor editor = new Editor(Beat.drumKit("t"));
        editor.tap(0, 0, false);
        assertTrue(editor.beat().track(0).step(0).on());
        editor.undo();
        assertFalse(editor.beat().track(0).step(0).on());
        editor.redo();
        assertTrue(editor.beat().track(0).step(0).on());

        editor.beginAdjust();
        editor.adjust(beat -> beat.setBpm(140));
        editor.endAdjust();
        assertEquals(140, editor.beat().bpm());
        editor.undo();
        assertEquals(96, editor.beat().bpm());
        assertTrue(editor.isDirty());
    }

    @Test
    void shiftClickUsesTheAccentVelocity() {
        Track kick = Beat.drumKit("t").track(0);
        kick.tap(0, true);
        assertTrue(kick.step(0).on());
        assertEquals(120, kick.step(0).velocity());
        kick.tap(0, true);
        assertEquals(100, kick.step(0).velocity());
        assertTrue(kick.step(0).on());
    }

    private static List<ShortMessage> noteOns(Sequence sequence) {
        List<ShortMessage> notes = new ArrayList<>();
        for (MidiEvent event : noteOnEvents(sequence)) {
            notes.add((ShortMessage) event.getMessage());
        }
        return notes;
    }

    private static List<MidiEvent> noteOnEvents(Sequence sequence) {
        List<MidiEvent> notes = new ArrayList<>();
        for (javax.sound.midi.Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                MidiEvent event = track.get(i);
                MidiMessage message = event.getMessage();
                if (message instanceof ShortMessage shortMessage
                    && shortMessage.getCommand() == ShortMessage.NOTE_ON
                    && shortMessage.getData2() > 0) {
                    notes.add(event);
                }
            }
        }
        notes.sort((left, right) -> Long.compare(left.getTick(), right.getTick()));
        return notes;
    }

    private static List<Long> noteOffTicks(Sequence sequence) {
        List<Long> ticks = new ArrayList<>();
        for (javax.sound.midi.Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                MidiEvent event = track.get(i);
                if (event.getMessage() instanceof ShortMessage shortMessage
                    && shortMessage.getCommand() == ShortMessage.NOTE_OFF) {
                    ticks.add(event.getTick());
                }
            }
        }
        return ticks;
    }

    private static int tempoMicros(Sequence sequence) throws Exception {
        for (javax.sound.midi.Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                if (track.get(i).getMessage() instanceof MetaMessage meta && meta.getType() == 0x51) {
                    byte[] data = meta.getData();
                    return ((data[0] & 0xff) << 16) | ((data[1] & 0xff) << 8) | (data[2] & 0xff);
                }
            }
        }
        throw new AssertionError("missing tempo");
    }
}
