package hits;

import hits.cli.HitsCli;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Note length, legato, and nudge. Version 1 files stay byte-identical. */
class NoteLengthTest {
    @Test
    void defaultsStayVersionOneAndRoundTripOnlyWhenSet(@TempDir Path directory) throws Exception {
        Path plain = Path.of("src/test/resources/slots/ab-plain.json");
        String original = Files.readString(plain);
        assertEquals(original, BeatJson.write(BeatJson.read(original)));
        assertFalse(original.contains("\"len\""));
        assertFalse(original.contains("\"nudge\""));
        assertFalse(original.contains("\"legato\""));
        assertFalse(original.contains("\"shift\""));

        Beat fresh = Beat.drumKit("plain");
        fresh.track(0).tap(0, false);
        fresh.track(0).tap(4, true);
        assertEquals(1, fresh.track(0).step(0).length());
        assertEquals(0, fresh.track(0).step(0).nudge());
        assertFalse(fresh.track(0).legato());
        assertEquals(0, fresh.track(0).shift());
        assertFalse(fresh.hasNoteLength());
        assertFalse(fresh.hasNudge());
        assertEquals(1, BeatJson.documentVersion(fresh));
        String written = BeatJson.write(fresh);
        assertFalse(written.contains("\"len\""));
        assertFalse(written.contains("\"nudge\""));
        assertFalse(written.contains("\"legato\""));
        assertFalse(written.contains("\"shift\""));
        assertEquals(written, BeatJson.write(BeatJson.read(written)));

        Beat beat = Beat.drumKit("held");
        Track bass = beat.track(6);
        bass.setMode(TrackMode.NOTE);
        bass.setLegato(true);
        bass.setShift(12);
        bass.step(1).setOn(true);
        bass.step(1).setLength(4);
        bass.step(1).setNudge(-17);
        bass.step(3).setOn(false);
        bass.step(3).setLength(2);
        bass.step(3).setNudge(0);
        assertTrue(beat.hasNoteLength());
        assertTrue(beat.hasNudge());
        assertEquals(2, BeatJson.documentVersion(beat));
        String json = BeatJson.write(beat);
        assertTrue(json.contains("\"version\": 2"));
        assertTrue(json.contains("\"legato\": true"));
        assertTrue(json.contains("\"shift\": 12"));
        assertTrue(json.contains("\"len\":4"));
        assertTrue(json.contains("\"len\":2"));
        assertTrue(json.contains("\"nudge\":-17"));
        assertFalse(json.contains("\"len\":1"));
        assertFalse(json.contains("\"nudge\":0"));
        assertFalse(json.contains("\"shift\": 0"));

        Beat loaded = BeatJson.read(json);
        assertTrue(loaded.track(6).legato());
        assertEquals(12, loaded.track(6).shift());
        assertEquals(4, loaded.track(6).step(1).length());
        assertEquals(-17, loaded.track(6).step(1).nudge());
        assertFalse(loaded.track(6).step(3).on());
        assertEquals(2, loaded.track(6).step(3).length());
        assertEquals(0, loaded.track(6).step(3).nudge());
        assertEquals(json, BeatJson.write(loaded));

        loaded.track(6).setLegato(false);
        loaded.track(6).setShift(0);
        loaded.track(6).step(1).setLength(1);
        loaded.track(6).step(1).setNudge(0);
        loaded.track(6).step(3).setLength(1);
        assertEquals(1, BeatJson.documentVersion(loaded));
        assertFalse(BeatJson.write(loaded).contains("\"len\""));
        assertFalse(BeatJson.write(loaded).contains("\"nudge\""));

        Step step = new Step(true, 90);
        step.setLength(0);
        assertEquals(1, step.length());
        step.setLength(99);
        assertEquals(32, step.length());
        step.setNudge(80);
        assertEquals(50, step.nudge());
        step.setNudge(-90);
        assertEquals(-50, step.nudge());
        Step copy = step.copy();
        assertEquals(32, copy.length());
        assertEquals(-50, copy.nudge());
        Step target = new Step();
        target.copyFrom(step);
        assertTrue(target.sameAs(step));
        target.setLength(3);
        assertFalse(target.sameAs(step));

        Track track = beat.track(6);
        track.setShift(80);
        assertEquals(50, track.shift());
        Track copied = track.copy();
        assertEquals(50, copied.shift());
        assertTrue(copied.legato());
        assertEquals(4, copied.step(1).length());
        copied.clearSteps();
        assertEquals(1, copied.step(1).length());
        assertEquals(0, copied.step(1).nudge());
        assertTrue(copied.legato());
        assertEquals(50, copied.shift());

        beat.copyBar();
        assertEquals(4, beat.track(6).step(17).length());
        assertEquals(-17, beat.track(6).step(17).nudge());
    }

