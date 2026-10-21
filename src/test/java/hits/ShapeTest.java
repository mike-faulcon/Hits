package hits;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.midi.MidiEvent;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tune, decay, and the built-in synth's sends and filter.
 * The audio cases are the Gervill spike: a control is here only when a rendered hit moved.
 */
class ShapeTest {
    private static final int FFT = 8192;

    @TempDir
    Path temp;

    @Test
    void defaultsStayOutOfTheFileAndOffTheWire() throws Exception {
        Beat plain = Beat.drumKit("plain");
        assertFalse(plain.hasShape());
        assertEquals(1, BeatJson.documentVersion(plain));
        String json = BeatJson.write(plain);
        assertFalse(json.contains("\"tune\""));
        assertFalse(json.contains("\"decay\""));
        assertFalse(json.contains("\"reverb\""));
        assertFalse(json.contains("\"chorus\""));
        assertFalse(json.contains("\"cutoff\""));
        assertFalse(json.contains("\"resonance\""));

        plain.track(0).tap(0, false);
        Sequence sequence = SequenceBuilder.export(plain, ExportOptions.matchingPlayback());
        assertEquals(Gm.DRUM_CHANNEL, noteChannel(sequence, 36));
        assertFalse(hasController(sequence, ChannelPlan.RELEASE));
        assertFalse(hasController(sequence, ChannelPlan.CUTOFF));
        assertFalse(hasController(sequence, ChannelPlan.RESONANCE));
        assertFalse(hasController(sequence, ChannelPlan.REVERB));
        assertFalse(hasController(sequence, ChannelPlan.CHORUS));
        assertFalse(hasController(sequence, 75));
        assertFalse(hasBend(sequence));
    }

    @Test
    void valuesClampRoundTripAndShowInInfo() throws Exception {
        Track track = new Track("Kick", TrackMode.DRUM, 0, 36);
        track.setTune(3000);
        track.setTuneParts(24, 50);
        assertEquals(Track.TUNE_MAX, track.tune());
        track.setTune(-3000);
        assertEquals(Track.TUNE_MIN, track.tune());
        track.setDecay(5);
        assertEquals(Track.DECAY_MIN, track.decay());
        track.setDecay(500);
        assertEquals(Track.DECAY_MAX, track.decay());
        track.setReverb(200);
        assertEquals(127, track.reverb());
        track.clearReverb();
        assertFalse(track.hasReverb());
        assertEquals(56, Track.decayController(10));
        assertEquals(57, Track.decayController(25));
        assertEquals(64, Track.decayController(100));
        assertEquals(127, Track.decayController(200));
        assertEquals(8192, Track.pitchBend(0));
        assertEquals(10581, Track.pitchBend(700));
        assertEquals(0, Track.pitchBend(-2400));
        assertEquals(16383, Track.pitchBend(2400));

        Beat beat = Beat.drumKit("shaped");
        beat.track(0).setTune(700);
        beat.track(0).setDecay(25);
        beat.track(0).setReverb(0);
        beat.track(0).setChorus(40);
        beat.track(0).setCutoff(12);
        beat.track(0).setResonance(80);
        assertTrue(beat.hasShape());
        assertEquals(2, BeatJson.documentVersion(beat));
        String json = BeatJson.write(beat);
        assertTrue(json.contains("\"tune\": 700"));
        assertTrue(json.contains("\"decay\": 25"));
        assertTrue(json.contains("\"reverb\": 0"));
        assertTrue(json.contains("\"chorus\": 40"));
        assertTrue(json.contains("\"cutoff\": 12"));
        assertTrue(json.contains("\"resonance\": 80"));
        Beat loaded = BeatJson.read(json);
        assertEquals(json, BeatJson.write(loaded));
        assertEquals(700, loaded.track(0).tune());
        assertEquals(25, loaded.track(0).decay());
        assertEquals(0, loaded.track(0).reverb());
        assertEquals(80, loaded.track(0).resonance());
        Track copy = loaded.track(0).copy();
        assertTrue(copy.sameAs(loaded.track(0)));
        copy.setDecay(Track.DECAY);
        assertFalse(copy.sameAs(loaded.track(0)));

        beat.track(0).setTune(0);
        beat.track(0).setDecay(Track.DECAY);
        beat.track(0).clearReverb();
        beat.track(0).clearChorus();
        beat.track(0).clearCutoff();
        beat.track(0).clearResonance();
        assertEquals(1, BeatJson.documentVersion(beat));
        assertEquals(BeatJson.write(Beat.drumKit("shaped")), BeatJson.write(beat));

        Track tuned = new Track("Kick", TrackMode.DRUM, 0, 36);
        tuned.setTune(700);
        tuned.setDecay(25);
        assertEquals("tune +700 cents, decay 25%", tuned.shapeText());
        Track filter = new Track("Snare", TrackMode.DRUM, 0, 38);
        filter.setCutoff(12);
        filter.setResonance(80);
        assertEquals("cutoff 12, resonance 80", filter.shapeText());
        Track sends = new Track("Hat", TrackMode.DRUM, 0, 42);
        sends.setReverb(90);
        sends.setChorus(40);
        assertEquals("reverb 90, chorus 40", sends.shapeText());
        assertEquals("", new Track("Kick", TrackMode.DRUM, 0, 36).shapeText());
    }

