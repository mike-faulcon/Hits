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
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-row low cut and presence. The filter is baked into PCM, so playback and WAV
 * share one sample-bank voice. MIDI keeps the ordinary note.
 */
class LowCutTest {
    private static final int FFT = 8192;
    private static final int RATE = 44100;

    @TempDir
    Path temp;

    @Test
    void cornerIsAboutThreeDbAndTheSlopeHoldsAnOctaveBelow() {
        int rate = RATE;
        double corner12 = ToneFilter.highPassDb(rate, 120, 12, 120);
        double below12 = ToneFilter.highPassDb(rate, 120, 12, 60);
        double corner24 = ToneFilter.highPassDb(rate, 120, 24, 120);
        double below24 = ToneFilter.highPassDb(rate, 120, 24, 60);
        assertEquals(-3.01, corner12, 0.6, "12 dB/oct at the corner");
        assertEquals(-3.01, corner24, 0.6, "24 dB/oct at the corner");
        assertEquals(-12.3, below12, 1.0, "12 dB/oct one octave down");
        assertEquals(-24.1, below24, 1.5, "24 dB/oct one octave down");
        assertEquals(0, ToneFilter.highPassDb(rate, 120, 12, 2000), 0.25);
        assertEquals(0, ToneFilter.highPassDb(rate, 120, 24, 2000), 0.25);

        assertEquals(corner12, sineDb(rate, 120, 12, 120), 0.8);
        assertEquals(below12, sineDb(rate, 120, 12, 60), 0.8);
        assertEquals(corner24, sineDb(rate, 120, 24, 120), 0.8);
        assertEquals(below24, sineDb(rate, 120, 24, 60), 1.2);

        assertEquals(0, ToneFilter.shelfDb(rate, Track.PRESENCE_HZ, 12, 200), 1.0);
        assertEquals(6, ToneFilter.shelfDb(rate, Track.PRESENCE_HZ, 12, Track.PRESENCE_HZ), 1.5);
        double highs = ToneFilter.shelfDb(rate, Track.PRESENCE_HZ, 12, 12000);
        assertTrue(highs > 10 && highs < 12.2, "shelf at 12 kHz is " + highs);

        short[] plain = new short[] {1000, -1000, 500};
        assertSame(plain, ToneFilter.apply(plain, rate, 0, 12, 0));
    }

    @Test
    void sliderIsLogarithmicAndTheValueClamps() {
        assertEquals(20, Track.lowCutHz(0));
        assertEquals(2000, Track.lowCutHz(Track.LOW_CUT_SLIDER));
        assertEquals(120, Track.lowCutHz(Track.lowCutSlider(120)));
        assertEquals(0, Track.lowCutSlider(20));
        assertEquals(Track.LOW_CUT_SLIDER, Track.lowCutSlider(2000));

        Track track = new Track("Kick", TrackMode.DRUM, 0, 36);
        track.setLowCut(5, 99);
        assertEquals(Track.LOW_CUT_MIN, track.lowCutHz());
        assertEquals(Track.LOW_CUT_SLOPE, track.lowCutSlope());
        track.setLowCut(9000, Track.LOW_CUT_STEEP);
        assertEquals(Track.LOW_CUT_MAX, track.lowCutHz());
        assertEquals(24, track.lowCutSlope());
        track.setPresence(40);
        assertEquals(12.0, track.presence());
        track.clearLowCut();
        track.setPresence(0);
        assertFalse(track.hasTone());
        assertEquals(Track.LOW_CUT_SLOPE, track.lowCutSlope());
        assertEquals("", track.shapeText());
    }

    @Test
    void aBeatWithoutLowCutSavesTheSameBytes() throws Exception {
        String golden = Files.readString(Path.of("src/test/resources/slots/ab-plain.json"));
        Beat plain = Beat.drumKit("plain");
        assertFalse(plain.hasTone());
        assertEquals(1, BeatJson.documentVersion(plain));
        assertEquals(golden, BeatJson.write(plain));

        plain.track(0).setLowCut(120, 24);
        plain.track(0).setPresence(6);
        plain.track(0).clearLowCut();
        plain.track(0).setPresence(0);
        assertEquals(golden, BeatJson.write(plain));
        assertFalse(BeatJson.write(plain).contains("\"lowCut\""));
        assertFalse(BeatJson.write(plain).contains("\"presence\""));
    }

