package hits;

import hits.cli.HitsCli;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.midi.MidiSystem;
import javax.sound.midi.Soundbank;
import javax.sound.midi.SoundbankResource;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-row peaking EQ and high cut. The filter is baked into PCM, in the same
 * order as low cut and presence, so playback and WAV share one voice.
 */
class EqTest {
    private static final int FFT = 8192;
    private static final int RATE = 44100;

    @TempDir
    Path temp;

    @Test
    void peakingCentreBandwidthAndHighCutCorner() {
        int rate = RATE;
        assertEquals(-12, ToneFilter.peakingDb(rate, 1000, -12, 1.4, 1000), 0.35, "cut at the centre");
        assertEquals(12, ToneFilter.peakingDb(rate, 1000, 12, 1.4, 1000), 0.35, "boost at the centre");
        assertEquals(0, ToneFilter.peakingDb(rate, 1000, -12, 1.4, 50), 1.0, "far below the band");
        assertEquals(-12, ToneFilter.peakingDb(rate, 90, -12, 1.4, 90), 0.4, "90 Hz centre");

        double low = halfGain(rate, 1000, -12, 1.4, false);
        double high = halfGain(rate, 1000, -12, 1.4, true);
        double octaves = Math.log(high / low) / Math.log(2);
        double expected = ToneFilter.bandwidthOctaves(1.4);
        System.out.println(String.format(
            Locale.ROOT,
            "peaking Q 1.4 bandwidth %.3f octaves, expected %.3f (%.1f–%.1f Hz)",
            octaves, expected, low, high
        ));
        assertEquals(expected, octaves, 0.12, "bandwidth versus Q");

        assertEquals(-3.01, ToneFilter.lowPassDb(rate, 4000, 4000), 0.5, "high cut at the corner");
        double octave = ToneFilter.lowPassDb(rate, 4000, 8000);
        assertEquals(-12.3, octave, 1.5, "12 dB/oct one octave above the corner, got " + octave);
        assertTrue(ToneFilter.lowPassDb(rate, 4000, 500) > -0.4, "passband of the high cut");
    }

    @Test
    void flatEqSavesTheSameBytesAndRoundTrips() throws Exception {
        String golden = Files.readString(Path.of("src/test/resources/slots/ab-plain.json"));
        Beat plain = Beat.drumKit("plain");
        plain.track(0).setEqBand(0, 90, -12, 1.4);
        plain.track(0).setHighCut(6000);
        plain.track(0).setPresence(6.5);
        plain.track(0).clearEq();
        plain.track(0).setPresence(0);
        assertEquals(golden, BeatJson.write(plain));
        assertFalse(BeatJson.write(plain).contains("\"eq\""));

        Beat beat = Beat.drumKit("eq");
        beat.track(0).setEqBand(0, 90, -12, 1.4);
        beat.track(0).setEqBand(1, 2400, 3.5, 0.7);
        beat.track(0).setHighCut(6000);
        beat.track(0).setPresence(6);
        String json = BeatJson.write(beat);
        assertTrue(json.contains(
            "\"eq\": {\"bands\": [{\"hz\": 90, \"gain\": -12, \"q\": 1.4}, {\"hz\": 2400, \"gain\": 3.5, \"q\": 0.7}], \"highCut\": 6000}"
        ), json);
        assertTrue(json.contains("\"presence\": 6\n") || json.contains("\"presence\": 6,"), json);
        assertFalse(json.contains("\"presence\": 6.0"));
        assertEquals(json, BeatJson.write(BeatJson.read(json)));
        Beat loaded = BeatJson.read(json);
        assertEquals(90, loaded.track(0).eqBand(0).hz());
        assertEquals(-12.0, loaded.track(0).eqBand(0).gain());
        assertEquals(1.4, loaded.track(0).eqBand(0).q(), 1e-9);
        assertEquals(3.5, loaded.track(0).eqBand(1).gain());
        assertEquals(6000, loaded.track(0).highCutHz());
        assertTrue(loaded.track(0).copy().sameAs(loaded.track(0)));
        assertTrue(loaded.track(0).shapeText().contains("eq 90 Hz -12 dB Q 1.4"), loaded.track(0).shapeText());
        assertTrue(loaded.track(0).shapeText().contains("high cut 6000 Hz"), loaded.track(0).shapeText());
    }