    @Test
    void tunedDrumBendsOnItsOwnChannelAndNotesDoToo() throws Exception {
        Beat beat = Beat.drumKit("tune");
        beat.track(0).setTune(700);
        beat.track(0).setNote(45);
        beat.track(0).tap(0, false);
        beat.track(1).setMode(TrackMode.NOTE);
        beat.track(1).setProgram(81);
        beat.track(1).setNote(48);
        beat.track(1).setTune(-700);
        beat.track(1).tap(0, false);
        beat.track(2).setNote(38);
        beat.track(2).tap(0, false);

        Sequence sequence = SequenceBuilder.export(beat, ExportOptions.matchingPlayback());
        assertEquals(0, noteChannel(sequence, 45));
        assertEquals(Gm.DRUM_BANK, controller(sequence, 0, 0));
        assertEquals(24, controller(sequence, 0, 6));
        assertEquals(Track.pitchBend(700), bend(sequence, 0));
        assertEquals(1, noteChannel(sequence, 48));
        assertEquals(0, controller(sequence, 1, 0));
        assertEquals(Track.pitchBend(-700), bend(sequence, 1));
        assertEquals(Gm.DRUM_CHANNEL, noteChannel(sequence, 38));
        assertFalse(hasController(sequence, 75));
        assertFalse(hasControllerOn(sequence, 0, 99));
    }

    @Test
    void explicitDecayAndReverbReplaceTheGate() throws Exception {
        assertEquals(54, controller(preset(5, Track.DECAY, Track.UNSET), 0, ChannelPlan.RELEASE));
        assertEquals(0, controller(preset(5, Track.DECAY, Track.UNSET), 0, ChannelPlan.REVERB));

        Sequence shaped = preset(5, 200, 10);
        assertEquals(Track.decayController(200), controller(shaped, 0, ChannelPlan.RELEASE));
        assertEquals(10, controller(shaped, 0, ChannelPlan.REVERB));
        assertFalse(hasController(shaped, 75));

        Beat beat = Beat.drumKit("clear");
        beat.track(0).setPreset(PitchedPreset.RIM);
        beat.track(0).setGate(5);
        beat.track(0).setDecay(200);
        beat.track(0).setReverb(90);
        beat.track(0).tap(0, false);
        beat.slot('b')[0].setMode(TrackMode.NOTE);
        beat.slot('b')[0].setProgram(0);
        beat.slot('b')[0].setNote(60);
        beat.slot('b')[0].tap(0, false);
        beat.addChain('a');
        beat.addChain('b');
        Sequence song = SequenceBuilder.export(beat, new ExportOptions(true, false, 1, true));
        long bar = SequenceBuilder.loopTicks(beat.stepCount('a'));
        assertEquals(127, controllerAt(song, 0, ChannelPlan.RELEASE, 0));
        assertEquals(90, controllerAt(song, 0, ChannelPlan.REVERB, 0));
        assertEquals(64, controllerAt(song, 0, ChannelPlan.RELEASE, bar));
        assertEquals(0, controllerAt(song, 0, ChannelPlan.REVERB, bar));
    }

