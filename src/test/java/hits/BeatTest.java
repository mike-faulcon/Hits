package hits;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Soundbank;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        assertEquals("Take 1", beat.name());
        Beat loaded = BeatFiles.load(saved);
        assertEquals("Take 1", loaded.name());
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
        beat.track(0).step(0).setPitch(40);
        beat.copyBar();
        assertEquals(40, beat.track(0).step(16).pitch());
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
    void saveKeepsTheDisplayNameAndPlansThePrompt(@TempDir Path directory) throws Exception {
        Beat beat = Beat.drumKit("Album Track – v3 (final)");
        beat.track(0).tap(0, false);
        Path saved = BeatFiles.save(beat, directory);
        assertEquals("AlbumTrackv3final.json", saved.getFileName().toString());
        assertEquals("Album Track – v3 (final)", beat.name());
        Beat loaded = BeatFiles.load(saved);
        assertEquals("Album Track – v3 (final)", loaded.name());
        assertTrue(loaded.track(0).step(0).on());

        Beat emoji = Beat.drumKit("🔥");
        assertThrows(IllegalArgumentException.class, () -> BeatFiles.save(emoji, directory));
        assertEquals("🔥", emoji.name());

        assertEquals("and", BeatFiles.safeName("ñandú"));
        assertEquals("myfirebeat", BeatFiles.safeName("my fire beat"));
        assertEquals("", BeatFiles.safeName("!!!"));
        assertEquals("", BeatFiles.safeName(null));

        Path target = directory.resolve("AlbumTrackv3final.json");
        assertEquals(BeatFiles.SaveChoice.CONFIRM_NAME,
            BeatFiles.plan("Album Track – v3 (final)", target, null, false, false));
        assertEquals(BeatFiles.SaveChoice.CONFIRM_REPLACE,
            BeatFiles.plan("Take1", target, null, false, true));
        assertEquals(BeatFiles.SaveChoice.WRITE,
            BeatFiles.plan("Take1", target, target, false, true));
        assertEquals(BeatFiles.SaveChoice.WRITE,
            BeatFiles.plan("Take 1", target, target, false, true));
        assertEquals(BeatFiles.SaveChoice.CONFIRM_REPLACE,
            BeatFiles.plan("Take1", target, target, true, true));
        assertEquals(BeatFiles.SaveChoice.CONFIRM_NAME,
            BeatFiles.plan("Take 1", directory.resolve("Other.json"), null, false, false));
    }

    @Test
    void exportKeepsMutedTracksUnlessAsHeard(@TempDir Path directory) throws Exception {
        Beat beat = Beat.drumKit("t");
        beat.track(0).setGate(50);
        beat.track(0).tap(0, false);
        beat.track(0).tap(1, false);
        assertEquals(
            noteOffTicks(SequenceBuilder.build(beat)),
            noteOffTicks(SequenceBuilder.export(beat, ExportOptions.allTracks()))
        );

        beat.track(0).setMute(true);
        beat.track(1).tap(0, false);
        List<ShortMessage> playback = noteOns(SequenceBuilder.build(beat));
        assertEquals(1, playback.size());
        assertEquals(38, playback.get(0).getData1());
        List<ShortMessage> all = noteOns(SequenceBuilder.export(beat, ExportOptions.allTracks()));
        assertEquals(3, all.size());
        assertTrue(all.stream().anyMatch(message -> message.getData1() == 36));
        assertTrue(all.stream().anyMatch(message -> message.getData1() == 38));

        List<ShortMessage> heard = noteOns(SequenceBuilder.export(beat, ExportOptions.matchingPlayback()));
        assertEquals(1, heard.size());
        assertEquals(38, heard.get(0).getData1());

        beat.track(0).setMute(false);
        beat.track(1).setSolo(true);
        assertEquals(3, noteOns(SequenceBuilder.export(beat, ExportOptions.allTracks())).size());
        List<ShortMessage> solo = noteOns(SequenceBuilder.export(beat, ExportOptions.matchingPlayback()));
        assertEquals(1, solo.size());
        assertEquals(38, solo.get(0).getData1());

        Path midi = directory.resolve("t.mid");
        beat.track(1).setSolo(false);
        beat.track(0).setMute(true);
        beat.track(1).tap(0, true);
        BeatFiles.exportMidi(beat, midi);
        assertFalse(noteOns(MidiSystem.getSequence(midi.toFile())).isEmpty());
        Path heardFile = directory.resolve("heard.mid");
        BeatFiles.exportMidi(beat, heardFile, ExportOptions.matchingPlayback());
        List<ShortMessage> heardFileNotes = noteOns(MidiSystem.getSequence(heardFile.toFile()));
        assertTrue(heardFileNotes.stream().noneMatch(message -> message.getData1() == 36));
        assertFalse(heardFileNotes.isEmpty());
    }

    @Test
    void exportCanChainSlotsAndRepeat() throws Exception {
        assertEquals(1, new ExportOptions(false, false, 0).repeats());
        assertEquals(ExportOptions.MAX_REPEATS, new ExportOptions(false, true, 100).repeats());
        assertEquals(ExportOptions.MAX_REPEATS * 2, new ExportOptions(false, true, 100).sections());

        Beat beat = Beat.drumKit("t");
        beat.track(0).tap(0, false);
        beat.setActiveSlot('b');
        beat.track(1).tap(0, false);
        beat.track(6).setMode(TrackMode.NOTE);
        beat.track(6).setProgram(40);
        beat.track(6).setNote(48);
        beat.track(6).tap(0, false);
        beat.setActiveSlot('a');
        beat.track(6).setMode(TrackMode.NOTE);
        beat.track(6).setProgram(33);
        beat.track(6).setNote(36);
        beat.track(6).tap(0, false);

        long bar = SequenceBuilder.loopTicks(16);
        Sequence chained = SequenceBuilder.export(beat, new ExportOptions(false, true, 1));
        List<MidiEvent> notes = noteOnEvents(chained);
        assertEquals(4, notes.size());
        assertEquals(0, tickOf(notes, Gm.DRUM_CHANNEL, 36));
        assertEquals(0, tickOf(notes, 6, 36));
        assertEquals(bar, tickOf(notes, Gm.DRUM_CHANNEL, 38));
        assertEquals(bar, tickOf(notes, 6, 48));
        assertEquals(bar * 2, markerTick(chained));
        assertTrue(programTicks(chained, 33).contains(0L));
        assertTrue(programTicks(chained, 40).contains(bar));

        beat.setActiveSlot('b');
        Sequence repeated = SequenceBuilder.export(beat, new ExportOptions(false, false, 3));
        List<MidiEvent> reps = noteOnEvents(repeated);
        assertEquals(6, reps.size());
        assertEquals(List.of(0L, bar, bar * 2), ticksOf(reps, Gm.DRUM_CHANNEL, 38));
        assertEquals(List.of(0L, bar, bar * 2), ticksOf(reps, 6, 48));
        assertEquals(bar * 3, markerTick(repeated));

        Sequence pairTwice = SequenceBuilder.export(beat, new ExportOptions(false, true, 2));
        List<MidiEvent> pairs = noteOnEvents(pairTwice);
        assertEquals(8, pairs.size());
        assertEquals(List.of(0L, bar * 2), ticksOf(pairs, Gm.DRUM_CHANNEL, 36));
        assertEquals(List.of(bar, bar * 3), ticksOf(pairs, Gm.DRUM_CHANNEL, 38));
        assertEquals(bar * 4, markerTick(pairTwice));
    }

    @Test
    void exportAsHeardUsesEachSlotsOwnSolo() throws Exception {
        Beat beat = Beat.drumKit("t");
        beat.track(0).tap(0, false);
        beat.track(1).tap(4, false);
        beat.track(0).setSolo(true);
        beat.setActiveSlot('b');
        beat.track(1).setName("Rim");
        beat.track(1).tap(0, false);
        beat.setActiveSlot('a');

        List<MidiEvent> heard = noteOnEvents(SequenceBuilder.export(beat, new ExportOptions(true, true, 1)));
        assertEquals(2, heard.size());
        assertEquals(0, heard.get(0).getTick());
        assertEquals(36, ((ShortMessage) heard.get(0).getMessage()).getData1());
        assertEquals(SequenceBuilder.loopTicks(16), heard.get(1).getTick());
        assertEquals(38, ((ShortMessage) heard.get(1).getMessage()).getData1());

        List<String> names = trackNames(SequenceBuilder.export(beat, new ExportOptions(false, true, 1)));
        assertTrue(names.contains("Snare / Rim"));
        List<String> heardNames = trackNames(SequenceBuilder.export(beat, new ExportOptions(true, true, 1)));
        assertTrue(heardNames.contains("Rim"));
        assertFalse(heardNames.contains("Snare / Rim"));
    }

    @Test
    void selectingAStepLeavesItOn() {
        Editor editor = new Editor(Beat.drumKit("t"));
        editor.tap(0, 3, false);
        editor.markClean();
        int velocity = editor.beat().track(0).step(3).velocity();
        editor.selectStep(0, 3);
        assertTrue(editor.beat().track(0).step(3).on());
        assertEquals(velocity, editor.beat().track(0).step(3).velocity());
        assertEquals(0, editor.trackIndex());
        assertEquals(3, editor.stepIndex());
        assertFalse(editor.isDirty());
        editor.selectStep(-1, 0);
        assertEquals(3, editor.stepIndex());
    }

    @Test
    void stepTintFollowsTheLoudHitValue() {
        assertEquals(Step.Shade.QUIET, new Step(true, 70).shade(120));
        assertEquals(Step.Shade.NORMAL, new Step(true, 100).shade(120));
        assertEquals(Step.Shade.NORMAL, new Step(true, 110).shade(120));
        assertEquals(Step.Shade.ACCENT, new Step(true, 100).shade(100));
        assertEquals(Step.Shade.ACCENT, new Step(true, 110).shade(100));
        assertEquals(Step.Shade.OFF, new Step(false, 127).shade(1));
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

    @Test
    void version1DocumentMigratesAndStepPitchWritesVersion2() {
        Beat legacy = BeatJson.read(minimalBeat(1, "", ""));
        assertEquals(1, BeatJson.documentVersion(legacy));
        assertEquals("old", legacy.name());
        assertEquals(90, legacy.bpm());
        assertEquals(52, legacy.swing());
        assertEquals('b', legacy.activeSlot());
        assertTrue(legacy.track(0).step(0).on());
        assertEquals(90, legacy.track(0).step(0).velocity());
        assertFalse(legacy.track(0).step(0).hasPitch());
        assertEquals(36, legacy.track(0).step(0).soundingNote(legacy.track(0).note()));
        assertFalse(legacy.track(0).step(1).on());
        assertEquals(64, legacy.track(3).program());

        Beat withField = BeatJson.read(minimalBeat(1, ",\"pitch\": 50", ""));
        assertEquals(50, withField.track(0).step(0).pitch());
        assertEquals(50, withField.track(0).step(0).soundingNote(36));
        assertTrue(BeatJson.write(withField).contains("\"version\": 2"));
        assertTrue(BeatJson.write(withField).contains("\"pitch\":50"));

        Beat version2 = BeatJson.read(minimalBeat(2, "", ",\"pan\": 64"));
        assertFalse(version2.hasStepPitch());
        assertEquals(1, BeatJson.documentVersion(version2));
        assertTrue(BeatJson.write(version2).contains("\"version\": 1"));
        assertFalse(BeatJson.write(Beat.drumKit("plain")).contains("pitch"));

        Beat pitched = Beat.drumKit("t");
        pitched.slot('b')[2].step(4).setOn(true);
        pitched.slot('b')[2].step(4).setPitch(70);
        assertEquals(2, BeatJson.documentVersion(pitched));
        Beat loaded = BeatJson.read(BeatJson.write(pitched));
        assertEquals(70, loaded.slot('b')[2].step(4).pitch());
        assertFalse(loaded.slot('a')[2].step(4).hasPitch());
        loaded.slot('b')[2].step(4).clearPitch();
        assertEquals(1, BeatJson.documentVersion(loaded));

        assertThrows(IllegalArgumentException.class, () -> BeatJson.read(minimalBeat(0, "", "")));
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class, () -> BeatJson.read(minimalBeat(3, "", "")));
        assertTrue(rejected.getMessage().contains("3"));

        Step step = new Step(true, 100);
        step.setPitch(200);
        assertEquals(127, step.pitch());
        step.setPitch(-3);
        assertEquals(0, step.pitch());
        assertEquals(0, step.soundingNote(60));
        step.clearPitch();
        assertFalse(step.hasPitch());
        assertEquals(60, step.soundingNote(60));
    }

    @Test
    void stepPitchIsTheNoteThatPlaysAndExports() throws Exception {
        Beat beat = Beat.drumKit("t");
        beat.track(0).setNote(36);
        beat.track(0).tap(0, false);
        beat.track(0).tap(1, false);
        beat.track(0).step(1).setPitch(40);
        List<ShortMessage> notes = noteOns(SequenceBuilder.build(beat));
        assertEquals(36, notes.get(0).getData1());
        assertEquals(40, notes.get(1).getData1());
        List<MidiEvent> exported = noteOnEvents(SequenceBuilder.export(beat, ExportOptions.allTracks()));
        assertEquals(0L, exported.get(0).getTick());
        assertEquals(36, ((ShortMessage) exported.get(0).getMessage()).getData1());
        assertEquals(24L, exported.get(1).getTick());
        assertEquals(40, ((ShortMessage) exported.get(1).getMessage()).getData1());

        beat.track(0).setNote(38);
        notes = noteOns(SequenceBuilder.build(beat));
        assertEquals(38, notes.get(0).getData1());
        assertEquals(40, notes.get(1).getData1());
    }

    @Test
    void beatsFolderRemembersAnAbsolutePath(@TempDir Path directory) {
        Path project = directory.resolve("project");
        assertEquals(project.resolve("beats").normalize(), BeatFolders.resolve(null, project));
        assertEquals(project.resolve("beats").normalize(), BeatFolders.resolve("  ", project));
        assertEquals(project.resolve("mybeats").normalize(), BeatFolders.resolve("mybeats", project));
        Path library = directory.resolve("library");
        String stored = BeatFolders.remember(library);
        assertEquals(library.toAbsolutePath().normalize().toString(), stored);
        assertEquals(library.toAbsolutePath().normalize(), BeatFolders.resolve(stored, directory.resolve("elsewhere")));
        assertTrue(Path.of(BeatFolders.remember(Path.of("beats"))).isAbsolute());
        assertThrows(IllegalArgumentException.class, () -> BeatFolders.remember(null));
    }

    @Test
    void midiOutputChoiceFallsBackToTheBuiltInSynth() {
        assertEquals("Bus 1|Apple|IAC", MidiOutputs.idFor("Bus 1", "Apple", "IAC"));
        assertEquals("a%7Cb|c%25d|", MidiOutputs.idFor("a|b", "c%d", null));
        assertEquals("Apple IAC Driver", MidiOutputs.labelFor("Apple IAC Driver", "Apple"));
        assertEquals("Bus 1 (Apple Inc.)", MidiOutputs.labelFor("Bus 1", "Apple Inc."));
        assertEquals("MIDI output", MidiOutputs.labelFor("  ", null));
        assertEquals(MidiOutputs.BUILTIN_ID, MidiOutputs.resolve(null, java.util.List.of()));
        assertEquals(MidiOutputs.BUILTIN_ID, MidiOutputs.resolve("gone", java.util.List.of(MidiOutputs.BUILTIN_ID, "other")));
        assertEquals("other", MidiOutputs.resolve("other", java.util.List.of(MidiOutputs.BUILTIN_ID, "other")));

        java.util.List<MidiOutputs.Choice> choices = MidiOutputs.list();
        assertFalse(choices.isEmpty());
        assertEquals(MidiOutputs.BUILTIN_ID, choices.get(0).id());
        assertEquals(MidiOutputs.BUILTIN_LABEL, choices.get(0).label());
        assertEquals(choices.size(), choices.stream().map(MidiOutputs.Choice::id).distinct().count());
        assertTrue(choices.stream().noneMatch(choice -> choice.label().equals("Gervill")));
        assertTrue(choices.stream().noneMatch(choice -> choice.label().equals("Real Time Sequencer")));
        assertEquals(MidiOutputs.BUILTIN_ID, MidiOutputs.resolve("unplugged", choices.stream().map(MidiOutputs.Choice::id).toList()));
    }

    @Test
    void soundFontLoadsThroughTheJdkReader(@TempDir Path directory) throws Exception {
        Path fixture = Path.of("src/test/resources/hits-test.sf2");
        Soundbank bank = SoundFonts.read(fixture);
        assertEquals("Hits Test", SoundFonts.displayName(bank, fixture));
        assertEquals("custom.sf2", SoundFonts.displayName(null, directory.resolve("custom.sf2")));
        assertTrue(SoundFonts.isSoundFontFile(directory.resolve("Kit.SF2")));
        assertFalse(SoundFonts.isSoundFontFile(directory.resolve("notes.txt")));
        assertFalse(SoundFonts.isSoundFontFile(null));
        Path text = directory.resolve("notes.txt");
        Files.writeString(text, "hello");
        assertThrows(IllegalArgumentException.class, () -> SoundFonts.read(text));
        assertThrows(java.io.IOException.class, () -> SoundFonts.read(directory.resolve("gone.sf2")));
        Path garbage = directory.resolve("bad.sf2");
        Files.writeString(garbage, "not a soundfont");
        assertThrows(InvalidMidiDataException.class, () -> SoundFonts.read(garbage));

        try (Player player = new Player()) {
            player.open();
            assertFalse(player.useOutput("not-connected|x|y"));
            assertEquals(MidiOutputs.BUILTIN_ID, player.outputId());
            assertNotNull(player.failure());
            if (player.isBuiltIn()) {
                assertEquals("Hits Test", player.loadSoundFont(fixture));
                assertEquals(fixture.toAbsolutePath().normalize(), player.soundFont());
                player.useDefaultSounds();
                assertNull(player.soundFont());
            } else {
                IllegalStateException exception = assertThrows(IllegalStateException.class, () -> player.loadSoundFont(fixture));
                assertTrue(exception.getMessage().contains("built-in synth"));
            }
        }
    }

    private static String minimalBeat(int version, String stepExtra, String trackExtra) {
        StringBuilder tracks = new StringBuilder();
        for (int i = 0; i < Beat.TRACKS; i++) {
            if (i > 0) {
                tracks.append(',');
            }
            String steps = i == 0
                ? "{\"on\":true,\"velocity\":90" + stepExtra + "}"
                : "{\"on\":false,\"velocity\":100}";
            tracks.append("{\"name\":\"T").append(i)
                .append("\",\"mode\":\"drum\",\"program\":").append(i == 3 ? 64 : 0)
                .append(trackExtra)
                .append(",\"note\":").append(36 + i)
                .append(",\"mute\":false,\"solo\":false,\"gate\":45,\"velocity\":100,\"accent\":120,\"steps\":[")
                .append(steps).append("]}");
        }
        String body = tracks.toString();
        return "{\"version\":" + version
            + ",\"name\":\"old\",\"bpm\":90,\"swing\":52,\"steps\":16,\"active\":\"b\",\"slots\":{"
            + "\"a\":{\"tracks\":[" + body + "]},\"b\":{\"tracks\":[" + body + "]}}}";
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

    private static long tickOf(List<MidiEvent> notes, int channel, int note) {
        List<Long> ticks = ticksOf(notes, channel, note);
        assertEquals(1, ticks.size());
        return ticks.get(0);
    }

    private static List<Long> ticksOf(List<MidiEvent> notes, int channel, int note) {
        List<Long> ticks = new ArrayList<>();
        for (MidiEvent event : notes) {
            ShortMessage message = (ShortMessage) event.getMessage();
            if (message.getChannel() == channel && message.getData1() == note) {
                ticks.add(event.getTick());
            }
        }
        return ticks;
    }

    private static long markerTick(Sequence sequence) {
        for (javax.sound.midi.Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                MidiEvent event = track.get(i);
                if (event.getMessage() instanceof MetaMessage meta && meta.getType() == 0x06) {
                    return event.getTick();
                }
            }
        }
        throw new AssertionError("missing end marker");
    }

    private static List<Long> programTicks(Sequence sequence, int program) {
        List<Long> ticks = new ArrayList<>();
        for (javax.sound.midi.Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                MidiEvent event = track.get(i);
                if (event.getMessage() instanceof ShortMessage message
                    && message.getCommand() == ShortMessage.PROGRAM_CHANGE
                    && message.getData1() == program) {
                    ticks.add(event.getTick());
                }
            }
        }
        return ticks;
    }

    private static List<String> trackNames(Sequence sequence) {
        List<String> names = new ArrayList<>();
        for (javax.sound.midi.Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                if (track.get(i).getMessage() instanceof MetaMessage meta && meta.getType() == 0x03) {
                    names.add(new String(meta.getData(), java.nio.charset.StandardCharsets.UTF_8));
                }
            }
        }
        return names;
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
