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
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The filter bugs from the v7 re-score: a filtered row must not change the plain
 * row in another pattern, and a filtered hit must line up, stay dry, and keep its level.
 */
class FilterFixTest {
    private static final int RATE = WavAudio.SAMPLE_RATE;
    private static final int BAR = RATE * 2;

    @TempDir
    Path temp;

    @Test
    void filteredPatternDoesNotChangeThePlainDrumOnTheSameRow() throws Exception {
        Beat chain = chainBeat();
        Beat reference = chain.copy();
        Track empty = reference.slot('a')[0];
        empty.clearSteps();
        empty.clearLowCut();
        empty.setPresence(0);
        empty.setTune(0);

        double[] chained = renderSong(chain, temp.resolve("chain.wav"));
        double[] alone = renderSong(reference, temp.resolve("alone.wav"));
        int from = Math.min(BAR, chained.length);
        int to = Math.min(BAR * 2, Math.min(chained.length, alone.length));
        double residual = residualDb(chained, alone, from, to);
        double low = bandDelta(chained, alone, from, 0, 60);
        double high = bandDelta(chained, alone, from, 3000, 12000);
        System.out.println(String.format(
            Locale.ROOT,
            "S1 bar B residual %.1f dB, 0-60 %.1f dB, >3k %.1f dB",
            residual, low, high
        ));
        assertTrue(Double.isInfinite(residual) || residual < -60, "bar B residual " + residual + " dB");

        SampleLibrary.Ready ready = SampleLibrary.prepare(chain, temp.resolve("chain.json"), java.util.List.of('a', 'b'));
        Sequence sequence = SequenceBuilder.export(
            chain, new ExportOptions(true, false, 1, true), SoundCatalog.builtin(), ready.use()
        );
        int bar = 16 * SequenceBuilder.STEP_TICKS;
        MidiEvent note = noteOnAt(sequence, 36, bar);
        ShortMessage message = (ShortMessage) note.getMessage();
        int bank = lastController(sequence, message.getChannel(), 0, note.getTick());
        assertEquals(Gm.DRUM_BANK, bank, "bar B kick channel " + message.getChannel());
    }

    @Test
    void filteredDrumLinesUpWithTheUnfilteredDrum() throws Exception {
        for (int tune : new int[] {0, 1500}) {
            double[] plain = render(kick(tune, 0, 0, 100), temp.resolve("onset-plain-" + tune + ".wav"));
            double[] filtered = render(kick(tune, 20, 0, 100), temp.resolve("onset-cut-" + tune + ".wav"));
            int plainAt = onset(plain);
            int filteredAt = onset(filtered);
            int delta = Math.abs(filteredAt - plainAt);
            System.out.println(String.format(
                Locale.ROOT,
                "S2 tune %+d st onset delta %d samples (%.2f ms)",
                tune / 100, delta, delta * 1000.0 / RATE
            ));
            assertTrue(delta < RATE / 1000, "onset delta " + delta + " samples at tune " + tune);
        }
    }

    @Test
    void dryCaptureDoesNotLengthenTheReverbRing() throws Exception {
        Beat plain = kick(0, 0, 0, 100);
        plain.track(0).setReverb(0);
        Beat filtered = kick(0, 20, 0, 100);
        filtered.track(0).setReverb(0);
        double[] open = render(plain, temp.resolve("ring-open.wav"));
        double[] cut = render(filtered, temp.resolve("ring-cut.wav"));
        double openMs = ringMs(open);
        double cutMs = ringMs(cut);
        System.out.println(String.format(Locale.ROOT, "S3 ring plain %.0f ms, low cut %.0f ms", openMs, cutMs));
        assertTrue(openMs > 40 && openMs < 400, "plain ring " + openMs);
        assertTrue(Math.abs(cutMs - openMs) / openMs < 0.10, "ring " + cutMs + " vs " + openMs);
    }