    @Test
    void extraSlotKeepsLengthAndNudgeThatDifferFromTheKit() {
        Beat beat = Beat.drumKit("slots");
        beat.slot('c')[2].step(4).setLength(3);
        assertEquals(2, BeatJson.documentVersion(beat));
        Beat loaded = BeatJson.read(BeatJson.write(beat));
        assertEquals(3, loaded.slot('c')[2].step(4).length());
        assertTrue(BeatJson.write(loaded).contains("\"c\""));

        Beat nudged = Beat.drumKit("nudge-only");
        nudged.slot('d')[1].step(0).setOn(false);
        nudged.slot('d')[1].step(0).setNudge(-8);
        assertFalse(nudged.slot('d')[1].sameAs(Beat.drumKit("kit").slot('d')[1]));
        Beat again = BeatJson.read(BeatJson.write(nudged));
        assertEquals(-8, again.slot('d')[1].step(0).nudge());
        assertTrue(BeatJson.write(again).contains("\"d\""));
    }

    @Test
    void editsUndo() {
        Editor editor = new Editor(Beat.drumKit("t"));
        editor.tap(6, 2, false);
        editor.selectStep(6, 2);
        editor.setStepLength(6);
        assertEquals(6, editor.beat().track(6).step(2).length());
        editor.undo();
        assertEquals(1, editor.beat().track(6).step(2).length());
        assertTrue(editor.beat().track(6).step(2).on());
        editor.redo();
        assertEquals(6, editor.beat().track(6).step(2).length());

        editor.setLegato(true);
        assertTrue(editor.beat().track(6).legato());
        editor.undo();
        assertFalse(editor.beat().track(6).legato());

        editor.bumpStepNudge(1);
        editor.bumpStepNudge(1);
        assertEquals(2, editor.beat().track(6).step(2).nudge());
        editor.undo();
        assertEquals(1, editor.beat().track(6).step(2).nudge());
        editor.setStepNudge(-40);
        assertEquals(-40, editor.beat().track(6).step(2).nudge());
        editor.undo();
        assertEquals(1, editor.beat().track(6).step(2).nudge());

        editor.setShift(15);
        assertEquals(15, editor.beat().track(6).shift());
        editor.undo();
        assertEquals(0, editor.beat().track(6).shift());

        editor.selectTrack(1);
        editor.setStepLength(4);
        assertEquals(1, editor.beat().track(1).step(0).length());
        assertEquals(6, editor.beat().track(6).step(2).length());
    }

    @Test
    void oneStepNotesMatchThePreviousGate() throws Exception {
        Beat beat = Beat.drumKit("gate");
        beat.setSwing(58);
        beat.setStepCount(16);
        Track kick = beat.track(0);
        kick.setGate(45);
        kick.tap(0, false);
        kick.tap(3, false);
        kick.tap(15, false);
        List<Hit> notes = hits(SequenceBuilder.export(beat, ExportOptions.matchingPlayback()));
        assertEquals(3, notes.size());
        assertEquals(hit(0, oldEnd(0, 16, 58, 45, 0, 384), 9, 36, 100), notes.get(0));
        assertEquals(hit(SequenceBuilder.tickForStep(3, 58), oldEnd(3, 16, 58, 45, 0, 384), 9, 36, 100), notes.get(1));
        assertEquals(hit(SequenceBuilder.tickForStep(15, 58), oldEnd(15, 16, 58, 45, 0, 384), 9, 36, 100), notes.get(2));
        assertEquals(notes, hits(SequenceBuilder.build(beat)));
    }

