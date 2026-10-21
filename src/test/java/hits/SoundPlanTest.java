package hits;

import hits.cli.HitsCli;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Channel plan, additive kit and preset JSON, and the built-in bank's real drum sounds. */
class SoundPlanTest {
    private static final int FFT = 8192;

    @TempDir
    Path temp;

    @Test
    void plainBeatsStayByteIdentical() throws Exception {
        String golden = Files.readString(Path.of("src/test/resources/slots/ab-plain.json"));
        String written = BeatJson.write(Beat.drumKit("plain"));
        assertEquals(golden, written);
        assertEquals(1, BeatJson.documentVersion(Beat.drumKit("plain")));
        assertFalse(written.contains("\"bank\""));
        assertFalse(written.contains("\"kit\""));
        assertFalse(written.contains("\"preset\""));
    }

    @Test
    void bankKitAndPresetRoundTripAndFreeTheRow() {
        Beat beat = Beat.drumKit("sounds");
        beat.track(6).setMode(TrackMode.NOTE);
        beat.track(6).setProgram(33);
        beat.track(6).setBank(129);
        beat.track(2).setKit(25);
        beat.track(4).setPreset(PitchedPreset.CLICK);
        beat.slot('c')[3].setPreset(PitchedPreset.RIM);
        beat.slot('c')[3].setName("Rim");

        assertEquals(2, BeatJson.documentVersion(beat));
        String json = BeatJson.write(beat);
        assertTrue(json.contains("\"bank\": 129"));
        assertTrue(json.contains("\"kit\": 25"));
        assertTrue(json.contains("\"preset\": \"click\""));
        assertTrue(json.contains("\"preset\": \"rim\""));
        assertTrue(json.contains("\"c\""));

        Beat loaded = BeatJson.read(json);
        assertEquals(129, loaded.track(6).bank());
        assertEquals(33, loaded.track(6).program());
        assertEquals(25, loaded.track(2).kit());
        assertEquals(PitchedPreset.CLICK, loaded.track(4).preset());
        assertEquals(91, loaded.track(4).playedNote());
        assertEquals("Woodblock click", loaded.track(4).soundName());
        assertEquals(PitchedPreset.RIM, loaded.slot('c')[3].preset());
        assertEquals("Hat C, kit 25", loaded.track(2).soundName());

        Track copy = loaded.track(4).copy();
        assertTrue(copy.sameAs(loaded.track(4)));
        copy.setPreset(null);
        assertFalse(copy.sameAs(loaded.track(4)));
        assertEquals(39, copy.playedNote());

        loaded.track(6).setBank(0);
        loaded.track(2).setKit(0);
        loaded.track(4).setPreset(null);
        loaded.slot('c')[3].setPreset(null);
        loaded.slot('c')[3].setName("Open Hat");
        assertEquals(1, BeatJson.documentVersion(loaded));
        String plain = BeatJson.write(loaded);
        assertFalse(plain.contains("\"bank\""));
        assertFalse(plain.contains("\"kit\""));
        assertFalse(plain.contains("\"preset\""));
        assertFalse(plain.contains("\"c\""));

        String unknown = json.replace("\"preset\": \"click\"", "\"preset\": \"nope\"");
        assertNull(BeatJson.read(unknown).track(4).preset());

        beat.track(6).setBank(20000);
        beat.track(2).setKit(-3);
        assertEquals(16383, beat.track(6).bank());
        assertEquals(0, beat.track(2).kit());
    }

    @Test
    void aDifferentPresetIsNotTheSameInstrument() {
        Beat beat = Beat.drumKit("mix");
        beat.slot('b')[0].setPreset(PitchedPreset.CRACK);
        Mix.setLevel(beat, 'a', 0, 40, true);
        assertEquals(40, beat.slot('a')[0].level());
        assertEquals(Track.LEVEL, beat.slot('b')[0].level());
        assertEquals(40, beat.slot('c')[0].level());
    }

