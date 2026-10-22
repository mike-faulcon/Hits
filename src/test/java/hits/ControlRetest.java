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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Retest of tune, decay, cutoff, a held note across a pattern, and neutral preset controllers.
 */
class ControlRetest {
    private static final int FFT = 8192;

    @TempDir
    Path temp;

    @Test
    void decayPercentMapsAcrossTheAudibleControllers() throws Exception {
        int previous = -1;
        for (int percent = Track.DECAY_MIN; percent <= Track.DECAY_MAX; percent++) {
            int controller = Track.decayController(percent);
            assertTrue(controller >= previous && controller <= 127, percent + " -> " + controller);
            previous = controller;
        }
        assertEquals(64, Track.decayController(100));
        assertEquals(127, Track.decayController(200));
        assertTrue(Track.decayController(50) > Track.decayController(10));
        assertTrue(Track.decayController(150) > 64);

        int[] percents = {10, 50, 100, 150, 200};
        Levels[] rim = new Levels[percents.length];
        Levels[] hat = new Levels[percents.length];
        Levels[] bass = new Levels[percents.length];
        Levels[] saw = new Levels[percents.length];
        for (int i = 0; i < percents.length; i++) {
            rim[i] = levels(decay(PitchedPreset.RIM, percents[i]));
            hat[i] = levels(decayDrum(46, percents[i]));
            bass[i] = levels(decayNote(38, 35, percents[i]));
            saw[i] = levels(decayNote(81, 48, percents[i]));
        }
        System.out.println("decay map " + mapped(percents));
        System.out.println("decay -20 ms rim " + join(percents, below20(rim)));
        System.out.println("decay -40 ms rim " + join(percents, below40(rim)));
        System.out.println("decay -20 ms hat " + join(percents, below20(hat)));
        System.out.println("decay -40 ms hat " + join(percents, below40(hat)));
        System.out.println("decay -20 ms bass " + join(percents, below20(bass)));
        System.out.println("decay -40 ms bass " + join(percents, below40(bass)));
        System.out.println("decay -20 ms saw " + join(percents, below20(saw)));
        System.out.println("decay -40 ms saw " + join(percents, below40(saw)));
        assertSpread("rim", below20(rim));
        assertSpread("hat", below20(hat));
        assertSpread("bass", below20(bass));
        assertTrue(below20(saw)[4] > below20(saw)[2] + 200, "saw " + java.util.Arrays.toString(below20(saw)));
    }

    @Test
    void cutoffSliderCoversTheRangeThatMovesTheCentroid() throws Exception {
        Track bass = new Track("Bass", TrackMode.NOTE, 38, 35);
        Track saw = new Track("Saw", TrackMode.NOTE, 81, 48);
        Track snare = new Track("Snare", TrackMode.DRUM, 0, 38);
        assertEquals(58, Track.cutoffSliderMin(bass));
        assertEquals(86, Track.cutoffSliderMax(bass));
        assertEquals(32, Track.cutoffSliderMin(saw));
        assertEquals(80, Track.cutoffSliderMax(saw));
        assertEquals(0, Track.cutoffSliderMin(snare));
        assertEquals(56, Track.cutoffSliderMax(snare));

        Beat stored = Beat.drumKit("stored");
        stored.track(0).setMode(TrackMode.NOTE);
        stored.track(0).setProgram(38);
        stored.track(0).setNote(35);
        stored.track(0).setCutoff(12);
        stored.track(0).tap(0, false);
        Sequence sent = SequenceBuilder.export(stored, ExportOptions.matchingPlayback());
        assertEquals(12, onlyController(sent, 0, ChannelPlan.CUTOFF));
        assertEquals(12, BeatJson.read(BeatJson.write(stored)).track(0).cutoff());
        stored.track(0).clearCutoff();
        Sequence open = SequenceBuilder.export(stored, ExportOptions.matchingPlayback());
        assertFalse(hasController(open, ChannelPlan.CUTOFF));

        int low = centroid(cutoffNote(38, 35, Track.cutoffSliderMin(bass)));
        int mid = centroid(cutoffNote(38, 35, 74));
        int high = centroid(cutoffNote(38, 35, Track.cutoffSliderMax(bass)));
        int closed = centroid(cutoffNote(38, 35, 0));
        int forty = centroid(cutoffNote(38, 35, 40));
        System.out.println("cutoff bass centroid cc " + sweepNote(38, 35, 0, 40, 58, 66, 74, 82, 86, 90));
        assertTrue(Math.abs(closed - forty) < 20, "CC 0 and 40 are the dead zone: " + closed + " vs " + forty);
        assertTrue(mid > low + 40, low + " -> " + mid);
        assertTrue(high > mid, mid + " -> " + high);

        int snareClosed = centroid(cutoffDrum(38, 0));
        int snareOpen = centroid(cutoffDrum(38, 48));
        System.out.println("cutoff snare centroid cc " + sweepDrum(38, 0, 16, 32, 48, 56));
        System.out.println("cutoff saw centroid cc " + sweepNote(81, 48, 0, 32, 48, 64, 80));
        assertTrue(snareOpen > snareClosed + 500, snareClosed + " -> " + snareOpen);
    }