    @Test
    void filteredDrumKeepsTheUnfilteredLevel() throws Exception {
        for (int velocity : new int[] {80, 100, 127}) {
            double[] plain = render(kick(0, 0, 0, velocity), temp.resolve("level-plain-" + velocity + ".wav"));
            double[] filtered = render(kick(0, 20, 0, velocity), temp.resolve("level-cut-" + velocity + ".wav"));
            double delta = windowDb(filtered, onset(filtered)) - windowDb(plain, onset(plain));
            System.out.println(String.format(Locale.ROOT, "S4 velocity %d level %+.2f dB", velocity, delta));
            assertTrue(Math.abs(delta) < 0.5, "velocity " + velocity + " is " + delta + " dB");
        }
    }

    @Test
    void sampleLowCutDoesNotClipAndKeepsItsLevel() throws Exception {
        int count = RATE / 2;
        short[] source = new short[count];
        for (int i = 0; i < count; i++) {
            double square = ((i * 30.0 / RATE) % 1.0) < 0.5 ? 28000 : -28000;
            double tone = Math.sin(2 * Math.PI * 1000.0 * i / RATE) * 4000;
            source[i] = (short) Math.round(Math.max(-32767, Math.min(32767, square + tone)));
        }
        double[] overshoot = ToneFilter.process(source, RATE, 40, 24, 0);
        double peak = 0;
        for (double sample : overshoot) {
            peak = Math.max(peak, Math.abs(sample));
        }
        assertTrue(peak > 32767, "filter peak " + peak + " did not leave 16-bit");

        Path wav = temp.resolve("hot.wav");
        SampleAudio.writeWav(wav, RATE, source);
        Beat plain = sampleBeat(wav, 0, 0);
        Beat cut = sampleBeat(wav, 40, 24);
        SampleLibrary.Ready ready = SampleLibrary.prepare(cut, temp.resolve("hot.json"), java.util.List.of('a'));
        Path bank = temp.resolve("hot.sf2");
        Files.write(bank, ready.sf2());
        short[] stored = samplePcm(bank);
        int flats = flatRun(stored, 3);
        System.out.println(String.format(
            Locale.ROOT,
            "S5 filter peak %.0f, stored flats %d",
            peak, flats
        ));
        assertEquals(0, flats, "flat-topped samples " + flats);

        double[] open = render(plain, temp.resolve("hot-open.wav"));
        double[] filtered = render(cut, temp.resolve("hot-cut.wav"));
        double delta = binDb(spectrum(filtered, onset(filtered) + RATE / 50), 1000)
            - binDb(spectrum(open, onset(open) + RATE / 50), 1000);
        System.out.println(String.format(Locale.ROOT, "S5 1 kHz level %+.2f dB", delta));
        assertTrue(Math.abs(delta) < 1.0, "1 kHz level " + delta + " dB");
    }

    @Test
    void midiExportWarnsThatFiltersAreDropped() throws Exception {
        Beat beat = Beat.drumKit("warn");
        beat.track(0).setName("Kick");
        beat.track(0).setLowCut(80, 24);
        beat.track(1).setName("Bass");
        beat.track(1).setMode(TrackMode.NOTE);
        beat.track(1).setProgram(38);
        beat.track(1).setNote(36);
        beat.track(1).setPresence(6);
        String warning = beat.midiFilterWarning();
        assertTrue(warning.contains("A Kick"), warning);
        assertTrue(warning.contains("A Bass"), warning);
        assertTrue(warning.contains("companion SoundFont"), warning);

        Path json = temp.resolve("warn.json");
        Files.writeString(json, BeatJson.write(beat));
        Cli info = cli("info", json.toString());
        assertEquals(0, info.code, info.err);
        assertTrue(info.err.contains("A Kick"), info.err);
        assertTrue(info.err.contains("A Bass"), info.err);

        Path mid = temp.resolve("warn.mid");
        Cli midi = cli("render", json.toString(), "--out", mid.toString());
        assertEquals(0, midi.code, midi.err);
        assertTrue(midi.err.contains("A Kick"), midi.err);
        assertTrue(midi.err.contains("not in the MIDI file"), midi.err);

        Path sf2 = temp.resolve("warn-sf2.mid");
        Cli companion = cli("render", json.toString(), "--out", sf2.toString(), "--samples-sf2");
        assertEquals(0, companion.code, companion.err);
        assertTrue(companion.err.contains("A Kick"), companion.err);

        Path wav = temp.resolve("warn.wav");
        Cli audio = cli("render", json.toString(), "--out", wav.toString());
        if (audio.code != 0 && synthUnavailable(audio.err)) {
            Assumptions.abort(audio.err);
        }
        assertEquals(0, audio.code, audio.err);
        assertFalse(audio.err.contains("not in the MIDI file"), audio.err);
    }

