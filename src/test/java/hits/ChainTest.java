package hits;

import org.junit.jupiter.api.Test;

import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChainTest {
    @Test
    void placeFollowsRepeatsSwingAndTheLoop() {
        List<Chain.Part> chain = List.of(new Chain.Part('a', 2), new Chain.Part('b', 1));
        long bar = SequenceBuilder.loopTicks(16);
        assertEquals(3, Chain.sections(chain));
        assertEquals(bar * 3, Chain.ticks(chain, 16));
        assertEquals('a', Chain.slotAt(chain, 0));
        assertEquals('a', Chain.slotAt(chain, 1));
        assertEquals('b', Chain.slotAt(chain, 2));
        assertTrue(Chain.usesBoth(chain));
        assertFalse(Chain.usesBoth(List.of(new Chain.Part('a', 4))));

        assertEquals(new Chain.Place('a', 0, 0), Chain.place(chain, 0, 16, 50));
        assertEquals(new Chain.Place('a', 1, 0), Chain.place(chain, 24, 16, 50));
        assertEquals(new Chain.Place('a', 0, 1), Chain.place(chain, bar, 16, 50));
        assertEquals(new Chain.Place('b', 0, 2), Chain.place(chain, bar * 2, 16, 50));
        assertEquals(new Chain.Place('b', 1, 2), Chain.place(chain, bar * 2 + 36, 16, 75));
        assertEquals(new Chain.Place('a', 0, 0), Chain.place(chain, bar * 3, 16, 50));
        assertEquals(new Chain.Place('b', 15, 2), Chain.place(chain, -1, 16, 50));

        assertEquals(new Chain.Place('a', 1, 0), Chain.place(List.of(), 24, 16, 50));
        assertEquals(32, new Chain.Part('b', 100).repeats());
        assertEquals(1, new Chain.Part('a', 0).repeats());
        assertEquals('a', new Chain.Part('x', 1).slot());
    }

    @Test
    void songExportUsesChainTicksAndIgnoresPatternRepeats() throws Exception {
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
        beat.addChain('a');
        beat.setChainRepeats(0, 2);
        beat.addChain('b');

        long bar = SequenceBuilder.loopTicks(16);
        Sequence song = SequenceBuilder.export(beat, new ExportOptions(false, true, 4, true));
        List<MidiEvent> notes = noteOnEvents(song);
        assertEquals(6, notes.size());
        assertEquals(List.of(0L, bar), ticksOf(notes, Gm.DRUM_CHANNEL, 36));
        assertEquals(List.of(bar * 2), ticksOf(notes, Gm.DRUM_CHANNEL, 38));
        assertEquals(List.of(0L, bar), ticksOf(notes, 6, 36));
        assertEquals(List.of(bar * 2), ticksOf(notes, 6, 48));
        assertEquals(bar * 3, markerTick(song));
        assertEquals(Chain.ticks(beat.chain(), 16), markerTick(song));
        assertTrue(programTicks(song, 33).contains(0L));
        assertTrue(programTicks(song, 33).contains(bar));
        assertTrue(programTicks(song, 40).contains(bar * 2));
        assertEquals(7_500_000L, WavAudio.musicalMicros(song));

        Sequence pattern = SequenceBuilder.export(beat, new ExportOptions(false, false, 1, false));
        assertEquals(bar, markerTick(pattern));
        assertEquals(2, noteOnEvents(pattern).size());
    }

    @Test
    void songExportRespectsEachSlotsMuteAndNames() throws Exception {
        Beat beat = Beat.drumKit("t");
        beat.track(0).tap(0, false);
        beat.track(1).tap(4, false);
        beat.track(0).setSolo(true);
        beat.setActiveSlot('b');
        beat.track(1).setName("Rim");
        beat.track(1).tap(0, false);
        beat.setActiveSlot('a');
        beat.addChain('a');
        beat.addChain('b');

        long bar = SequenceBuilder.loopTicks(16);
        List<MidiEvent> heard = noteOnEvents(SequenceBuilder.export(beat, new ExportOptions(true, false, 1, true)));
        assertEquals(2, heard.size());
        assertEquals(0, heard.get(0).getTick());
        assertEquals(36, ((ShortMessage) heard.get(0).getMessage()).getData1());
        assertEquals(bar, heard.get(1).getTick());
        assertEquals(38, ((ShortMessage) heard.get(1).getMessage()).getData1());

        List<String> names = trackNames(SequenceBuilder.export(beat, new ExportOptions(false, false, 1, true)));
        assertTrue(names.contains("Snare / Rim"));
        List<String> heardNames = trackNames(SequenceBuilder.export(beat, new ExportOptions(true, false, 1, true)));
        assertTrue(heardNames.contains("Rim"));
        assertFalse(heardNames.contains("Snare / Rim"));

        Sequence playback = SequenceBuilder.buildSong(beat);
        assertEquals(bar * 2, markerTick(playback));
        assertEquals(2, noteOnEvents(playback).size());
    }

    @Test
    void chainRoundTripAndVersion1StayCompatible() {
        Beat beat = Beat.drumKit("Take \"one\"");
        beat.track(0).step(1).setOn(true);
        beat.track(0).step(1).setPitch(40);
        beat.addChain('a');
        beat.setChainRepeats(0, 2);
        beat.addChain('b');
        beat.addChain('a');
        assertEquals(2, BeatJson.documentVersion(beat));
        String json = BeatJson.write(beat);
        assertTrue(json.contains("\"version\": 2"));
        assertTrue(json.contains("\"chain\""));
        assertTrue(json.contains("\"pitch\":40"));

        Beat loaded = BeatJson.read(json);
        assertEquals(beat.chain(), loaded.chain());
        assertEquals(40, loaded.track(0).step(1).pitch());
        assertEquals('a', loaded.chain().get(0).slot());
        assertEquals(2, loaded.chain().get(0).repeats());
        assertEquals('b', loaded.chain().get(1).slot());

        Beat copy = loaded.copy();
        copy.removeChain(0);
        assertEquals(3, loaded.chain().size());
        assertEquals(2, copy.chain().size());

        loaded.setChain(List.of());
        loaded.track(0).step(1).clearPitch();
        assertEquals(1, BeatJson.documentVersion(loaded));
        String version1 = BeatJson.write(loaded);
        assertTrue(version1.contains("\"version\": 1"));
        assertFalse(version1.contains("chain"));
        assertFalse(version1.contains("pitch"));

        Beat legacy = BeatJson.read(minimalBeat(1));
        assertFalse(legacy.hasChain());
        assertEquals(1, BeatJson.documentVersion(legacy));
        assertEquals('b', legacy.activeSlot());
        assertTrue(legacy.track(0).step(0).on());
        assertFalse(BeatJson.write(legacy).contains("chain"));

        Beat version2 = BeatJson.read(minimalBeat(2));
        assertFalse(version2.hasChain());
        assertEquals(1, BeatJson.documentVersion(version2));
        assertFalse(BeatJson.write(version2).contains("chain"));

        String withChain = minimalBeat(2).replace(
            "\"slots\"",
            "\"chain\":[{\"slot\":\"b\",\"repeats\":2,\"label\":\"intro\"},{\"slot\":\"nope\",\"repeats\":0}],\"slots\""
        );
        Beat chained = BeatJson.read(withChain);
        assertEquals(List.of(new Chain.Part('b', 2), new Chain.Part('a', 1)), chained.chain());
        assertEquals(2, BeatJson.documentVersion(chained));
        Beat again = BeatJson.read(BeatJson.write(chained));
        assertEquals(chained.chain(), again.chain());

        assertThrows(IllegalArgumentException.class, () -> BeatJson.read(minimalBeat(3)));
        assertThrows(IllegalArgumentException.class, () -> BeatJson.read(
            minimalBeat(2).replace("\"slots\"", "\"chain\":[{\"slot\":\"a\"}],\"slots\"")
        ));
    }

    @Test
    void chainEditsUndoAndSongViewDoesNotDirtyTheBeat() {
        Editor editor = new Editor(Beat.drumKit("t"));
        editor.edit(beat -> assertTrue(beat.addChain('a')));
        editor.edit(beat -> beat.addChain('b'));
        editor.edit(beat -> beat.setChainRepeats(0, 3));
        editor.edit(beat -> beat.moveChain(1, -1));
        assertEquals(List.of(new Chain.Part('b', 1), new Chain.Part('a', 3)), editor.beat().chain());
        editor.undo();
        assertEquals('a', editor.beat().chain().get(0).slot());
        assertEquals(3, editor.beat().chain().get(0).repeats());
        editor.undo();
        assertEquals(1, editor.beat().chain().get(0).repeats());
        editor.undo();
        assertEquals(1, editor.beat().chain().size());
        editor.undo();
        assertFalse(editor.beat().hasChain());
        editor.redo();
        assertEquals(List.of(new Chain.Part('a', 1)), editor.beat().chain());

        Beat full = Beat.drumKit("full");
        for (int i = 0; i < Chain.MAX_PARTS; i++) {
            assertTrue(full.addChain(i % 2 == 0 ? 'a' : 'b'));
        }
        assertFalse(full.addChain('a'));
        assertEquals(Chain.MAX_PARTS, full.chain().size());

        editor.beat().slot('b')[0].tap(0, false);
        editor.markClean();
        editor.setSongMode(true);
        editor.showPlayingSlot('b');
        assertEquals('b', editor.viewSlot());
        assertEquals('a', editor.beat().activeSlot());
        assertFalse(editor.isDirty());
        editor.tap(0, 1, false);
        assertTrue(editor.beat().slot('b')[0].step(1).on());
        assertFalse(editor.beat().slot('a')[0].step(1).on());
        assertTrue(editor.isDirty());
        editor.holdSlot('a');
        editor.showPlayingSlot('b');
        assertEquals('a', editor.viewSlot());
        assertTrue(editor.holding());
        editor.followAgain();
        assertFalse(editor.holding());
        editor.showPlayingSlot('b');
        assertEquals('b', editor.viewSlot());
        editor.stopped();
        assertEquals('a', editor.viewSlot());
        editor.undo();
        assertFalse(editor.beat().slot('b')[0].step(1).on());
        assertTrue(editor.songMode());

        editor.setSongMode(false);
        editor.showPlayingSlot('b');
        editor.tap(0, 2, false);
        assertTrue(editor.beat().slot('a')[0].step(2).on());
        assertFalse(editor.beat().slot('b')[0].step(2).on());
    }

    @Test
    void copyTargetsTheSlotOnScreenWithoutChangingTheSavedPattern() {
        Beat beat = Beat.drumKit("t");
        beat.slot('b')[0].tap(0, false);
        beat.slot('b')[0].tap(3, true);
        int accent = beat.slot('b')[0].step(3).velocity();
        beat.copyBar('b');
        assertEquals('a', beat.activeSlot());
        assertEquals(32, beat.stepCount());
        assertTrue(beat.slot('b')[0].step(16).on());
        assertTrue(beat.slot('b')[0].step(19).on());
        assertEquals(accent, beat.slot('b')[0].step(19).velocity());
        assertFalse(beat.slot('a')[0].step(16).on());

        beat.copySlotToOther('b');
        assertEquals('a', beat.activeSlot());
        assertTrue(beat.slot('a')[0].step(0).on());
        assertTrue(beat.slot('a')[0].step(16).on());
        assertTrue(beat.slot('b')[0].step(0).on());
    }

    private static String minimalBeat(int version) {
        StringBuilder tracks = new StringBuilder();
        for (int i = 0; i < Beat.TRACKS; i++) {
            if (i > 0) {
                tracks.append(',');
            }
            String steps = i == 0
                ? "{\"on\":true,\"velocity\":90}"
                : "{\"on\":false,\"velocity\":100}";
            tracks.append("{\"name\":\"T").append(i)
                .append("\",\"mode\":\"drum\",\"program\":0,\"note\":").append(36 + i)
                .append(",\"mute\":false,\"solo\":false,\"gate\":45,\"velocity\":100,\"accent\":120,\"steps\":[")
                .append(steps).append("]}");
        }
        return "{\"version\":" + version
            + ",\"name\":\"old\",\"bpm\":90,\"swing\":52,\"steps\":16,\"active\":\"b\",\"slots\":{"
            + "\"a\":{\"tracks\":[" + tracks + "]},\"b\":{\"tracks\":[" + tracks + "]}}}";
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