    @Test
    void notesKeepAChannelWhenDrumsWouldFillThePool() {
        boolean[] needsPrivate = new boolean[16];
        boolean[] melodic = new boolean[16];
        Arrays.fill(needsPrivate, true);
        melodic[9] = true;
        ChannelPlan plan = ChannelPlan.assign(16, needsPrivate, melodic);
        assertFalse(plan.overflow(9));
        int overflow = -1;
        for (int row = 0; row < 16; row++) {
            if (plan.overflow(row)) {
                assertEquals(-1, overflow);
                overflow = row;
            }
        }
        assertTrue(overflow >= 0 && overflow != 9);
        Track drum = new Track("Kick", TrackMode.DRUM, 0, 36);
        drum.setPan(10);
        assertEquals(Gm.DRUM_CHANNEL, plan.channel(drum, overflow));

        boolean[] eight = new boolean[8];
        Arrays.fill(eight, true);
        ChannelPlan small = ChannelPlan.assign(8, eight, new boolean[8]);
        for (int row = 0; row < 8; row++) {
            assertEquals(row, small.reserved(row));
            assertFalse(small.overflow(row));
            assertTrue(small.reserved(row) != SequenceBuilder.CLICK_CHANNEL);
        }
    }

    @Test
    void sharedDrumsStayOnChannelNineAndPresetsDoNotStealIt() throws Exception {
        Beat beat = Beat.drumKit("channels");
        beat.track(0).tap(0, false);
        beat.track(1).tap(0, false);
        beat.track(0).setPan(10);
        beat.track(3).setPreset(PitchedPreset.CLICK);
        beat.track(3).tap(0, false);
        beat.track(2).setKit(25);
        beat.track(2).tap(0, false);
        beat.track(6).setMode(TrackMode.NOTE);
        beat.track(6).setProgram(38);
        beat.track(6).setBank(129);
        beat.track(6).setNote(60);
        beat.track(6).tap(0, false);

        Sequence sequence = SequenceBuilder.export(beat, ExportOptions.matchingPlayback());
        assertEquals(0, channelOf(sequence, 36));
        assertEquals(Gm.DRUM_CHANNEL, channelOf(sequence, 38));
        assertEquals(3, channelOf(sequence, 91));
        assertEquals(2, channelOf(sequence, 42));
        assertEquals(6, channelOf(sequence, 60));

        assertEquals(Gm.DRUM_BANK, control(sequence, 0, 0));
        assertEquals(0, control(sequence, 0, 32));
        assertEquals(0, program(sequence, 0));
        assertEquals(10, control(sequence, 0, 10));

        assertEquals(0, control(sequence, 3, 0));
        assertEquals(0, control(sequence, 3, 32));
        assertEquals(PitchedPreset.CLICK.program(), program(sequence, 3));
        assertEquals(Gm.DRUM_BANK, control(sequence, 2, 0));
        assertEquals(0, control(sequence, 2, 32));
        assertEquals(25, program(sequence, 2));
        assertEquals(1, control(sequence, 6, 0));
        assertEquals(1, control(sequence, 6, 32));
        assertEquals(38, program(sequence, 6));

        assertFalse(hasProgram(sequence, Gm.DRUM_CHANNEL));
        assertTrue(controlsOn(sequence, Gm.DRUM_CHANNEL).stream()
            .allMatch(message -> message.getData1() == 10 && message.getData2() == Track.PAN_CENTER));

        beat.track(3).step(0).setPitch(60);
        Sequence pitched = SequenceBuilder.export(beat, ExportOptions.matchingPlayback());
        assertEquals(60, noteOn(pitched, 3).getData1());
        assertFalse(hasNote(pitched, 91));
    }