    @Test
    void sampleGainAboveTheRangeWarnsAndClamps() throws Exception {
        Beat beat = Beat.drumKit("gain");
        beat.track(0).setName("Kick");
        beat.track(0).setMode(TrackMode.SAMPLE);
        beat.track(0).setSample(new SampleRef("hits:click", 60, 0, true, 30, false));
        String json = BeatJson.write(beat).replace("\"gain\": 24", "\"gain\": 30");
        assertTrue(json.contains("\"gain\": 30"), json);
        Beat loaded = BeatJson.read(json);
        assertEquals(24, loaded.track(0).sample().gain());
        String warning = loaded.gainWarning('a', "Kick");
        assertEquals("A Kick: sample gain 30 dB was clamped to 24 dB.", warning);
        assertTrue(loaded.copy().loadWarnings().contains(warning));

        Path file = temp.resolve("gain.json");
        Files.writeString(file, json);
        Cli info = cli("info", file.toString());
        assertEquals(0, info.code, info.err);
        assertTrue(info.err.contains("clamped to 24"), info.err);
        assertTrue(info.out.contains("sample gain: +8 dB"), info.out);
    }

    @Test
    void filteredNoteKeepsLevelLoopAndRelease() throws Exception {
        Beat plain = note(0);
        Beat filtered = note(20);
        double[] open = render(plain, temp.resolve("note-open.wav"));
        double[] cut = render(filtered, temp.resolve("note-cut.wav"));
        int openAt = onset(open);
        int cutAt = onset(cut);
        double level = windowDb(cut, cutAt + RATE / 2) - windowDb(open, openAt + RATE / 2);
        double openWobble = wobble(open, openAt + RATE / 4, openAt + RATE * 4 / 5);
        double cutWobble = wobble(cut, cutAt + RATE / 4, cutAt + RATE * 4 / 5);
        double openRelease = releaseMs(open, openAt);
        double cutRelease = releaseMs(cut, cutAt);
        System.out.println(String.format(
            Locale.ROOT,
            "S8 level %+.2f dB, wobble plain %.2f cut %.2f dB, release plain %.0f cut %.0f ms",
            level, openWobble, cutWobble, openRelease, cutRelease
        ));
        assertTrue(Math.abs(level) < 0.5, "note level " + level + " dB");
        assertTrue(cutWobble < openWobble + 1.5, "loop wobble " + cutWobble + " vs " + openWobble);
        assertTrue(cutRelease >= 250, "release " + cutRelease + " ms");
        assertTrue(cutRelease > openRelease * 0.7, "release " + cutRelease + " vs " + openRelease);
    }

    @Test
    void renderWarnsWhenTheLimiterEngagesAndInfoNamesTheGainDefault() throws Exception {
        Beat quiet = kick(0, 0, 0, 100);
        String quietWarning = renderWarning(quiet, temp.resolve("quiet.wav"));
        System.out.println("S9 quiet warning: " + quietWarning);
        assertNull(quietWarning);

        short[] hot = new short[RATE / 5];
        for (int i = 0; i < hot.length; i++) {
            hot[i] = (short) (Math.sin(2 * Math.PI * 80.0 * i / RATE) * 30000);
        }
        Path wav = temp.resolve("slam.wav");
        SampleAudio.writeWav(wav, RATE, hot);
        Beat slam = Beat.drumKit("slam");
        slam.setBpm(120);
        for (int row = 0; row < 4; row++) {
            slam.track(row).setMode(TrackMode.SAMPLE);
            slam.track(row).setSample(new SampleRef(wav.toString(), 60, 0, true, SampleRef.GAIN_MAX, false));
            slam.track(row).tap(0, false);
            slam.track(row).step(0).setVelocity(127);
            slam.track(row).tap(4, false);
            slam.track(row).step(4).setVelocity(127);
            slam.track(row).tap(8, false);
            slam.track(row).step(8).setVelocity(127);
        }
        String warning = renderWarning(slam, temp.resolve("slam-out.wav"));
        System.out.println("S9 limiter warning: " + warning);
        assertTrue(warning != null && (warning.contains("limiter") || warning.contains("clipped")), warning);

        Path json = temp.resolve("plain-info.json");
        Files.writeString(json, BeatJson.write(Beat.drumKit("plain")));
        Cli info = cli("info", json.toString());
        assertEquals(0, info.code, info.err);
        assertTrue(info.out.contains("sample gain: +8 dB is the default"), info.out);
        assertFalse(info.err.contains("not in the MIDI file"), info.err);
    }