    @Test
    void longNotesGateOnlyTheLastStepAndLegatoHoldsToTheNextHit() throws Exception {
        Beat beat = noteBeat(75, 50);
        Track bass = beat.track(6);
        bass.step(0).setOn(true);
        bass.step(0).setLength(2);
        bass.step(0).setVelocity(100);
        List<Hit> straight = hits(SequenceBuilder.export(beat, ExportOptions.matchingPlayback()));
        assertEquals(List.of(hit(0, 42, 6, 36, 100)), straight);

        bass.setGate(100);
        assertEquals(List.of(hit(0, 48, 6, 36, 100)), hits(SequenceBuilder.export(beat, ExportOptions.matchingPlayback())));

        beat.setSwing(50);
        bass.setGate(80);
        bass.step(0).setLength(1);
        bass.setLegato(true);
        bass.step(4).setOn(true);
        bass.step(4).setVelocity(90);
        List<Hit> held = hits(SequenceBuilder.export(beat, ExportOptions.matchingPlayback()));
        assertEquals(hit(0, 96, 6, 36, 100), held.get(0));
        assertEquals(hit(96, 379, 6, 36, 90), held.get(1));

        bass.step(0).setLength(8);
        bass.step(2).setOn(true);
        bass.step(2).setVelocity(70);
        List<Hit> tied = hits(SequenceBuilder.export(beat, ExportOptions.matchingPlayback()));
        assertEquals(48, tied.get(0).off());
        assertEquals(48, tied.get(1).on());
        assertEquals(3, tied.size());
    }

    @Test
    void samePitchTruncatesAndADifferentPitchOverlaps() throws Exception {
        Beat beat = noteBeat(50, 100);
        Track bass = beat.track(6);
        bass.step(0).setOn(true);
        bass.step(0).setLength(4);
        bass.step(2).setOn(true);
        bass.step(2).setLength(1);
        List<Hit> same = hits(SequenceBuilder.export(beat, ExportOptions.matchingPlayback()));
        assertEquals(List.of(hit(0, 48, 6, 36, 100), hit(48, 72, 6, 36, 100)), same);

        bass.step(2).setPitch(40);
        List<Hit> both = hits(SequenceBuilder.export(beat, ExportOptions.matchingPlayback()));
        assertEquals(hit(0, 96, 6, 36, 100), both.get(0));
        assertEquals(hit(48, 72, 6, 40, 100), both.get(1));
    }

    @Test
    void aLongNoteCanCrossASectionAndPatternPlaybackDoesNotHang() throws Exception {
        Beat beat = noteBeat(50, 100);
        Track bass = beat.track(6);
        bass.step(14).setOn(true);
        bass.step(14).setLength(4);
        bass.step(14).setVelocity(110);

        List<Hit> exported = hits(SequenceBuilder.export(beat, ExportOptions.matchingPlayback()));
        assertEquals(List.of(hit(336, 432, 6, 36, 110)), exported);

        List<Hit> looped = hits(SequenceBuilder.build(beat));
        assertEquals(List.of(hit(336, 384, 6, 36, 110)), looped);
        assertTrue(looped.stream().allMatch(hit -> hit.off() <= 384 && hit.off() > hit.on()));

        beat.addChain('a');
        beat.addChain('a');
        List<Hit> songFile = hits(SequenceBuilder.export(beat, new ExportOptions(true, false, 1, true)));
        assertEquals(hit(336, 432, 6, 36, 110), songFile.get(0));
        assertEquals(hit(720, 816, 6, 36, 110), songFile.get(1));

        List<Hit> songPlay = hits(SequenceBuilder.buildSong(beat));
        assertEquals(hit(336, 432, 6, 36, 110), songPlay.get(0));
        assertEquals(hit(720, 768, 6, 36, 110), songPlay.get(1));
        assertTrue(songPlay.stream().allMatch(hit -> hit.off() <= 768));

        bass.step(14).setLength(1);
        Beat next = noteBeat(50, 100);
        next.track(6).step(14).setOn(true);
        next.track(6).step(14).setLength(4);
        next.track(6).step(14).setVelocity(110);
        next.slot('b')[6].setMode(TrackMode.NOTE);
        next.slot('b')[6].setNote(36);
        next.slot('b')[6].setGate(100);
        next.slot('b')[6].step(0).setOn(true);
        next.slot('b')[6].step(0).setVelocity(80);
        next.addChain('a');
        next.addChain('b');
        List<Hit> crossed = hits(SequenceBuilder.export(next, new ExportOptions(true, false, 1, true)));
        assertEquals(hit(336, 384, 6, 36, 110), crossed.get(0));
        assertEquals(hit(384, 408, 6, 36, 80), crossed.get(1));
    }