    @Test
    void aLaterPatternClearsAStickingFilter() throws Exception {
        Beat beat = Beat.drumKit("filter");
        beat.track(0).setCutoff(10);
        beat.track(0).setResonance(20);
        beat.track(0).setChorus(70);
        beat.track(0).tap(0, false);
        beat.slot('b')[0].setPan(20);
        beat.slot('b')[0].tap(0, false);
        beat.addChain('a');
        beat.addChain('b');
        Sequence song = SequenceBuilder.export(beat, new ExportOptions(true, false, 1, true));
        long bar = SequenceBuilder.loopTicks(beat.stepCount('a'));
        assertEquals(0, noteChannelAt(song, 36, 0));
        assertEquals(0, noteChannelAt(song, 36, bar));
        assertEquals(10, controllerAt(song, 0, ChannelPlan.CUTOFF, 0));
        assertEquals(20, controllerAt(song, 0, ChannelPlan.RESONANCE, 0));
        assertEquals(70, controllerAt(song, 0, ChannelPlan.CHORUS, 0));
        assertEquals(Track.FILTER_NEUTRAL, controllerAt(song, 0, ChannelPlan.CUTOFF, bar));
        assertEquals(Track.FILTER_NEUTRAL, controllerAt(song, 0, ChannelPlan.RESONANCE, bar));
        assertEquals(Track.CHORUS_NEUTRAL, controllerAt(song, 0, ChannelPlan.CHORUS, bar));
    }

    @Test
    void overflowUsesPerKeyMessagesInsteadOfChannelBend() throws Exception {
        boolean[] needs = new boolean[16];
        boolean[] melodic = new boolean[16];
        for (int row = 0; row < needs.length; row++) {
            needs[row] = true;
        }
        ChannelPlan plan = ChannelPlan.assign(16, needs, melodic);
        assertTrue(plan.overflow(15));
        Track drum = new Track("Kick", TrackMode.DRUM, 0, 36);
        drum.setTune(700);
        drum.setReverb(90);
        drum.setCutoff(10);
        List<ShortMessage> messages = plan.setup(drum, Gm.DRUM_CHANNEL, 15);
        assertEquals(36, nrpnKey(messages, 0x18));
        assertEquals(Track.tuneKey(700), nrpnValue(messages, 0x18));
        assertEquals(90, nrpnValue(messages, 0x1D));
        assertFalse(messages.stream().anyMatch(message -> message.getCommand() == ShortMessage.PITCH_BEND));
        assertFalse(messages.stream().anyMatch(message ->
            message.getCommand() == ShortMessage.CONTROL_CHANGE && message.getData1() == ChannelPlan.CUTOFF));
        assertFalse(messages.stream().anyMatch(message ->
            message.getCommand() == ShortMessage.CONTROL_CHANGE && message.getData1() == ChannelPlan.RELEASE));
    }

    @Test
    void allPatternsCopiesSendsAndLeavesADifferentInstrument() {
        Beat beat = Beat.drumKit("sends");
        beat.slot('c')[0].setName("Foot");
        Mix.setReverb(beat, 'a', 0, 90, true);
        Mix.setChorus(beat, 'a', 0, 40, true);
        assertEquals(90, beat.slot('b')[0].reverb());
        assertEquals(40, beat.slot('h')[0].chorus());
        assertFalse(beat.slot('c')[0].hasReverb());
        assertFalse(beat.slot('c')[0].hasChorus());
        Mix.setReverb(beat, 'b', 0, Track.UNSET, false);
        assertFalse(beat.slot('b')[0].hasReverb());
        assertEquals(90, beat.slot('a')[0].reverb());
        Mix.setChorus(beat, 'a', 1, 12, false);
        assertEquals(12, beat.track(1).chorus());
        assertFalse(beat.slot('b')[1].hasChorus());
    }