    @Test
    void tunePartsRoundSoASemitoneStepAddsOneHundredCents() {
        Track track = new Track("Kick", TrackMode.DRUM, 0, 36);
        track.setTune(275);
        assertEquals(3, track.tuneSemitones());
        assertEquals(-25, track.tuneCents());
        assertTrue(Math.abs(track.tuneCents()) <= 50);
        track.setTuneParts(track.tuneSemitones() + 1, track.tuneCents());
        assertEquals(375, track.tune());

        track.setTune(-275);
        assertEquals(-3, track.tuneSemitones());
        assertEquals(25, track.tuneCents());
        track.setTuneParts(track.tuneSemitones() + 1, track.tuneCents());
        assertEquals(-175, track.tune());

        track.setTune(2399);
        assertEquals(24, track.tuneSemitones());
        assertEquals(-1, track.tuneCents());
        track.setTune(50);
        assertEquals(1, track.tuneSemitones());
        assertEquals(-50, track.tuneCents());
        track.setTuneParts(track.tuneSemitones() + 1, track.tuneCents());
        assertEquals(150, track.tune());
        track.setTune(-50);
        assertEquals(0, track.tuneSemitones());
        assertEquals(-50, track.tuneCents());
    }

    @Test
    void decayIsMarkedOffForSoundsThatIgnoreIt() {
        for (int note : new int[] {35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 47, 48, 50}) {
            assertFalse(drum(note).decayApplies(), "note " + note);
        }
        assertTrue(drum(46).decayApplies());
        assertTrue(drum(49).decayApplies());
        assertTrue(drum(51).decayApplies());

        Track crack = drum(38);
        crack.setPreset(PitchedPreset.CRACK);
        assertFalse(crack.decayApplies());
        assertTrue(crack.decayNote().contains("reverb"), crack.decayNote());

        Track click = drum(37);
        click.setPreset(PitchedPreset.CLICK);
        assertFalse(click.decayApplies());
        assertFalse(click.decayNote().contains("reverb"));

        Track rim = drum(37);
        rim.setPreset(PitchedPreset.RIM);
        assertTrue(rim.decayApplies());
        assertEquals(null, rim.decayNote());

        assertTrue(new Track("Bass", TrackMode.NOTE, 38, 35).decayApplies());
        assertFalse(new Track("Timpani", TrackMode.NOTE, 47, 60).decayApplies());
        assertFalse(new Track("Wood", TrackMode.NOTE, 115, 60).decayApplies());

        Track sample = new Track("Hit", TrackMode.SAMPLE, 0, 38);
        assertTrue(sample.decayApplies(), "a sample uses Gervill's envelope");
        assertEquals(null, sample.decayNote());
        assertEquals(0, Track.cutoffSliderMin(sample));
        assertEquals(Track.FILTER_NEUTRAL, Track.cutoffSliderMax(sample));
        assertEquals(56, Track.cutoffSliderMax(drum(38)));
    }