    @Test
    void aCenterDrumInOnePatternKeepsChannelNine() throws Exception {
        Beat beat = Beat.drumKit("split");
        beat.track(0).tap(0, false);
        beat.setActiveSlot('b');
        beat.track(0).setMode(TrackMode.NOTE);
        beat.track(0).setProgram(40);
        beat.track(0).setNote(48);
        beat.track(0).tap(0, false);
        beat.setActiveSlot('a');
        beat.addChain('a');
        beat.addChain('b');

        long bar = SequenceBuilder.loopTicks(beat.stepCount());
        Sequence song = SequenceBuilder.export(beat, new ExportOptions(true, false, 1, true));
        ShortMessage kick = noteAt(song, 0, 36);
        assertEquals(Gm.DRUM_CHANNEL, kick.getChannel());
        ShortMessage lead = noteAt(song, bar, 48);
        assertEquals(0, lead.getChannel());
        assertEquals(40, programAt(song, bar, 0));
        assertFalse(hasProgram(song, Gm.DRUM_CHANNEL));

        Sequence played = SequenceBuilder.playback(beat, true);
        assertEquals(described(song, ShortMessage.NOTE_ON), described(played, ShortMessage.NOTE_ON));
    }

    @Test
    void playbackMatchesExportForAPresetAndAKit() throws Exception {
        Beat beat = Beat.drumKit("hear");
        beat.track(1).setPreset(PitchedPreset.CRACK);
        beat.track(1).tap(0, false);
        beat.track(1).tap(4, false);
        beat.track(5).setKit(8);
        beat.track(5).setPan(Track.PAN_CENTER);
        beat.track(5).tap(8, false);
        beat.track(0).tap(0, false);

        Sequence exported = SequenceBuilder.export(beat, ExportOptions.matchingPlayback());
        Sequence played = SequenceBuilder.playback(beat, false);
        assertEquals(described(exported, ShortMessage.NOTE_ON), described(played, ShortMessage.NOTE_ON));
        assertEquals(described(exported, ShortMessage.PROGRAM_CHANGE), described(played, ShortMessage.PROGRAM_CHANGE));
        assertEquals(PitchedPreset.CRACK.program(), program(exported, 1));
        assertEquals(88, channelNote(exported, 1));
        assertEquals(Gm.DRUM_BANK, control(exported, 5, 0));
        assertEquals(8, program(exported, 5));
        assertEquals(5, channelOf(exported, 45));
    }