    @Test
    void lowCutAndPresenceRoundTrip() throws Exception {
        Beat beat = Beat.drumKit("filtered");
        beat.track(0).setLowCut(120, 24);
        beat.track(0).setPresence(6);
        assertTrue(beat.hasTone());
        assertEquals(2, BeatJson.documentVersion(beat));
        String json = BeatJson.write(beat);
        assertTrue(json.contains("\"lowCut\": {\"hz\": 120, \"slope\": 24}"));
        assertTrue(json.contains("\"presence\": 6"));
        assertEquals(json, BeatJson.write(BeatJson.read(json)));
        Beat loaded = BeatJson.read(json);
        assertEquals(120, loaded.track(0).lowCutHz());
        assertEquals(24, loaded.track(0).lowCutSlope());
        assertEquals(6.0, loaded.track(0).presence());
        assertTrue(loaded.track(0).copy().sameAs(loaded.track(0)));
        assertEquals("low cut 120 Hz 24 dB/oct, presence +6 dB", loaded.track(0).shapeText());

        Beat gentle = Beat.drumKit("gentle");
        gentle.track(2).setLowCut(800, 12);
        String written = BeatJson.write(gentle);
        assertTrue(written.contains("\"lowCut\": {\"hz\": 800, \"slope\": 12}"));
        assertFalse(written.contains("\"presence\""));
        Beat slopeDropped = BeatJson.read(written.replace(", \"slope\": 12", ""));
        assertEquals(800, slopeDropped.track(2).lowCutHz());
        assertEquals(12, slopeDropped.track(2).lowCutSlope());
    }

    @Test
    void aMovingSliderKeepsThePreviousVoiceUntilTheBankRebuilds() throws Exception {
        Path wav = temp.resolve("body.wav");
        SampleAudio.writeWav(wav, RATE, tone(RATE / 5, 80, 400));
        Beat beat = Beat.drumKit("two");
        sampleRow(beat.track(0), wav);
        beat.track(0).setLowCut(120, 12);
        sampleRow(beat.slot('b')[0], wav);
        beat.slot('b')[0].setLowCut(400, 24);
        SampleLibrary.Ready ready = SampleLibrary.prepare(beat, temp.resolve("two.json"), List.of('a', 'b'));
        assertEquals(0, ready.use().program(beat.track(0), 0));
        int other = ready.use().program(beat.slot('b')[0], 0);
        assertTrue(other > 0, "second preset " + other);
        int held = ready.use().program(beat.track(0), 0);
        beat.track(0).setLowCut(180, 12);
        assertTrue(ready.use().plays(beat.track(0), 0));
        assertEquals(held, ready.use().program(beat.track(0), 0));
        assertEquals(other, ready.use().program(beat.slot('b')[0], 0));
    }

    @Test
    void gainInvertAndLowCutApplyTogether() throws Exception {
        short[] source = tone(RATE, 80, 400);
        Path wav = temp.resolve("shaped.wav");
        SampleAudio.writeWav(wav, RATE, source);
        Beat shaped = sampleBeat(wav, SampleRef.GAIN, true, 120, 24);
        SampleLibrary.Ready ready = SampleLibrary.prepare(shaped, temp.resolve("shaped.json"), List.of('a'));
        assertTrue(ready.use().plays(shaped.track(0), 0), ready.warning());
        short[] flipped = new short[source.length];
        for (int i = 0; i < source.length; i++) {
            flipped[i] = (short) -source[i];
        }
        short[] filtered = ToneFilter.apply(flipped, RATE, 120, 24, 0);
        Path bank = temp.resolve("shaped.sf2");
        java.nio.file.Files.write(bank, ready.sf2());
        short[] stored = samplePcm(bank);
        int compared = Math.min(filtered.length, stored.length);
        int mismatches = 0;
        for (int i = 0; i < compared; i++) {
            if (stored[i] != filtered[i]) {
                mismatches++;
            }
        }
        assertEquals(0, mismatches, "stored PCM mismatches " + mismatches + " of " + compared);

        Path quietWav = temp.resolve("gain0.wav");
        Path boostedWav = temp.resolve("gain8.wav");
        Path uprightWav = temp.resolve("upright.wav");
        renderWav(sampleBeat(wav, 0, true, 120, 24), quietWav);
        renderWav(shaped, boostedWav);
        renderWav(sampleBeat(wav, SampleRef.GAIN, false, 120, 24), uprightWav);
        double[] quiet = toneBands(mono(quietWav));
        double[] boosted = toneBands(mono(boostedWav));
        double gain = boosted[1] - quiet[1];
        double low = boosted[0] - boosted[1];
        double phase = correlation(mono(boostedWav), mono(uprightWav));
        System.out.println(String.format(
            Locale.ROOT,
            "gain %+.1f dB, 80 vs 400 %+.1f dB, invert correlation %+.2f",
            gain,
            low,
            phase
        ));
        assertEquals(SampleRef.GAIN, gain, 3.0, "400 Hz gain");
        assertTrue(low < -8, "80 Hz versus 400 Hz is " + low + " dB");
        assertTrue(phase < -0.5, "invert correlation " + phase);
    }