    @Test
    void fractionalGainsSaveExactlyAndOutOfRangeValuesWarn() throws Exception {
        Beat beat = Beat.drumKit("levels");
        beat.track(0).setName("Kick");
        beat.track(0).setMode(TrackMode.SAMPLE);
        beat.track(0).setSample(new SampleRef("hits:click", 60, 0, true, 3.6, false));
        beat.track(0).setPresence(6.5);
        beat.track(0).setEqBand(0, 90, -12.5, 1.4);
        String json = BeatJson.write(beat);
        assertTrue(json.contains("\"gain\": 3.6"), json);
        assertTrue(json.contains("\"presence\": 6.5"), json);
        assertTrue(json.contains("\"gain\": -12.5"), json);
        assertFalse(json.contains("3.60"), json);
        Beat exact = BeatJson.read(json);
        assertTrue(exact.loadWarnings().isEmpty(), exact.loadWarnings().toString());
        assertEquals(3.6, exact.track(0).sample().gain());
        assertEquals(6.5, exact.track(0).presence());
        assertEquals(-12.5, exact.track(0).eqBand(0).gain());

        beat.track(0).setSample(beat.track(0).sample().withGain(4));
        beat.track(0).setPresence(6);
        String whole = BeatJson.write(beat);
        assertTrue(whole.contains("\"gain\": 4"), whole);
        assertFalse(whole.contains("\"gain\": 4.0"), whole);
        assertTrue(whole.contains("\"presence\": 6,") || whole.contains("\"presence\": 6\n"), whole);
        assertFalse(whole.contains("\"presence\": 6.0"), whole);

        String roundedGain = json.replace("\"gain\": 3.6", "\"gain\": 3.64");
        Beat gainRounded = BeatJson.read(roundedGain);
        assertEquals(3.6, gainRounded.track(0).sample().gain());
        String gainWarning = gainRounded.gainWarning('a', "Kick");
        assertEquals("A Kick: sample gain 3.64 dB was rounded to 3.6 dB.", gainWarning);

        String hotPresence = json.replace("\"presence\": 6.5", "\"presence\": 15");
        Beat presenceClamped = BeatJson.read(hotPresence);
        assertEquals(12.0, presenceClamped.track(0).presence());
        assertEquals(
            "A Kick: presence 15 dB was clamped to 12 dB.",
            presenceClamped.rowWarning('a', "Kick", "presence")
        );

        String hotEq = json.replace("\"gain\": -12.5", "\"gain\": 20");
        Beat eqClamped = BeatJson.read(hotEq);
        assertEquals(12.0, eqClamped.track(0).eqBand(0).gain());
        assertEquals(
            "A Kick: EQ band 1 gain 20 dB was clamped to 12 dB.",
            eqClamped.rowWarning('a', "Kick", "EQ band 1 gain")
        );

        Path file = temp.resolve("levels.json");
        Files.writeString(file, hotPresence);
        Cli info = cli("info", file.toString());
        assertEquals(0, info.code, info.err);
        assertTrue(info.err.contains("presence 15 dB was clamped to 12 dB"), info.err);
        Path mid = temp.resolve("levels.mid");
        Cli midi = cli("render", file.toString(), "--out", mid.toString());
        assertEquals(0, midi.code, midi.err);
        assertTrue(midi.err.contains("presence 15 dB was clamped to 12 dB"), midi.err);
        assertTrue(midi.err.contains("not in the MIDI file"), midi.err);
    }

