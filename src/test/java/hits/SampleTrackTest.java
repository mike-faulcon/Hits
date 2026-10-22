package hits;

import hits.cli.HitsCli;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Soundbank;
import javax.sound.midi.SoundbankResource;
import javax.sound.midi.Synthesizer;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sample tracks: the SoundFont writer, JSON, fallback, and offline playback. */
class SampleTrackTest {
    @TempDir
    Path temp;

    @Test
    void soundFontRoundTripKeepsRateAndLength() throws Exception {
        short[] pcm = new short[44100];
        pcm[0] = 1000;
        pcm[pcm.length - 1] = -1000;
        byte[] sf2 = SampleBank.write(List.of(new SampleBank.Voice(3, "tone", 44100, pcm, 60, 0, true)));
        Soundbank bank = SampleBank.load(sf2);
        assertNotNull(bank);
        SoundbankResource sample = null;
        for (SoundbankResource resource : bank.getResources()) {
            if ("SF2Sample".equals(resource.getClass().getSimpleName()) && !"EOS".equals(resource.getName())) {
                sample = resource;
                break;
            }
        }
        assertNotNull(sample, "loaded bank has no sample");
        int rate = ((Number) sample.getClass().getMethod("getSampleRate").invoke(sample)).intValue();
        assertEquals(44100, rate);
        Object data = sample.getData();
        assertTrue(data instanceof AudioInputStream);
        long frames = ((AudioInputStream) data).getFrameLength();
        long audible = frames > pcm.length + 46 ? frames / 2 : frames;
        assertTrue(audible == pcm.length || audible == pcm.length + 46, "sample length " + frames);

        assertEquals(64, bank.getInstruments()[0].getPatch().getBank() >> 7);
        assertEquals(3, bank.getInstruments()[0].getPatch().getProgram());
    }

    @Test
    void longFilesAreCappedAtAboutTenSeconds() throws Exception {
        int rate = 22050;
        int count = (int) Math.round(rate * 10.5);
        short[] pcm = new short[count];
        Path wav = temp.resolve("long.wav");
        SampleAudio.writeWav(wav, rate, pcm);
        SampleAudio.Clip clip = SampleAudio.read(wav);
        assertEquals(rate, clip.rate());
        assertEquals((int) Math.round(rate * SampleAudio.MAX_SECONDS), clip.samples().length);
    }

    @Test
    void twoBanksStayLoadedTogether() throws Exception {
        Synthesizer synth;
        try {
            synth = MidiSystem.getSynthesizer();
            synth.open();
        } catch (Exception exception) {
            Assumptions.abort("synth did not open: " + exception.getMessage());
            return;
        }
        try {
            Soundbank kits = MidiSystem.getSoundbank(Path.of("src/test/resources/hits-test.sf2").toFile());
            short[] pcm = new short[128];
            Soundbank samples = SampleBank.load(SampleBank.write(List.of(
                new SampleBank.Voice(0, "tap", 44100, pcm, 60, 0, true)
            )));
            SoundFonts.install(synth, kits, synth.getDefaultSoundbank());
            SoundFonts.install(synth, samples, null);
            boolean kitsLoaded = false;
            boolean samplesLoaded = false;
            for (javax.sound.midi.Instrument instrument : synth.getLoadedInstruments()) {
                int bank = instrument.getPatch().getBank();
                if (bank == kits.getInstruments()[0].getPatch().getBank()) {
                    kitsLoaded = true;
                }
                if (bank == SampleBank.MIDI_BANK || bank == SampleBank.SF2_BANK) {
                    samplesLoaded = true;
                }
            }
            assertTrue(kitsLoaded, "user bank was unloaded");
            assertTrue(samplesLoaded, "sample bank was not loaded");
        } finally {
            synth.close();
        }
    }

