package hits;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetronomeTest {
    @Test
    void clickIsAPitchedSquareWaveNotADrumWoodBlock() {
        assertEquals(15, SequenceBuilder.CLICK_CHANNEL);
        assertEquals(80, SequenceBuilder.CLICK_PROGRAM);
        assertEquals(84, SequenceBuilder.CLICK_ACCENT_NOTE);
        assertEquals(72, SequenceBuilder.CLICK_NOTE);
        assertTrue(SequenceBuilder.CLICK_CHANNEL != Gm.DRUM_CHANNEL);
        assertTrue(SequenceBuilder.CLICK_ACCENT_NOTE != 76 && SequenceBuilder.CLICK_NOTE != 77);
        assertTrue(SequenceBuilder.CLICK_ACCENT_NOTE != 75 && SequenceBuilder.CLICK_NOTE != 81);
        for (int note : Gm.DRUM_NOTES) {
            assertTrue(note != SequenceBuilder.CLICK_ACCENT_NOTE && note != SequenceBuilder.CLICK_NOTE);
        }
    }

    @Test
    void playbackClicksOnBeatsAndAccentsBeatOne() throws Exception {
        Beat beat = Beat.drumKit("t");
        beat.setSwing(75);
        Sequence sequence = SequenceBuilder.build(beat, true);
        List<MidiEvent> clicks = clicks(sequence);
        assertEquals(List.of(0L, 96L, 192L, 288L), ticks(clicks));
        assertEquals(SequenceBuilder.CLICK_ACCENT_NOTE, note(clicks.get(0)));
        assertEquals(SequenceBuilder.CLICK_ACCENT_VELOCITY, velocity(clicks.get(0)));
        assertEquals(SequenceBuilder.CLICK_CHANNEL, channel(clicks.get(0)));
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
    void chancePlaybackClicksOnEveryPassAndExportDoesNot() throws Exception {
        Beat beat = Beat.drumKit("t");
        beat.track(0).tap(0, false);
        beat.track(0).step(0).setProbability(50);
        assertEquals(Chance.PLAYBACK_PASSES, SequenceBuilder.playbackPasses(beat, false));

        Sequence played = SequenceBuilder.playback(beat, false, new java.util.Random(1), true);
        List<MidiEvent> clicks = clicks(played);
        int quarters = beat.stepCount() / 4;
        assertEquals(quarters * Chance.PLAYBACK_PASSES, clicks.size());
        long loop = SequenceBuilder.loopTicks(beat.stepCount());
        assertEquals(0L, clicks.get(0).getTick());
        assertEquals(loop, clicks.get(quarters).getTick());
        assertEquals(SequenceBuilder.CLICK_ACCENT_NOTE, note(clicks.get(quarters)));
        assertEquals(loop * (Chance.PLAYBACK_PASSES - 1), clicks.get(clicks.size() - quarters).getTick());

        beat.addChain('a');
        beat.addChain('b');
        beat.slot('b')[0].step(0).setOn(true);
        beat.slot('b')[0].step(0).setProbability(40);
        Sequence song = SequenceBuilder.playback(beat, true, new java.util.Random(1), true);
        int songBars = Chain.sections(beat.chain()) * SequenceBuilder.playbackPasses(beat, true);
        assertEquals(quarters * songBars, clicks(song).size());
        assertEquals(loop, clicks(song).get(quarters).getTick());

        assertTrue(clicks(SequenceBuilder.playback(beat, true, new java.util.Random(1), false)).isEmpty());
        assertTrue(clicks(SequenceBuilder.export(beat, ExportOptions.matchingPlayback())).isEmpty());
        assertTrue(clicks(SequenceBuilder.export(beat, new ExportOptions(true, false, 1, true))).isEmpty());
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

    @Test
    void theClickIsSpectrallyDistinctFromAWoodBlock(@TempDir Path directory) throws Exception {
        Beat clickBeat = Beat.drumKit("click");
        clickBeat.setBpm(120);
        clickBeat.setSwing(50);
        Sequence clickSequence = SequenceBuilder.build(clickBeat, true);
        assertTrue(program(clickSequence, SequenceBuilder.CLICK_CHANNEL, SequenceBuilder.CLICK_PROGRAM));

        Beat wood = Beat.drumKit("wood");
        wood.setBpm(120);
        wood.setSwing(50);
        wood.track(0).setNote(76);
        wood.track(0).tap(0, false);
        wood.track(0).step(0).setVelocity(120);
        Sequence woodSequence = SequenceBuilder.build(wood, false);

        Path clickWav = directory.resolve("click.wav");
        Path woodWav = directory.resolve("wood.wav");
        try {
            WavRenderer.render(clickSequence, clickWav, null, null, null);
            WavRenderer.render(woodSequence, woodWav, null, null, null);
        } catch (Exception exception) {
            if (WavRenderer.unavailable(exception)) {
                Assumptions.abort("offline render is not available: " + exception.getMessage());
            }
            throw exception;
        }

        int samples = 2048;
        double[] clickSpectrum = spectrum(mono(Files.readAllBytes(clickWav), samples));
        double[] woodSpectrum = spectrum(mono(Files.readAllBytes(woodWav), samples));
        double correlation = cosine(clickSpectrum, woodSpectrum);
        assertTrue(correlation < 0.85, "click matched the wood block, correlation " + correlation);

        double expectedHz = 440.0 * Math.pow(2.0, (SequenceBuilder.CLICK_ACCENT_NOTE - 69) / 12.0);
        double peakHz = peakHz(clickSpectrum, samples);
        assertEquals(expectedHz, peakHz, 40.0, "click peak " + peakHz + " Hz, expected " + expectedHz);
    }

    private static boolean program(Sequence sequence, int channel, int program) {
        for (javax.sound.midi.Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                if (track.get(i).getMessage() instanceof ShortMessage message
                    && message.getCommand() == ShortMessage.PROGRAM_CHANGE
                    && message.getChannel() == channel
                    && message.getData1() == program) {
                    return true;
                }
            }
        }
        return false;
    }

    private static short[] mono(byte[] wav, int count) {
        short[] samples = new short[count];
        int index = 0;
        for (int i = WavAudio.HEADER_BYTES; i + 3 < wav.length && index < count; i += 4) {
            int left = (wav[i] & 0xff) | (wav[i + 1] << 8);
            int right = (wav[i + 2] & 0xff) | (wav[i + 3] << 8);
            samples[index++] = (short) ((left + right) / 2);
        }
        return samples;
    }

    /** Magnitude of bins 1..n/2-1. Bin 0 is left at 0 so silence does not dominate the correlation. */
    private static double[] spectrum(short[] samples) {
        int n = samples.length;
        double[] magnitude = new double[n / 2];
        for (int bin = 1; bin < magnitude.length; bin++) {
            double real = 0;
            double imag = 0;
            double step = 2 * Math.PI * bin / n;
            for (int i = 0; i < n; i++) {
                double angle = step * i;
                real += samples[i] * Math.cos(angle);
                imag -= samples[i] * Math.sin(angle);
            }
            magnitude[bin] = Math.hypot(real, imag);
        }
        return magnitude;
    }

    private static double cosine(double[] left, double[] right) {
        double dot = 0;
        double leftEnergy = 0;
        double rightEnergy = 0;
        int bins = Math.min(left.length, right.length);
        for (int i = 1; i < bins; i++) {
            dot += left[i] * right[i];
            leftEnergy += left[i] * left[i];
            rightEnergy += right[i] * right[i];
        }
        if (leftEnergy == 0 || rightEnergy == 0) {
            return 1;
        }
        return dot / Math.sqrt(leftEnergy * rightEnergy);
    }

    private static double peakHz(double[] magnitude, int n) {
        int peak = 1;
        for (int bin = 2; bin < magnitude.length; bin++) {
            if (magnitude[bin] > magnitude[peak]) {
                peak = bin;
            }
        }
        return peak * (WavAudio.SAMPLE_RATE / (double) n);
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