    @Test
    void midiKeepsTheOrdinaryKickAndWavSelectsTheFilteredVoice() throws Exception {
        Beat beat = kick(track -> track.setLowCut(120, 24));
        Path midi = temp.resolve("kick.mid");
        BeatFiles.exportMidi(beat, midi, ExportOptions.matchingPlayback(), null, false);
        Sequence file = MidiSystem.getSequence(midi.toFile());
        assertEquals(Gm.DRUM_CHANNEL, noteChannel(file, 36));
        assertFalse(hasControl(file, 0, SampleBank.SF2_BANK), "MIDI selected the sample bank");

        SampleLibrary.Ready ready = SampleLibrary.prepare(beat, null, List.of('a'), null, true);
        assertTrue(ready.use().plays(beat.track(0), 0));
        Sequence live = SequenceBuilder.playback(beat, false, null, false, SoundCatalog.builtin(), ready.use());
        Sequence wav = SequenceBuilder.export(beat, ExportOptions.matchingPlayback(), SoundCatalog.builtin(), ready.use());
        assertEquals(SampleBank.SF2_BANK, controller(live, noteChannel(live, 36), 0));
        assertEquals(SampleBank.SF2_BANK, controller(wav, noteChannel(wav, 36), 0));
        assertEquals(noteChannel(live, 36), noteChannel(wav, 36));
        assertEquals(0, program(live, noteChannel(live, 36)));
        assertEquals(0, program(wav, noteChannel(wav, 36)));
    }

    @Test
    void sampleRowAndGmKickLoseTheLowsAndKeepTheBody() throws Exception {
        Path source = temp.resolve("two-tone.wav");
        SampleAudio.writeWav(source, RATE, tone(RATE, 80, 400));
        Beat sample = Beat.drumKit("sample");
        sample.setBpm(120);
        sample.setStepCount(1);
        for (int row = 0; row < Beat.TRACKS; row++) {
            sample.track(row).clearSteps();
        }
        sampleRow(sample.track(0), source);
        sample.track(0).setNote(60);
        sample.track(0).tap(0, false);
        sample.track(0).step(0).setVelocity(100);
        Path openSample = temp.resolve("sample-open.wav");
        Path cutSample = temp.resolve("sample-cut.wav");
        renderWav(sample, openSample);
        sample.track(0).setLowCut(120, 24);
        renderWav(sample, cutSample);
        double[] openTone = toneBands(mono(openSample));
        double[] cutTone = toneBands(mono(cutSample));
        double low = cutTone[0] - openTone[0];
        double body = cutTone[1] - openTone[1];
        System.out.println(String.format(Locale.ROOT, "sample 80 Hz %+.1f dB, 400 Hz %+.1f dB", low, body));
        assertTrue(low < -8, "80 Hz moved " + low + " dB");
        assertTrue(Math.abs(body) < 4, "400 Hz moved " + body + " dB");

        Path midi = temp.resolve("sample.mid");
        BeatFiles.exportMidi(sample, midi, ExportOptions.matchingPlayback(), temp.resolve("sample.json"), true);
        double[] stored = toneBands(samplePcm(BeatFiles.samplesFont(midi)));
        System.out.println(String.format(
            Locale.ROOT,
            "midi companion 80 Hz %.1f dB, 400 Hz %.1f dB",
            stored[0],
            stored[1]
        ));
        assertTrue(Math.abs(stored[0] - stored[1]) < 3, "MIDI companion filtered the sample");

        Beat plain = kick(track -> {});
        Beat slope12 = kick(track -> track.setLowCut(120, 12));
        Beat slope24 = kick(track -> track.setLowCut(120, 24));
        Path plainWav = temp.resolve("kick-open.wav");
        Path gentleWav = temp.resolve("kick-12.wav");
        Path steepWav = temp.resolve("kick-24.wav");
        renderWav(plain, plainWav);
        renderWav(slope12, gentleWav);
        writeBeat(slope24, temp.resolve("kick.json"));
        Path cliWav = temp.resolve("kick-cli.wav");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = HitsCli.run(
            new String[] {"render", temp.resolve("kick.json").toString(), "--out", cliWav.toString()},
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8)
        );
        assertEquals(HitsCli.OK, code, err.toString(StandardCharsets.UTF_8));
        renderWav(slope24, steepWav);