    @Test
    void probabilitySkipsTheWholeNote() throws Exception {
        Beat silent = noteBeat(50, 100);
        silent.track(6).step(0).setOn(true);
        silent.track(6).step(0).setLength(4);
        silent.track(6).step(0).setProbability(0);
        silent.track(6).step(8).setOn(true);
        assertEquals(List.of(hit(192, 216, 6, 36, 100)), hits(SequenceBuilder.export(silent, ExportOptions.matchingPlayback())));

        Beat rolled = noteBeat(50, 100);
        Track bass = rolled.track(6);
        bass.step(0).setOn(true);
        bass.step(0).setLength(6);
        bass.step(0).setProbability(40);
        bass.step(2).setOn(false);
        bass.step(8).setOn(true);
        bass.step(8).setProbability(40);
        Random mirror = new Random(Chance.EXPORT_SEED);
        boolean first = Chance.plays(40, mirror);
        boolean second = Chance.plays(40, mirror);
        List<Hit> notes = hits(SequenceBuilder.export(rolled, ExportOptions.matchingPlayback()));
        int expected = (first ? 1 : 0) + (second ? 1 : 0);
        assertEquals(expected, notes.size());
        if (first) {
            assertEquals(0, notes.get(0).on());
            assertEquals(144, notes.get(0).off());
        }
        if (second) {
            Hit last = notes.get(notes.size() - 1);
            assertEquals(192, last.on());
            assertEquals(216, last.off());
        }
    }

    @Test
    void nudgeFollowsSwingClampsAtTheEndsAndMeetsLength() throws Exception {
        Beat beat = noteBeat(75, 100);
        Track bass = beat.track(6);
        bass.step(1).setOn(true);
        bass.step(1).setNudge(17);
        assertEquals(List.of(hit(40, 48, 6, 36, 100)), hits(SequenceBuilder.export(beat, ExportOptions.matchingPlayback())));

        bass.step(1).setNudge(-25);
        assertEquals(List.of(hit(30, 48, 6, 36, 100)), hits(SequenceBuilder.export(beat, ExportOptions.matchingPlayback())));

        bass.step(1).setOn(false);
        bass.step(1).setNudge(0);
        bass.step(0).setOn(true);
        bass.step(0).setNudge(-50);
        bass.step(0).setLength(4);
        List<Hit> wrapped = hits(SequenceBuilder.build(beat));
        assertEquals(List.of(hit(372, 384, 6, 36, 100)), wrapped);

        bass.step(14).setOn(true);
        bass.step(14).setLength(2);
        bass.step(14).setVelocity(90);
        List<Hit> wrappedOver = hits(SequenceBuilder.build(beat));
        assertEquals(List.of(hit(336, 372, 6, 36, 90), hit(372, 384, 6, 36, 100)), wrappedOver);
        bass.step(14).setOn(false);
        bass.step(14).setLength(1);

        beat.addChain('a');
        beat.addChain('a');
        List<Hit> song = hits(SequenceBuilder.buildSong(beat));
        assertEquals(hit(0, 96, 6, 36, 100), song.get(0));
        assertEquals(hit(372, 480, 6, 36, 100), song.get(1));

        Beat shifted = noteBeat(50, 100);
        Track row = shifted.track(6);
        row.setShift(10);
        row.step(0).setOn(true);
        row.step(0).setNudge(10);
        assertEquals(List.of(hit(5, 24, 6, 36, 100)), hits(SequenceBuilder.export(shifted, ExportOptions.matchingPlayback())));
        row.setShift(40);
        row.step(0).setNudge(30);
        assertEquals(50, row.playedNudge(0));
        assertEquals(List.of(hit(12, 24, 6, 36, 100)), hits(SequenceBuilder.export(shifted, ExportOptions.matchingPlayback())));

        row.setShift(0);
        row.step(0).setNudge(25);
        row.step(0).setLength(3);
        row.setGate(80);
        assertEquals(List.of(hit(6, 67, 6, 36, 100)), hits(SequenceBuilder.export(shifted, ExportOptions.matchingPlayback())));
    }