    @Test
    void renderCliWritesThePreset() throws Exception {
        Beat beat = Beat.drumKit("cli");
        beat.setSlotSteps('a', 4);
        beat.track(4).setPreset(PitchedPreset.CLICK);
        beat.track(4).tap(0, false);
        beat.track(0).tap(0, false);
        Path json = temp.resolve("cli.json");
        Files.writeString(json, BeatJson.write(beat));
        Path wav = temp.resolve("cli.wav");
        Path mid = temp.resolve("cli.mid");

        assertEquals(0, HitsCli.run(new String[] {"render", json.toString(), "--out", wav.toString()}, mute(), mute()));
        assertEquals(0, HitsCli.run(new String[] {"render", json.toString(), "--out", mid.toString()}, mute(), mute()));
        assertTrue(Files.size(wav) > 44);
        Sequence sequence = MidiSystem.getSequence(mid.toFile());
        assertEquals(4, channelOf(sequence, 91));
        assertEquals(PitchedPreset.CLICK.program(), program(sequence, 4));
        assertEquals(Gm.DRUM_CHANNEL, channelOf(sequence, 36));

        ByteArrayOutputStream info = new ByteArrayOutputStream();
        assertEquals(0, HitsCli.run(new String[] {"info", json.toString()}, new PrintStream(info, true, StandardCharsets.UTF_8), mute()));
        String text = info.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("Woodblock click"));
        assertTrue(text.contains(Notes.name(PitchedPreset.CLICK.note())));
    }

    @Test
    void builtinCatalogListsRealKitsOnly() {
        Soundbank bank = SoundCatalog.builtin();
        Assumptions.assumeTrue(bank != null, "no built-in soundbank");
        List<SoundCatalog.Kit> kits = SoundCatalog.kits(bank);
        assertEquals(1, kits.size());
        assertEquals(0, kits.get(0).program());
        List<SoundCatalog.Voice> voices = SoundCatalog.melodic(bank);
        assertEquals(128, voices.size());
        assertTrue(voices.stream().noneMatch(voice -> voice.name().toLowerCase(Locale.ROOT).contains("kit")));
        assertEquals(List.of(new SoundCatalog.Kit(0, "Standard")), SoundCatalog.kits(null));
        assertEquals(128, SoundCatalog.melodic(null).size());
    }

    @Test
    void curatedDrumSoundsAreDistinctOnTheBuiltinBank() throws Exception {
        Profile[] curated = new Profile[Gm.DRUM_NOTES.length];
        for (int i = 0; i < Gm.DRUM_NOTES.length; i++) {
            int note = Gm.DRUM_NOTES[i];
            curated[i] = profile(renderKey(note), Gm.drumChoice(note));
            assertTrue(curated[i].rms > 0.02, curated[i].name + " was silent, rms " + curated[i].rms);
        }
        StringBuilder pairs = new StringBuilder();
        for (int i = 0; i < curated.length; i++) {
            for (int j = i + 1; j < curated.length; j++) {
                double correlation = curated[i].correlation(curated[j]);
                pairs.append(String.format(
                    Locale.ROOT,
                    "%s vs %s  corr %.3f  peaks %.0f / %.0f Hz  rms %.3f / %.3f%n",
                    curated[i].name,
                    curated[j].name,
                    correlation,
                    curated[i].peakHz,
                    curated[j].peakHz,
                    curated[i].rms,
                    curated[j].rms
                ));
                assertTrue(distinct(curated[i], curated[j]), pairs.toString());
            }
        }

        Profile closed = profile(renderKey(42), "closed");
        Profile pedal = profile(renderKey(44), "pedal");
        Profile snare = profile(renderKey(38), "snare");
        Profile snare2 = profile(renderKey(40), "snare2");
        Profile crash = profile(renderKey(49), "crash");
        Profile ride = profile(renderKey(51), "ride");
        assertFalse(distinct(closed, pedal), describe(closed, pedal));
        assertFalse(distinct(snare, snare2), describe(snare, snare2));
        assertFalse(distinct(crash, ride), describe(crash, ride));

        Profile click = profile(renderPreset(PitchedPreset.CLICK), "click");
        Profile crack = profile(renderPreset(PitchedPreset.CRACK), "crack");
        Profile rim = profile(renderPreset(PitchedPreset.RIM), "rim");
        assertTrue(distinct(click, crack), describe(click, crack));
        assertTrue(distinct(crack, rim), describe(crack, rim));
        assertTrue(distinct(click, rim), describe(click, rim));
        assertTrue(distinct(crack, snare), describe(crack, snare));
        assertTrue(snare.above3k < 0.15, "snare energy above 3 kHz was " + snare.above3k);
        assertTrue(crack.mid > 0.70, "crack mid energy was " + crack.mid);
        assertTrue(click.mid > 0.70, "click mid energy was " + click.mid);
        assertTrue(rim.above3k > 0.70, "rim energy above 3 kHz was " + rim.above3k);
        assertTrue(click.peakHz > 500 && click.peakHz < 1500, "click peak " + click.peakHz);
        assertTrue(crack.peakHz > 900 && crack.peakHz < 1800, "crack peak " + crack.peakHz);

        Profile kit0 = profile(renderChannel(Gm.DRUM_CHANNEL, 0, 0, 0, 36), "ch9 kit 0");
        Profile kit8 = profile(renderChannel(Gm.DRUM_CHANNEL, 0, 0, 8, 36), "ch9 kit 8");
        Profile kit25 = profile(renderChannel(Gm.DRUM_CHANNEL, 0, 0, 25, 36), "ch9 kit 25");
        assertFalse(distinct(kit0, kit8), describe(kit0, kit8));
        assertFalse(distinct(kit0, kit25), describe(kit0, kit25));
        assertTrue(kit0.correlation(kit8) > 0.97, describe(kit0, kit8));
        assertTrue(kit0.correlation(kit25) > 0.97, describe(kit0, kit25));

        Profile bank120 = profile(renderChannel(0, Gm.DRUM_BANK, 0, 0, 36), "bank 120 program 0");
        Profile silent = profile(renderChannel(0, Gm.DRUM_BANK, 0, 8, 36), "bank 120 program 8");
        assertTrue(bank120.rms > 0.02, "bank 120 program 0 was silent");
        assertTrue(silent.rms < 0.005, "bank 120 program 8 was not silent: rms " + silent.rms);
        assertTrue(bank120.correlation(kit0) > 0.90, describe(bank120, kit0));
    }

    private Path renderKey(int note) throws Exception {
        return renderBeat("key-" + note, beat -> {
            beat.track(0).setNote(note);
            beat.track(0).tap(0, false);
            beat.track(0).setGate(80);
        });
    }

    private Path renderPreset(PitchedPreset preset) throws Exception {
        return renderBeat(preset.json(), beat -> {
            beat.track(0).setPreset(preset);
            beat.track(0).tap(0, false);
            beat.track(0).setGate(45);
        });
    }

    private Path renderBeat(String name, java.util.function.Consumer<Beat> edit) throws Exception {
        Beat beat = Beat.drumKit(name);
        beat.setBpm(120);
        beat.setSwing(50);
        beat.setSlotSteps('a', 1);
        for (int row = 0; row < Beat.TRACKS; row++) {
            beat.track(row).clearSteps();
        }
        edit.accept(beat);
        Path wav = temp.resolve(name + ".wav");
        try {
            WavRenderer.render(SequenceBuilder.export(beat, ExportOptions.matchingPlayback()), wav, null, null, null);
        } catch (Exception exception) {
            if (WavRenderer.unavailable(exception)) {
                Assumptions.abort("offline render is not available: " + exception.getMessage());
            }
            throw exception;
        }
        return wav;
    }

    private Path renderChannel(int channel, int msb, int lsb, int program, int note) throws Exception {
        Sequence sequence = new Sequence(Sequence.PPQ, SequenceBuilder.PPQ);
        javax.sound.midi.Track track = sequence.createTrack();
        int microseconds = 500_000;
        MetaMessage tempo = new MetaMessage();
        tempo.setMessage(0x51, new byte[] {
            (byte) (microseconds >> 16),
            (byte) (microseconds >> 8),
            (byte) microseconds
        }, 3);
        track.add(new MidiEvent(tempo, 0));
        track.add(new MidiEvent(control(channel, 0, msb), 0));
        track.add(new MidiEvent(control(channel, 32, lsb), 0));
        ShortMessage change = new ShortMessage();
        change.setMessage(ShortMessage.PROGRAM_CHANGE, channel, program, 0);
        track.add(new MidiEvent(change, 0));
        ShortMessage on = new ShortMessage();
        on.setMessage(ShortMessage.NOTE_ON, channel, note, 100);
        track.add(new MidiEvent(on, 0));
        ShortMessage off = new ShortMessage();
        off.setMessage(ShortMessage.NOTE_OFF, channel, note, 0);
        track.add(new MidiEvent(off, SequenceBuilder.PPQ));
        String name = "raw-" + channel + "-" + msb + "-" + program + "-" + note;
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

    private static ShortMessage control(int channel, int controller, int value) throws Exception {
        ShortMessage message = new ShortMessage();
        message.setMessage(ShortMessage.CONTROL_CHANGE, channel, controller, value);
        return message;
    }

    private static boolean distinct(Profile left, Profile right) {
        if (left.correlation(right) < 0.90) {
            return true;
        }
        double peak = Math.max(left.peakHz, right.peakHz);
        if (peak > 1 && Math.abs(left.peakHz - right.peakHz) / peak > 0.12) {
            return true;
        }
        double quiet = Math.min(left.rms, right.rms);
        double loud = Math.max(left.rms, right.rms);
        return quiet > 0 && loud / quiet > 1.35;
    }

    private static String describe(Profile left, Profile right) {
        return String.format(
            Locale.ROOT,
            "%s vs %s corr %.3f peaks %.0f/%.0f rms %.4f/%.4f",
            left.name,
            right.name,
            left.correlation(right),
            left.peakHz,
            right.peakHz,
            left.rms,
            right.rms
        );
    }

    private static Profile profile(Path wav, String name) throws Exception {
        double[] samples = mono(wav);
        int window = Math.min(FFT, samples.length);
        int start = Math.max(0, peakIndex(samples, window) - window / 8);
        if (start + window > samples.length) {
            start = Math.max(0, samples.length - window);
        }
        double[] real = new double[FFT];
        double[] imag = new double[FFT];
        for (int i = 0; i < window; i++) {
            double hann = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / Math.max(1, window - 1));
            real[i] = samples[start + i] * hann;
        }
        fft(real, imag);
        int bins = FFT / 2;
        double[] mag = new double[bins];
        for (int i = 1; i < bins; i++) {
            mag[i] = Math.hypot(real[i], imag[i]);
        }
        int peakBin = 1;
        for (int i = 2; i < bins; i++) {
            if (mag[i] > mag[peakBin]) {
                peakBin = i;
            }
        }
        double low = 0;
        double body = 0;
        double mid = 0;
        double high = 0;
        for (int i = 1; i < bins; i++) {
            double hz = i * (WavAudio.SAMPLE_RATE / (double) FFT);
            double power = mag[i] * mag[i];
            if (hz < 120) {
                low += power;
            } else if (hz < 600) {
                body += power;
            } else if (hz < 3000) {
                mid += power;
            } else {
                high += power;
            }
        }
        double all = low + body + mid + high;
        double sum = 0;
        int counted = Math.min(samples.length, WavAudio.SAMPLE_RATE / 10);
        for (int i = 0; i < counted; i++) {
            sum += samples[i] * samples[i];
        }
        return new Profile(name, mag, Math.sqrt(sum / Math.max(1, counted)), peakBin * (WavAudio.SAMPLE_RATE / (double) FFT), mid / all, high / all);
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

    private static int peakIndex(double[] samples, int window) {
        int best = 0;
        double loudest = -1;
        int limit = Math.max(1, samples.length - window);
        for (int i = 0; i < limit; i += 64) {
            double value = Math.abs(samples[i]);
            if (value > loudest) {
                loudest = value;
                best = i;
            }
        }
        return best;
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

    private record Profile(String name, double[] magnitude, double rms, double peakHz, double mid, double above3k) {
        String summary() {
            return String.format(
                Locale.ROOT,
                "%-22s peak %7.1f Hz  rms %.4f  mid %.3f  above3k %.3f",
                name,
                peakHz,
                rms,
                mid,
                above3k
            );
        }

        double correlation(Profile other) {
            double dot = 0;
            double left = 0;
            double right = 0;
            for (int i = 1; i < magnitude.length; i++) {
                dot += magnitude[i] * other.magnitude[i];
                left += magnitude[i] * magnitude[i];
                right += other.magnitude[i] * other.magnitude[i];
            }
            if (left == 0 || right == 0) {
                return 0;
            }
            return dot / Math.sqrt(left * right);
        }
    }

    private static PrintStream mute() {
        return new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    }

    private static int channelOf(Sequence sequence, int note) {
        return noteOn(sequence, -1, note).getChannel();
    }

    private static int channelNote(Sequence sequence, int channel) {
        return noteOn(sequence, channel, -1).getData1();
    }

    private static ShortMessage noteOn(Sequence sequence, int channel) {
        return noteOn(sequence, channel, -1);
    }

    private static ShortMessage noteOn(Sequence sequence, int channel, int note) {
        ShortMessage found = null;
        for (MidiEvent event : events(sequence, ShortMessage.NOTE_ON)) {
            ShortMessage message = (ShortMessage) event.getMessage();
            if (message.getData2() == 0) {
                continue;
            }
            if (channel >= 0 && message.getChannel() != channel) {
                continue;
            }
            if (note >= 0 && message.getData1() != note) {
                continue;
            }
            found = message;
        }
        assertTrue(found != null, "missing note channel " + channel + " note " + note);
        return found;
    }

    private static ShortMessage noteAt(Sequence sequence, long tick, int note) {
        for (MidiEvent event : events(sequence, ShortMessage.NOTE_ON)) {
            ShortMessage message = (ShortMessage) event.getMessage();
            if (event.getTick() == tick && message.getData1() == note && message.getData2() > 0) {
                return message;
            }
        }
        throw new AssertionError("missing note " + note + " at " + tick);
    }

    private static boolean hasNote(Sequence sequence, int note) {
        for (MidiEvent event : events(sequence, ShortMessage.NOTE_ON)) {
            ShortMessage message = (ShortMessage) event.getMessage();
            if (message.getData1() == note && message.getData2() > 0) {
                return true;
            }
        }
        return false;
    }

    private static int control(Sequence sequence, int channel, int controller) {
        List<Integer> values = new ArrayList<>();
        for (MidiEvent event : events(sequence, ShortMessage.CONTROL_CHANGE)) {
            ShortMessage message = (ShortMessage) event.getMessage();
            if (message.getChannel() == channel && message.getData1() == controller) {
                values.add(message.getData2());
            }
        }
        assertEquals(1, values.size(), "controller " + controller + " on " + channel);
        return values.get(0);
    }

    private static int program(Sequence sequence, int channel) {
        return programAt(sequence, 0, channel);
    }

    private static int programAt(Sequence sequence, long tick, int channel) {
        List<Integer> values = new ArrayList<>();
        for (MidiEvent event : events(sequence, ShortMessage.PROGRAM_CHANGE)) {
            ShortMessage message = (ShortMessage) event.getMessage();
            if (event.getTick() == tick && message.getChannel() == channel) {
                values.add(message.getData1());
            }
        }
        assertEquals(1, values.size(), "program on " + channel + " at " + tick);
        return values.get(0);
    }

    private static boolean hasProgram(Sequence sequence, int channel) {
        for (MidiEvent event : events(sequence, ShortMessage.PROGRAM_CHANGE)) {
            if (((ShortMessage) event.getMessage()).getChannel() == channel) {
                return true;
            }
        }
        return false;
    }

    private static List<ShortMessage> controlsOn(Sequence sequence, int channel) {
        List<ShortMessage> messages = new ArrayList<>();
        for (MidiEvent event : events(sequence, ShortMessage.CONTROL_CHANGE)) {
            ShortMessage message = (ShortMessage) event.getMessage();
            if (message.getChannel() == channel) {
                messages.add(message);
            }
        }
        return messages;
    }

    private static List<String> described(Sequence sequence, int command) {
        List<String> lines = new ArrayList<>();
        for (MidiEvent event : events(sequence, command)) {
            ShortMessage message = (ShortMessage) event.getMessage();
            if (message.getCommand() == ShortMessage.NOTE_ON && message.getData2() == 0) {
                continue;
            }
            lines.add(event.getTick() + " ch" + message.getChannel() + " " + message.getData1() + " " + message.getData2());
        }
        return lines;
    }

    private static List<MidiEvent> events(Sequence sequence, int command) {
        List<MidiEvent> found = new ArrayList<>();
        for (javax.sound.midi.Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                MidiEvent event = track.get(i);
                if (event.getMessage() instanceof ShortMessage message && message.getCommand() == command) {
                    found.add(event);
                }
            }
        }
        return found;
    }
}