        double[] base = bands(mono(plainWav));
        double[] gentle = delta(base, bands(mono(gentleWav)));
        double[] steep = delta(base, bands(mono(steepWav)));
        double[] cli = delta(base, bands(mono(cliWav)));
        String table = bandTable(gentle, steep);
        System.out.println(table);
        System.out.println("cli vs exportWav 24 dB/oct " + format(delta(bands(mono(steepWav)), bands(mono(cliWav)))));
        // Reverb is applied at playback, not baked into the recording, so the room
        // fills some of 60–120 Hz that a wet capture used to filter away.
        assertTrue(steep[1] <= -8, "60–120 Hz moved " + steep[1] + " dB\n" + table);
        assertTrue(steep[1] < gentle[1] - 2, "24 dB/oct should cut 60–120 harder than 12\n" + table);
        assertTrue(Math.abs(steep[2]) <= 3, "120–600 Hz moved " + steep[2] + " dB\n" + table);
        assertTrue(Math.abs(cli[1] - steep[1]) < 0.5, "CLI and export diverged on 60–120 Hz");
        assertTrue(Math.abs(cli[2] - steep[2]) < 0.5, "CLI and export diverged on 120–600 Hz");
    }

    private static double sineDb(int rate, int corner, int slope, double freq) {
        int count = rate;
        short[] input = new short[count];
        for (int i = 0; i < count; i++) {
            input[i] = (short) Math.round(Math.sin(2 * Math.PI * freq * i / rate) * 8000);
        }
        short[] output = ToneFilter.apply(input, rate, corner, slope, 0);
        int from = count / 2;
        double before = rms(input, from, count);
        double after = rms(output, from, count);
        return 20 * Math.log10(after / before);
    }

    private static double rms(short[] samples, int start, int end) {
        double sum = 0;
        int count = 0;
        for (int i = start; i < end; i++) {
            double sample = samples[i];
            sum += sample * sample;
            count++;
        }
        return Math.sqrt(sum / count);
    }

    private static short[] tone(int count, double lowHz, double highHz) {
        short[] pcm = new short[count];
        for (int i = 0; i < count; i++) {
            double sample = Math.sin(2 * Math.PI * lowHz * i / RATE) * 6000
                + Math.sin(2 * Math.PI * highHz * i / RATE) * 6000;
            pcm[i] = (short) Math.round(sample);
        }
        return pcm;
    }

    private static void sampleRow(Track track, Path wav) {
        track.setMode(TrackMode.SAMPLE);
        track.setSample(new SampleRef(wav.toAbsolutePath().toString(), 60, 0, true));
    }

    private static Beat sampleBeat(Path wav, int gain, boolean invert, int hz, int slope) {
        Beat beat = Beat.drumKit("shaped");
        beat.setBpm(120);
        beat.setStepCount(1);
        for (int row = 0; row < Beat.TRACKS; row++) {
            beat.track(row).clearSteps();
        }
        Track track = beat.track(0);
        track.setMode(TrackMode.SAMPLE);
        track.setNote(60);
        track.setSample(new SampleRef(wav.toAbsolutePath().toString(), 60, 0, true).withGain(gain).withInvert(invert));
        track.setLowCut(hz, slope);
        track.tap(0, false);
        track.step(0).setVelocity(ToneRender.CAPTURE_VELOCITY);
        return beat;
    }

    /** Sign agreement of two renders. Negative means one is inverted. */
    private static double correlation(double[] left, double[] right) {
        int from = RATE / 50;
        int to = Math.min(left.length, Math.min(right.length, RATE / 5));
        double dot = 0;
        double leftEnergy = 0;
        double rightEnergy = 0;
        for (int i = from; i < to; i++) {
            dot += left[i] * right[i];
            leftEnergy += left[i] * left[i];
            rightEnergy += right[i] * right[i];
        }
        if (leftEnergy == 0 || rightEnergy == 0) {
            return 0;
        }
        return dot / Math.sqrt(leftEnergy * rightEnergy);
    }

    private static Beat kick(Consumer<Track> edit) {
        Beat beat = Beat.drumKit("kick");
        beat.setBpm(120);
        beat.setSwing(50);
        beat.setStepCount(1);
        for (int row = 0; row < Beat.TRACKS; row++) {
            beat.track(row).clearSteps();
        }
        Track track = beat.track(0);
        track.setNote(36);
        track.tap(0, false);
        track.step(0).setVelocity(ToneRender.CAPTURE_VELOCITY);
        edit.accept(track);
        return beat;
    }

    private static void writeBeat(Beat beat, Path path) throws Exception {
        BeatFiles.write(beat, path);
    }

    private static void renderWav(Beat beat, Path wav) throws Exception {
        try {
            BeatFiles.exportWav(beat, wav, ExportOptions.matchingPlayback(), null, null, () -> false, null);
        } catch (Exception exception) {
            if (WavRenderer.unavailable(exception)) {
                Assumptions.abort("offline render is not available: " + exception.getMessage());
            }
            throw exception;
        }
    }

    /** 80 Hz and 400 Hz levels, in dB, from the start of a mono clip. */
    private static double[] toneBands(short[] pcm) {
        double[] audio = new double[pcm.length];
        for (int i = 0; i < pcm.length; i++) {
            audio[i] = pcm[i] / 32768.0;
        }
        return toneBands(audio);
    }

    private static double[] toneBands(double[] audio) {
        double[] mag = spectrum(audio, 0);
        return new double[] {binDb(mag, 80), binDb(mag, 400)};
    }

    private static short[] samplePcm(Path sf2) throws Exception {
        Soundbank bank = MidiSystem.getSoundbank(sf2.toFile());
        for (SoundbankResource resource : bank.getResources()) {
            if (!"SF2Sample".equals(resource.getClass().getSimpleName()) || "EOS".equals(resource.getName())) {
                continue;
            }
            Object data = resource.getData();
            if (!(data instanceof AudioInputStream stream)) {
                continue;
            }
            byte[] bytes = stream.readAllBytes();
            int frames = bytes.length / Math.max(1, stream.getFormat().getFrameSize());
            short[] pcm = new short[frames];
            int channels = stream.getFormat().getChannels();
            for (int i = 0; i < frames; i++) {
                int offset = i * stream.getFormat().getFrameSize();
                int sample = (bytes[offset] & 0xff) | (bytes[offset + 1] << 8);
                if (channels > 1) {
                    int right = (bytes[offset + 2] & 0xff) | (bytes[offset + 3] << 8);
                    sample = (sample + (short) right) / 2;
                }
                pcm[i] = (short) sample;
            }
            return pcm;
        }
        throw new AssertionError("companion SoundFont has no sample");
    }

    /** Energy in 30–60, 60–120, 120–600, 600–3k, and above 3 kHz, in dB. */
    private static double[] bands(double[] mono) {
        int onset = onset(mono);
        double[] mag = spectrum(mono, onset);
        return new double[] {
            band(mag, 30, 60),
            band(mag, 60, 120),
            band(mag, 120, 600),
            band(mag, 600, 3000),
            band(mag, 3000, RATE / 2.0)
        };
    }

    private static double[] delta(double[] base, double[] heard) {
        double[] out = new double[base.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = heard[i] - base[i];
        }
        return out;
    }

    private static String bandTable(double[] slope12, double[] slope24) {
        String[] names = {"30-60", "60-120", "120-600", "600-3k", ">3k"};
        StringBuilder text = new StringBuilder("GM kick note 36, low cut 120 Hz, dB versus open\n");
        text.append(String.format(Locale.ROOT, "%-10s %8s %8s%n", "band", "12 dB/oct", "24 dB/oct"));
        for (int i = 0; i < names.length; i++) {
            text.append(String.format(Locale.ROOT, "%-10s %+8.1f %+8.1f%n", names[i], slope12[i], slope24[i]));
        }
        return text.toString();
    }

    private static String format(double[] values) {
        String[] names = {"30-60", "60-120", "120-600", "600-3k", ">3k"};
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < names.length; i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append(String.format(Locale.ROOT, "%s %+.2f", names[i], values[i]));
        }
        return text.toString();
    }

    private static int onset(double[] samples) {
        double peak = 0;
        for (double sample : samples) {
            peak = Math.max(peak, Math.abs(sample));
        }
        double started = peak * 0.1;
        for (int i = 0; i < samples.length; i++) {
            if (Math.abs(samples[i]) >= started) {
                return i;
            }
        }
        return 0;
    }

    private static double band(double[] mag, double low, double high) {
        double sum = 0;
        for (int i = 1; i < mag.length; i++) {
            double hz = i * (RATE / (double) FFT);
            if (hz >= low && hz < high) {
                sum += mag[i] * mag[i];
            }
        }
        if (sum <= 1e-20) {
            return -120;
        }
        return 10 * Math.log10(sum);
    }

    private static double binDb(double[] mag, double hz) {
        int bin = (int) Math.round(hz * FFT / RATE);
        double sum = 0;
        for (int i = Math.max(1, bin - 1); i <= bin + 1 && i < mag.length; i++) {
            sum += mag[i] * mag[i];
        }
        if (sum <= 1e-20) {
            return -120;
        }
        return 10 * Math.log10(sum);
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
                    int left = start + i;
                    int right = left + half;
                    double evenReal = real[left];
                    double evenImag = imag[left];
                    double oddReal = real[right] * curReal - imag[right] * curImag;
                    double oddImag = real[right] * curImag + imag[right] * curReal;
                    real[left] = evenReal + oddReal;
                    imag[left] = evenImag + oddImag;
                    real[right] = evenReal - oddReal;
                    imag[right] = evenImag - oddImag;
                    double nextReal = curReal * wReal - curImag * wImag;
                    curImag = curReal * wImag + curImag * wReal;
                    curReal = nextReal;
                }
            }
        }
    }

    private static double[] mono(Path wav) throws Exception {
        try (AudioInputStream in = AudioSystem.getAudioInputStream(wav.toFile())) {
            byte[] bytes = in.readAllBytes();
            int frames = bytes.length / 4;
            double[] mono = new double[frames];
            for (int i = 0; i < frames; i++) {
                int left = (bytes[i * 4] & 0xff) | (bytes[i * 4 + 1] << 8);
                int right = (bytes[i * 4 + 2] & 0xff) | (bytes[i * 4 + 3] << 8);
                mono[i] = (((short) left) + ((short) right)) / 2.0 / 32768.0;
            }
            return mono;
        }
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

    private static int controller(Sequence sequence, int channel, int controller) {
        for (MidiEvent event : events(sequence)) {
            if (event.getMessage() instanceof ShortMessage message
                && message.getCommand() == ShortMessage.CONTROL_CHANGE
                && message.getChannel() == channel
                && message.getData1() == controller) {
                return message.getData2();
            }
        }
        throw new AssertionError("missing controller " + controller + " on " + channel);
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

    private static boolean hasControl(Sequence sequence, int channel, int value) {
        for (MidiEvent event : events(sequence)) {
            if (event.getMessage() instanceof ShortMessage message
                && message.getCommand() == ShortMessage.CONTROL_CHANGE
                && message.getChannel() == channel
                && message.getData1() == 0
                && message.getData2() == value) {
                return true;
            }
        }
        return false;
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
}