    @Test
    void dragExportAndCliRenderTheSameHeldNudgedNotes(@TempDir Path directory) throws Exception {
        Beat beat = noteBeat(60, 80);
        beat.setBpm(120);
        Track bass = beat.track(6);
        bass.step(0).setOn(true);
        bass.step(0).setLength(3);
        bass.step(0).setNudge(17);
        bass.step(8).setOn(true);
        bass.setLegato(true);
        beat.addChain('a');
        beat.addChain('b');
        beat.slot('b')[6].setMode(TrackMode.NOTE);
        beat.slot('b')[6].setNote(40);
        beat.slot('b')[6].setGate(80);
        beat.slot('b')[6].step(0).setOn(true);
        beat.slot('b')[6].step(0).setLength(2);

        ExportOptions song = new ExportOptions(true, false, 1, true);
        List<Hit> fromBuilder = hits(SequenceBuilder.export(beat, song));
        assertFalse(fromBuilder.isEmpty());
        assertTrue(fromBuilder.get(0).off() > SequenceBuilder.tickForStep(1, 60));

        Path mid = directory.resolve("held.mid");
        BeatFiles.exportMidi(beat, mid, song);
        assertEquals(fromBuilder, hits(MidiSystem.getSequence(mid.toFile())));

        Path json = directory.resolve("held.json");
        Files.writeString(json, BeatJson.write(beat));
        Path cliMid = directory.resolve("cli.mid");
        Path cliWav = directory.resolve("cli.wav");
        assertEquals(0, run("render", json.toString(), "--song", "--out", cliMid.toString()));
        assertEquals(fromBuilder, hits(MidiSystem.getSequence(cliMid.toFile())));
        assertEquals(0, run("render", json.toString(), "--song", "--out", cliWav.toString()));
        assertTrue(Files.size(cliWav) > 44);
    }

    @Test
    void theGridShowsTheHeldCellsAndTheDragLength() {
        Beat beat = noteBeat(50, 100);
        Track bass = beat.track(6);
        bass.step(0).setOn(true);
        bass.step(0).setLength(4);
        bass.step(2).setOn(true);
        assertEquals(4, NoteSpan.displayEnd(bass, 0, 16));
        assertEquals(0, NoteSpan.holder(bass, 1, 16));
        assertEquals(-1, NoteSpan.holder(bass, 2, 16));
        assertEquals(0, NoteSpan.holder(bass, 3, 16));
        bass.step(2).setOn(false);
        assertEquals(4, NoteSpan.displayEnd(bass, 0, 16));
        assertEquals(0, NoteSpan.holder(bass, 1, 16));
        assertEquals(0, NoteSpan.holder(bass, 3, 16));
        assertEquals(-1, NoteSpan.holder(bass, 4, 16));

        bass.setLegato(true);
        bass.step(6).setOn(true);
        assertEquals(6, NoteSpan.displayEnd(bass, 0, 16));
        assertEquals(0, NoteSpan.holder(bass, 5, 16));
        assertEquals(16, NoteSpan.displayEnd(bass, 6, 16));

        assertEquals(1, NoteSpan.lengthForDrag(4, -1, 16));
        assertEquals(1, NoteSpan.lengthForDrag(4, 4, 16));
        assertEquals(5, NoteSpan.lengthForDrag(2, 6, 16));
        assertEquals(14, NoteSpan.lengthForDrag(2, 20, 16));
    }