    @Test
    void tuneShiftsTheSpectralPeak() throws Exception {
        Hearing tom = hearing(hit(45, track -> track.step(0).setLength(4)), "tom");
        Hearing up = hearing(hit(45, track -> {
            track.step(0).setLength(4);
            track.setTune(700);
        }), "tom+700");
        Hearing down = hearing(hit(45, track -> {
            track.step(0).setLength(4);
            track.setTune(-700);
        }), "tom-700");
        Hearing kick = hearing(hit(36, track -> {}), "kick");
        Hearing kickUp = hearing(hit(36, track -> track.setTune(700)), "kick+700");
        System.out.println("tune tom " + tom.peakHz + " +700 " + up.peakHz + " -700 " + down.peakHz);
        System.out.println("tune kick " + kick.peakHz + " +700 " + kickUp.peakHz);
        assertTrue(up.peakHz > tom.peakHz * 1.35, up + " vs " + tom);
        assertTrue(down.peakHz < tom.peakHz * 0.75, down + " vs " + tom);
        assertTrue(kick.peakHz < 75 && kickUp.peakHz > 80, kick + " vs " + kickUp);
    }

    @Test
    void decayChangesTheHatAndANote() throws Exception {
        Hearing shortHat = hearing(hit(46, track -> track.setDecay(25)), "hat25");
        Hearing midHat = hearing(hit(46, track -> {}), "hat100");
        Hearing longHat = hearing(hit(46, track -> track.setDecay(200)), "hat200");
        System.out.println("decay hat -40 dB " + shortHat.below40 + " / " + midHat.below40 + " / " + longHat.below40);
        assertTrue(shortHat.below40 < midHat.below40 - 80, shortHat + " vs " + midHat);
        assertTrue(midHat.below40 > 700 && midHat.below40 < 1200, midHat.toString());
        assertTrue(longHat.below40 > midHat.below40 + 400, longHat.toString());
        assertTrue(shortHat.below40 < midHat.below40 && midHat.below40 < longHat.below40);

        Hearing shortNote = hearing(note(track -> track.setDecay(25)), "note25");
        Hearing longNote = hearing(note(track -> track.setDecay(200)), "note200");
        System.out.println("decay note -20 dB " + shortNote.below20 + " / " + longNote.below20);
        assertTrue(longNote.below20 > shortNote.below20 + 400, shortNote + " vs " + longNote);
    }

    @Test
    void cutoffDropsTheCentroid() throws Exception {
        Hearing open = hearing(hit(38, track -> track.setCutoff(127)), "snare127");
        Hearing closed = hearing(hit(38, track -> track.setCutoff(0)), "snare0");
        Hearing kickOpen = hearing(hit(36, track -> track.setCutoff(127)), "kick127");
        Hearing kickClosed = hearing(hit(36, track -> track.setCutoff(0)), "kick0");
        System.out.println("cutoff snare centroid " + closed.centroid + " / " + open.centroid);
        System.out.println("cutoff kick centroid " + kickClosed.centroid + " / " + kickOpen.centroid);
        assertTrue(closed.centroid < 500, closed.toString());
        assertTrue(open.centroid > 2500, open.toString());
        assertTrue(kickClosed.centroid < 250, kickClosed.toString());
        assertTrue(kickOpen.centroid > 600, kickOpen.toString());
    }

    @Test
    void resonanceChangesAFilteredNote() throws Exception {
        Hearing dull = hearing(note(track -> {
            track.setNote(48);
            track.setCutoff(30);
            track.setResonance(0);
            track.step(0).setLength(8);
        }), "res0");
        Hearing sharp = hearing(note(track -> {
            track.setNote(48);
            track.setCutoff(30);
            track.setResonance(127);
            track.step(0).setLength(8);
        }), "res127");
        System.out.println("resonance body " + dull.body + " / " + sharp.body + " centroid " + dull.centroid + " / " + sharp.centroid);
        assertTrue(sharp.body > dull.body * 1.8, dull + " vs " + sharp);
    }

