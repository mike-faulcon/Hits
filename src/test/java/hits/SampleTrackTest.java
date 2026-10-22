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

import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        assertEquals(List.of(36), noteOns(sequence, Gm.DRUM_CHANNEL));
        assertTrue(noteOns(sequence, 0).isEmpty(), "a fallback sample must not stay on the melodic channel");
        assertFalse(hasControl(sequence, Gm.DRUM_CHANNEL, 0, Gm.DRUM_BANK));
        assertFalse(hasControl(sequence, Gm.DRUM_CHANNEL, 0, SampleBank.SF2_BANK));
        assertFalse(hasControl(sequence, 0, 0, Gm.DRUM_BANK));
        assertFalse(hasControl(sequence, 0, 0, SampleBank.SF2_BANK));
        Path rendered = renderBeat(beat, "missing-kick.wav", beatFile);
        if (rendered == null) {
            return;
        }
        double peak = peak(mono(rendered));
        assertTrue(peak > 0.02, "fallback drum was silent, peak " + peak);
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
        int ring = ringMs(kick.samples(), kick.rate(), -20);
        double clickCentroid = centroid(toDouble(click.samples()), 0);
        double snapCentroid = centroid(toDouble(snap.samples()), 0);
        double[] hp = highpass(toDouble(snap.samples()), snap.rate(), 300);
        int snapOnset = onset(hp);
        int from = Math.max(0, snapOnset - (int) Math.round(0.005 * snap.rate()));
        int to = Math.min(hp.length, snapOnset + (int) Math.round(0.070 * snap.rate()));
        double snapWindow = centroidRange(hp, from, to, snap.rate());
        double[] clickPower = bandShare(click.samples(), click.rate());
        System.out.println("808 early " + early + " Hz, later " + mid + " Hz, −20 dB ring " + ring + " ms");
        System.out.println("click file centroid " + clickCentroid + " Hz, bands " + clickPower[0] + " / " + clickPower[1]);
        System.out.println("snap file centroid " + snapCentroid + " Hz, high-passed onset window " + snapWindow + " Hz");
        assertTrue(early > 74 && early < 82, "early " + early);
        assertTrue(mid > 64 && mid < 72, "later " + mid);
        assertTrue(early > mid + 6);
        assertTrue(ring > 370 && ring < 450, "−20 dB ring " + ring);
        assertTrue(clickPower[0] > 0.08, "low click band " + clickPower[0]);
        assertTrue(clickPower[1] > 0.08, "high click band " + clickPower[1]);
        assertTrue(snapCentroid > 1700 && snapCentroid < 2000, "snap " + snapCentroid);
        assertTrue(snapWindow > 2000 && snapWindow < 2500, "snap window " + snapWindow);
        String eight = BuiltinSamples.description(BuiltinSamples.EIGHT_OH_EIGHT);
        String clickText = BuiltinSamples.description(BuiltinSamples.CLICK);
        String snapText = BuiltinSamples.description(BuiltinSamples.SNAP);
        assertTrue(eight.contains("−20 dB") && eight.contains("400 ms"), eight);
        assertTrue(clickText.contains("1.4 kHz"), clickText);
        assertTrue(snapText.contains("300 Hz") && snapText.contains("2.2 kHz"), snapText);
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

    @Test
    void eachPatternPlaysItsOwnSample() throws Exception {
        Beat beat = Beat.drumKit("chain");
        beat.setBpm(120);
        beat.setSwing(50);
        Track click = beat.slot('a')[1];
        click.setMode(TrackMode.SAMPLE);
        click.setNote(60);
        click.setSample(SampleRef.builtin(BuiltinSamples.CLICK, 60));
        click.tap(0, false);
        Track snap = beat.slot('b')[1];
        snap.setMode(TrackMode.SAMPLE);
        snap.setNote(60);
        snap.setSample(SampleRef.builtin(BuiltinSamples.SNAP, 60));
        snap.tap(0, false);
        beat.setChain(List.of(new Chain.Part('a', 1), new Chain.Part('b', 1)));

        List<Character> slots = List.of('a', 'b');
        SampleLibrary.Ready ready = SampleLibrary.prepare(beat, null, slots);
        assertTrue(ready.warning() == null || ready.warning().isBlank(), ready.warning());
        int clickProgram = ready.use().program(click, 1);
        int snapProgram = ready.use().program(snap, 1);
        assertTrue(clickProgram >= 0 && snapProgram >= 0);
        assertTrue(clickProgram != snapProgram, "programs " + clickProgram + " and " + snapProgram);

        ExportOptions song = new ExportOptions(true, false, 1, true);
        Sequence sequence = SequenceBuilder.export(beat, song, null, ready.use());
        int channel = ChannelPlan.of(beat, slots, null, ready.use()).channel(click, 1);
        assertEquals(channel, ChannelPlan.of(beat, slots, null, ready.use()).channel(snap, 1));
        assertTrue(channel != Gm.DRUM_CHANNEL);
        assertTrue(hasControl(sequence, channel, 0, SampleBank.SF2_BANK));
        assertFalse(hasControl(sequence, channel, 0, Gm.DRUM_BANK));
        List<Integer> programs = programChanges(sequence, channel);
        assertTrue(programs.contains(clickProgram), programs.toString());
        assertTrue(programs.contains(snapProgram), programs.toString());

        Path chained = temp.resolve("chain.wav");
        Path clickOnly = renderBuiltin(BuiltinSamples.CLICK, "click-only.wav");
        Path snapOnly = renderBuiltin(BuiltinSamples.SNAP, "snap-only.wav");
        if (clickOnly == null) {
            return;
        }
        try {
            BeatFiles.exportWav(beat, chained, song, null, null, () -> false, null);
        } catch (Exception exception) {
            if (WavRenderer.unavailable(exception)) {
                Assumptions.abort("offline render is not available: " + exception.getMessage());
            }
            throw exception;
        }
        double[] songPcm = mono(chained);
        int bar = 44100 * 2;
        double[] first = slice(songPcm, 0, Math.min(songPcm.length, bar));
        double[] second = slice(songPcm, Math.min(songPcm.length, bar), songPcm.length);
        double[] clickPcm = mono(clickOnly);
        double[] snapPcm = mono(snapOnly);
        double firstClick = hitCorrelation(first, clickPcm);
        double secondClick = hitCorrelation(second, clickPcm);
        double secondSnap = hitCorrelation(second, snapPcm);
        System.out.println("pattern A vs click " + firstClick + ", B vs click " + secondClick + ", B vs snap " + secondSnap);
        assertTrue(firstClick > 0.7, "pattern A correlation " + firstClick);
        assertTrue(secondSnap > 0.7, "pattern B vs snap " + secondSnap);
        assertTrue(secondClick < 0.4, "pattern B still matches click " + secondClick);
    }

    @Test
    void decayShortensAndLengthensSampleRows() throws Exception {
        int rate = 44100;
        short[] pcm = new short[rate];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (short) (Math.sin(2 * Math.PI * 220 * i / rate) * 16000);
        }
        Path wav = temp.resolve("long-tone.wav");
        SampleAudio.writeWav(wav, rate, pcm);
        SampleBank.Envelope full = SampleBank.envelope(true, 100, pcm.length, rate);
        SampleBank.Envelope over = SampleBank.envelope(true, 200, pcm.length, rate);
        SampleBank.Envelope under = SampleBank.envelope(true, 10, pcm.length, rate);
        assertEquals(full, over);
        assertEquals(SampleBank.SUSTAIN_SILENT, under.sustain());
        assertTrue(under.decay() > SampleBank.DECAY_NONE);
        SampleBank.Envelope choke = SampleBank.envelope(false, 100, pcm.length, rate);
        SampleBank.Envelope chokeUnder = SampleBank.envelope(false, 10, pcm.length, rate);
        SampleBank.Envelope chokeOver = SampleBank.envelope(false, 200, pcm.length, rate);
        assertEquals(SampleBank.RELEASE_CHOKE, choke.release());
        assertEquals(SampleBank.SUSTAIN_SILENT, chokeUnder.sustain());
        assertTrue(chokeOver.release() > SampleBank.RELEASE_CHOKE);

        int shot100 = audibleMs(mono(renderDecay(wav, true, 100, 20, 120)));
        int shot200 = audibleMs(mono(renderDecay(wav, true, 200, 20, 120)));
        int shot10 = audibleMs(mono(renderDecay(wav, true, 10, 20, 120)));
        int choke100 = audibleMs(mono(renderDecay(wav, false, 100, 100, 60)));
        int choke10 = audibleMs(mono(renderDecay(wav, false, 10, 100, 60)));
        int chokeCut = audibleMs(mono(renderDecay(wav, false, 100, 15, 120)));
        int choke200 = audibleMs(mono(renderDecay(wav, false, 200, 15, 120)));
        System.out.println("one-shot 10/100/200 " + shot10 + " / " + shot100 + " / " + shot200
            + " ms; choke 10/100 " + choke10 + " / " + choke100
            + " ms; choke cut/200 " + chokeCut + " / " + choke200 + " ms");
        assertTrue(shot10 < shot100 / 2, "one-shot 10% " + shot10 + " vs 100% " + shot100);
        assertTrue(Math.abs(shot200 - shot100) < 40, "one-shot 200% " + shot200 + " vs 100% " + shot100);
        assertTrue(choke10 < choke100 - 40, "choke 10% " + choke10 + " vs 100% " + choke100);
        assertTrue(choke200 > chokeCut + 150, "choke 200% " + choke200 + " vs 100% " + chokeCut);
    }

    @Test
    void defaultGainSitsNearTheFileAndClickMatchesTheWoodblock() throws Exception {
        int rate = 44100;
        short[] pcm = new short[rate / 4];
        for (int i = 0; i < pcm.length; i++) {
            double attack = i < 200 ? i / 200.0 : 1;
            pcm[i] = (short) Math.round(Math.sin(2 * Math.PI * 440 * i / rate) * 0.92 * 32767 * attack);
        }
        Path wav = temp.resolve("hot.wav");
        SampleAudio.writeWav(wav, rate, pcm);
        assertTrue(SampleBank.attenuation(SampleRef.GAIN) < 0);
        assertEquals(0, SampleBank.attenuation(0));
        double fileDb = 20 * Math.log10(0.92);
        double loud = peakDb(mono(renderGain(wav, SampleRef.GAIN)));
        double quiet = peakDb(mono(renderGain(wav, 0)));
        System.out.println("file " + fileDb + " dBFS, default gain " + loud + " dBFS, gain 0 " + quiet + " dBFS");
        assertTrue(loud > fileDb - 5, "default gain " + loud + " vs file " + fileDb);
        assertTrue(quiet < loud - 6, "gain 0 " + quiet + " vs default " + loud);

        double[] clickPcm = mono(renderBuiltin(BuiltinSamples.CLICK, "click-level.wav"));
        double click = peakDb(clickPcm);
        double wood = peakDb(mono(renderWoodblock()));
        int clickOnset = onset(clickPcm);
        int clickEnd = Math.min(clickPcm.length, clickOnset + (int) Math.round(0.07 * 44100));
        double clickCentroid = centroidRange(clickPcm, Math.max(0, clickOnset - (int) Math.round(0.005 * 44100)), clickEnd, 44100);
        System.out.println("built-in click " + click + " dBFS, woodblock " + wood
            + " dBFS, rendered centroid " + clickCentroid + " Hz, onset " + clickOnset);
        assertTrue(Math.abs(click - wood) < 4, "click " + click + " vs woodblock " + wood);
        assertTrue(clickCentroid > 1300 && clickCentroid < 1600, "rendered click centroid " + clickCentroid);

        Beat plain = Beat.drumKit("plain");
        String drum = BeatJson.write(plain);
        plain.track(0).setVelocity(90);
        assertFalse(BeatJson.write(plain).contains("\"gain\""));
        assertEquals(drum.contains("\"gain\""), false);
    }

    @Test
    void sampleRowsSendDryReverbUnlessTheRowSetsIt() throws Exception {
        Beat beat = sampleBeat(BuiltinSamples.CLICK, true, 80);
        beat.track(0).setSample(SampleRef.builtin(BuiltinSamples.EIGHT_OH_EIGHT, 36));
        SampleLibrary.Ready ready = SampleLibrary.prepare(beat, null, List.of('a'));
        Sequence dry = SequenceBuilder.export(beat, ExportOptions.matchingPlayback(), null, ready.use());
        int channel = ChannelPlan.of(beat, List.of('a'), null, ready.use()).channel(beat.track(0), 0);
        assertTrue(hasControl(dry, channel, ChannelPlan.REVERB, 0));
        assertFalse(hasControl(dry, channel, ChannelPlan.REVERB, Track.REVERB_NEUTRAL));
        beat.track(0).setReverb(30);
        SampleLibrary.Ready wetReady = SampleLibrary.prepare(beat, null, List.of('a'));
        Sequence wet = SequenceBuilder.export(beat, ExportOptions.matchingPlayback(), null, wetReady.use());
        int wetChannel = ChannelPlan.of(beat, List.of('a'), null, wetReady.use()).channel(beat.track(0), 0);
        assertTrue(hasControl(wet, wetChannel, ChannelPlan.REVERB, 30));

        beat.track(0).clearReverb();
        Path rendered = renderBeat(beat, "808-dry.wav", null);
        if (rendered == null) {
            return;
        }
        BuiltinSamples.Pcm file = BuiltinSamples.pcm(BuiltinSamples.EIGHT_OH_EIGHT);
        int fileRing = ringMs(file.samples(), file.rate(), -20);
        int heard = ringFromOnset(mono(rendered), -20);
        System.out.println("808 file −20 dB " + fileRing + " ms, dry render " + heard + " ms");
        assertTrue(Math.abs(heard - fileRing) < 60, "render " + heard + " vs file " + fileRing);
    }

    @Test
    void renderAndInfoWarnAboutAMissingSample() throws Exception {
        Beat missing = Beat.drumKit("missing-cli");
        missing.track(1).setMode(TrackMode.SAMPLE);
        missing.track(1).setSample(new SampleRef("gone.wav", 60, 0, true));
        missing.track(1).tap(0, false);
        Path beatFile = temp.resolve("missing-cli.json");
        Files.writeString(beatFile, BeatJson.write(missing));
        Path midi = temp.resolve("missing-cli.mid");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = HitsCli.run(
            new String[] {"render", beatFile.toString(), "--out", midi.toString()},
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8)
        );
        String errors = err.toString(StandardCharsets.UTF_8);
        assertEquals(HitsCli.OK, code, errors);
        assertTrue(errors.contains("not found"), errors);
        assertTrue(Files.isRegularFile(midi));

        ByteArrayOutputStream infoOut = new ByteArrayOutputStream();
        ByteArrayOutputStream infoErr = new ByteArrayOutputStream();
        int info = HitsCli.run(
            new String[] {"info", beatFile.toString()},
            new PrintStream(infoOut, true, StandardCharsets.UTF_8),
            new PrintStream(infoErr, true, StandardCharsets.UTF_8)
        );
        String listing = infoOut.toString(StandardCharsets.UTF_8);
        String infoErrors = infoErr.toString(StandardCharsets.UTF_8);
        assertEquals(HitsCli.OK, info, infoErrors);
        assertTrue(infoErrors.contains("not found"), infoErrors);
        assertTrue(listing.contains("gone.wav"), listing);
        assertTrue(listing.contains("missing"), listing);
        assertTrue(listing.contains("plays "), listing);

        Beat ready = Beat.drumKit("ready-cli");
        ready.track(1).setMode(TrackMode.SAMPLE);
        ready.track(1).setSample(SampleRef.builtin(BuiltinSamples.CLICK, 60));
        Path readyFile = temp.resolve("ready-cli.json");
        Files.writeString(readyFile, BeatJson.write(ready));
        ByteArrayOutputStream readyOut = new ByteArrayOutputStream();
        ByteArrayOutputStream readyErr = new ByteArrayOutputStream();
        int readyCode = HitsCli.run(
            new String[] {"info", readyFile.toString()},
            new PrintStream(readyOut, true, StandardCharsets.UTF_8),
            new PrintStream(readyErr, true, StandardCharsets.UTF_8)
        );
        String readyText = readyOut.toString(StandardCharsets.UTF_8);
        assertEquals(HitsCli.OK, readyCode, readyErr.toString(StandardCharsets.UTF_8));
        assertEquals("", readyErr.toString(StandardCharsets.UTF_8));
        assertTrue(readyText.contains("hits:click"), readyText);
        assertTrue(readyText.contains("ready"), readyText);
    }

    @Test
    void exportWavWithoutABeatFileResolvesOrRejectsRelativeSamples() throws Exception {
        Beat present = sampleBeat("src/test/resources/samples/tone.wav", true, 80);
        Path out = temp.resolve("relative.wav");
        try {
            BeatFiles.exportWav(present, out, ExportOptions.matchingPlayback(), null, null);
        } catch (Exception exception) {
            if (WavRenderer.unavailable(exception)) {
                Assumptions.abort("offline render is not available: " + exception.getMessage());
            }
            throw exception;
        }
        assertTrue(peak(mono(out)) > 0.01, "relative sample was dropped");

        Beat missing = sampleBeat("no-such-sample.wav", true, 80);
        IOException thrown = assertThrows(IOException.class, () ->
            BeatFiles.exportWav(missing, temp.resolve("dropped.wav"), ExportOptions.matchingPlayback(), null, null)
        );
        assertTrue(thrown.getMessage().contains("no-such-sample.wav"), thrown.getMessage());
    }

    @Test
    void polarityIsOmittedWhenOffAndFlipsTheRender() throws Exception {
        int rate = 44100;
        short[] pcm = new short[rate / 4];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (short) (Math.sin(2 * Math.PI * 110 * i / rate) * 12000);
        }
        Path wav = temp.resolve("sine.wav");
        SampleAudio.writeWav(wav, rate, pcm);
        Beat beat = sampleBeat(wav.toAbsolutePath().toString(), true, 80);
        String json = BeatJson.write(beat);
        assertFalse(json.contains("\"invert\""));
        assertFalse(json.contains("\"gain\""));
        assertEquals(SampleRef.GAIN, BeatJson.read(json).track(0).sample().gain());
        assertFalse(BeatJson.read(json).track(0).sample().invert());

        beat.track(0).setSample(beat.track(0).sample().withGain(-3).withInvert(true));
        String flipped = BeatJson.write(beat);
        assertTrue(flipped.contains("\"gain\": -3"), flipped);
        assertTrue(flipped.contains("\"invert\": true"), flipped);
        SampleRef read = BeatJson.read(flipped).track(0).sample();
        assertEquals(-3, read.gain());
        assertTrue(read.invert());
        assertTrue(read.same(beat.track(0).sample()));

        Beat uprightBeat = sampleBeat(wav.toAbsolutePath().toString(), true, 80);
        Path upright = renderBeat(uprightBeat, "upright.wav", null);
        Path inverted = renderBeat(beat, "inverted.wav", null);
        if (upright == null) {
            return;
        }
        double corr = correlation(mono(upright), mono(inverted));
        System.out.println("polarity correlation " + corr);
        assertTrue(corr < -0.5, "correlation " + corr);
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

    private Path renderBeat(Beat beat, String name, Path beatFile) throws Exception {
        Path out = temp.resolve(name);
        try {
            BeatFiles.exportWav(beat, out, ExportOptions.matchingPlayback(), null, null, () -> false, beatFile);
        } catch (Exception exception) {
            if (WavRenderer.unavailable(exception)) {
                Assumptions.abort("offline render is not available: " + exception.getMessage());
            }
            throw exception;
        }
        return out;
    }

    private Path renderDecay(Path wav, boolean oneShot, int decay, int gate, int bpm) throws Exception {
        Beat beat = sampleBeat(wav.toAbsolutePath().toString(), oneShot, gate);
        beat.setBpm(bpm);
        beat.track(0).setDecay(decay);
        return renderBeat(beat, "decay-" + oneShot + "-" + decay + "-" + gate + "-" + bpm + ".wav", null);
    }

    private Path renderGain(Path wav, int gain) throws Exception {
        Beat beat = sampleBeat(wav.toAbsolutePath().toString(), true, 80);
        beat.track(0).setSample(beat.track(0).sample().withGain(gain));
        return renderBeat(beat, "gain-" + gain + ".wav", null);
    }

    private Path renderBuiltin(String id, String name) throws Exception {
        Beat beat = sampleBeat(id, true, 80);
        beat.track(0).setNote(60);
        beat.track(0).setSample(SampleRef.builtin(id, 60));
        return renderBeat(beat, name, null);
    }

    private Path renderWoodblock() throws Exception {
        Beat beat = Beat.drumKit("wood");
        beat.setBpm(120);
        beat.setSwing(50);
        for (int row = 0; row < Beat.TRACKS; row++) {
            beat.track(row).clearSteps();
        }
        beat.track(0).setPreset(PitchedPreset.CLICK);
        beat.track(0).tap(0, false);
        return renderBeat(beat, "woodblock.wav", null);
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

    private static List<Integer> programChanges(Sequence sequence, int channel) {
        List<Integer> programs = new ArrayList<>();
        for (javax.sound.midi.Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                MidiEvent event = track.get(i);
                if (event.getMessage() instanceof ShortMessage message
                    && message.getCommand() == ShortMessage.PROGRAM_CHANGE
                    && message.getChannel() == channel) {
                    programs.add(message.getData1());
                }
            }
        }
        return programs;
    }

    private static double[] slice(double[] samples, int from, int to) {
        int start = Math.max(0, Math.min(samples.length, from));
        int end = Math.max(start, Math.min(samples.length, to));
        return Arrays.copyOfRange(samples, start, end);
    }

    private static double peak(double[] samples) {
        double peak = 0;
        for (double sample : samples) {
            peak = Math.max(peak, Math.abs(sample));
        }
        return peak;
    }

    private static double peakDb(double[] samples) {
        double peak = peak(samples);
        return peak < 1e-9 ? -120 : 20 * Math.log10(peak);
    }

    private static double correlation(double[] left, double[] right) {
        int count = Math.min(left.length, right.length);
        if (count == 0) {
            return 0;
        }
        double meanLeft = 0;
        double meanRight = 0;
        for (int i = 0; i < count; i++) {
            meanLeft += left[i];
            meanRight += right[i];
        }
        meanLeft /= count;
        meanRight /= count;
        double product = 0;
        double leftEnergy = 0;
        double rightEnergy = 0;
        for (int i = 0; i < count; i++) {
            double a = left[i] - meanLeft;
            double b = right[i] - meanRight;
            product += a * b;
            leftEnergy += a * a;
            rightEnergy += b * b;
        }
        double scale = Math.sqrt(leftEnergy * rightEnergy);
        return scale == 0 ? 0 : product / scale;
    }

    /** Correlation of the first quarter-second after each buffer's onset. */
    private static double hitCorrelation(double[] left, double[] right) {
        int window = 44100 / 4;
        int leftStart = onset(left);
        int rightStart = onset(right);
        return correlation(
            slice(left, leftStart, leftStart + window),
            slice(right, rightStart, rightStart + window)
        );
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

    private static int ringFromOnset(double[] samples, double db) {
        int start = onset(samples);
        double peak = peak(samples);
        double threshold = peak * Math.pow(10, db / 20.0);
        int last = start;
        for (int i = start; i < samples.length; i++) {
            if (Math.abs(samples[i]) >= threshold) {
                last = i;
            }
        }
        return (int) Math.round((last - start) * 1000.0 / 44100.0);
    }

    private static double[] highpass(double[] input, int rate, double freq) {
        double w0 = 2 * Math.PI * freq / rate;
        double alpha = Math.sin(w0) / (2 * 0.707);
        double cos = Math.cos(w0);
        double a0 = 1 + alpha;
        double b0 = ((1 + cos) / 2) / a0;
        double b1 = -(1 + cos) / a0;
        double b2 = b0;
        double a1 = (-2 * cos) / a0;
        double a2 = (1 - alpha) / a0;
        double[] out = new double[input.length];
        double z1 = 0;
        double z2 = 0;
        for (int i = 0; i < input.length; i++) {
            double y = b0 * input[i] + z1;
            z1 = b1 * input[i] - a1 * y + z2;
            z2 = b2 * input[i] - a2 * y;
            out[i] = y;
        }
        return out;
    }

    private static double centroidRange(double[] samples, int from, int to, int rate) {
        int n = 4096;
        double[] real = new double[n];
        double[] imag = new double[n];
        int count = Math.max(0, Math.min(n, to - from));
        for (int i = 0; i < count; i++) {
            double hann = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / Math.max(1, count - 1));
            real[i] = samples[from + i] * hann;
        }
        fft(real, imag);
        double weight = 0;
        double mass = 0;
        for (int i = 1; i < n / 2; i++) {
            double power = real[i] * real[i] + imag[i] * imag[i];
            double hz = i * (rate / (double) n);
            weight += hz * power;
            mass += power;
        }
        return mass == 0 ? 0 : weight / mass;
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