    private static Beat noteBeat(int swing, int gate) {
        Beat beat = Beat.drumKit("notes");
        beat.setSwing(swing);
        beat.setStepCount(16);
        Track bass = beat.track(6);
        bass.setMode(TrackMode.NOTE);
        bass.setNote(36);
        bass.setGate(gate);
        return beat;
    }

    /** The gate rule from before note length: one step, ending at the next grid point. */
    private static long oldEnd(int step, int steps, int swing, int gate, long origin, long sectionTicks) {
        long start = origin + SequenceBuilder.tickForStep(step, swing);
        long boundary = step + 1 < steps
            ? origin + SequenceBuilder.tickForStep(step + 1, swing)
            : origin + sectionTicks;
        long length = Math.max(1, Math.round((boundary - start) * (gate / 100.0)));
        if (start + length > origin + sectionTicks) {
            length = Math.max(1, origin + sectionTicks - start);
        }
        return start + length;
    }

    private static Hit hit(long on, long off, int channel, int note, int velocity) {
        return new Hit(on, off, channel, note, velocity);
    }

    private static List<Hit> hits(Sequence sequence) {
        List<MidiEvent> events = new ArrayList<>();
        for (javax.sound.midi.Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                MidiEvent event = track.get(i);
                MidiMessage message = event.getMessage();
                if (message instanceof ShortMessage shortMessage
                    && (shortMessage.getCommand() == ShortMessage.NOTE_ON
                    || shortMessage.getCommand() == ShortMessage.NOTE_OFF)) {
                    events.add(event);
                }
            }
        }
        events.sort((left, right) -> {
            int ticks = Long.compare(left.getTick(), right.getTick());
            if (ticks != 0) {
                return ticks;
            }
            return Integer.compare(rank((ShortMessage) left.getMessage()), rank((ShortMessage) right.getMessage()));
        });
        List<Hit> notes = new ArrayList<>();
        List<Open> open = new ArrayList<>();
        for (MidiEvent event : events) {
            ShortMessage message = (ShortMessage) event.getMessage();
            boolean noteOff = message.getCommand() == ShortMessage.NOTE_OFF
                || (message.getCommand() == ShortMessage.NOTE_ON && message.getData2() == 0);
            if (noteOff) {
                for (int i = 0; i < open.size(); i++) {
                    Open started = open.get(i);
                    if (started.channel == message.getChannel() && started.note == message.getData1()) {
                        notes.add(new Hit(started.tick, event.getTick(), started.channel, started.note, started.velocity));
                        open.remove(i);
                        break;
                    }
                }
            } else if (message.getData2() > 0) {
                open.add(new Open(event.getTick(), message.getChannel(), message.getData1(), message.getData2()));
            }
        }
        assertTrue(open.isEmpty(), "note without a note-off");
        notes.sort((left, right) -> {
            int byStart = Long.compare(left.on, right.on);
            return byStart != 0 ? byStart : Long.compare(left.off, right.off);
        });
        return notes;
    }

    private static int rank(ShortMessage message) {
        if (message.getCommand() == ShortMessage.NOTE_OFF || message.getData2() == 0) {
            return 0;
        }
        return 1;
    }

    private static int run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = HitsCli.run(
            args,
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8)
        );
        if (code != 0) {
            throw new AssertionError(err.toString(StandardCharsets.UTF_8));
        }
        return code;
    }

    private record Hit(long on, long off, int channel, int note, int velocity) {}

    private record Open(long tick, int channel, int note, int velocity) {}
}