    @Test
    void reverbExtendsTheTail() throws Exception {
        Hearing dry = hearing(hit(36, track -> track.setReverb(0)), "rev0");
        Hearing wet = hearing(hit(36, track -> track.setReverb(127)), "rev127");
        System.out.println("reverb tail " + dry.tail + " / " + wet.tail + " -40 " + dry.below40 + " / " + wet.below40);
        assertTrue(dry.tail < 0.0008, dry.toString());
        assertTrue(wet.tail > 0.003, wet.toString());
        assertTrue(wet.below40 > dry.below40 + 200, dry + " vs " + wet);
    }

    @Test
    void chorusWidensTheHat() throws Exception {
        Hearing dry = hearing(hit(46, track -> track.setChorus(0)), "cho0");
        Hearing wide = hearing(hit(46, track -> track.setChorus(127)), "cho127");
        System.out.println("chorus width " + dry.width + " / " + wide.width);
        assertTrue(dry.width < 0.08, dry.toString());
        assertTrue(wide.width > 0.4, wide.toString());
    }

    private Sequence preset(int gate, int decay, int reverb) throws Exception {
        Beat beat = Beat.drumKit("preset");
        beat.track(0).setPreset(PitchedPreset.RIM);
        beat.track(0).setGate(gate);
        if (decay != Track.DECAY) {
            beat.track(0).setDecay(decay);
        }
        if (reverb != Track.UNSET) {
            beat.track(0).setReverb(reverb);
        }
        beat.track(0).tap(0, false);
        return SequenceBuilder.export(beat, ExportOptions.matchingPlayback());
    }

    private Beat hit(int note, java.util.function.Consumer<Track> edit) {
        Beat beat = Beat.drumKit("hit-" + note);
        beat.setBpm(120);
        beat.setSwing(50);
        for (int row = 0; row < Beat.TRACKS; row++) {
            beat.track(row).clearSteps();
        }
        Track track = beat.track(0);
        track.setNote(note);
        track.setGate(100);
        track.tap(0, false);
        edit.accept(track);
        return beat;
    }

    private Beat note(java.util.function.Consumer<Track> edit) {
        Beat beat = hit(36, track -> {
            track.setMode(TrackMode.NOTE);
            track.setProgram(81);
            track.setNote(36);
        });
        edit.accept(beat.track(0));
        return beat;
    }

    private Hearing hearing(Beat beat, String name) throws Exception {
        Path wav = temp.resolve(name + ".wav");
        try {
            WavRenderer.render(SequenceBuilder.export(beat, ExportOptions.matchingPlayback()), wav, null, null, null);
        } catch (Exception exception) {
            if (WavRenderer.unavailable(exception)) {
                Assumptions.abort("offline render is not available: " + exception.getMessage());
            }
            throw exception;
        }
        return Hearing.of(stereo(wav), name);
    }

    private static int noteChannel(Sequence sequence, int note) {
        return noteChannelAt(sequence, note, -1);
    }

    private static int noteChannelAt(Sequence sequence, int note, long tick) {
        for (MidiEvent event : events(sequence)) {
            if (tick >= 0 && event.getTick() != tick) {
                continue;
            }
            if (event.getMessage() instanceof ShortMessage message
                && message.getCommand() == ShortMessage.NOTE_ON
                && message.getData1() == note
                && message.getData2() > 0) {
                return message.getChannel();
            }
        }
        throw new AssertionError("missing note " + note);
    }

