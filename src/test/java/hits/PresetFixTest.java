package hits;

import hits.cli.HitsCli;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.midi.MidiEvent;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Soundbank;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Retest of the pitched presets: gate cuts the ring, a missing kit is audible,
 * the step pitch and mixer follow the pitch that plays, Note mode drops the preset,
 * and drum key 40 has a name.
 */
class PresetFixTest {
    @TempDir
    Path temp;

    @Test
    void gateScalesPresetReleaseAndReverb() {
        assertEquals(54, ChannelPlan.presetRelease(5));
        assertEquals(0, ChannelPlan.presetReverb(5));
        assertEquals(64, ChannelPlan.presetRelease(45));
        assertEquals(40, ChannelPlan.presetReverb(45));
        assertEquals(80, ChannelPlan.presetRelease(100));
        assertEquals(80, ChannelPlan.presetReverb(100));
        assertEquals(ChannelPlan.presetRelease(5), ChannelPlan.presetRelease(0));
        assertEquals(ChannelPlan.presetRelease(100), ChannelPlan.presetRelease(127));
    }

    @Test
    void presetExportSendsTheGateControllers() throws Exception {
        assertEquals(54, controller(presetBeat(PitchedPreset.RIM, 5), 0, 72));
        assertEquals(0, controller(presetBeat(PitchedPreset.RIM, 5), 0, 91));
        assertEquals(64, controller(presetBeat(PitchedPreset.CRACK, 45), 0, 72));
        assertEquals(40, controller(presetBeat(PitchedPreset.CRACK, 45), 0, 91));
        assertEquals(80, controller(presetBeat(PitchedPreset.CLICK, 100), 0, 72));
        assertEquals(80, controller(presetBeat(PitchedPreset.CLICK, 100), 0, 91));

        Beat plain = Beat.drumKit("plain");
        plain.track(0).tap(0, false);
        Sequence drums = SequenceBuilder.export(plain, ExportOptions.matchingPlayback());
        assertFalse(hasController(drums, 72), "a kit drum must not send release");
        assertFalse(hasController(drums, 91), "a kit drum must not send reverb");
    }

    @Test
    void aLaterNoteClearsThePresetRelease() throws Exception {
        Beat beat = Beat.drumKit("shape");
        beat.track(0).setPreset(PitchedPreset.RIM);
        beat.track(0).setGate(5);
        beat.track(0).tap(0, false);
        beat.slot('b')[0].setMode(TrackMode.NOTE);
        beat.slot('b')[0].setProgram(0);
        beat.slot('b')[0].setNote(60);
        beat.slot('b')[0].tap(0, false);
        beat.addChain('a');
        beat.addChain('b');

        Sequence song = SequenceBuilder.export(beat, new ExportOptions(true, false, 1, true));
        long bar = SequenceBuilder.loopTicks(beat.stepCount('a'));
        assertEquals(54, controllerAt(song, 0, 72, 0));
        assertEquals(0, controllerAt(song, 0, 91, 0));
        assertEquals(64, controllerAt(song, 0, 72, bar));
        assertEquals(0, controllerAt(song, 0, 91, bar));
        assertEquals(108, noteAt(song, 0, 0));
        assertEquals(60, noteAt(song, 0, bar));
    }

    @Test
    void gateShortensRimAndCrackOnTheBuiltinBank() throws Exception {
        Decay rim5 = decay(PitchedPreset.RIM, 5);
        Decay rim45 = decay(PitchedPreset.RIM, 45);
        Decay rim100 = decay(PitchedPreset.RIM, 100);
        Decay crack5 = decay(PitchedPreset.CRACK, 5);
        Decay crack45 = decay(PitchedPreset.CRACK, 45);
        Decay crack100 = decay(PitchedPreset.CRACK, 100);
        Decay click100 = decay(PitchedPreset.CLICK, 100);
        System.out.println("P1 rim gate 5: " + rim5);
        System.out.println("P1 rim gate 45: " + rim45);
        System.out.println("P1 rim gate 100: " + rim100);
        System.out.println("P1 crack gate 5: " + crack5);
        System.out.println("P1 crack gate 45: " + crack45);
        System.out.println("P1 crack gate 100: " + crack100);
        System.out.println("P1 click gate 100: " + click100);

        assertTrue(rim5.peakDbfs > -35, "short rim was silent: " + rim5);
        assertTrue(crack5.peakDbfs > -35, "short crack was silent: " + crack5);
        assertTrue(rim5.below40 < 150, rim5.toString());
        assertTrue(rim45.below40 > 250 && rim45.below40 < 550, rim45.toString());
        assertTrue(rim100.below40 > 550, rim100.toString());
        assertTrue(rim5.below40 < rim45.below40 && rim45.below40 < rim100.below40, "rim gates did not lengthen");
        assertTrue(crack5.below40 < 160, crack5.toString());
        assertTrue(crack45.below40 > 180 && crack45.below40 < 400, crack45.toString());
        assertTrue(crack100.below40 > 400, crack100.toString());
        assertTrue(crack5.below40 < crack45.below40 && crack45.below40 < crack100.below40, "crack gates did not lengthen");
        assertTrue(click100.below40 < 120, click100.toString());
    }

