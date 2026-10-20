package hits;

import org.junit.jupiter.api.Test;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MixTest {
    @Test
    void unityLeavesTheHitAndSilenceClamps() {
        assertEquals(100, Mix.velocity(100, Track.LEVEL, Beat.MASTER));
        assertEquals(50, Mix.velocity(100, 50, Beat.MASTER));
        assertEquals(50, Mix.velocity(100, Track.LEVEL, 64));
        assertEquals(127, Mix.velocity(127, 127, Beat.MASTER));
        assertEquals(0, Mix.velocity(100, 0, Beat.MASTER));
        assertEquals(0, Mix.velocity(100, Track.LEVEL, 0));
        assertEquals(0, Mix.velocity(1, 1, 1));
        assertEquals("C", Mix.panText(64));
        assertEquals("L20", Mix.panText(44));
        assertEquals("R10", Mix.panText(74));
        assertEquals("L64", Mix.panText(-4));
        assertEquals("R63", Mix.panText(200));
    }

    @Test
    void defaultsStayOnTheDrumChannelAndOutOfTheFile() throws Exception {
        Beat beat = Beat.drumKit("plain");
        beat.track(0).tap(0, false);
        beat.track(0).setMute(true);
        beat.track(1).setSolo(true);
        String json = BeatJson.write(beat);
        assertEquals(1, BeatJson.documentVersion(beat));
        assertTrue(json.contains("\"version\": 1"));
        assertTrue(json.contains("\"mute\": true"));
        assertTrue(json.contains("\"solo\": true"));
        assertFalse(json.contains("\"pan\""));
        assertFalse(json.contains("\"level\""));
        assertFalse(json.contains("\"master\""));
        assertCentredDrumPan(SequenceBuilder.build(beat));

        beat.track(0).setMute(false);
        beat.track(1).setSolo(false);
        Sequence sequence = SequenceBuilder.build(beat);
        ShortMessage note = noteOns(sequence).get(0);
        assertEquals(Gm.DRUM_CHANNEL, note.getChannel());
        assertEquals(100, note.getData2());
        assertCentredDrumPan(sequence);
    }

    @Test
    void panLevelAndMasterRoundTripAsVersion2() {
        Beat beat = Beat.drumKit("mix");
        beat.track(0).setPan(20);
        beat.track(0).setLevel(80);
        beat.track(0).setPan(200);
        beat.track(3).setLevel(-4);
        beat.setMaster(40);
        beat.setMaster(300);
        assertEquals(127, beat.track(0).pan());
        assertEquals(0, beat.track(3).level());
        assertEquals(127, beat.master());
        beat.track(0).setPan(20);
        beat.track(3).setLevel(40);
        beat.setMaster(90);
        beat.slot('b')[2].setPan(100);

        assertEquals(2, BeatJson.documentVersion(beat));
        String json = BeatJson.write(beat);
        assertTrue(json.contains("\"version\": 2"));
        assertTrue(json.contains("\"pan\": 20"));
        assertTrue(json.contains("\"pan\": 100"));
        assertTrue(json.contains("\"level\": 40"));
        assertTrue(json.contains("\"master\": 90"));
        assertFalse(json.contains("\"level\": 100"));
        assertFalse(json.contains("\"pan\": 64"));

        Beat loaded = BeatJson.read(json);
        assertEquals(20, loaded.track(0).pan());
        assertEquals(80, loaded.track(0).level());
        assertEquals(40, loaded.track(3).level());
        assertEquals(Track.PAN_CENTER, loaded.track(3).pan());
        assertEquals(100, loaded.slot('b')[2].pan());
        assertEquals(90, loaded.master());

        loaded.track(0).setPan(Track.PAN_CENTER);
        loaded.track(0).setLevel(Track.LEVEL);
        loaded.track(3).setLevel(Track.LEVEL);
        loaded.slot('b')[2].setPan(Track.PAN_CENTER);
        loaded.setMaster(Beat.MASTER);
        assertEquals(1, BeatJson.documentVersion(loaded));
        String plain = BeatJson.write(loaded);
        assertTrue(plain.contains("\"version\": 1"));
        assertFalse(plain.contains("\"pan\""));
        assertFalse(plain.contains("\"level\""));
        assertFalse(plain.contains("\"master\""));
    }

    @Test
    void playbackAndExportFollowTheFaders() throws Exception {
        Beat beat = Beat.drumKit("t");
        beat.track(0).tap(0, false);
        beat.track(0).setLevel(50);
        beat.setMaster(64);
        List<ShortMessage> quiet = noteOns(SequenceBuilder.build(beat));
        assertEquals(1, quiet.size());
        assertEquals(25, quiet.get(0).getData2());

        beat.setMaster(0);
        assertTrue(noteOns(SequenceBuilder.build(beat)).isEmpty());
        beat.setMaster(Beat.MASTER);
        beat.track(0).setLevel(Track.LEVEL);

        beat.track(0).setPan(10);
        beat.track(1).tap(0, false);
        beat.track(6).setMode(TrackMode.NOTE);
        beat.track(6).setProgram(33);
        beat.track(6).setNote(60);
        beat.track(6).tap(0, false);
        beat.track(6).setPan(90);

        Sequence sequence = SequenceBuilder.build(beat);
        assertEquals(0, channelOf(noteOns(sequence), 36));
        assertEquals(Gm.DRUM_CHANNEL, channelOf(noteOns(sequence), 38));
        assertEquals(6, channelOf(noteOns(sequence), 60));
        assertEquals(10, control(sequence, 0, 10));
        assertEquals(Gm.DRUM_BANK, control(sequence, 0, 0));
        assertEquals(0, control(sequence, 0, 32));
        assertTrue(programs(sequence, 0).contains(0));
        assertEquals(90, control(sequence, 6, 10));
        assertEquals(0, control(sequence, 6, 0));
        assertEquals(0, control(sequence, 6, 32));
        assertTrue(programs(sequence, 6).contains(33));
        assertTrue(controls(sequence).stream()
            .filter(message -> message.getChannel() == Gm.DRUM_CHANNEL)
            .allMatch(message -> message.getData1() == 10 && message.getData2() == Track.PAN_CENTER));

        beat.track(0).setMute(true);
        assertTrue(noteOns(SequenceBuilder.build(beat)).stream().noneMatch(message -> message.getData1() == 36));
        assertTrue(noteOns(SequenceBuilder.export(beat, null)).stream().noneMatch(message -> message.getData1() == 36));
        List<ShortMessage> ignored = noteOns(SequenceBuilder.export(beat, ExportOptions.allTracks()));
        assertEquals(1, ignored.stream().filter(message -> message.getData1() == 36).count());
        assertEquals(0, channelOf(ignored, 36));

        beat.track(0).setMute(false);
        beat.track(1).setSolo(true);
        List<ShortMessage> solo = noteOns(SequenceBuilder.export(beat, ExportOptions.matchingPlayback()));
        assertEquals(1, solo.size());
        assertEquals(38, solo.get(0).getData1());
        assertEquals(3, noteOns(SequenceBuilder.export(beat, ExportOptions.allTracks())).size());
    }

    @Test
    void aPannedDrumGivesTheChannelBackToTheNoteThatFollows() throws Exception {
        Beat beat = Beat.drumKit("t");
        beat.track(0).tap(0, false);
        beat.track(0).setPan(0);
        beat.setActiveSlot('b');
        beat.track(0).setMode(TrackMode.NOTE);
        beat.track(0).setProgram(40);
        beat.track(0).setNote(48);
        beat.track(0).tap(0, false);
        beat.track(0).setPan(100);
        beat.setActiveSlot('a');

        long bar = SequenceBuilder.loopTicks(16);
        Sequence chained = SequenceBuilder.export(beat, new ExportOptions(false, true, 2));
        List<MidiEvent> notes = noteOnEvents(chained);
        assertEquals(4, notes.size());
        assertEquals(0, notes.get(0).getTick());
        assertEquals(0, ((ShortMessage) notes.get(0).getMessage()).getChannel());
        assertEquals(36, ((ShortMessage) notes.get(0).getMessage()).getData1());
        assertEquals(bar, notes.get(1).getTick());
        ShortMessage second = (ShortMessage) notes.get(1).getMessage();
        assertEquals(0, second.getChannel());
        assertEquals(48, second.getData1());
        assertEquals(100, controlAt(chained, bar, 0, 10));
        assertEquals(0, controlAt(chained, bar, 0, 0));
        assertTrue(programAt(chained, bar, 40));
        assertEquals(bar * 2, notes.get(2).getTick());
        assertEquals(Gm.DRUM_BANK, controlAt(chained, bar * 2, 0, 0));
    }

    @Test
    void soloIsJudgedPerSlotAndMuteStillWins() throws Exception {
        Beat beat = Beat.drumKit("t");
        beat.track(0).tap(0, false);
        beat.track(1).tap(0, false);
        beat.track(0).setMute(true);
        beat.track(0).setSolo(true);
        assertTrue(noteOns(SequenceBuilder.build(beat)).isEmpty());

        beat.track(0).setMute(false);
        assertEquals(36, noteOns(SequenceBuilder.build(beat)).get(0).getData1());
        assertEquals(1, noteOns(SequenceBuilder.build(beat)).size());
    }

    @Test
    void centredPanIsSentAtTheNextPatternBoundary() throws Exception {
        Beat beat = Beat.drumKit("pan");
        beat.track(7).setMode(TrackMode.NOTE);
        beat.track(7).setProgram(38);
        beat.track(7).setNote(36);
        beat.track(7).tap(0, false);
        beat.track(7).setPan(0);
        beat.track(7).setLevel(40);
        beat.setActiveSlot('b');
        beat.track(7).setMode(TrackMode.NOTE);
        beat.track(7).setProgram(38);
        beat.track(7).setNote(40);
        beat.track(7).tap(0, false);
        beat.track(7).setPan(Track.PAN_CENTER);
        beat.track(7).setLevel(Track.LEVEL);
        beat.setActiveSlot('a');
        beat.addChain('a');
        beat.addChain('b');

        long bar = SequenceBuilder.loopTicks(beat.stepCount());
        Sequence exported = SequenceBuilder.export(beat, new ExportOptions(true, false, 1, true));
        assertEquals(0, controlAt(exported, 0, 7, 10));
        assertEquals(Track.PAN_CENTER, controlAt(exported, bar, 7, 10));
        assertEquals(40, velocityAt(exported, 0, 36));
        assertEquals(100, velocityAt(exported, bar, 40));

        Sequence played = SequenceBuilder.playback(beat, true);
        assertEquals(Track.PAN_CENTER, controlAt(played, bar, 7, 10));
        assertEquals(100, velocityAt(played, bar, 40));
    }

    @Test
    void aNoteTrackResetsTheBankOnALaterPlaybackPass() throws Exception {
        Beat beat = Beat.drumKit("bank");
        beat.track(7).setMode(TrackMode.NOTE);
        beat.track(7).setProgram(81);
        beat.track(7).setNote(60);
        beat.track(7).tap(0, false);
        beat.track(7).step(4).setOn(true);
        beat.track(7).step(4).setProbability(50);
        beat.setActiveSlot('b');
        beat.track(7).setMode(TrackMode.DRUM);
        beat.track(7).setNote(37);
        beat.track(7).tap(0, false);
        beat.track(7).setPan(20);
        beat.setActiveSlot('a');
        beat.addChain('a');
        beat.addChain('b');

        assertTrue(SequenceBuilder.playbackPasses(beat, true) > 1);
        Sequence played = SequenceBuilder.playback(beat, true, new java.util.Random(1), false);
        long bar = SequenceBuilder.loopTicks(beat.stepCount());
        long pass2 = bar * Chain.sections(beat.chain());
        assertEquals(Gm.DRUM_BANK, controlAt(played, bar, 7, 0));
        assertBankBeforeProgram(played, pass2, 7, 81);
        assertEquals(Track.PAN_CENTER, controlAt(played, pass2, 7, 10));
    }

    @Test
    void liveChannelStateSendsCentrePanAndANoteBank() throws Exception {
        Beat beat = Beat.drumKit("live");
        beat.track(7).setMode(TrackMode.NOTE);
        beat.track(7).setProgram(81);
        beat.track(7).setNote(60);
        beat.track(7).tap(0, false);
        beat.setActiveSlot('b');
        beat.track(7).setMode(TrackMode.DRUM);
        beat.track(7).setNote(37);
        beat.track(7).tap(0, false);
        beat.track(7).setPan(20);
        beat.setActiveSlot('a');
        beat.addChain('a');
        beat.addChain('b');

        long bar = SequenceBuilder.loopTicks(beat.stepCount());
        java.util.List<ShortMessage> sent = new java.util.ArrayList<>();
        javax.sound.midi.Receiver receiver = new javax.sound.midi.Receiver() {
            @Override
            public void send(javax.sound.midi.MidiMessage message, long timeStamp) {
                if (message instanceof ShortMessage shortMessage) {
                    sent.add(shortMessage);
                }
            }

            @Override
            public void close() {
            }
        };
        SequenceBuilder.playChannelState(receiver, beat, true, bar);
        assertEquals(20, controlValue(sent, 7, 10));
        assertEquals(Gm.DRUM_BANK, controlValue(sent, 7, 0));

        sent.clear();
        beat.slot('b')[7].setMode(TrackMode.NOTE);
        beat.slot('b')[7].setProgram(81);
        beat.slot('b')[7].setPan(Track.PAN_CENTER);
        SequenceBuilder.playChannelState(receiver, beat, true, bar);
        assertEquals(0, controlValue(sent, 7, 0));
        assertEquals(0, controlValue(sent, 7, 32));
        assertEquals(Track.PAN_CENTER, controlValue(sent, 7, 10));
        assertTrue(sent.stream().anyMatch(message ->
            message.getCommand() == ShortMessage.PROGRAM_CHANGE && message.getChannel() == 7 && message.getData1() == 81));
        int programAt = indexOfProgram(sent, 7, 81);
        assertTrue(indexOfControl(sent, 7, 0) < programAt);
        assertTrue(indexOfControl(sent, 7, 32) < programAt);
    }

    @Test
    void levelAndPanCanApplyToEveryPatternAndStillUndo() {
        Beat beat = Beat.drumKit("all");
        Mix.setPan(beat, 'a', 1, 20, true);
        Mix.setLevel(beat, 'a', 1, 80, true);
        for (int i = 0; i < Beat.SLOT_COUNT; i++) {
            Track track = beat.slot(Beat.slotLetter(i))[1];
            assertEquals(20, track.pan());
            assertEquals(80, track.level());
        }
        Mix.setPan(beat, 'c', 1, 100, false);
        Mix.setLevel(beat, 'c', 1, 40, false);
        assertEquals(100, beat.slot('c')[1].pan());
        assertEquals(40, beat.slot('c')[1].level());
        assertEquals(20, beat.slot('a')[1].pan());
        assertEquals(80, beat.slot('b')[1].level());
        assertEquals(20, beat.slot('h')[1].pan());

        Editor editor = new Editor(beat);
        editor.edit(current -> {
            Mix.setPan(current, 'a', 1, 12, true);
            Mix.setLevel(current, 'a', 1, 50, true);
        });
        assertEquals(12, editor.beat().slot('e')[1].pan());
        editor.undo();
        assertEquals(20, editor.beat().slot('e')[1].pan());
        assertEquals(80, editor.beat().slot('e')[1].level());
        assertEquals(100, editor.beat().slot('c')[1].pan());
        editor.redo();
        assertEquals(12, editor.beat().slot('h')[1].pan());
        assertEquals(50, editor.beat().slot('d')[1].level());

        String json = BeatJson.write(editor.beat());
        assertFalse(json.contains("allPatterns"));
        assertTrue(json.contains("\"pan\""));
        Beat plain = Beat.drumKit("plain");
        assertEquals(1, BeatJson.documentVersion(plain));
        assertFalse(BeatJson.write(plain).contains("\"pan\""));
    }

    @Test
    void mixerEditsUndo() {
        Editor editor = new Editor(Beat.drumKit("t"));
        editor.edit(beat -> {
            beat.track(2).setPan(12);
            beat.track(2).setLevel(70);
            beat.setMaster(80);
        });
        editor.toggleMute(2);
        editor.toggleSolo(1);
        assertEquals(12, editor.beat().track(2).pan());
        assertTrue(editor.beat().track(2).mute());
        assertTrue(editor.beat().track(1).solo());
        editor.undo();
        assertFalse(editor.beat().track(1).solo());
        editor.undo();
        assertFalse(editor.beat().track(2).mute());
        editor.undo();
        assertEquals(Track.PAN_CENTER, editor.beat().track(2).pan());
        assertEquals(Track.LEVEL, editor.beat().track(2).level());
        assertEquals(Beat.MASTER, editor.beat().master());
        editor.redo();
        assertEquals(12, editor.beat().track(2).pan());
        assertEquals(70, editor.beat().track(2).level());
        assertEquals(80, editor.beat().master());

        Beat copy = editor.beat().copy();
        copy.track(2).setPan(Track.PAN_CENTER);
        assertEquals(12, editor.beat().track(2).pan());
        assertEquals(Track.PAN_CENTER, copy.track(2).pan());
        assertEquals(80, copy.master());
    }

    private static int channelOf(List<ShortMessage> notes, int note) {
        for (ShortMessage message : notes) {
            if (message.getData1() == note) {
                return message.getChannel();
            }
        }
        throw new AssertionError("missing note " + note);
    }

    private static void assertCentredDrumPan(Sequence sequence) {
        List<ShortMessage> messages = controls(sequence);
        assertFalse(messages.isEmpty());
        for (ShortMessage message : messages) {
            assertEquals(Gm.DRUM_CHANNEL, message.getChannel());
            assertEquals(10, message.getData1());
            assertEquals(Track.PAN_CENTER, message.getData2());
        }
    }

    private static int velocityAt(Sequence sequence, long tick, int note) {
        List<Integer> values = new ArrayList<>();
        for (MidiEvent event : noteOnEvents(sequence)) {
            ShortMessage message = (ShortMessage) event.getMessage();
            if (event.getTick() == tick && message.getData1() == note) {
                values.add(message.getData2());
            }
        }
        assertEquals(1, values.size());
        return values.get(0);
    }

    private static void assertBankBeforeProgram(Sequence sequence, long tick, int channel, int program) {
        boolean bankMsb = false;
        boolean bankLsb = false;
        boolean programSeen = false;
        for (javax.sound.midi.Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                MidiEvent event = track.get(i);
                if (event.getTick() != tick || !(event.getMessage() instanceof ShortMessage message)) {
                    continue;
                }
                if (message.getChannel() != channel) {
                    continue;
                }
                if (message.getCommand() == ShortMessage.CONTROL_CHANGE && message.getData1() == 0) {
                    assertFalse(programSeen);
                    assertEquals(0, message.getData2());
                    bankMsb = true;
                } else if (message.getCommand() == ShortMessage.CONTROL_CHANGE && message.getData1() == 32) {
                    assertFalse(programSeen);
                    assertEquals(0, message.getData2());
                    bankLsb = true;
                } else if (message.getCommand() == ShortMessage.PROGRAM_CHANGE && message.getData1() == program) {
                    programSeen = true;
                }
            }
        }
        assertTrue(bankMsb);
        assertTrue(bankLsb);
        assertTrue(programSeen);
    }

    private static int controlValue(List<ShortMessage> messages, int channel, int controller) {
        List<Integer> values = new ArrayList<>();
        for (ShortMessage message : messages) {
            if (message.getCommand() == ShortMessage.CONTROL_CHANGE
                && message.getChannel() == channel
                && message.getData1() == controller) {
                values.add(message.getData2());
            }
        }
        assertEquals(1, values.size());
        return values.get(0);
    }

    private static int indexOfControl(List<ShortMessage> messages, int channel, int controller) {
        for (int i = 0; i < messages.size(); i++) {
            ShortMessage message = messages.get(i);
            if (message.getCommand() == ShortMessage.CONTROL_CHANGE
                && message.getChannel() == channel
                && message.getData1() == controller) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfProgram(List<ShortMessage> messages, int channel, int program) {
        for (int i = 0; i < messages.size(); i++) {
            ShortMessage message = messages.get(i);
            if (message.getCommand() == ShortMessage.PROGRAM_CHANGE
                && message.getChannel() == channel
                && message.getData1() == program) {
                return i;
            }
        }
        return -1;
    }

    private static int control(Sequence sequence, int channel, int controller) throws InvalidMidiDataException {
        List<Integer> values = new ArrayList<>();
        for (ShortMessage message : controls(sequence)) {
            if (message.getChannel() == channel && message.getData1() == controller) {
                values.add(message.getData2());
            }
        }
        assertEquals(1, values.size());
        return values.get(0);
    }

    private static int controlAt(Sequence sequence, long tick, int channel, int controller) {
        List<Integer> values = new ArrayList<>();
        for (MidiEvent event : events(sequence)) {
            if (event.getTick() != tick || !(event.getMessage() instanceof ShortMessage message)) {
                continue;
            }
            if (message.getCommand() == ShortMessage.CONTROL_CHANGE
                && message.getChannel() == channel
                && message.getData1() == controller) {
                values.add(message.getData2());
            }
        }
        assertEquals(1, values.size());
        return values.get(0);
    }

    private static boolean programAt(Sequence sequence, long tick, int program) {
        for (MidiEvent event : events(sequence)) {
            if (event.getTick() == tick
                && event.getMessage() instanceof ShortMessage message
                && message.getCommand() == ShortMessage.PROGRAM_CHANGE
                && message.getData1() == program) {
                return true;
            }
        }
        return false;
    }

    private static List<Integer> programs(Sequence sequence, int channel) {
        List<Integer> programs = new ArrayList<>();
        for (MidiEvent event : events(sequence)) {
            if (event.getMessage() instanceof ShortMessage message
                && message.getCommand() == ShortMessage.PROGRAM_CHANGE
                && message.getChannel() == channel) {
                programs.add(message.getData1());
            }
        }
        return programs;
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
        for (MidiEvent event : events(sequence)) {
            MidiMessage message = event.getMessage();
            if (message instanceof ShortMessage shortMessage
                && shortMessage.getCommand() == ShortMessage.NOTE_ON
                && shortMessage.getData2() > 0) {
                notes.add(event);
            }
        }
        notes.sort((left, right) -> Long.compare(left.getTick(), right.getTick()));
        return notes;
    }

    private static List<ShortMessage> controls(Sequence sequence) {
        List<ShortMessage> messages = new ArrayList<>();
        for (MidiEvent event : events(sequence)) {
            if (event.getMessage() instanceof ShortMessage message
                && message.getCommand() == ShortMessage.CONTROL_CHANGE) {
                messages.add(message);
            }
        }
        return messages;
    }

    private static List<MidiEvent> events(Sequence sequence) {
        List<MidiEvent> events = new ArrayList<>();
        for (javax.sound.midi.Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                events.add(track.get(i));
            }
        }
        return events;
    }
}