    @Test
    void unknownModeIsDrumAndSampleRoundTrips() {
        assertEquals(TrackMode.DRUM, TrackMode.parse("nope"));
        assertEquals(TrackMode.DRUM, TrackMode.parse(null));
        assertEquals(TrackMode.SAMPLE, TrackMode.parse("sample"));
        Beat beat = Beat.drumKit("samples");
        beat.track(1).setMode(TrackMode.SAMPLE);
        beat.track(1).setSample(new SampleRef("click.wav", 60, 12, true));
        beat.track(1).tap(0, false);
        String json = BeatJson.write(beat);
        assertTrue(json.contains("\"mode\": \"sample\""));
        assertTrue(json.contains("\"sample\": {\"path\": \"click.wav\", \"root\": 60, \"start\": 12, \"oneShot\": true}"));
        Beat read = BeatJson.read(json);
        assertEquals(TrackMode.SAMPLE, read.track(1).mode());
        assertTrue(read.track(1).sample().same(beat.track(1).sample()));
        assertTrue(read.usesFormatV2());
    }

    @Test
    void aDrumThatLeftSampleModeSavesLikeAPlainBeat() {
        Beat plain = Beat.drumKit("plain");
        String before = BeatJson.write(plain);
        plain.track(0).setMode(TrackMode.SAMPLE);
        plain.track(0).setSample(new SampleRef("click.wav", 36, 0, true));
        plain.track(0).setMode(TrackMode.DRUM);
        assertEquals(before, BeatJson.write(plain));
        assertFalse(plain.usesFormatV2());
    }

    @Test
    void saveAsRewritesThePathRelativeToTheNewFile(@TempDir Path folder) throws Exception {
        Path source = folder.resolve("kit.wav");
        SampleAudio.writeWav(source, 44100, new short[] {1000, -1000, 500});
        Beat beat = Beat.drumKit("Take");
        beat.track(0).setMode(TrackMode.SAMPLE);
        beat.track(0).setSample(new SampleRef(source.toAbsolutePath().toString(), 36, 0, false));
        Path saved = folder.resolve("album").resolve("Take.json");
        Files.createDirectories(saved.getParent());
        assertTrue(SamplePaths.retarget(beat, null, saved));
        assertEquals("../kit.wav", beat.track(0).sample().path());
        BeatFiles.write(beat, saved);
        Beat loaded = BeatFiles.load(saved);
        assertEquals("../kit.wav", loaded.track(0).sample().path());
        assertNotNull(SamplePaths.resolve(loaded.track(0).sample().path(), saved));
        assertTrue(Files.isRegularFile(SamplePaths.resolve(loaded.track(0).sample().path(), saved)));
    }

    @Test
    void missingFileFallsBackToTheDrumNote() throws Exception {
        Beat beat = Beat.drumKit("missing");
        beat.setBpm(120);
        beat.setSwing(50);
        beat.track(0).clearSteps();
        beat.track(0).setNote(36);
        beat.track(0).setMode(TrackMode.SAMPLE);
        beat.track(0).setSample(new SampleRef("gone.wav", 60, 0, true));
        beat.track(0).tap(0, false);
        beat.track(0).step(0).setPitch(72);
        Path beatFile = temp.resolve("missing.json");
        SampleLibrary.Ready ready = SampleLibrary.prepare(beat, beatFile, List.of('a'));
        assertTrue(ready.warning().contains("not found"));
        assertFalse(ready.use().plays(beat.track(0), 0));
        Sequence sequence = SequenceBuilder.export(beat, ExportOptions.matchingPlayback(), null, ready.use());
        List<Integer> notes = noteOns(sequence, 0);
        assertEquals(List.of(36), notes);
        assertTrue(hasControl(sequence, 0, 0, Gm.DRUM_BANK));
        assertFalse(hasControl(sequence, 0, 0, SampleBank.SF2_BANK));
    }