    @Test
    void aMissingKitPlaysStandardAndWarns() throws Exception {
        Soundbank bank = SoundCatalog.builtin();
        Assumptions.assumeTrue(bank != null, "no built-in soundbank");
        assertTrue(SoundCatalog.hasKit(bank, 0));
        assertFalse(SoundCatalog.hasKit(bank, 8));
        assertFalse(SoundCatalog.hasKit(bank, 25));
        assertEquals(8, SoundCatalog.playedKit(null, 8));
        assertEquals(0, SoundCatalog.playedKit(bank, 8));
        assertEquals(0, SoundCatalog.playedKit(bank, 25));

        Beat center = kitBeat(8, Track.PAN_CENTER);
        Sequence heard = SequenceBuilder.export(center, ExportOptions.matchingPlayback(), bank);
        assertEquals(Gm.DRUM_CHANNEL, noteChannel(heard, 36));
        assertFalse(hasProgram(heard, 8));
        assertFalse(hasController(heard, 0));

        Beat panned = kitBeat(8, 20);
        Sequence side = SequenceBuilder.export(panned, ExportOptions.matchingPlayback(), bank);
        assertEquals(0, program(side, 0));
        assertEquals(Gm.DRUM_BANK, controller(side, 0, 0));
        assertNotEquals(8, program(side, 0));

        Sequence filed = SequenceBuilder.export(panned, ExportOptions.matchingPlayback());
        assertEquals(8, program(filed, 0));
        assertTrue(BeatJson.write(panned).contains("\"kit\": 8"));

        assertAudible(heard, "center kit 8");
        assertAudible(SequenceBuilder.export(kitBeat(25, 20), ExportOptions.matchingPlayback(), bank), "panned kit 25");

        Path json = temp.resolve("kit8.json");
        Files.writeString(json, BeatJson.write(center));
        ByteArrayOutputStream info = new ByteArrayOutputStream();
        assertEquals(0, HitsCli.run(
            new String[] {"info", json.toString()},
            new PrintStream(info, true, StandardCharsets.UTF_8),
            new PrintStream(new ByteArrayOutputStream())
        ));
        String text = info.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("kit 8 is not in this bank, plays Standard"), text);
        assertTrue(text.contains("Snare, kit 8") || text.contains("Kick, kit 8") || text.contains("kit 8"), text);
    }

    @Test
    void allPatternsIgnoresTheHiddenDrumKey() {
        Beat beat = Beat.drumKit("link");
        Track left = beat.track(0);
        left.setName("Clicks");
        left.setNote(76);
        left.setPreset(PitchedPreset.CLICK);
        Track right = beat.slot('b')[0];
        right.setName("Clicks");
        right.setNote(77);
        right.setPreset(PitchedPreset.CLICK);

        Mix.setLevel(beat, 'a', 0, 40, true);
        assertEquals(40, left.level());
        assertEquals(40, right.level());

        right.setPreset(PitchedPreset.RIM);
        Mix.setLevel(beat, 'a', 0, 80, true);
        assertEquals(80, left.level());
        assertEquals(40, right.level());

        Track hat = beat.track(1);
        hat.setName("Hat");
        hat.setNote(42);
        Track otherHat = beat.slot('b')[1];
        otherHat.setName("Hat");
        otherHat.setNote(46);
        Mix.setLevel(beat, 'a', 1, 30, true);
        assertEquals(30, hat.level());
        assertEquals(Track.LEVEL, otherHat.level());
    }

    @Test
    void noteModeClearsThePreset() throws Exception {
        Beat beat = Beat.drumKit("mode");
        Track track = beat.track(0);
        track.setPreset(PitchedPreset.CLICK);
        track.setMode(TrackMode.NOTE);
        assertNull(track.preset());
        assertFalse(BeatJson.write(beat).contains("\"preset\""));
        track.setPreset(PitchedPreset.CLICK);
        assertNull(track.preset());
        track.setMode(TrackMode.DRUM);
        assertNull(track.preset());
        assertTrue(ChannelPlan.sharesDrumChannel(track));
        assertEquals(Gm.DRUM_CHANNEL, ChannelPlan.of(beat, java.util.List.of('a')).channel(track, 0));

        String stale = BeatJson.write(beat).replaceFirst(
            "\"mode\": \"drum\"",
            "\"mode\": \"note\",\n          \"preset\": \"click\""
        );
        Beat loaded = BeatJson.read(stale);
        assertEquals(TrackMode.NOTE, loaded.track(0).mode());
        assertNull(loaded.track(0).preset());
        assertFalse(BeatJson.write(loaded).contains("\"preset\""));
    }

    @Test
    void drumKey40HasAName() {
        assertEquals("Electric Snare  E1", Gm.drumChoice(40));
        assertNotEquals(Notes.name(40), Gm.drumChoice(40));
        assertEquals("Snare", Gm.drumName(40));
        for (int note : Gm.DRUM_NOTES) {
            assertNotEquals(40, note);
        }
    }

    private Sequence presetBeat(PitchedPreset preset, int gate) throws Exception {
        Beat beat = Beat.drumKit(preset.json() + gate);
        beat.setSlotSteps('a', 1);
        beat.track(0).clearSteps();
        beat.track(0).setPreset(preset);
        beat.track(0).setGate(gate);
        beat.track(0).tap(0, false);
        return SequenceBuilder.export(beat, ExportOptions.matchingPlayback());
    }

    private static Beat kitBeat(int kit, int pan) {
        Beat beat = Beat.drumKit("kit-" + kit + "-" + pan);
        beat.setSlotSteps('a', 1);
        for (int row = 0; row < Beat.TRACKS; row++) {
            beat.track(row).clearSteps();
        }
        beat.track(0).setKit(kit);
        beat.track(0).setPan(pan);
        beat.track(0).tap(0, false);
        return beat;
    }

    private Decay decay(PitchedPreset preset, int gate) throws Exception {
        Beat beat = Beat.drumKit(preset.json() + "-gate-" + gate);
        beat.setBpm(120);
        beat.setSwing(50);
        beat.setSlotSteps('a', 1);
        for (int row = 0; row < Beat.TRACKS; row++) {
            beat.track(row).clearSteps();
        }
        beat.track(0).setPreset(preset);
        beat.track(0).setGate(gate);
        beat.track(0).tap(0, false);
        Path wav = temp.resolve(preset.json() + "-" + gate + ".wav");
        try {
            WavRenderer.render(SequenceBuilder.export(beat, ExportOptions.matchingPlayback()), wav, null, null, null);
        } catch (Exception exception) {
            if (WavRenderer.unavailable(exception)) {
                Assumptions.abort("offline render is not available: " + exception.getMessage());
            }
            throw exception;
        }
        return Decay.of(mono(wav));
    }

    private void assertAudible(Sequence sequence, String name) throws Exception {
        Path wav = temp.resolve(name.replace(' ', '-') + ".wav");
        try {
            WavRenderer.render(sequence, wav, null, null, null);
        } catch (Exception exception) {
            if (WavRenderer.unavailable(exception)) {
                Assumptions.abort("offline render is not available: " + exception.getMessage());
            }
            throw exception;
        }
        double[] samples = mono(wav);
        double sum = 0;
        int counted = Math.min(samples.length, WavAudio.SAMPLE_RATE / 10);
        for (int i = 0; i < counted; i++) {
            sum += samples[i] * samples[i];
        }
        double rms = Math.sqrt(sum / Math.max(1, counted));
        assertTrue(rms > 0.02, name + " was silent, rms " + rms);
    }

    private static double[] mono(Path wav) throws Exception {
        try (AudioInputStream in = AudioSystem.getAudioInputStream(wav.toFile())) {
            byte[] bytes = in.readAllBytes();
            int frames = bytes.length / 4;
            double[] samples = new double[frames];
            for (int i = 0; i < frames; i++) {
                int left = (bytes[i * 4] & 0xff) | (bytes[i * 4 + 1] << 8);
                int right = (bytes[i * 4 + 2] & 0xff) | (bytes[i * 4 + 3] << 8);
                samples[i] = ((short) left + (short) right) / 65536.0;
            }
            return samples;
        }
    }

    private static int controller(Sequence sequence, int channel, int controller) {
        Integer value = null;
        int count = 0;
        for (MidiEvent event : events(sequence)) {
            if (!(event.getMessage() instanceof ShortMessage message) || message.getCommand() != ShortMessage.CONTROL_CHANGE) {
                continue;
            }
            if (message.getChannel() == channel && message.getData1() == controller) {
                value = message.getData2();
                count++;
            }
        }
        assertEquals(1, count, "controller " + controller + " on " + channel);
        return value;
    }

    private static int controllerAt(Sequence sequence, int channel, int controller, long tick) {
        for (MidiEvent event : events(sequence)) {
            if (event.getTick() != tick || !(event.getMessage() instanceof ShortMessage message)) {
                continue;
            }
            if (message.getCommand() == ShortMessage.CONTROL_CHANGE
                && message.getChannel() == channel
                && message.getData1() == controller) {
                return message.getData2();
            }
        }
        throw new AssertionError("missing controller " + controller + " at " + tick);
    }

    private static boolean hasController(Sequence sequence, int controller) {
        for (MidiEvent event : events(sequence)) {
            if (event.getMessage() instanceof ShortMessage message
                && message.getCommand() == ShortMessage.CONTROL_CHANGE
                && message.getData1() == controller) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasProgram(Sequence sequence, int program) {
        for (MidiEvent event : events(sequence)) {
            if (event.getMessage() instanceof ShortMessage message
                && message.getCommand() == ShortMessage.PROGRAM_CHANGE
                && message.getData1() == program) {
                return true;
            }
        }
        return false;
    }

    private static int program(Sequence sequence, int channel) {
        for (MidiEvent event : events(sequence)) {
            if (event.getMessage() instanceof ShortMessage message
                && message.getCommand() == ShortMessage.PROGRAM_CHANGE
                && message.getChannel() == channel) {
                return message.getData1();
            }
        }
        throw new AssertionError("missing program on " + channel);
    }

    private static int noteChannel(Sequence sequence, int note) {
        for (MidiEvent event : events(sequence)) {
            if (event.getMessage() instanceof ShortMessage message
                && message.getCommand() == ShortMessage.NOTE_ON
                && message.getData1() == note
                && message.getData2() > 0) {
                return message.getChannel();
            }
        }
        throw new AssertionError("missing note " + note);
    }

    private static int noteAt(Sequence sequence, int channel, long tick) {
        for (MidiEvent event : events(sequence)) {
            if (event.getTick() != tick || !(event.getMessage() instanceof ShortMessage message)) {
                continue;
            }
            if (message.getCommand() == ShortMessage.NOTE_ON
                && message.getChannel() == channel
                && message.getData2() > 0) {
                return message.getData1();
            }
        }
        throw new AssertionError("missing note on " + channel + " at " + tick);
    }

    private static java.util.List<MidiEvent> events(Sequence sequence) {
        java.util.List<MidiEvent> events = new java.util.ArrayList<>();
        for (javax.sound.midi.Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                events.add(track.get(i));
            }
        }
        return events;
    }

    /** Peak and the first 10 ms window that sits under −20 dB and −40 dB. */
    private record Decay(double peakDbfs, int below20, int below40) {
        static Decay of(double[] samples) {
            double peak = 0;
            for (double sample : samples) {
                peak = Math.max(peak, Math.abs(sample));
            }
            double db = peak <= 0 ? -240 : 20 * Math.log10(peak);
            int onset = 0;
            double started = peak * 0.1;
            for (int i = 0; i < samples.length; i++) {
                if (Math.abs(samples[i]) >= started) {
                    onset = i;
                    break;
                }
            }
            return new Decay(db, firstBelow(samples, peak, onset, -20), firstBelow(samples, peak, onset, -40));
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "peak %.1f dBFS, -20 %d ms, -40 %d ms", peakDbfs, below20, below40);
        }

        private static int firstBelow(double[] samples, double peak, int onset, double db) {
            if (peak <= 0) {
                return 0;
            }
            double threshold = peak * Math.pow(10, db / 20.0);
            int window = WavAudio.SAMPLE_RATE / 100;
            for (int i = Math.max(0, onset); i < samples.length; i++) {
                int end = Math.min(samples.length, i + window);
                double max = 0;
                for (int j = i; j < end; j++) {
                    max = Math.max(max, Math.abs(samples[j]));
                }
                if (max < threshold) {
                    return (int) Math.round((i - onset) * 1000.0 / WavAudio.SAMPLE_RATE);
                }
            }
            return (int) Math.round((samples.length - onset) * 1000.0 / WavAudio.SAMPLE_RATE);
        }
    }
}
