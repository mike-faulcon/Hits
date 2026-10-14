package hits;

import org.junit.jupiter.api.Test;

import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetronomeTest {
    @Test
    void playbackClicksOnBeatsAndAccentsBeatOne() throws Exception {
        Beat beat = Beat.drumKit("t");
        beat.setSwing(75);
        Sequence sequence = SequenceBuilder.build(beat, true);
        List<MidiEvent> clicks = clicks(sequence);
        assertEquals(List.of(0L, 96L, 192L, 288L), ticks(clicks));
        assertEquals(SequenceBuilder.CLICK_ACCENT_NOTE, note(clicks.get(0)));
        assertEquals(SequenceBuilder.CLICK_ACCENT_VELOCITY, velocity(clicks.get(0)));
        assertEquals(Gm.DRUM_CHANNEL, channel(clicks.get(0)));
        for (int i = 1; i < clicks.size(); i++) {
            assertEquals(SequenceBuilder.CLICK_NOTE, note(clicks.get(i)));
            assertEquals(SequenceBuilder.CLICK_VELOCITY, velocity(clicks.get(i)));
        }
        assertTrue(noteOffTicks(sequence).containsAll(List.of(6L, 102L, 198L, 294L)));
        assertTrue(trackNames(sequence).contains("Click"));
    }

    @Test
    void aSecondBarAccentsItsOwnDownbeat() throws Exception {
        Beat beat = Beat.drumKit("t");
        beat.setStepCount(32);
        List<MidiEvent> clicks = clicks(SequenceBuilder.build(beat, true));
        assertEquals(8, clicks.size());
        assertEquals(SequenceBuilder.CLICK_ACCENT_NOTE, note(clicks.get(0)));
        assertEquals(SequenceBuilder.CLICK_ACCENT_NOTE, note(clicks.get(4)));
        assertEquals(384L, clicks.get(4).getTick());
        assertEquals(SequenceBuilder.CLICK_NOTE, note(clicks.get(5)));
    }

    @Test
    void songPlaybackClicksThroughTheChain() throws Exception {
        Beat beat = Beat.drumKit("t");
        beat.addChain('a');
        beat.setChainRepeats(0, 2);
        List<MidiEvent> clicks = clicks(SequenceBuilder.buildSong(beat, true));
        assertEquals(8, clicks.size());
        assertEquals(0L, clicks.get(0).getTick());
        assertEquals(SequenceBuilder.CLICK_ACCENT_NOTE, note(clicks.get(0)));
        assertEquals(384L, clicks.get(4).getTick());
        assertEquals(SequenceBuilder.CLICK_ACCENT_NOTE, note(clicks.get(4)));
    }

    @Test
    void clicksKeepPlayingWhenThePatternIsMutedOrSoloed() throws Exception {
        Beat beat = Beat.drumKit("t");
        beat.track(0).tap(0, false);
        beat.track(0).setMute(true);
        beat.track(1).tap(4, false);
        beat.track(1).setSolo(true);
        assertEquals(4, clicks(SequenceBuilder.build(beat, true)).size());
        assertEquals(1, noteOns(SequenceBuilder.build(beat, true)));
    }

    @Test
    void playbackWithoutTheMetronomeHasNoClick() throws Exception {
        Beat beat = Beat.drumKit("t");
        beat.track(0).tap(0, false);
        assertTrue(clicks(SequenceBuilder.build(beat)).isEmpty());
        assertTrue(clicks(SequenceBuilder.build(beat, false)).isEmpty());
        assertTrue(clicks(SequenceBuilder.buildSong(beat)).isEmpty());
    }

    @Test
    void exportsLeaveTheClickOut() throws Exception {
        Beat beat = Beat.drumKit("t");
        beat.track(0).tap(0, false);
        beat.addChain('a');
        beat.addChain('b');
        assertTrue(clicks(SequenceBuilder.export(beat, ExportOptions.allTracks())).isEmpty());
        assertTrue(clicks(SequenceBuilder.export(beat, ExportOptions.matchingPlayback())).isEmpty());
        assertTrue(clicks(SequenceBuilder.export(beat, new ExportOptions(false, true, 2))).isEmpty());
        assertTrue(clicks(SequenceBuilder.export(beat, new ExportOptions(true, false, 1, true))).isEmpty());
        assertTrue(clicks(SequenceBuilder.build(beat, true)).size() > 0);
    }

    private static List<MidiEvent> clicks(Sequence sequence) {
        List<MidiEvent> notes = new ArrayList<>();
        for (javax.sound.midi.Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                MidiEvent event = track.get(i);
                MidiMessage message = event.getMessage();
                if (message instanceof ShortMessage shortMessage
                    && shortMessage.getCommand() == ShortMessage.NOTE_ON
                    && shortMessage.getData2() > 0
                    && (shortMessage.getData1() == SequenceBuilder.CLICK_ACCENT_NOTE
                        || shortMessage.getData1() == SequenceBuilder.CLICK_NOTE)) {
                    notes.add(event);
                }
            }
        }
        notes.sort((left, right) -> Long.compare(left.getTick(), right.getTick()));
        return notes;
    }

    private static List<Long> ticks(List<MidiEvent> events) {
        List<Long> ticks = new ArrayList<>();
        for (MidiEvent event : events) {
            ticks.add(event.getTick());
        }
        return ticks;
    }

    private static List<Long> noteOffTicks(Sequence sequence) {
        List<Long> ticks = new ArrayList<>();
        for (javax.sound.midi.Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                MidiEvent event = track.get(i);
                if (event.getMessage() instanceof ShortMessage shortMessage
                    && shortMessage.getCommand() == ShortMessage.NOTE_OFF
                    && (shortMessage.getData1() == SequenceBuilder.CLICK_ACCENT_NOTE
                        || shortMessage.getData1() == SequenceBuilder.CLICK_NOTE)) {
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
                if (track.get(i).getMessage() instanceof javax.sound.midi.MetaMessage meta && meta.getType() == 0x03) {
                    names.add(new String(meta.getData(), java.nio.charset.StandardCharsets.UTF_8));
                }
            }
        }
        return names;
    }

    private static int note(MidiEvent event) {
        return ((ShortMessage) event.getMessage()).getData1();
    }

    private static int velocity(MidiEvent event) {
        return ((ShortMessage) event.getMessage()).getData2();
    }

    private static int channel(MidiEvent event) {
        return ((ShortMessage) event.getMessage()).getChannel();
    }

    private static int noteOns(Sequence sequence) {
        int count = 0;
        for (javax.sound.midi.Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                if (track.get(i).getMessage() instanceof ShortMessage shortMessage
                    && shortMessage.getCommand() == ShortMessage.NOTE_ON
                    && shortMessage.getData2() > 0
                    && shortMessage.getData1() != SequenceBuilder.CLICK_ACCENT_NOTE
                    && shortMessage.getData1() != SequenceBuilder.CLICK_NOTE) {
                    count++;
                }
            }
        }
        return count;
    }
}