    @Test
    void relativeSampleResolvesAgainstTheBeatFile() throws Exception {
        Path beat = Path.of("beat.json");
        Path resolved = SamplePaths.resolve("kick.wav", beat);
        assertEquals(Path.of("").toAbsolutePath().resolve("kick.wav").normalize(), resolved);

        short[] click = new short[RATE / 20];
        for (int i = 0; i < click.length; i++) {
            click[i] = (short) (Math.sin(2 * Math.PI * 1000.0 * i / RATE) * 8000);
        }
        SampleAudio.writeWav(temp.resolve("kick.wav"), RATE, click);
        Beat beatFile = Beat.drumKit("relative");
        beatFile.track(0).setMode(TrackMode.SAMPLE);
        beatFile.track(0).setSample(new SampleRef("kick.wav", 60, 0, true));
        beatFile.track(0).tap(0, false);
        Files.writeString(temp.resolve("beat.json"), BeatJson.write(beatFile));

        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(
            java,
            "--add-exports", "java.desktop/com.sun.media.sound=ALL-UNNAMED",
            "--add-opens", "java.desktop/com.sun.media.sound=ALL-UNNAMED",
            "-cp", System.getProperty("java.class.path"),
            "hits.cli.HitsCli",
            "info",
            "beat.json"
        ).directory(temp.toFile()).start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = process.waitFor();
        assertEquals(0, code, err);
        assertTrue(out.contains("kick.wav"), out);
        assertTrue(out.contains("ready"), out + err);
        assertFalse(out.contains("missing"), out);
    }

    private static Beat chainBeat() {
        Beat beat = Beat.drumKit("chain");
        beat.setBpm(120);
        beat.setStepCount(16);
        Track filtered = beat.slot('a')[0];
        filtered.setTune(1500);
        filtered.setPresence(12);
        hit(filtered, 100);
        Track plain = beat.slot('b')[0];
        plain.setTune(1900);
        hit(plain, 100);
        beat.addChain('a');
        beat.addChain('b');
        return beat;
    }

    private static Beat kick(int tune, int lowCut, int presence, int velocity) {
        Beat beat = Beat.drumKit("kick");
        beat.setBpm(120);
        Track track = beat.track(0);
        track.setTune(tune);
        if (lowCut > 0) {
            track.setLowCut(lowCut, Track.LOW_CUT_SLOPE);
        }
        track.setPresence(presence);
        hit(track, velocity);
        return beat;
    }

    private static Beat note(int lowCut) {
        Beat beat = Beat.drumKit("note");
        beat.setBpm(120);
        Track track = beat.track(0);
        track.setMode(TrackMode.NOTE);
        track.setProgram(81);
        track.setNote(72);
        track.setGate(100);
        if (lowCut > 0) {
            track.setLowCut(lowCut, Track.LOW_CUT_SLOPE);
        }
        hit(track, 100);
        track.step(0).setLength(8);
        return beat;
    }

    private static Beat sampleBeat(Path wav, int hz, int slope) {
        Beat beat = Beat.drumKit("sample");
        beat.setBpm(120);
        Track track = beat.track(0);
        track.setMode(TrackMode.SAMPLE);
        track.setSample(new SampleRef(wav.toString(), 60, 0, true, 0, false));
        if (hz > 0) {
            track.setLowCut(hz, slope);
        }
        hit(track, 100);
        return beat;
    }