    @Test
    void midiDropsEqAndWavKeepsThePeakingCut() throws Exception {
        Beat beat = hit(36, "Kick");
        beat.track(0).setEqBand(0, 90, -12, 1.4);
        Path midi = temp.resolve("kick.mid");
        BeatFiles.exportMidi(beat, midi, ExportOptions.matchingPlayback(), null, false);
        javax.sound.midi.Sequence file = MidiSystem.getSequence(midi.toFile());
        assertEquals(Gm.DRUM_CHANNEL, noteChannel(file, 36));
        assertTrue(beat.midiFilterWarning().contains("A Kick"), beat.midiFilterWarning());

        Path openWav = temp.resolve("kick-open.wav");
        Path cutWav = temp.resolve("kick-eq.wav");
        renderWav(hit(36, "Kick"), openWav);
        renderWav(beat, cutWav);
        double[] openAudio = mono(openWav);
        double[] cutAudio = mono(cutWav);
        double[] delta = delta(bands(openAudio), bands(cutAudio));
        double sub = band(spectrum(cutAudio, onset(cutAudio)), 30, 60)
            - band(spectrum(openAudio, onset(openAudio)), 30, 60);
        double lowMid = band(spectrum(cutAudio, onset(cutAudio)), 200, 600)
            - band(spectrum(openAudio, onset(openAudio)), 200, 600);
        String table = bandTable("GM kick note 36, peaking 90 Hz -12 dB Q 1.4, dB versus open", delta);
        System.out.println(bandTable("kick open", bands(openAudio)));
        System.out.println(bandTable("kick peaking", bands(cutAudio)));
        System.out.println(table);
        System.out.println(String.format(Locale.ROOT, "kick 30-60 %+.2f dB, 200-600 %+.2f dB", sub, lowMid));
        assertTrue(delta[1] <= -9, "60–120 Hz moved " + delta[1] + " dB\n" + table);
        // A Q of 1.4 is about an octave wide, so the skirt is already −5 dB at 60 Hz
        // and −1 dB at 30 Hz. This kick's sub sits near the top of 30–60 Hz, and the
        // integrated band moves about −3.6 dB. 200–600 Hz stays well inside 3 dB.
        assertTrue(Math.abs(sub) <= 4, "30–60 Hz moved " + sub + " dB\n" + table);
        assertTrue(Math.abs(lowMid) <= 3, "200–600 Hz moved " + lowMid + " dB\n" + table);

        Path json = temp.resolve("kick.json");
        Files.writeString(json, BeatJson.write(beat));
        Path cliWav = temp.resolve("kick-cli.wav");
        Cli cli = cli("render", json.toString(), "--out", cliWav.toString());
        assertEquals(HitsCli.OK, cli.code, cli.err);
        assertFalse(cli.err.contains("not in the MIDI file"), cli.err);
        double[] cliDelta = delta(bands(mono(cutWav)), bands(mono(cliWav)));
        System.out.println("cli vs exportWav " + format(cliDelta));
        assertTrue(Math.abs(cliDelta[1]) < 0.5, "CLI and export diverged on 60–120 Hz");
    }

    @Test
    void snareHighCutLowersTheCentroid() throws Exception {
        Beat openBeat = hit(38, "Snare");
        Beat cutBeat = hit(38, "Snare");
        cutBeat.track(0).setHighCut(4000);
        Path openWav = temp.resolve("snare-open.wav");
        Path cutWav = temp.resolve("snare-cut.wav");
        renderWav(openBeat, openWav);
        renderWav(cutBeat, cutWav);
        double[] openAudio = mono(openWav);
        double[] cutAudio = mono(cutWav);
        double openCentroid = centroid(openAudio);
        double cutCentroid = centroid(cutAudio);
        double[] delta = delta(bands(openAudio), bands(cutAudio));
        System.out.println(bandTable("snare open", bands(openAudio)));
        System.out.println(bandTable("snare high cut", bands(cutAudio)));
        System.out.println(bandTable("GM snare note 38, high cut 4 kHz, dB versus open", delta));
        System.out.println(String.format(
            Locale.ROOT,
            "snare centroid open %.0f Hz, high cut %.0f Hz",
            openCentroid,
            cutCentroid
        ));
        assertTrue(cutCentroid < openCentroid - 50, openCentroid + " -> " + cutCentroid);
        assertTrue(delta[4] < -3, ">3 kHz moved " + delta[4] + " dB");
    }

