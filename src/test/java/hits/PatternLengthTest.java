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

/** Per-pattern length: additive JSON, mixed song timing, and the loop end. */
class PatternLengthTest {
    @Test
    void aBeatWithoutItsOwnLengthsSavesTheSameDocument() throws Exception {
        String plain = Files.readString(Path.of("src/test/resources/slots/ab-plain.json"));
        Beat kit = Beat.drumKit("plain");
        assertEquals(plain, BeatJson.write(kit));
        assertEquals(1, BeatJson.documentVersion(kit));
        assertFalse(kit.hasCustomLengths());

        kit.setSlotSteps('a', 16);
        kit.setSlotSteps('c', kit.stepCount());
        assertEquals(plain, BeatJson.write(kit));
        assertEquals(plain, BeatJson.write(BeatJson.read(plain)));
        assertFalse(BeatJson.write(kit).contains("\n      \"steps\":"));
    }

    @Test
    void slotStepsRoundTripOnlyWhenTheyDifferFromTheRoot() {
        Beat beat = Beat.drumKit("stops");
        beat.setSlotSteps('a', 32);
        beat.setSlotSteps('c', 12);
        beat.slot('c')[0].tap(0, false);
        beat.slot('c')[0].tap(14, false);
        beat.addChain('a');
        beat.addChain('c');

        assertEquals(2, BeatJson.documentVersion(beat));
        assertEquals(16, beat.stepCount());
        assertEquals(32, beat.stepCount('a'));
        assertEquals(12, beat.stepCount('c'));
        assertEquals(16, beat.stepCount('b'));

        String json = BeatJson.write(beat);
        assertTrue(json.contains("\"version\": 2"));
        assertTrue(json.contains("\n  \"steps\": 16,\n"));
        assertTrue(json.contains("\n      \"steps\": 32,\n"));
        assertTrue(json.contains("\n      \"steps\": 12,\n"));
        assertTrue(json.indexOf("\"steps\": 32") < json.indexOf("\"steps\": 12"));
        assertFalse(json.contains("\"b\": {\n      \"steps\":"));

        Beat loaded = BeatJson.read(json);
        assertEquals(json, BeatJson.write(loaded));
        assertEquals(32, loaded.stepCount('a'));
        assertEquals(12, loaded.stepCount('c'));
        assertTrue(loaded.slot('c')[0].step(0).on());
        assertTrue(loaded.slot('c')[0].step(14).on());
        assertEquals(16, loaded.stepCount('b'));

        loaded.setSlotSteps('a', 16);
        loaded.setSlotSteps('c', 16);
        loaded.slot('c')[0].step(0).setOn(false);
        loaded.slot('c')[0].step(14).setOn(false);
        loaded.setChain(List.of());
        assertEquals(1, BeatJson.documentVersion(loaded));
        assertEquals(BeatJson.write(Beat.drumKit("stops")), BeatJson.write(loaded));
    }

    @Test
    void aLengthOnlyPatternIsWrittenAndUndoRestoresIt() {
        Beat beat = Beat.drumKit("t");
        beat.setSlotSteps('c', 8);
        assertTrue(beat.usesExtraSlots());
        String json = BeatJson.write(beat);
        assertTrue(json.contains("\"c\":"));
        assertTrue(json.contains("\n      \"steps\": 8,\n"));
        assertEquals(8, BeatJson.read(json).stepCount('c'));

        Editor editor = new Editor(Beat.drumKit("t"));
        editor.edit(current -> current.setSlotSteps('a', 12));
        assertEquals(12, editor.beat().stepCount('a'));
        assertEquals(2, BeatJson.documentVersion(editor.beat()));
        editor.undo();
        assertEquals(16, editor.beat().stepCount('a'));
        assertEquals(1, BeatJson.documentVersion(editor.beat()));
        editor.redo();
        assertEquals(12, editor.beat().stepCount('a'));
    }

    @Test
    void aMixedChainMatchesTheNoteEventsAndTheLoopEnd() throws Exception {
        Beat beat = mixedChain();
        Sequence sequence = SequenceBuilder.export(beat, new ExportOptions(true, false, 1, true));
        assertEquals(List.of(
            "0 on 36",
            "11 off 36",
            "384 on 38",
            "395 off 38",
            "768 on 38",
            "779 off 38"
        ), noteEvents(sequence));
        assertEquals(1152, markerTick(sequence));
        assertEquals(1152, SequenceBuilder.playbackTicks(beat, true));

        Chain.Place before = Chain.place(beat, 383);
        assertEquals('a', before.slot());
        assertEquals(15, before.step());
        assertEquals(0, before.section());

        Chain.Place boundary = Chain.place(beat, 384);
        assertEquals('b', boundary.slot());
        assertEquals(0, boundary.step());
        assertEquals(1, boundary.section());

        Chain.Place secondBar = Chain.place(beat, 768);
        assertEquals('b', secondBar.slot());
        assertEquals(16, secondBar.step());

        Chain.Place wrapped = Chain.place(beat, 1152);
        assertEquals('a', wrapped.slot());
        assertEquals(0, wrapped.step());
        assertEquals(0, wrapped.section());

        beat.setSwing(75);
        assertEquals('a', Chain.place(beat, 372).slot());
        assertEquals(15, Chain.place(beat, 372).step());
        assertEquals('b', Chain.place(beat, 384).slot());
        assertEquals(0, Chain.place(beat, 384).step());
    }