    private static void hit(Track track, int velocity) {
        track.tap(0, false);
        track.step(0).setVelocity(velocity);
    }

    private double[] renderSong(Beat beat, Path wav) throws Exception {
        try {
            BeatFiles.exportWav(beat, wav, new ExportOptions(true, false, 1, true), null, null, () -> false, temp.resolve("song.json"));
        } catch (Exception exception) {
            abortIfNoSynth(exception);
            throw exception;
        }
        return mono(wav);
    }

    private double[] render(Beat beat, Path wav) throws Exception {
        try {
            BeatFiles.exportWav(beat, wav, ExportOptions.matchingPlayback(), null, null, () -> false, temp.resolve("one.json"));
        } catch (Exception exception) {
            abortIfNoSynth(exception);
            throw exception;
        }
        return mono(wav);
    }

    private String renderWarning(Beat beat, Path wav) throws Exception {
        try {
            return BeatFiles.exportWav(beat, wav, ExportOptions.matchingPlayback(), null, null, () -> false, temp.resolve("warn-level.json"));
        } catch (Exception exception) {
            abortIfNoSynth(exception);
            throw exception;
        }
    }

    private static void abortIfNoSynth(Exception exception) {
        if (WavRenderer.unavailable(exception) || synthUnavailable(exception.getMessage())) {
            Assumptions.abort("offline render is not available: " + exception.getMessage());
        }
    }