    @Test
    void eqAppliesToASampleRowAndADrumRowWithoutClipping() throws Exception {
        short[] tone = new short[RATE];
        for (int i = 0; i < tone.length; i++) {
            tone[i] = (short) Math.round(
                Math.sin(2 * Math.PI * 80 * i / RATE) * 14000
                    + Math.sin(2 * Math.PI * 400 * i / RATE) * 14000
            );
        }
        Path wav = temp.resolve("two-tone.wav");
        SampleAudio.writeWav(wav, RATE, tone);
        Beat sample = Beat.drumKit("sample");
        sample.setBpm(120);
        sample.setStepCount(1);
        for (int row = 0; row < Beat.TRACKS; row++) {
            sample.track(row).clearSteps();
        }
        Track row = sample.track(0);
        row.setName("Hit");
        row.setMode(TrackMode.SAMPLE);
        row.setNote(60);
        row.setSample(new SampleRef(wav.toAbsolutePath().toString(), 60, 0, true));
        row.tap(0, false);
        row.step(0).setVelocity(100);
        Path openSample = temp.resolve("sample-open.wav");
        Path cutSample = temp.resolve("sample-eq.wav");
        renderWav(sample, openSample);
        row.setEqBand(0, 80, -12, 2);
        renderWav(sample, cutSample);
        double[] openTone = toneBands(mono(openSample));
        double[] cutTone = toneBands(mono(cutSample));
        System.out.println(String.format(
            Locale.ROOT,
            "sample 80 Hz %+.1f dB, 400 Hz %+.1f dB",
            cutTone[0] - openTone[0],
            cutTone[1] - openTone[1]
        ));
        assertTrue(cutTone[0] - openTone[0] < -8, "80 Hz moved " + (cutTone[0] - openTone[0]));
        assertTrue(Math.abs(cutTone[1] - openTone[1]) < 3, "400 Hz moved " + (cutTone[1] - openTone[1]));

        Beat drum = hit(36, "Kick");
        drum.track(0).setEqBand(0, 90, -12, 1.4);
        SampleLibrary.Ready ready = SampleLibrary.prepare(drum, null, List.of('a'), null, true);
        assertTrue(ready.use().plays(drum.track(0), 0), ready.warning());

        short[] hot = new short[RATE / 2];
        for (int i = 0; i < hot.length; i++) {
            hot[i] = (short) Math.round(
                Math.sin(2 * Math.PI * 200 * i / RATE) * 16000
                    + Math.sin(2 * Math.PI * 8000 * i / RATE) * 12000
            );
        }
        Path hotWav = temp.resolve("hot.wav");
        SampleAudio.writeWav(hotWav, RATE, hot);
        Beat boosted = Beat.drumKit("hot");
        boosted.track(0).setName("Hot");
        boosted.track(0).setMode(TrackMode.SAMPLE);
        boosted.track(0).setSample(new SampleRef(hotWav.toAbsolutePath().toString(), 60, 0, true, 0, false));
        boosted.track(0).setEqBand(0, 200, 12, 2);
        boosted.track(0).setEqBand(1, 8000, 12, 2);
        boosted.track(0).setPresence(12);
        SampleLibrary.Ready bank = SampleLibrary.prepare(boosted, temp.resolve("hot.json"), List.of('a'));
        assertTrue(bank.use().plays(boosted.track(0), 0), bank.warning());
        Path sf2 = temp.resolve("hot.sf2");
        Files.write(sf2, bank.sf2());
        short[] stored = samplePcm(sf2);
        int flats = flatRun(stored, 3);
        int peak = 0;
        for (short value : stored) {
            peak = Math.max(peak, Math.abs(value));
        }
        System.out.println(String.format(Locale.ROOT, "boosted sample peak %d, flats %d", peak, flats));
        assertEquals(0, flats, "flat-topped samples " + flats);
        assertTrue(peak > 20000 && peak <= 32767, "peak " + peak);

        Beat loudKick = hit(36, "Kick");
        loudKick.track(0).setEqBand(0, 90, 12, 1.4);
        loudKick.track(0).setPresence(12);
        SampleLibrary.Ready kickBank = SampleLibrary.prepare(loudKick, null, List.of('a'), null, true);
        assertTrue(kickBank.use().plays(loudKick.track(0), 0), kickBank.warning());
        Path kickSf2 = temp.resolve("kick-boost.sf2");
        Files.write(kickSf2, kickBank.sf2());
        short[] kickPcm = samplePcm(kickSf2);
        assertEquals(0, flatRun(kickPcm, 3), "kick PCM flat-topped");
    }