    @Test
    void aTwelveStepPatternLoopsAtItsOwnEnd() throws Exception {
        Beat beat = Beat.drumKit("t");
        beat.setBpm(120);
        beat.setSlotSteps('a', 12);
        beat.track(0).tap(0, false);
        beat.track(0).tap(11, false);

        assertEquals(12 * SequenceBuilder.STEP_TICKS, SequenceBuilder.playbackTicks(beat, false));
        Sequence sequence = SequenceBuilder.build(beat);
        assertEquals(288, markerTick(sequence));
        assertEquals(List.of("0 on 36", "11 off 36", "264 on 36", "275 off 36"), noteEvents(sequence));
        assertEquals(11, SequenceBuilder.stepForTick(287, 12, 50));
        assertEquals(0, SequenceBuilder.stepForTick(288, 12, 50));

        beat.setSlotSteps('a', 13);
        beat.setSwing(75);
        beat.track(0).tap(12, false);
        Sequence odd = SequenceBuilder.build(beat);
        assertEquals(13 * SequenceBuilder.STEP_TICKS, markerTick(odd));
        assertEquals(288, SequenceBuilder.tickForStep(12, 75));
        assertTrue(noteEvents(odd).contains("288 on 36"));
        assertTrue(noteEvents(odd).contains("299 off 36"));
    }

    @Test
    void songExportDragAndAThenBUseEachPatternLength() throws Exception {
        Beat beat = mixedChain();
        // Song drag uses the chain once, as heard: A then B and repeats do not apply.
        ExportOptions drag = new ExportOptions(true, false, 1, true);
        assertEquals(1152, markerTick(SequenceBuilder.export(beat, drag)));

        beat.setSlotSteps('a', 32);
        beat.setSlotSteps('b', 16);
        beat.slot('a')[0].tap(16, false);
        Sequence pair = SequenceBuilder.export(beat, new ExportOptions(true, true, 1));
        assertEquals(List.of(
            "0 on 36",
            "11 off 36",
            "384 on 36",
            "395 off 36",
            "768 on 38",
            "779 off 38"
        ), noteEvents(pair));
        assertEquals(32 * SequenceBuilder.STEP_TICKS + 16 * SequenceBuilder.STEP_TICKS, markerTick(pair));
    }

    @Test
    void metronomeAccentsEachBarInsideASection() throws Exception {
        Beat beat = Beat.drumKit("t");
        beat.setSlotSteps('a', 12);
        List<MidiEvent> shortPattern = clicks(SequenceBuilder.build(beat, true));
        assertEquals(List.of(0L, 96L, 192L), ticks(shortPattern));
        assertEquals(SequenceBuilder.CLICK_ACCENT_NOTE, note(shortPattern.get(0)));
        assertEquals(SequenceBuilder.CLICK_NOTE, note(shortPattern.get(1)));
        assertEquals(SequenceBuilder.CLICK_NOTE, note(shortPattern.get(2)));

        Beat song = mixedChain();
        List<MidiEvent> chain = clicks(SequenceBuilder.buildSong(song, true));
        assertEquals(List.of(0L, 384L, 768L), accentTicks(chain));
        assertEquals(12, chain.size());
    }

    private static Beat mixedChain() {
        Beat beat = Beat.drumKit("mixed");
        beat.setBpm(120);
        beat.setSwing(50);
        beat.setSlotSteps('b', 32);
        beat.slot('a')[0].tap(0, false);
        beat.slot('b')[1].tap(0, false);
        beat.slot('b')[1].tap(16, false);
        beat.addChain('a');
        beat.addChain('b');
        return beat;
    }

    private static List<String> noteEvents(Sequence sequence) {
        List<String> events = new ArrayList<>();
        for (javax.sound.midi.Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                MidiEvent event = track.get(i);
                if (event.getMessage() instanceof ShortMessage message
                    && (message.getCommand() == ShortMessage.NOTE_ON || message.getCommand() == ShortMessage.NOTE_OFF)
                    && message.getData1() != SequenceBuilder.CLICK_ACCENT_NOTE
                    && message.getData1() != SequenceBuilder.CLICK_NOTE) {
                    String kind = message.getCommand() == ShortMessage.NOTE_ON && message.getData2() > 0 ? "on" : "off";
                    if (message.getCommand() == ShortMessage.NOTE_ON && message.getData2() == 0) {
                        kind = "off";
                    }
                    events.add(event.getTick() + " " + kind + " " + message.getData1());
                }
            }
        }
        events.sort(String::compareTo);
        return events;
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

    private static List<MidiEvent> clicks(Sequence sequence) {
        List<MidiEvent> clicks = new ArrayList<>();
        for (javax.sound.midi.Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                MidiEvent event = track.get(i);
                MidiMessage message = event.getMessage();
                if (message instanceof ShortMessage shortMessage
                    && shortMessage.getCommand() == ShortMessage.NOTE_ON
                    && shortMessage.getData2() > 0
                    && (shortMessage.getData1() == SequenceBuilder.CLICK_ACCENT_NOTE
                        || shortMessage.getData1() == SequenceBuilder.CLICK_NOTE)) {
                    clicks.add(event);
                }
            }
        }
        clicks.sort((left, right) -> Long.compare(left.getTick(), right.getTick()));
        return clicks;
    }

    private static List<Long> ticks(List<MidiEvent> events) {
        return events.stream().map(MidiEvent::getTick).toList();
    }

    private static List<Long> accentTicks(List<MidiEvent> events) {
        List<Long> ticks = new ArrayList<>();
        for (MidiEvent event : events) {
            if (note(event) == SequenceBuilder.CLICK_ACCENT_NOTE) {
                ticks.add(event.getTick());
            }
        }
        return ticks;
    }

    private static int note(MidiEvent event) {
        return ((ShortMessage) event.getMessage()).getData1();
    }
}