    @Test
    void oneShotRingsAndChokeFollowsTheStep() throws Exception {
        int rate = 44100;
        short[] pcm = new short[rate / 2];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (short) (Math.sin(2 * Math.PI * 440 * i / rate) * 16000);
        }
        Path wav = temp.resolve("held.wav");
        SampleAudio.writeWav(wav, rate, pcm);
        Path shot = renderSample(wav, true, 15);
        Path choked = renderSample(wav, false, 15);
        if (shot == null) {
            return;
        }
        double[] shotPcm = mono(shot);
        double[] chokePcm = mono(choked);
        int shotMs = audibleMs(shotPcm);
        int chokeMs = audibleMs(chokePcm);
        System.out.println("one-shot " + shotMs + " ms, choke " + chokeMs + " ms");
        assertTrue(shotMs > 200, "one-shot was cut to " + shotMs + " ms");
        assertTrue(chokeMs < 100, "choke rang for " + chokeMs + " ms");
        assertTrue(shotMs > chokeMs * 2);
    }

    @Test
    void renderedToneMatchesTheSourceOnsetAndSpectrum() throws Exception {
        Path source = Path.of("src/test/resources/samples/tone.wav");
        assertTrue(Files.isRegularFile(source));
        double[] sourcePcm = mono(source);
        double sourceCentroid = centroid(sourcePcm, onset(sourcePcm));
        Path beatFile = temp.resolve("tone.json");
        Beat beat = sampleBeat(source.toAbsolutePath().toString(), true, 80);
        BeatFiles.write(beat, beatFile);
        Path rendered = temp.resolve("tone-out.wav");
        try {
            BeatFiles.exportWav(beat, rendered, ExportOptions.matchingPlayback(), null, null, () -> false, beatFile);
        } catch (Exception exception) {
            if (WavRenderer.unavailable(exception)) {
                Assumptions.abort("offline render is not available: " + exception.getMessage());
            }
            throw exception;
        }
        double[] heard = mono(rendered);
        int start = onset(heard);
        assertTrue(start < 44100 / 20, "onset at " + start + " frames");
        double heardCentroid = centroid(heard, start);
        System.out.println("tone centroid source " + sourceCentroid + " rendered " + heardCentroid);
        assertEquals(sourceCentroid, heardCentroid, 80);
    }

    @Test
    void builtinOneShotsHaveTheShapeTheyWereBuiltFor() {
        BuiltinSamples.Pcm kick = BuiltinSamples.pcm(BuiltinSamples.EIGHT_OH_EIGHT);
        BuiltinSamples.Pcm click = BuiltinSamples.pcm(BuiltinSamples.CLICK);
        BuiltinSamples.Pcm snap = BuiltinSamples.pcm(BuiltinSamples.SNAP);
        double early = pitchHz(kick.samples(), kick.rate(), 0.01, 0.06);
        double mid = pitchHz(kick.samples(), kick.rate(), 0.25, 0.38);
        int ring = ringMs(kick.samples(), kick.rate(), -40);
        double clickCentroid = centroid(toDouble(click.samples()), 0);
        double snapCentroid = centroid(toDouble(snap.samples()), 0);
        double[] clickPower = bandShare(click.samples(), click.rate());
        System.out.println("808 early " + early + " Hz, later " + mid + " Hz, ring " + ring + " ms");
        System.out.println("click centroid " + clickCentroid + " Hz, bands " + clickPower[0] + " / " + clickPower[1]);
        System.out.println("snap centroid " + snapCentroid + " Hz");
        assertTrue(early > 74 && early < 82, "early " + early);
        assertTrue(mid > 64 && mid < 72, "later " + mid);
        assertTrue(early > mid + 6);
        assertTrue(ring > 350 && ring < 480, "ring " + ring);
        assertTrue(clickPower[0] > 0.08, "low click band " + clickPower[0]);
        assertTrue(clickPower[1] > 0.08, "high click band " + clickPower[1]);
        assertTrue(snapCentroid > 1700 && snapCentroid < 2000, "snap " + snapCentroid);
    }

    @Test
    void cliWritesACompanionSoundFont() throws Exception {
        Path source = temp.resolve("snap.wav");
        SampleAudio.writeWav(source, 8000, new short[] {1000, 0, -1000, 0});
        Beat beat = Beat.drumKit("cli");
        beat.track(2).setMode(TrackMode.SAMPLE);
        beat.track(2).setSample(new SampleRef(source.getFileName().toString(), 60, 0, true));
        Path beatFile = temp.resolve("cli.json");
        Files.writeString(beatFile, BeatJson.write(beat));
        Path midi = temp.resolve("cli.mid");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = HitsCli.run(
            new String[] {"render", beatFile.toString(), "--out", midi.toString(), "--samples-sf2"},
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8)
        );
        assertEquals(HitsCli.OK, code, err.toString(StandardCharsets.UTF_8));
        assertTrue(Files.isRegularFile(midi));
        Path font = BeatFiles.samplesFont(midi);
        assertTrue(Files.isRegularFile(font), out.toString(StandardCharsets.UTF_8));
        assertNotNull(MidiSystem.getSoundbank(font.toFile()));
        int info = HitsCli.run(
            new String[] {"info", beatFile.toString(), "--samples-sf2"},
            new PrintStream(new ByteArrayOutputStream()),
            new PrintStream(err, true, StandardCharsets.UTF_8)
        );
        assertEquals(HitsCli.USAGE, info);
    }

    @Test
    void collectCopiesBuiltinsAndFilesBesideTheBeat() throws Exception {
        Path source = temp.resolve("room.wav");
        SampleAudio.writeWav(source, 44100, new short[] {1, 2, 3, 4});
        Beat beat = Beat.drumKit("Packed");
        beat.track(0).setMode(TrackMode.SAMPLE);
        beat.track(0).setSample(SampleRef.builtin(BuiltinSamples.EIGHT_OH_EIGHT, 36));
        beat.track(1).setMode(TrackMode.SAMPLE);
        beat.track(1).setSample(new SampleRef(source.toAbsolutePath().toString(), 38, 0, true));
        Path beatFile = temp.resolve("Packed.json");
        int updated = SamplePaths.collect(beat, beatFile);
        assertEquals(2, updated);
        assertEquals("Packed.samples/808.wav", beat.track(0).sample().path());
        assertEquals("Packed.samples/room.wav", beat.track(1).sample().path());
        assertTrue(Files.isRegularFile(temp.resolve("Packed.samples/808.wav")));
        assertTrue(Files.isRegularFile(temp.resolve("Packed.samples/room.wav")));
    }

    private Path renderSample(Path wav, boolean oneShot, int gate) throws Exception {
        Beat beat = sampleBeat(wav.toAbsolutePath().toString(), oneShot, gate);
        Path out = temp.resolve((oneShot ? "shot" : "choke") + ".wav");
        try {
            BeatFiles.exportWav(beat, out, ExportOptions.matchingPlayback(), null, null, () -> false, null);
        } catch (Exception exception) {
            if (WavRenderer.unavailable(exception)) {
                Assumptions.abort("offline render is not available: " + exception.getMessage());
            }
            throw exception;
        }
        return out;
    }

    private static Beat sampleBeat(String path, boolean oneShot, int gate) {
        Beat beat = Beat.drumKit("sample");
        beat.setBpm(120);
        beat.setSwing(50);
        beat.setSlotSteps('a', 16);
        for (int row = 0; row < Beat.TRACKS; row++) {
            beat.track(row).clearSteps();
        }
        beat.track(0).setNote(36);
        beat.track(0).setMode(TrackMode.SAMPLE);
        beat.track(0).setSample(new SampleRef(path, 36, 0, oneShot));
        beat.track(0).setGate(gate);
        beat.track(0).tap(0, false);
        return beat;
    }

    private static List<Integer> noteOns(Sequence sequence, int channel) {
        List<Integer> notes = new ArrayList<>();
        for (javax.sound.midi.Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                MidiEvent event = track.get(i);
                if (event.getMessage() instanceof ShortMessage message
                    && message.getCommand() == ShortMessage.NOTE_ON
                    && message.getChannel() == channel
                    && message.getData2() > 0) {
                    notes.add(message.getData1());
                }
            }
        }
        return notes;
    }

    private static boolean hasControl(Sequence sequence, int channel, int controller, int value) {
        for (javax.sound.midi.Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                MidiEvent event = track.get(i);
                if (event.getMessage() instanceof ShortMessage message
                    && message.getCommand() == ShortMessage.CONTROL_CHANGE
                    && message.getChannel() == channel
                    && message.getData1() == controller
                    && message.getData2() == value) {
                    return true;
                }
            }
        }
        return false;
    }

    private static double[] mono(Path wav) throws Exception {
        try (AudioInputStream stream = AudioSystem.getAudioInputStream(wav.toFile())) {
            SampleAudio.Clip clip = SampleAudio.read(stream);
            return toDouble(clip.samples());
        }
    }

    private static double[] toDouble(short[] samples) {
        double[] out = new double[samples.length];
        for (int i = 0; i < samples.length; i++) {
            out[i] = samples[i] / 32768.0;
        }
        return out;
    }

    private static int onset(double[] samples) {
        double peak = 0;
        for (double sample : samples) {
            peak = Math.max(peak, Math.abs(sample));
        }
        double threshold = peak * 0.05;
        for (int i = 0; i < samples.length; i++) {
            if (Math.abs(samples[i]) >= threshold) {
                return i;
            }
        }
        return 0;
    }

    private static int audibleMs(double[] samples) {
        int start = onset(samples);
        double peak = 0;
        for (double sample : samples) {
            peak = Math.max(peak, Math.abs(sample));
        }
        double threshold = peak * Math.pow(10, -40 / 20.0);
        int last = start;
        for (int i = start; i < samples.length; i++) {
            if (Math.abs(samples[i]) >= threshold) {
                last = i;
            }
        }
        return (int) Math.round((last - start) * 1000.0 / 44100.0);
    }

    private static int ringMs(short[] samples, int rate, double db) {
        double peak = 0;
        for (short sample : samples) {
            peak = Math.max(peak, Math.abs(sample));
        }
        double threshold = peak * Math.pow(10, db / 20.0);
        int last = 0;
        for (int i = 0; i < samples.length; i++) {
            if (Math.abs(samples[i]) >= threshold) {
                last = i;
            }
        }
        return (int) Math.round(last * 1000.0 / rate);
    }

    /** Average pitch from interpolated rising zero crossings. */
    private static double pitchHz(short[] samples, int rate, double from, double to) {
        int start = Math.max(1, (int) Math.round(from * rate));
        int end = Math.min(samples.length - 1, (int) Math.round(to * rate));
        double previous = -1;
        double sum = 0;
        int periods = 0;
        for (int i = start; i <= end; i++) {
            if (samples[i - 1] <= 0 && samples[i] > 0) {
                double delta = samples[i] - samples[i - 1];
                double frac = delta == 0 ? 0 : -samples[i - 1] / (double) delta;
                double time = (i - 1 + frac) / rate;
                if (previous >= 0) {
                    double period = time - previous;
                    if (period > 0) {
                        sum += 1.0 / period;
                        periods++;
                    }
                }
                previous = time;
            }
        }
        return periods == 0 ? 0 : sum / periods;
    }

    /** Power share in 300–600 Hz and 2.4–4.8 kHz. */
    private static double[] bandShare(short[] samples, int rate) {
        int n = 4096;
        double[] real = new double[n];
        double[] imag = new double[n];
        int count = Math.min(n, samples.length);
        for (int i = 0; i < count; i++) {
            double hann = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / Math.max(1, count - 1));
            real[i] = (samples[i] / 32768.0) * hann;
        }
        fft(real, imag);
        double low = 0;
        double high = 0;
        double total = 0;
        for (int i = 1; i < n / 2; i++) {
            double power = real[i] * real[i] + imag[i] * imag[i];
            double hz = i * (rate / (double) n);
            total += power;
            if (hz >= 300 && hz < 600) {
                low += power;
            }
            if (hz >= 2400 && hz < 4800) {
                high += power;
            }
        }
        if (total == 0) {
            return new double[] {0, 0};
        }
        return new double[] {low / total, high / total};
    }

    private static double centroid(double[] samples, int start) {
        int n = 4096;
        double[] real = new double[n];
        double[] imag = new double[n];
        int count = Math.min(n, Math.max(0, samples.length - start));
        for (int i = 0; i < count; i++) {
            double hann = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / Math.max(1, count - 1));
            real[i] = samples[start + i] * hann;
        }
        fft(real, imag);
        double weight = 0;
        double mass = 0;
        for (int i = 1; i < n / 2; i++) {
            double power = real[i] * real[i] + imag[i] * imag[i];
            double hz = i * (44100.0 / n);
            weight += hz * power;
            mass += power;
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
            for (int i = 0; i < n; i += length) {
                double curReal = 1;
                double curImag = 0;
                for (int k = 0; k < length / 2; k++) {
                    int even = i + k;
                    int odd = even + length / 2;
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