    @Test
    void aHeldNoteIsNotRetunedWhenTheNextPatternStarts() throws Exception {
        Beat beat = Beat.drumKit("hold");
        beat.setBpm(120);
        beat.setSwing(50);
        Track first = beat.track(0);
        first.setMode(TrackMode.NOTE);
        first.setProgram(38);
        first.setNote(35);
        first.setTune(220);
        first.setCutoff(20);
        first.tap(15, false);
        first.step(15).setLength(4);
        Track second = beat.slot('b')[0];
        second.setMode(TrackMode.NOTE);
        second.setProgram(38);
        second.setNote(35);
        second.tap(8, false);
        beat.addChain('a');
        beat.addChain('b');

        ExportOptions song = new ExportOptions(true, false, 1, true);
        Sequence exported = SequenceBuilder.export(beat, song);
        Sequence played = SequenceBuilder.buildSong(beat);
        long boundary = SequenceBuilder.loopTicks(beat.stepCount('a'));
        long off = noteOff(exported, 35, 360);
        assertTrue(off > boundary, "held past the bar, off " + off + " boundary " + boundary);
        assertEquals(off, noteOff(played, 35, 360), "playback and export release together");

        assertFalse(hasBendAt(exported, boundary), "export retuned at the bar");
        assertFalse(hasBendAt(played, boundary), "playback retuned at the bar");
        assertFalse(hasControllerAt(exported, ChannelPlan.CUTOFF, boundary));
        assertFalse(hasControllerAt(played, ChannelPlan.CUTOFF, boundary));
        assertEquals(Track.pitchBend(220), bendAt(exported, 0));
        assertEquals(20, controllerAt(exported, ChannelPlan.CUTOFF, 0));
        assertEquals(Track.pitchBend(0), bendAt(exported, off));
        assertEquals(Track.pitchBend(0), bendAt(played, off));
        assertEquals(Track.FILTER_NEUTRAL, controllerAt(exported, ChannelPlan.CUTOFF, off));
        assertEquals(Track.FILTER_NEUTRAL, controllerAt(played, ChannelPlan.CUTOFF, off));
        assertTrue(off < noteOn(exported, 35, boundary), "the reset lands before pattern B's hit");
    }

    @Test
    void neutralPresetControllersAreSentOnlyWhenTheChannelChanges() throws Exception {
        Beat steady = Beat.drumKit("steady");
        for (char slot : new char[] {'a', 'b', 'c', 'd'}) {
            steady.slot(slot)[0].setPreset(PitchedPreset.RIM);
            steady.slot(slot)[0].setGate(45);
            steady.slot(slot)[0].tap(0, false);
            steady.addChain(slot);
        }
        Sequence repeated = SequenceBuilder.export(steady, new ExportOptions(true, false, 1, true));
        assertEquals(0, count(repeated, ChannelPlan.RELEASE));
        assertEquals(0, count(repeated, ChannelPlan.REVERB));

        Beat jump = Beat.drumKit("jump");
        jump.track(0).setPreset(PitchedPreset.RIM);
        jump.track(0).setGate(5);
        jump.track(0).tap(0, false);
        jump.slot('c')[0].setPreset(PitchedPreset.RIM);
        jump.slot('c')[0].setGate(100);
        jump.slot('c')[0].tap(0, false);
        jump.addChain('a');
        jump.addChain('c');
        Sequence jumped = SequenceBuilder.export(jump, new ExportOptions(true, false, 1, true));
        long bar = SequenceBuilder.loopTicks(16);
        assertEquals(54, controllerAt(jumped, ChannelPlan.RELEASE, 0));
        assertEquals(0, controllerAt(jumped, ChannelPlan.REVERB, 0));
        assertEquals(80, controllerAt(jumped, ChannelPlan.RELEASE, bar));
        assertEquals(80, controllerAt(jumped, ChannelPlan.REVERB, bar));
        assertEquals(2, count(jumped, ChannelPlan.RELEASE));
        assertEquals(2, count(jumped, ChannelPlan.REVERB));

        Beat loop = Beat.drumKit("loop");
        loop.track(0).setPreset(PitchedPreset.CLICK);
        loop.track(0).setGate(45);
        loop.track(0).tap(0, false);
        loop.slot('b')[0].setPreset(PitchedPreset.CLICK);
        loop.slot('b')[0].setGate(5);
        loop.slot('b')[0].tap(0, false);
        loop.addChain('a');
        loop.addChain('b');
        Sequence looped = SequenceBuilder.export(loop, new ExportOptions(true, false, 1, true));
        assertEquals(64, controllerAt(looped, ChannelPlan.RELEASE, 0));
        assertEquals(40, controllerAt(looped, ChannelPlan.REVERB, 0));
        assertEquals(54, controllerAt(looped, ChannelPlan.RELEASE, bar));
        assertEquals(0, controllerAt(looped, ChannelPlan.REVERB, bar));
        assertEquals(2, count(looped, ChannelPlan.RELEASE));
        assertEquals(2, count(looped, ChannelPlan.REVERB));
    }