    private Cli cli(String... args) {
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

    private static double residualDb(double[] left, double[] right, int from, int to) {
        double peak = 0;
        int end = Math.min(to, Math.min(left.length, right.length));
        for (int i = Math.max(0, from); i < end; i++) {
            peak = Math.max(peak, Math.abs(left[i] - right[i]));
        }
        if (peak <= 1e-9) {
            return Double.NEGATIVE_INFINITY;
        }
        return 20 * Math.log10(peak);
    }

    private static int onset(double[] audio) {
        double peak = 0;
        for (double sample : audio) {
            peak = Math.max(peak, Math.abs(sample));
        }
        double gate = peak * 0.1;
        for (int i = 0; i < audio.length; i++) {
            if (Math.abs(audio[i]) >= gate) {
                return i;
            }
        }
        return 0;
    }

    /** RMS from 15 ms to 80 ms after the onset, in dBFS. */
    private static double windowDb(double[] audio, int at) {
        int from = Math.max(0, at + RATE * 15 / 1000);
        int to = Math.min(audio.length, at + RATE * 80 / 1000);
        return rmsDb(audio, from, to);
    }

    private static double ringMs(double[] audio) {
        int at = onset(audio);
        double peak = 0;
        for (int i = at; i < audio.length; i++) {
            peak = Math.max(peak, Math.abs(audio[i]));
        }
        double gate = Math.max(1e-6, peak * 0.01);
        int last = at;
        for (int i = at; i < audio.length; i++) {
            if (Math.abs(audio[i]) >= gate) {
                last = i;
            }
        }
        return (last - at) * 1000.0 / RATE;
    }

    /** Max minus min of 50 ms windows, in dB. */
    private static double wobble(double[] audio, int from, int to) {
        int window = RATE / 20;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (int start = from; start + window < to && start + window < audio.length; start += window / 2) {
            double level = rmsDb(audio, start, start + window);
            min = Math.min(min, level);
            max = Math.max(max, level);
        }
        if (min == Double.POSITIVE_INFINITY) {
            return 0;
        }
        return max - min;
    }

    /** Time from one second after the onset until the note falls below −40 dB of its sustain. */
    private static double releaseMs(double[] audio, int at) {
        int sustain = at + RATE / 2;
        double level = rmsDb(audio, sustain, Math.min(audio.length, sustain + RATE / 10));
        double gate = Math.pow(10, (level - 40) / 20.0);
        int release = at + RATE;
        int last = release;
        for (int i = release; i < audio.length; i++) {
            if (Math.abs(audio[i]) >= gate) {
                last = i;
            }
        }
        return (last - release) * 1000.0 / RATE;
    }

    private static double rmsDb(double[] audio, int from, int to) {
        int start = Math.max(0, from);
        int end = Math.min(audio.length, to);
        if (end <= start) {
            return -120;
        }
        double sum = 0;
        for (int i = start; i < end; i++) {
            sum += audio[i] * audio[i];
        }
        double mean = sum / (end - start);
        if (mean <= 1e-20) {
            return -120;
        }
        return 10 * Math.log10(mean);
    }

    /** Band energy of {@code left} minus {@code right}, in dB, from {@code start}. */
    private static double bandDelta(double[] left, double[] right, int start, int lowHz, int highHz) {
        return bandDb(left, start, lowHz, highHz) - bandDb(right, start, lowHz, highHz);
    }

    private static double bandDb(double[] audio, int start, int lowHz, int highHz) {
        double[] mag = spectrum(audio, start);
        int low = Math.max(1, lowHz * mag.length / (RATE / 2));
        int high = Math.min(mag.length - 1, highHz * mag.length / (RATE / 2));
        double sum = 0;
        for (int i = low; i <= high; i++) {
            sum += mag[i] * mag[i];
        }
        if (sum <= 1e-20) {
            return -120;
        }
        return 10 * Math.log10(sum);
    }

    private static double binDb(double[] mag, int hz) {
        int bin = Math.max(1, Math.min(mag.length - 1, hz * mag.length / (RATE / 2)));
        double sum = mag[bin] * mag[bin];
        if (bin + 1 < mag.length) {
            sum += mag[bin + 1] * mag[bin + 1];
        }
        if (sum <= 1e-20) {
            return -120;
        }
        return 10 * Math.log10(sum);
    }

    private static final int FFT = 8192;

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
            double[] audio = new double[frames];
            for (int i = 0; i < frames; i++) {
                int left = (bytes[i * 4] & 0xff) | (bytes[i * 4 + 1] << 8);
                int right = (bytes[i * 4 + 2] & 0xff) | (bytes[i * 4 + 3] << 8);
                audio[i] = (((short) left) + ((short) right)) / 2.0 / 32768.0;
            }
            return audio;
        }
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
            int frame = Math.max(1, stream.getFormat().getFrameSize());
            int frames = bytes.length / frame;
            short[] pcm = new short[frames];
            for (int i = 0; i < frames; i++) {
                int offset = i * frame;
                pcm[i] = (short) ((bytes[offset] & 0xff) | (bytes[offset + 1] << 8));
            }
            return pcm;
        }
        throw new AssertionError("SoundFont has no sample");
    }

    private static MidiEvent noteOnAt(Sequence sequence, int note, long tick) {
        MidiEvent found = null;
        for (javax.sound.midi.Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                MidiEvent event = track.get(i);
                if (event.getTick() < tick) {
                    continue;
                }
                if (event.getMessage() instanceof ShortMessage message
                    && message.getCommand() == ShortMessage.NOTE_ON
                    && message.getData1() == note
                    && message.getData2() > 0
                    && (found == null || event.getTick() < found.getTick())) {
                    found = event;
                }
            }
        }
        if (found == null) {
            throw new AssertionError("missing note " + note + " at tick " + tick);
        }
        return found;
    }

    private static int lastController(Sequence sequence, int channel, int controller, long tick) {
        int value = -1;
        long at = -1;
        for (javax.sound.midi.Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                MidiEvent event = track.get(i);
                if (event.getTick() > tick) {
                    continue;
                }
                if (event.getMessage() instanceof ShortMessage message
                    && message.getCommand() == ShortMessage.CONTROL_CHANGE
                    && message.getChannel() == channel
                    && message.getData1() == controller
                    && event.getTick() >= at) {
                    value = message.getData2();
                    at = event.getTick();
                }
            }
        }
        return value;
    }

    private static boolean synthUnavailable(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("add-exports")
            || lower.contains("unavailable")
            || lower.contains("cannot render");
    }
}
