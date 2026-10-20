package hits;

import org.junit.jupiter.api.Test;

import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.ShortMessage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChanceTest {
    @Test
    void newHitsKeepNormalAndLoudVelocitiesAndAlwaysPlay() throws Exception {
        Beat beat = Beat.drumKit("t");
        beat.track(0).tap(0, false);
        beat.track(0).tap(1, true);
        assertEquals(100, beat.track(0).step(0).velocity());
        assertEquals(120, beat.track(0).step(1).velocity());
        assertEquals(100, beat.track(0).step(0).probability());
        assertFalse(beat.track(0).step(0).uncertain());
        assertFalse(beat.hasChance());
        assertFalse(beat.playingChance('a'));
        assertEquals(1, BeatJson.documentVersion(beat));
        assertFalse(BeatJson.write(beat).contains("probability"));

        List<Note> built = notes(SequenceBuilder.build(beat));
        List<Note> played = notes(SequenceBuilder.playback(beat, false));
        assertEquals(built, played);
        assertEquals(100, built.get(0).velocity);
        assertEquals(120, built.get(1).velocity);
        assertEquals(1, SequenceBuilder.playbackPasses(beat, false));
        assertEquals(SequenceBuilder.loopTicks(16), SequenceBuilder.playbackTicks(beat, false));
        assertEquals(SequenceBuilder.loopTicks(16), markerTick(SequenceBuilder.playback(beat, false)));
    }

    @Test
    void legacyBtfHitsStayCertainAtTheStoredVelocity() throws Exception {
        Beat legacy = BtfFormat.read(Files.readAllBytes(Path.of("beats/beat1.btf")), "beat1");
        assertEquals(100, legacy.track(0).step(0).probability());
        assertEquals(1, BeatJson.documentVersion(legacy));
        assertFalse(BeatJson.write(legacy).contains("probability"));
    }

    @Test
    void probabilityIsClampedCopiedAndKeptAcrossTaps() {
        Step step = new Step(true, 90);
        assertEquals(100, step.probability());
        step.setProbability(140);
        assertEquals(100, step.probability());
        step.setProbability(-4);
        assertEquals(0, step.probability());
        step.setProbability(40);
        Step copy = step.copy();
        assertEquals(40, copy.probability());
        assertEquals(90, copy.velocity());
        Step target = new Step();
        target.copyFrom(step);
        assertEquals(40, target.probability());

        Track kick = Beat.drumKit("t").track(0);
        kick.step(0).setProbability(25);
        kick.tap(0, false);
        assertTrue(kick.step(0).on());
        assertEquals(kick.velocity(), kick.step(0).velocity());
        assertEquals(25, kick.step(0).probability());
        kick.tap(0, true);
        assertEquals(kick.accent(), kick.step(0).velocity());
        assertEquals(25, kick.step(0).probability());
        kick.tap(0, false);
        assertFalse(kick.step(0).on());
        assertEquals(25, kick.step(0).probability());
        kick.clearSteps();
        assertEquals(100, kick.step(0).probability());
    }

    @Test
    void probabilityRoundTripsAsAVersion2FieldOnlyWhenItIsNotCertain() {
        Beat beat = Beat.drumKit("Take");
        beat.track(0).step(3).setOn(true);
        beat.track(0).step(3).setVelocity(80);
        beat.track(0).step(3).setProbability(40);
        beat.slot('b')[1].step(2).setOn(false);
        beat.slot('b')[1].step(2).setProbability(0);
        assertTrue(beat.hasChance());
        assertTrue(beat.playingChance('a'));
        assertFalse(beat.playingChance('b'));
        assertEquals(2, BeatJson.documentVersion(beat));

        String json = BeatJson.write(beat);
        assertTrue(json.contains("\"version\": 2"));
        assertTrue(json.contains("\"probability\":40"));
        assertTrue(json.contains("\"probability\":0"));
        assertFalse(json.contains("\"probability\":100"));

        Beat loaded = BeatJson.read(json);
        assertEquals(40, loaded.track(0).step(3).probability());
        assertEquals(80, loaded.track(0).step(3).velocity());
        assertEquals(0, loaded.slot('b')[1].step(2).probability());
        assertFalse(loaded.slot('b')[1].step(2).on());

        loaded.track(0).step(3).setProbability(100);
        loaded.slot('b')[1].step(2).setProbability(100);
        assertEquals(1, BeatJson.documentVersion(loaded));
        assertFalse(BeatJson.write(loaded).contains("probability"));

        Beat both = Beat.drumKit("both");
        both.track(2).step(1).setOn(true);
        both.track(2).step(1).setPitch(62);
        both.track(2).step(1).setProbability(15);
        Beat again = BeatJson.read(BeatJson.write(both));
        assertEquals(62, again.track(2).step(1).pitch());
        assertEquals(15, again.track(2).step(1).probability());

        Beat copied = beat.copy();
        beat.copyBar();
        assertEquals(40, beat.track(0).step(19).probability());
        beat.copyActiveToOther();
        assertEquals(40, beat.slot('b')[0].step(3).probability());
        assertEquals(40, copied.track(0).step(3).probability());
    }

    @Test
    void chanceEditUndoes() {
        Editor editor = new Editor(Beat.drumKit("t"));
        editor.tap(0, 2, false);
        editor.selectStep(0, 2);
        assertEquals(100, editor.beat().track(0).step(2).velocity());
        editor.setStepProbability(33);
        assertEquals(33, editor.beat().track(0).step(2).probability());
        assertTrue(editor.isDirty());
        editor.undo();
        assertEquals(100, editor.beat().track(0).step(2).probability());
        assertTrue(editor.beat().track(0).step(2).on());
        editor.redo();
        assertEquals(33, editor.beat().track(0).step(2).probability());
        editor.selectTrack(1);
        editor.setStepProbability(10);
        assertEquals(100, editor.beat().track(1).step(0).probability());
        assertEquals(33, editor.beat().track(0).step(2).probability());
    }

    @Test
    void rollsAreStableAndExtremesDoNotConsumeTheSequence() {
        assertFalse(Chance.plays(0, null));
        assertFalse(Chance.plays(0, new Random(1)));
        assertTrue(Chance.plays(100, null));
        assertTrue(Chance.plays(50, null));

        Random once = new Random(1);
        boolean first = Chance.plays(40, once);
        Chance.plays(100, once);
        Chance.plays(0, once);
        boolean second = Chance.plays(40, once);

        Random again = new Random(1);
        assertEquals(first, Chance.plays(40, again));
        Chance.plays(100, again);
        Chance.plays(0, again);
        assertEquals(second, Chance.plays(40, again));

        boolean baseline = Chance.plays(50, new Random(1));
        boolean differed = false;
        for (int seed = 2; seed < 40; seed++) {
            if (Chance.plays(50, new Random(seed)) != baseline) {
                differed = true;
                break;
            }
        }
        assertTrue(differed);
    }

    @Test
    void zeroIsSilentAndExportRepeatsTheSameNotes() throws Exception {
        Beat beat = Beat.drumKit("t");
        beat.track(0).tap(0, false);
        beat.track(0).step(0).setVelocity(73);
        beat.track(0).step(0).setProbability(0);
        beat.track(0).tap(4, false);
        beat.track(0).step(4).setVelocity(110);
        beat.track(1).tap(8, false);
        beat.track(1).step(8).setProbability(50);

        List<Note> built = notes(SequenceBuilder.build(beat));
        assertEquals(List.of(4, 8), built.stream().map(note -> note.step(beat)).toList());
        assertEquals(110, built.get(0).velocity);
        assertEquals(100, built.get(1).velocity);

        List<Note> exported = notes(SequenceBuilder.export(beat, ExportOptions.allTracks()));
        List<Note> again = notes(SequenceBuilder.export(beat, ExportOptions.allTracks()));
        assertEquals(exported, again);
        assertTrue(exported.stream().noneMatch(note -> note.step(beat) == 0));
        assertTrue(exported.stream().anyMatch(note -> note.step(beat) == 4 && note.velocity == 110));

        ExportOptions repeats = new ExportOptions(false, false, 32);
        List<Note> repeated = notes(SequenceBuilder.export(beat, repeats));
        assertEquals(repeated, notes(SequenceBuilder.export(beat, repeats)));
        long misses = repeated.stream().filter(note -> note.note == beat.track(1).note()).count();
        assertTrue(misses > 0 && misses < 32, "repeats " + misses);
    }

    @Test
    void playbackRollsEveryPassAndTheFirstPassMatchesExport() throws Exception {
        Beat beat = Beat.drumKit("t");
        beat.track(0).tap(0, false);
        beat.track(0).step(0).setProbability(50);
        beat.track(0).step(0).setVelocity(88);
        beat.track(1).tap(2, false);
        beat.track(1).step(2).setProbability(40);
        beat.track(1).step(2).setVelocity(64);
        beat.track(2).tap(4, false);
        beat.track(2).step(4).setProbability(100);
        beat.track(3).tap(6, false);
        beat.track(3).step(6).setProbability(0);

        assertEquals(Chance.PLAYBACK_PASSES, SequenceBuilder.playbackPasses(beat, false));
        Random roll = new Random(Chance.EXPORT_SEED);
        List<Note> played = notes(SequenceBuilder.playback(beat, false, roll));
        List<Note> same = notes(SequenceBuilder.playback(beat, false, new Random(Chance.EXPORT_SEED)));
        assertEquals(played, same);

        long loop = SequenceBuilder.loopTicks(beat.stepCount());
        assertEquals(loop * Chance.PLAYBACK_PASSES, markerTick(SequenceBuilder.playback(beat, false, new Random(1))));
        List<Note> firstPass = played.stream().filter(note -> note.tick < loop).toList();
        List<Note> exported = notes(SequenceBuilder.export(beat, ExportOptions.matchingPlayback()));
        assertEquals(exported, firstPass);
        assertTrue(firstPass.stream().noneMatch(note -> note.note == beat.track(3).note()));
        assertTrue(firstPass.stream().anyMatch(note -> note.velocity == 100));
        assertTrue(played.stream().filter(note -> note.velocity == 88).count() > 0);
        assertTrue(played.stream().filter(note -> note.velocity == 88).count() < Chance.PLAYBACK_PASSES);

        long hats = played.stream().filter(note -> note.velocity == 100).count();
        assertEquals(Chance.PLAYBACK_PASSES, hats);
    }

    @Test
    void songPlaybackRollsEachEntryAndALongChainStaysBounded() throws Exception {
        Beat beat = Beat.drumKit("t");
        beat.track(0).tap(0, false);
        beat.track(0).step(0).setProbability(50);
        beat.addChain('a');
        beat.addChain('a');
        assertEquals(Chance.PLAYBACK_PASSES, SequenceBuilder.playbackPasses(beat, true));

        List<Note> played = notes(SequenceBuilder.playback(beat, true, new Random(Chance.EXPORT_SEED)));
        long song = Chain.ticks(beat.chain(), beat.stepCount());
        List<Note> first = played.stream().filter(note -> note.tick < song).toList();
        List<Note> exported = notes(SequenceBuilder.export(beat, new ExportOptions(true, false, 1, true)));
        assertEquals(exported, first);
        assertEquals(played, notes(SequenceBuilder.playback(beat, true, new Random(Chance.EXPORT_SEED))));

        List<Chain.Part> parts = new ArrayList<>();
        for (int i = 0; i < Chain.MAX_PARTS; i++) {
            parts.add(new Chain.Part('a', ExportOptions.MAX_REPEATS));
        }
        beat.setChain(parts);
        int sections = Chain.sections(beat.chain());
        assertEquals(Chain.MAX_PARTS * ExportOptions.MAX_REPEATS, sections);
        assertEquals(Chance.MAX_PLAYBACK_SECTIONS / sections, SequenceBuilder.playbackPasses(beat, true));
        assertEquals(Chance.PLAYBACK_PASSES, SequenceBuilder.playbackPasses(beat, false));
        assertEquals(
            SequenceBuilder.loopTicks(beat.stepCount()) * Chance.PLAYBACK_PASSES,
            SequenceBuilder.playbackTicks(beat, false)
        );
    }

    private static List<Note> notes(javax.sound.midi.Sequence sequence) {
        List<Note> notes = new ArrayList<>();
        for (javax.sound.midi.Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                MidiEvent event = track.get(i);
                MidiMessage message = event.getMessage();
                if (message instanceof ShortMessage shortMessage
                    && shortMessage.getCommand() == ShortMessage.NOTE_ON
                    && shortMessage.getData2() > 0) {
                    notes.add(new Note(event.getTick(), shortMessage.getChannel(), shortMessage.getData1(), shortMessage.getData2()));
                }
            }
        }
        notes.sort((left, right) -> {
            int ticks = Long.compare(left.tick, right.tick);
            if (ticks != 0) {
                return ticks;
            }
            return Integer.compare(left.note, right.note);
        });
        return notes;
    }

    private static long markerTick(javax.sound.midi.Sequence sequence) {
        for (javax.sound.midi.Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                MidiEvent event = track.get(i);
                if (event.getMessage() instanceof javax.sound.midi.MetaMessage meta && meta.getType() == 0x06) {
                    return event.getTick();
                }
            }
        }
        throw new AssertionError("missing end marker");
    }

    private record Note(long tick, int channel, int note, int velocity) {
        int step(Beat beat) {
            return SequenceBuilder.stepForTick(tick, beat.stepCount(), beat.swing());
        }
    }
}
