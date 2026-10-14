package hits;

import org.junit.jupiter.api.Test;

import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatternSlotsTest {
    @Test
    void beatsThatOnlyUseAAndBSaveTheSameDocument() throws Exception {
        assertEquals(golden("ab-plain.json"), BeatJson.write(Beat.drumKit("plain")));
        assertEquals(golden("ab-round.json"), BeatJson.write(roundTripBeat()));
        assertEquals(golden("ab-pitch.json"), BeatJson.write(pitchedBeat()));
        assertEquals(golden("ab-chain.json"), BeatJson.write(chainBeat()));

        for (String name : List.of("ab-plain.json", "ab-round.json", "ab-pitch.json", "ab-chain.json")) {
            String original = golden(name);
            assertFalse(original.contains("\"c\":"));
            assertEquals(original, BeatJson.write(BeatJson.read(original)));
        }

        Beat legacy = BtfFormat.read(Path.of("beats/raw.btf"));
        String saved = BeatJson.write(legacy);
        assertEquals(1, BeatJson.documentVersion(legacy));
        assertFalse(saved.contains("\"c\":"));
        assertEquals(saved, BeatJson.write(BeatJson.read(saved)));
        assertEquals('a', BeatJson.read(golden("ab-plain.json").replace("\"active\": \"a\"", "\"active\": \"z\"")).activeSlot());
    }

    @Test
    void extraSlotsAreVersion2AndRoundTrip() {
        Beat plain = Beat.drumKit("plain");
        plain.setActiveSlot('h');
        assertTrue(plain.usesExtraSlots());
        assertEquals(2, BeatJson.documentVersion(plain));
        String selected = BeatJson.write(plain);
        assertTrue(selected.contains("\"active\": \"h\""));
        assertFalse(selected.contains("\"h\":"));
        Beat loadedSelected = BeatJson.read(selected);
        assertEquals('h', loadedSelected.activeSlot());
        assertFalse(loadedSelected.slot('h')[0].step(0).on());
        loadedSelected.setActiveSlot('a');
        assertEquals(BeatJson.write(Beat.drumKit("plain")), BeatJson.write(loadedSelected));

        Beat referenced = Beat.drumKit("plain");
        referenced.addChain('c');
        String chain = BeatJson.write(referenced);
        assertTrue(chain.contains("\"slot\": \"c\""));
        assertFalse(chain.contains("\"c\":"));
        assertEquals('c', BeatJson.read(chain).chain().get(0).slot());
        referenced.setChain(List.of());
        assertEquals(1, BeatJson.documentVersion(referenced));
        assertEquals(BeatJson.write(Beat.drumKit("plain")), BeatJson.write(referenced));

        Beat beat = Beat.drumKit("t");
        beat.slot('c')[0].setName("Foot");
        beat.slot('c')[0].tap(0, false);
        beat.slot('g')[3].step(2).setOn(true);
        beat.slot('g')[3].step(2).setPitch(64);
        beat.addChain('g');
        beat.addChain('c');
        assertEquals(2, BeatJson.documentVersion(beat));
        String json = BeatJson.write(beat);
        assertTrue(json.indexOf("\"c\":") < json.indexOf("\"g\":"));
        assertFalse(json.contains("\"d\":"));
        assertFalse(json.contains("\"e\":"));
        assertFalse(json.contains("\"h\":"));
        Beat loaded = BeatJson.read(json);
        assertEquals("Foot", loaded.slot('c')[0].name());
        assertTrue(loaded.slot('c')[0].step(0).on());
        assertEquals(64, loaded.slot('g')[3].step(2).pitch());
        assertEquals(List.of(new Chain.Part('g', 1), new Chain.Part('c', 1)), loaded.chain());
        assertEquals(json, BeatJson.write(loaded));
        restoreKit(loaded, 'c');
        restoreKit(loaded, 'g');
        loaded.setChain(List.of());
        assertEquals(1, BeatJson.documentVersion(loaded));
        assertEquals(BeatJson.write(Beat.drumKit("t")), BeatJson.write(loaded));
    }

    @Test
    void copyAndChainEditsUndoAcrossAllSlots() {
        assertEquals('h', new Chain.Part('h', 2).slot());
        assertEquals('a', new Chain.Part('H', 1).slot());
        assertEquals('a', new Chain.Part('x', 1).slot());
        assertEquals(
            List.of('c', 'e'),
            Chain.distinctSlots(List.of(new Chain.Part('e', 1), new Chain.Part('c', 2), new Chain.Part('e', 1)))
        );
        assertTrue(Chain.usesBoth(List.of(new Chain.Part('c', 1), new Chain.Part('d', 1))));
        assertFalse(Chain.usesBoth(List.of(new Chain.Part('c', 4))));
        assertEquals('c', new ExportOptions(false, false, 1).slotAt(0, 'c'));
        assertEquals('a', new ExportOptions(false, true, 1).slotAt(2, 'c'));
        assertEquals('b', new ExportOptions(false, true, 1).slotAt(1, 'h'));

        Editor editor = new Editor(Beat.drumKit("t"));
        editor.edit(beat -> beat.setActiveSlot('c'));
        editor.tap(0, 0, false);
        editor.edit(beat -> beat.copySlot('c', 'f'));
        assertEquals('c', editor.beat().activeSlot());
        assertTrue(editor.beat().slot('f')[0].step(0).on());
        assertFalse(editor.beat().slot('a')[0].step(0).on());
        editor.beat().slot('c')[0].tap(1, false);
        assertFalse(editor.beat().slot('f')[0].step(1).on());
        editor.undo();
        assertFalse(editor.beat().slot('f')[0].step(0).on());
        assertTrue(editor.beat().slot('c')[0].step(0).on());
        editor.undo();
        assertFalse(editor.beat().slot('c')[0].step(0).on());
        editor.undo();
        assertEquals('a', editor.beat().activeSlot());
        editor.redo();
        assertEquals('c', editor.beat().activeSlot());

        editor.edit(beat -> assertTrue(beat.addChain('h')));
        assertEquals('h', editor.beat().chain().get(0).slot());
        editor.edit(beat -> beat.setChainRepeats(0, 2));
        assertEquals(2, editor.beat().chain().get(0).repeats());
        editor.undo();
        assertEquals(1, editor.beat().chain().get(0).repeats());
        editor.undo();
        assertFalse(editor.beat().hasChain());

        editor.markClean();
        editor.setSongMode(true);
        editor.holdSlot('e');
        assertEquals('e', editor.viewSlot());
        assertTrue(editor.holding());
        assertFalse(editor.isDirty());
        editor.showPlayingSlot('g');
        assertEquals('e', editor.viewSlot());
        editor.followAgain();
        editor.showPlayingSlot('g');
        assertEquals('g', editor.viewSlot());
        editor.tap(1, 2, false);
        assertTrue(editor.beat().slot('g')[1].step(2).on());
        assertFalse(editor.beat().slot('a')[1].step(2).on());
    }

    @Test
    void songAndAsHeardExportsUseEveryPattern() throws Exception {
        Beat beat = Beat.drumKit("t");
        beat.setActiveSlot('c');
        beat.track(0).tap(0, false);
        beat.track(1).setName("Sidestick");
        beat.track(1).tap(0, false);
        beat.setActiveSlot('e');
        beat.track(0).setMute(true);
        beat.track(1).setName("Rim");
        beat.track(1).tap(0, false);
        beat.track(6).setName("Bass");
        beat.track(6).setMode(TrackMode.NOTE);
        beat.track(6).setProgram(40);
        beat.track(6).setNote(36);
        beat.track(6).tap(0, false);
        beat.setActiveSlot('a');
        beat.addChain('e');
        beat.addChain('c');
        beat.setChainRepeats(1, 2);

        long bar = SequenceBuilder.loopTicks(16);
        Sequence heard = SequenceBuilder.export(beat, new ExportOptions(true, false, 9, true));
        List<MidiEvent> notes = noteOnEvents(heard);
        assertEquals(bar * 3, markerTick(heard));
        assertEquals(List.of(bar, bar * 2), ticksOf(notes, Gm.DRUM_CHANNEL, 36));
        assertEquals(List.of(0L, bar, bar * 2), ticksOf(notes, Gm.DRUM_CHANNEL, 38));
        assertEquals(List.of(0L), ticksOf(notes, 6, 36));
        assertTrue(programTicks(heard, 40).contains(0L));
        List<String> heardNames = trackNames(heard);
        assertTrue(heardNames.contains("Sidestick / Rim"));
        assertTrue(heardNames.contains("Crash / Bass"));
        assertTrue(heardNames.contains("Kick"));
        assertFalse(heardNames.contains("Snare / Rim"));

        List<String> allNames = trackNames(SequenceBuilder.export(beat, new ExportOptions(false, false, 1, true)));
        assertTrue(allNames.contains("Sidestick / Rim"));
        assertTrue(allNames.contains("Kick"));
        assertTrue(allNames.contains("Crash / Bass"));

        Sequence pair = SequenceBuilder.export(beat, new ExportOptions(false, true, 1));
        assertEquals(bar * 2, markerTick(pair));
        assertTrue(noteOnEvents(pair).isEmpty());

        beat.setActiveSlot('e');
        List<ShortMessage> alone = noteOns(SequenceBuilder.export(beat, ExportOptions.matchingPlayback()));
        assertTrue(alone.stream().noneMatch(message -> message.getChannel() == Gm.DRUM_CHANNEL && message.getData1() == 36));
        assertTrue(alone.stream().anyMatch(message -> message.getChannel() == Gm.DRUM_CHANNEL && message.getData1() == 38));
        assertTrue(alone.stream().anyMatch(message -> message.getChannel() == 6 && message.getData1() == 36));
        assertEquals(bar * 2, markerTick(SequenceBuilder.export(beat, new ExportOptions(false, false, 2))));

        Sequence playback = SequenceBuilder.buildSong(beat);
        assertEquals(bar * 3, markerTick(playback));
        assertEquals(6, noteOnEvents(playback).size());
    }

    private static Beat roundTripBeat() {
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
        return beat;
    }

    private static Beat pitchedBeat() {
        Beat pitched = Beat.drumKit("t");
        pitched.slot('b')[2].step(4).setOn(true);
        pitched.slot('b')[2].step(4).setPitch(70);
        return pitched;
    }

    private static Beat chainBeat() {
        Beat chain = Beat.drumKit("Take \"one\"");
        chain.track(0).step(1).setOn(true);
        chain.track(0).step(1).setPitch(40);
        chain.addChain('a');
        chain.setChainRepeats(0, 2);
        chain.addChain('b');
        chain.addChain('a');
        return chain;
    }

    private static void restoreKit(Beat beat, char slot) {
        Beat kit = Beat.drumKit("kit");
        Track[] fresh = new Track[Beat.TRACKS];
        for (int i = 0; i < Beat.TRACKS; i++) {
            fresh[i] = kit.slot(slot)[i].copy();
        }
        beat.replaceSlot(slot, fresh);
    }

    private static String golden(String name) throws Exception {
        return Files.readString(Path.of("src/test/resources/slots", name));
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

    private static List<ShortMessage> noteOns(Sequence sequence) {
        List<ShortMessage> notes = new ArrayList<>();
        for (MidiEvent event : noteOnEvents(sequence)) {
            notes.add((ShortMessage) event.getMessage());
        }
        return notes;
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
}