    private static void assertSpread(String name, int[] times) {
        for (int i = 1; i < times.length; i++) {
            assertTrue(times[i] > times[i - 1] + 20, name + " " + java.util.Arrays.toString(times));
        }
    }

    private static int[] below20(Levels[] levels) {
        int[] times = new int[levels.length];
        for (int i = 0; i < levels.length; i++) {
            times[i] = levels[i].below20;
        }
        return times;
    }

    private static int[] below40(Levels[] levels) {
        int[] times = new int[levels.length];
        for (int i = 0; i < levels.length; i++) {
            times[i] = levels[i].below40;
        }
        return times;
    }

    private static String mapped(int[] percents) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < percents.length; i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append(percents[i]).append("%→").append(Track.decayController(percents[i]));
        }
        return text.toString();
    }

    private String sweepNote(int program, int pitch, int... controllers) throws Exception {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < controllers.length; i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append(controllers[i]).append(":").append(centroid(cutoffNote(program, pitch, controllers[i])));
        }
        return text.toString();
    }

    private String sweepDrum(int note, int... controllers) throws Exception {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < controllers.length; i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append(controllers[i]).append(":").append(centroid(cutoffDrum(note, controllers[i])));
        }
        return text.toString();
    }

    private static String join(int[] percents, int[] times) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < percents.length; i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append(percents[i]).append("% ").append(times[i]);
        }
        return text.toString();
    }

    private static Track drum(int note) {
        return new Track("Drum", TrackMode.DRUM, 0, note);
    }

    private Sequence decay(PitchedPreset preset, int percent) throws Exception {
        Beat beat = base("decay-" + preset.json() + "-" + percent);
        beat.track(0).setPreset(preset);
        beat.track(0).setGate(45);
        if (percent != Track.DECAY) {
            beat.track(0).setDecay(percent);
        }
        beat.track(0).tap(0, false);
        return SequenceBuilder.export(beat, ExportOptions.matchingPlayback());
    }

    private Sequence decayDrum(int note, int percent) throws Exception {
        Beat beat = base("decay-drum-" + note + "-" + percent);
        beat.track(0).setNote(note);
        beat.track(0).setGate(45);
        if (percent != Track.DECAY) {
            beat.track(0).setDecay(percent);
        }
        beat.track(0).tap(0, false);
        return SequenceBuilder.export(beat, ExportOptions.matchingPlayback());
    }

    private Sequence decayNote(int program, int pitch, int percent) throws Exception {
        Beat beat = base("decay-note-" + program + "-" + percent);
        beat.track(0).setMode(TrackMode.NOTE);
        beat.track(0).setProgram(program);
        beat.track(0).setNote(pitch);
        beat.track(0).setGate(80);
        if (percent != Track.DECAY) {
            beat.track(0).setDecay(percent);
        }
        beat.track(0).tap(0, false);
        return SequenceBuilder.export(beat, ExportOptions.matchingPlayback());
    }

    private Sequence cutoffNote(int program, int pitch, int cutoff) throws Exception {
        Beat beat = base("cut-note-" + program + "-" + cutoff);
        beat.track(0).setMode(TrackMode.NOTE);
        beat.track(0).setProgram(program);
        beat.track(0).setNote(pitch);
        beat.track(0).setGate(80);
        beat.track(0).setCutoff(cutoff);
        beat.track(0).tap(0, false);
        return SequenceBuilder.export(beat, ExportOptions.matchingPlayback());
    }

    private Sequence cutoffDrum(int note, int cutoff) throws Exception {
        Beat beat = base("cut-drum-" + note + "-" + cutoff);
        beat.track(0).setNote(note);
        beat.track(0).setGate(80);
        beat.track(0).setCutoff(cutoff);
        beat.track(0).tap(0, false);
        return SequenceBuilder.export(beat, ExportOptions.matchingPlayback());
    }

    private static Beat base(String name) {
        Beat beat = Beat.drumKit(name);
        beat.setBpm(120);
        beat.setSwing(50);
        beat.setSlotSteps('a', 1);
        for (int row = 0; row < Beat.TRACKS; row++) {
            beat.track(row).clearSteps();
        }
        return beat;
    }

    private Levels levels(Sequence sequence) throws Exception {
        return Levels.of(mono(render(sequence, "l" + System.identityHashCode(sequence))));
    }

    private int centroid(Sequence sequence) throws Exception {
        return (int) Math.round(Levels.centroid(mono(render(sequence, "c" + System.identityHashCode(sequence)))));
    }

    private Path render(Sequence sequence, String name) throws Exception {
        Path wav = temp.resolve(name + ".wav");
        try {
            WavRenderer.render(sequence, wav, null, null, null);
        } catch (Exception exception) {
            if (WavRenderer.unavailable(exception)) {
                Assumptions.abort("offline render is not available: " + exception.getMessage());
            }
            throw exception;
        }
        return wav;
    }

    private static double[] mono(Path wav) throws Exception {
        try (AudioInputStream in = AudioSystem.getAudioInputStream(wav.toFile())) {
            byte[] bytes = in.readAllBytes();
            int frames = bytes.length / 4;
            double[] samples = new double[frames];
            for (int i = 0; i < frames; i++) {
                int left = (bytes[i * 4] & 0xff) | (bytes[i * 4 + 1] << 8);
                int right = (bytes[i * 4 + 2] & 0xff) | (bytes[i * 4 + 3] << 8);
                samples[i] = (((short) left) + ((short) right)) / 65536.0;
            }
            return samples;
        }
    }

    private static long noteOff(Sequence sequence, int note, long after) {
        for (MidiEvent event : events(sequence)) {
            if (event.getTick() < after || !(event.getMessage() instanceof ShortMessage message)) {
                continue;
            }
            boolean off = message.getCommand() == ShortMessage.NOTE_OFF
                || (message.getCommand() == ShortMessage.NOTE_ON && message.getData2() == 0);
            if (off && message.getData1() == note) {
                return event.getTick();
            }
        }
        throw new AssertionError("missing note-off " + note);
    }

    private static long noteOn(Sequence sequence, int note, long after) {
        for (MidiEvent event : events(sequence)) {
            if (event.getTick() < after || !(event.getMessage() instanceof ShortMessage message)) {
                continue;
            }
            if (message.getCommand() == ShortMessage.NOTE_ON && message.getData1() == note && message.getData2() > 0) {
                return event.getTick();
            }
        }
        throw new AssertionError("missing note-on " + note);
    }

    private static int bendAt(Sequence sequence, long tick) {
        for (MidiEvent event : events(sequence)) {
            if (event.getTick() != tick || !(event.getMessage() instanceof ShortMessage message)) {
                continue;
            }
            if (message.getCommand() == ShortMessage.PITCH_BEND) {
                return message.getData1() | (message.getData2() << 7);
            }
        }
        throw new AssertionError("missing bend at " + tick);
    }

    private static boolean hasBendAt(Sequence sequence, long tick) {
        for (MidiEvent event : events(sequence)) {
            if (event.getTick() == tick
                && event.getMessage() instanceof ShortMessage message
                && message.getCommand() == ShortMessage.PITCH_BEND) {
                return true;
            }
        }
        return false;
    }

    private static int controllerAt(Sequence sequence, int controller, long tick) {
        for (MidiEvent event : events(sequence)) {
            if (event.getTick() != tick || !(event.getMessage() instanceof ShortMessage message)) {
                continue;
            }
            if (message.getCommand() == ShortMessage.CONTROL_CHANGE && message.getData1() == controller) {
                return message.getData2();
            }
        }
        throw new AssertionError("missing controller " + controller + " at " + tick);
    }

    private static boolean hasControllerAt(Sequence sequence, int controller, long tick) {
        for (MidiEvent event : events(sequence)) {
            if (event.getTick() == tick
                && event.getMessage() instanceof ShortMessage message
                && message.getCommand() == ShortMessage.CONTROL_CHANGE
                && message.getData1() == controller) {
                return true;
            }
        }
        return false;
    }

    private static int onlyController(Sequence sequence, int channel, int controller) {
        Integer value = null;
        int found = 0;
        for (MidiEvent event : events(sequence)) {
            if (event.getMessage() instanceof ShortMessage message
                && message.getCommand() == ShortMessage.CONTROL_CHANGE
                && message.getChannel() == channel
                && message.getData1() == controller) {
                value = message.getData2();
                found++;
            }
        }
        assertEquals(1, found, "controller " + controller);
        return value;
    }

    private static int count(Sequence sequence, int controller) {
        int found = 0;
        for (MidiEvent event : events(sequence)) {
            if (event.getMessage() instanceof ShortMessage message
                && message.getCommand() == ShortMessage.CONTROL_CHANGE
                && message.getData1() == controller) {
                found++;
            }
        }
        return found;
    }

    private static boolean hasController(Sequence sequence, int controller) {
        return count(sequence, controller) > 0;
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

    private record Levels(int below20, int below40) {
        static Levels of(double[] samples) {
            double peak = 0;
            for (double sample : samples) {
                peak = Math.max(peak, Math.abs(sample));
            }
            int onset = 0;
            for (int i = 0; i < samples.length; i++) {
                if (Math.abs(samples[i]) >= peak * 0.1) {
                    onset = i;
                    break;
                }
            }
            return new Levels(firstBelow(samples, peak, onset, -20), firstBelow(samples, peak, onset, -40));
        }

        static double centroid(double[] samples) {
            double peak = 0;
            for (double sample : samples) {
                peak = Math.max(peak, Math.abs(sample));
            }
            int onset = 0;
            for (int i = 0; i < samples.length; i++) {
                if (Math.abs(samples[i]) >= peak * 0.1) {
                    onset = i;
                    break;
                }
            }
            double[] real = new double[FFT];
            double[] imag = new double[FFT];
            int count = Math.min(FFT, Math.max(0, samples.length - onset));
            for (int i = 0; i < count; i++) {
                double hann = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / Math.max(1, count - 1));
                real[i] = samples[onset + i] * hann;
            }
            fft(real, imag);
            double weight = 0;
            double mass = 0;
            for (int i = 1; i < FFT / 2; i++) {
                double mag = Math.hypot(real[i], imag[i]);
                double hz = i * (WavAudio.SAMPLE_RATE / (double) FFT);
                weight += hz * mag;
                mass += mag;
            }
            return mass == 0 ? 0 : weight / mass;
        }

        private static int firstBelow(double[] samples, double peak, int onset, double db) {
            if (peak <= 0) {
                return 0;
            }
            double threshold = peak * Math.pow(10, db / 20.0);
            int window = WavAudio.SAMPLE_RATE / 100;
            for (int i = onset; i < samples.length; i++) {
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