    private static int controller(Sequence sequence, int channel, int controller) {
        Integer value = null;
        int count = 0;
        for (MidiEvent event : events(sequence)) {
            if (event.getMessage() instanceof ShortMessage message
                && message.getCommand() == ShortMessage.CONTROL_CHANGE
                && message.getChannel() == channel
                && message.getData1() == controller) {
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

    private static boolean hasControllerOn(Sequence sequence, int channel, int controller) {
        for (MidiEvent event : events(sequence)) {
            if (event.getMessage() instanceof ShortMessage message
                && message.getCommand() == ShortMessage.CONTROL_CHANGE
                && message.getChannel() == channel
                && message.getData1() == controller) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasBend(Sequence sequence) {
        for (MidiEvent event : events(sequence)) {
            if (event.getMessage() instanceof ShortMessage message && message.getCommand() == ShortMessage.PITCH_BEND) {
                return true;
            }
        }
        return false;
    }

    private static int bend(Sequence sequence, int channel) {
        Integer value = null;
        int count = 0;
        for (MidiEvent event : events(sequence)) {
            if (event.getMessage() instanceof ShortMessage message
                && message.getCommand() == ShortMessage.PITCH_BEND
                && message.getChannel() == channel) {
                value = message.getData1() | (message.getData2() << 7);
                count++;
            }
        }
        assertEquals(1, count, "pitch bend on " + channel);
        return value;
    }

    private static int nrpnValue(List<ShortMessage> messages, int msb) {
        for (int i = 0; i < messages.size(); i++) {
            ShortMessage message = messages.get(i);
            if (message.getCommand() == ShortMessage.CONTROL_CHANGE && message.getData1() == 99 && message.getData2() == msb) {
                for (int j = i + 1; j < messages.size(); j++) {
                    ShortMessage data = messages.get(j);
                    if (data.getCommand() == ShortMessage.CONTROL_CHANGE && data.getData1() == 6) {
                        return data.getData2();
                    }
                }
            }
        }
        throw new AssertionError("missing NRPN " + msb);
    }

    private static int nrpnKey(List<ShortMessage> messages, int msb) {
        for (int i = 0; i < messages.size(); i++) {
            ShortMessage message = messages.get(i);
            if (message.getCommand() == ShortMessage.CONTROL_CHANGE && message.getData1() == 99 && message.getData2() == msb) {
                for (int j = i + 1; j < messages.size(); j++) {
                    ShortMessage data = messages.get(j);
                    if (data.getCommand() == ShortMessage.CONTROL_CHANGE && data.getData1() == 98) {
                        return data.getData2();
                    }
                }
            }
        }
        throw new AssertionError("missing NRPN key " + msb);
    }

    private static List<MidiEvent> events(Sequence sequence) {
        List<MidiEvent> found = new ArrayList<>();
        for (javax.sound.midi.Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                found.add(track.get(i));
            }
        }
        return found;
    }

    private static Stereo stereo(Path wav) throws Exception {
        try (AudioInputStream in = AudioSystem.getAudioInputStream(wav.toFile())) {
            byte[] bytes = in.readAllBytes();
            int frames = bytes.length / 4;
            double[] left = new double[frames];
            double[] right = new double[frames];
            double[] mono = new double[frames];
            for (int i = 0; i < frames; i++) {
                int l = (bytes[i * 4] & 0xff) | (bytes[i * 4 + 1] << 8);
                int r = (bytes[i * 4 + 2] & 0xff) | (bytes[i * 4 + 3] << 8);
                left[i] = ((short) l) / 32768.0;
                right[i] = ((short) r) / 32768.0;
                mono[i] = (left[i] + right[i]) / 2.0;
            }
            return new Stereo(left, right, mono);
        }
    }

    private record Stereo(double[] left, double[] right, double[] mono) {}

    private record Hearing(String name, double peakHz, double centroid, int below20, int below40, double body, double tail, double width) {
        static Hearing of(Stereo stereo, String name) {
            double[] samples = stereo.mono;
            double peak = 0;
            for (double sample : samples) {
                peak = Math.max(peak, Math.abs(sample));
            }
            int onset = 0;
            double started = peak * 0.1;
            for (int i = 0; i < samples.length; i++) {
                if (Math.abs(samples[i]) >= started) {
                    onset = i;
                    break;
                }
            }
            double[] mag = spectrum(samples, onset);
            int rate = WavAudio.SAMPLE_RATE;
            return new Hearing(
                name,
                peakHz(mag),
                centroid(mag),
                firstBelow(samples, peak, onset, -20),
                firstBelow(samples, peak, onset, -40),
                rms(samples, onset, onset + rate / 8),
                rms(samples, onset + (int) (0.40 * rate), onset + (int) (1.40 * rate)),
                width(stereo, onset, onset + rate)
            );
        }

        @Override
        public String toString() {
            return String.format(
                Locale.ROOT,
                "%s peak %.1f Hz centroid %.0f Hz -20 %d ms -40 %d ms body %.4f tail %.5f width %.3f",
                name, peakHz, centroid, below20, below40, body, tail, width
            );
        }

        private static double rms(double[] samples, int start, int end) {
            int from = Math.max(0, start);
            int to = Math.min(samples.length, end);
            if (to <= from) {
                return 0;
            }
            double sum = 0;
            for (int i = from; i < to; i++) {
                sum += samples[i] * samples[i];
            }
            return Math.sqrt(sum / (to - from));
        }

        private static double width(Stereo stereo, int start, int end) {
            int from = Math.max(0, start);
            int to = Math.min(Math.min(stereo.left.length, stereo.right.length), end);
            double dot = 0;
            double left = 0;
            double right = 0;
            for (int i = from; i < to; i++) {
                dot += stereo.left[i] * stereo.right[i];
                left += stereo.left[i] * stereo.left[i];
                right += stereo.right[i] * stereo.right[i];
            }
            if (left == 0 || right == 0) {
                return 0;
            }
            return 1 - dot / Math.sqrt(left * right);
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

        private static double[] spectrum(double[] samples, int start) {
            double[] real = new double[FFT];
            double[] imag = new double[FFT];
            int count = Math.min(FFT, Math.max(0, samples.length - start));
            for (int i = 0; i < count; i++) {
                double hann = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / Math.max(1, count - 1));
                real[i] = samples[start + i] * hann;
            }
            fft(real, imag);
            double[] mag = new double[FFT / 2];
            for (int i = 1; i < mag.length; i++) {
                mag[i] = Math.hypot(real[i], imag[i]);
            }
            return mag;
        }

        private static double peakHz(double[] mag) {
            int peak = 1;
            for (int i = 2; i < mag.length; i++) {
                if (mag[i] > mag[peak]) {
                    peak = i;
                }
            }
            double bin = peak;
            if (peak > 1 && peak + 1 < mag.length) {
                double left = mag[peak - 1];
                double center = mag[peak];
                double right = mag[peak + 1];
                double denom = left - 2 * center + right;
                if (denom != 0) {
                    bin += 0.5 * (left - right) / denom;
                }
            }
            return bin * (WavAudio.SAMPLE_RATE / (double) FFT);
        }

        private static double centroid(double[] mag) {
            double weight = 0;
            double mass = 0;
            for (int i = 1; i < mag.length; i++) {
                double hz = i * (WavAudio.SAMPLE_RATE / (double) FFT);
                weight += hz * mag[i];
                mass += mag[i];
            }
            return mass == 0 ? 0 : weight / mass;
        }

        private static void fft(double[] real, double[] imag) {
            int n = real.length;
            for (int i = 1, j = 0; i < n; i++) {
                int bit = n >> 1;
                for (; (j & bit) != 0; bit >>= 1) {
                    j ^= bit;
                }
                j ^= bit;
                if (i < j) {
                    double swap = real[i];
                    real[i] = real[j];
                    real[j] = swap;
                    swap = imag[i];
                    imag[i] = imag[j];
                    imag[j] = swap;
                }
            }
            for (int length = 2; length <= n; length <<= 1) {
                double angle = -2 * Math.PI / length;
                double wReal = Math.cos(angle);
                double wImag = Math.sin(angle);
                for (int start = 0; start < n; start += length) {
                    double curReal = 1;
                    double curImag = 0;
                    int half = length / 2;
                    for (int i = 0; i < half; i++) {
                        int even = start + i;
                        int odd = even + half;
                        double oddReal = real[odd] * curReal - imag[odd] * curImag;
                        double oddImag = real[odd] * curImag + imag[odd] * curReal;
                        real[odd] = real[even] - oddReal;
                        imag[odd] = imag[even] - oddImag;
                        real[even] += oddReal;
                        imag[even] += oddImag;
                        double nextReal = curReal * wReal - curImag * wImag;
                        curImag = curReal * wImag + curImag * wReal;
                        curReal = nextReal;
                    }
                }
            }
        }
    }
}