    private static double halfGain(int rate, double center, double gain, double q, boolean above) {
        double target = gain / 2.0;
        double lo = above ? center : center / 32.0;
        double hi = above ? Math.min(rate * 0.45, center * 32.0) : center;
        for (int i = 0; i < 48; i++) {
            double mid = Math.sqrt(lo * hi);
            double db = ToneFilter.peakingDb(rate, center, gain, q, mid);
            boolean inside = Math.abs(db) >= Math.abs(target);
            if (above) {
                if (inside) {
                    lo = mid;
                } else {
                    hi = mid;
                }
            } else if (inside) {
                hi = mid;
            } else {
                lo = mid;
            }
        }
        return Math.sqrt(lo * hi);
    }

    private static Beat hit(int note, String name) {
        Beat beat = Beat.drumKit(name);
        beat.setBpm(120);
        beat.setSwing(50);
        beat.setStepCount(1);
        for (int row = 0; row < Beat.TRACKS; row++) {
            beat.track(row).clearSteps();
        }
        Track track = beat.track(0);
        track.setName(name);
        track.setNote(note);
        track.tap(0, false);
        track.step(0).setVelocity(ToneRender.CAPTURE_VELOCITY);
        return beat;
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

    private static Cli cli(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = HitsCli.run(
            args,
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8)
        );
        return new Cli(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private record Cli(int code, String out, String err) {}

    private static double[] toneBands(double[] audio) {
        double[] mag = spectrum(audio, 0);
        return new double[] {binDb(mag, 80), binDb(mag, 400)};
    }

    private static double centroid(double[] samples) {
        double[] mag = spectrum(samples, onset(samples));
        double weighted = 0;
        double energy = 0;
        for (int i = 1; i < mag.length; i++) {
            double power = mag[i] * mag[i];
            weighted += i * (RATE / (double) FFT) * power;
            energy += power;
        }
        return energy <= 1e-20 ? 0 : weighted / energy;
    }

    private static double[] bandsAt(double[] mono, int start) {
        double[] mag = spectrum(mono, start);
        return new double[] {
            band(mag, 30, 60),
            band(mag, 60, 120),
            band(mag, 120, 600),
            band(mag, 600, 3000),
            band(mag, 3000, RATE / 2.0)
        };
    }

    private static double[] bands(double[] mono) {
        return bandsAt(mono, onset(mono));
    }

    private static double[] delta(double[] base, double[] heard) {
        double[] out = new double[base.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = heard[i] - base[i];
        }
        return out;
    }

    private static String bandTable(String title, double[] delta) {
        String[] names = {"30-60", "60-120", "120-600", "600-3k", ">3k"};
        StringBuilder text = new StringBuilder(title).append('\n');
        for (int i = 0; i < names.length; i++) {
            text.append(String.format(Locale.ROOT, "%-10s %+8.2f%n", names[i], delta[i]));
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
        throw new AssertionError("SoundFont has no sample");
    }

    private static int flatRun(short[] pcm, int run) {
        int flats = 0;
        int held = 0;
        int sign = 0;
        for (short sample : pcm) {
            int rail = sample >= 32767 ? 1 : sample <= -32767 ? -1 : 0;
            if (rail != 0 && rail == sign) {
                held++;
                if (held == run) {
                    flats++;
                }
            } else if (rail != 0) {
                sign = rail;
                held = 1;
            } else {
                sign = 0;
                held = 0;
            }
        }
        return flats;
    }

    private static int noteChannel(javax.sound.midi.Sequence sequence, int note) {
        for (javax.sound.midi.Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                if (track.get(i).getMessage() instanceof javax.sound.midi.ShortMessage message
                    && message.getCommand() == javax.sound.midi.ShortMessage.NOTE_ON
                    && message.getData1() == note
                    && message.getData2() > 0) {
                    return message.getChannel();
                }
            }
        }
        throw new AssertionError("missing note " + note);
    }
}
