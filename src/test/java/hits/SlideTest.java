package hits;

import hits.cli.HitsCli;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pitch slides: the glide, the bend range, channel isolation, and files that do not slide. */
class SlideTest {
    @TempDir
    Path temp;

    @Test
    void oldBeatsStayByteIdenticalAndSlidesRoundTrip() throws Exception {
        String golden = Files.readString(Path.of("src/test/resources/slots/ab-plain.json"));
        Beat plain = BeatJson.read(golden);
        assertEquals(golden, BeatJson.write(plain));
        assertFalse(plain.hasSlides());
        assertFalse(BeatJson.write(Beat.drumKit("fresh")).contains("slide"));

        for (int cents : new int[] {-2400, -100, 0, 100, 2400}) {
            assertEquals(Track.pitchBend(cents), Track.pitchBendForRange(cents, ChannelPlan.BEND_RANGE));
        }

        Beat beat = noteBeat();
        beat.track(1).setSlideMs(120);
        beat.track(1).setSlideCurve(Slide.Curve.EXPONENTIAL);
        beat.track(1).step(1).setSlide(true);
        beat.track(1).step(8).setOn(true);
        beat.track(1).step(8).setSlide(true);
        String json = BeatJson.write(beat);
        assertTrue(json.contains("\"slideMs\": 120"), json);
        assertTrue(json.contains("\"slideCurve\": \"exponential\""), json);
        assertTrue(json.contains("\"slide\":true"), json);
        assertFalse(json.contains("\"slide\":false"), json);
        Beat loaded = BeatJson.read(json);
        assertEquals(json, BeatJson.write(loaded));
        assertEquals(120, loaded.track(1).slideMs());
        assertEquals(Slide.Curve.EXPONENTIAL, loaded.track(1).slideCurve());
        assertTrue(loaded.track(1).step(1).slide());
        assertTrue(loaded.track(1).step(8).slide());
        assertFalse(loaded.track(1).step(0).slide());
        assertTrue(loaded.track(1).shapeText().contains("slide 120 ms exponential"), loaded.track(1).shapeText());
        assertTrue(loaded.track(1).shapeText().contains("2 glide steps"), loaded.track(1).shapeText());
        assertTrue(loaded.hasSlides());
        assertTrue(loaded.usesFormatV2());

        Beat clamped = BeatJson.read(json.replace("\"slideMs\": 120", "\"slideMs\": 900"));
        assertEquals(Slide.MAX_MS, clamped.track(1).slideMs());
        assertTrue(clamped.loadWarnings().get(0).contains("clamped"), clamped.loadWarnings().toString());

        Editor editor = new Editor(loaded);
        editor.selectTrack(1);
        editor.selectStep(1);
        editor.toggleSlide(1, 1);
        assertFalse(editor.beat().track(1).step(1).slide());
        editor.undo();
        assertTrue(editor.beat().track(1).step(1).slide());
        editor.setSlideMs(80);
        assertEquals(80, editor.beat().track(1).slideMs());
        editor.undo();
        assertEquals(120, editor.beat().track(1).slideMs());
    }

    @Test
    void glideBendsFromC1ToG1WithoutASecondAttack() throws Exception {
        assertEquals(0, Slide.Curve.LINEAR.shape(0), 1e-9);
        assertEquals(1, Slide.Curve.LINEAR.shape(1), 1e-9);
        assertEquals(0.5, Slide.Curve.LINEAR.shape(0.5), 1e-9);
        assertTrue(Slide.Curve.EXPONENTIAL.shape(0.25) > Slide.Curve.LINEAR.shape(0.25));
        assertEquals(1, Slide.Curve.EXPONENTIAL.shape(1), 1e-9);

        Beat slide = sineBeat(true);
        Beat separate = sineBeat(false);
        SampleUse slidUse = SampleLibrary.prepare(slide, null, List.of('a')).use();
        SampleUse splitUse = SampleLibrary.prepare(separate, null, List.of('a')).use();
        Sequence slid = SequenceBuilder.export(slide, ExportOptions.matchingPlayback(), null, slidUse);
        Sequence split = SequenceBuilder.export(separate, ExportOptions.matchingPlayback(), null, splitUse);
        int channel = ChannelPlan.of(slide, List.of('a'), null, slidUse).channel(slide.track(1), 1);
        assertEquals(1, noteOns(slid, channel).size(), "a slide does not attack again");
        assertEquals(2, noteOns(split, channel).size(), "a step without slide still attacks");
        assertTrue(hasRpn(slid, channel, 7), "C1 to G1 is 7 semitones");
        assertFalse(hasRpn(split, channel, 7));

        List<Integer> plain = noteOns(SequenceBuilder.export(noteBeat(), ExportOptions.matchingPlayback()), 1);
        assertEquals(List.of(36, 43), plain);

        double[] audio = render(slide);
        int rate = 44100;
        int onset = onset(audio, 0.2);
        double c1 = Notes.frequency(36);
        double g1 = Notes.frequency(43);
        double early = fundamental(audio, rate, onset + (int) (0.02 * rate), onset + (int) (0.07 * rate), 50, 130);
        double mid = fundamental(audio, rate, onset + (int) (0.15 * rate), onset + (int) (0.18 * rate), 50, 130);
        double late = fundamental(audio, rate, onset + (int) (0.24 * rate), onset + (int) (0.32 * rate), 50, 130);
        System.out.println(String.format(
            java.util.Locale.ROOT,
            "slide Hz early %.1f mid %.1f late %.1f (C1 %.1f G1 %.1f)",
            early, mid, late, c1, g1
        ));
        assertTrue(Math.abs(early - c1) < 4, "start " + early);
        assertTrue(mid > early + 4 && mid < late - 2, "glide " + mid + " between " + early + " and " + late);
        assertTrue(Math.abs(late - g1) < 6, "end " + late);
        double edge = rms(audio, onset + (int) (0.11 * rate), onset + (int) (0.14 * rate));
        double body = rms(audio, onset + (int) (0.02 * rate), onset + (int) (0.06 * rate));
        assertTrue(edge > body * 0.35, "level dipped at the second step: " + edge + " vs " + body);
    }

    @Test
    void bakedFilterStillFollowsTheBend() throws Exception {
        Beat beat = noteBeat();
        Track bass = beat.track(1);
        bass.setEqBand(0, 400, -6, 1.2);
        bass.step(1).setSlide(true);
        bass.setSlideMs(80);
        double[] audio = render(beat);
        int rate = 44100;
        int onset = onset(audio, 0.15);
        double early = fundamental(audio, rate, onset + (int) (0.02 * rate), onset + (int) (0.07 * rate), 50, 160);
        double late = fundamental(audio, rate, onset + (int) (0.24 * rate), onset + (int) (0.34 * rate), 50, 160);
        System.out.println(String.format(java.util.Locale.ROOT, "baked slide %.1f -> %.1f", early, late));
        assertTrue(late > early + 8, "baked sample did not follow the bend: " + early + " -> " + late);
    }

    @Test
    void aSlidingRowDoesNotShareAChannel() throws Exception {
        Beat beat = Beat.drumKit("crowd");
        beat.setBpm(120);
        beat.setSwing(50);
        beat.setTrackCount(16);
        for (int row = 0; row < 16; row++) {
            Track track = beat.track(row);
            track.clearSteps();
            track.setName(row == 15 ? "Glide" : "N" + (row + 1));
            track.setMode(TrackMode.NOTE);
            track.setProgram(row == 15 ? 38 : row);
            track.setNote(36);
            track.tap(row == 15 ? 8 : 0, false);
        }
        beat.track(15).step(8).setPitch(43);
        beat.track(15).step(8).setSlide(true);
        ChannelPlan plan = ChannelPlan.of(beat, List.of('a'));
        assertTrue(plan.omitted(15), "a slide cannot take another row's channel");
        assertFalse(plan.timeShared(15));
        assertTrue(plan.slides(15));
        String warning = plan.channelWarning(beat);
        assertNotNull(warning);
        assertTrue(warning.contains("slides"), warning);
        assertTrue(warning.contains("Glide"), warning);
        int glide = plan.wavChannel(15);
        for (int row = 0; row < 15; row++) {
            if (plan.omitted(row)) {
                assertTrue(plan.wavChannel(row) != glide, "overflow synth shares a bend");
            }
        }

        beat.track(15).step(8).setSlide(false);
        ChannelPlan shared = ChannelPlan.of(beat, List.of('a'));
        assertFalse(shared.omitted(15), "without slide the same notes can share across time");
        assertTrue(shared.timeShared(15));

        Beat drum = noteBeat();
        drum.track(0).tap(0, false);
        drum.track(0).tap(4, false);
        drum.track(0).step(4).setSlide(true);
        Sequence drums = SequenceBuilder.export(drum, ExportOptions.matchingPlayback());
        assertFalse(hasRpn(drums, Gm.DRUM_CHANNEL, 7), "a drum stores slide and does not bend the kit");
        assertEquals(2, noteOns(drums, Gm.DRUM_CHANNEL).size());
    }

    @Test
    void infoNamesTheGlide() throws Exception {
        Beat beat = noteBeat();
        beat.track(1).step(1).setSlide(true);
        beat.track(1).setSlideCurve(Slide.Curve.EXPONENTIAL);
        Path file = temp.resolve("glide.json");
        Files.writeString(file, BeatJson.write(beat));
        Cli info = cli("info", file.toString());
        assertEquals(0, info.code, info.err);
        assertTrue(info.out.contains("slide 80 ms exponential"), info.out);
        assertTrue(info.out.contains("glide step"), info.out);
        Path mid = temp.resolve("glide.mid");
        Cli midi = cli("render", file.toString(), "--out", mid.toString());
        assertEquals(0, midi.code, midi.err);
        assertTrue(Files.size(mid) > 0);
    }

    private static Beat noteBeat() {
        Beat beat = Beat.drumKit("slide");
        beat.setBpm(120);
        beat.setSwing(50);
        beat.setStepCount(16);
        for (int row = 0; row < beat.trackCount(); row++) {
            beat.track(row).clearSteps();
        }
        Track bass = beat.track(1);
        bass.setName("Bass");
        bass.setMode(TrackMode.NOTE);
        bass.setProgram(38);
        bass.setNote(36);
        bass.setGate(100);
        bass.tap(0, false);
        bass.tap(1, false);
        bass.step(0).setPitch(36);
        bass.step(1).setPitch(43);
        bass.step(1).setLength(4);
        return beat;
    }

    private static Beat sineBeat(boolean slide) throws Exception {
        short[] pcm = new short[44100];
        double freq = Notes.frequency(36);
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (short) Math.round(Math.sin(2 * Math.PI * freq * i / 44100.0) * 14000);
        }
        Path wav = Files.createTempDirectory("slide-sine").resolve("c1.wav");
        SampleAudio.writeWav(wav, 44100, pcm);
        Beat beat = noteBeat();
        Track bass = beat.track(1);
        bass.setMode(TrackMode.SAMPLE);
        bass.setSample(new SampleRef(wav.toAbsolutePath().toString(), 36, 0, true));
        bass.setDecay(200);
        bass.setGate(100);
        if (slide) {
            bass.step(1).setSlide(true);
            bass.setSlideMs(80);
            bass.setSlideCurve(Slide.Curve.LINEAR);
        }
        return beat;
    }

    private double[] render(Beat beat) throws Exception {
        Path wav = temp.resolve("slide-" + System.nanoTime() + ".wav");
        try {
            BeatFiles.exportWav(beat, wav, ExportOptions.matchingPlayback(), null, null, () -> false, null);
        } catch (Exception exception) {
            if (WavRenderer.unavailable(exception)) {
                Assumptions.abort("offline render is not available: " + exception.getMessage());
            }
            throw exception;
        }
        try (AudioInputStream in = AudioSystem.getAudioInputStream(wav.toFile())) {
            AudioFormat format = in.getFormat();
            byte[] bytes = in.readAllBytes();
            int frame = format.getFrameSize();
            int frames = frame == 0 ? 0 : bytes.length / frame;
            double[] mono = new double[frames];
            boolean stereo = format.getChannels() > 1 && frame >= 4;
            for (int i = 0; i < frames; i++) {
                int left = (bytes[i * frame] & 0xff) | (bytes[i * frame + 1] << 8);
                int right = stereo ? (bytes[i * frame + 2] & 0xff) | (bytes[i * frame + 3] << 8) : left;
                mono[i] = (((short) left) + ((short) right)) / 2.0 / 32768.0;
            }
            return mono;
        }
    }

    private static int onset(double[] audio, double fraction) {
        double peak = 0;
        for (double sample : audio) {
            peak = Math.max(peak, Math.abs(sample));
        }
        double gate = peak * fraction;
        for (int i = 0; i < audio.length; i++) {
            if (Math.abs(audio[i]) >= gate) {
                return i;
            }
        }
        return 0;
    }

    private static double rms(double[] audio, int from, int to) {
        int start = Math.max(0, from);
        int end = Math.min(audio.length, Math.max(start + 1, to));
        double sum = 0;
        for (int i = start; i < end; i++) {
            sum += audio[i] * audio[i];
        }
        return Math.sqrt(sum / (end - start));
    }

    private static double fundamental(double[] audio, int rate, int from, int to, double low, double high) {
        int start = Math.max(0, from);
        int end = Math.min(audio.length, Math.max(start + 1, to));
        double bestHz = low;
        double best = -1;
        for (double hz = low; hz <= high; hz += 0.5) {
            double real = 0;
            double imag = 0;
            double step = 2 * Math.PI * hz / rate;
            for (int i = start; i < end; i++) {
                real += audio[i] * Math.cos(step * (i - start));
                imag -= audio[i] * Math.sin(step * (i - start));
            }
            double power = Math.hypot(real, imag);
            if (power > best) {
                best = power;
                bestHz = hz;
            }
        }
        return bestHz;
    }

    private static List<Integer> noteOns(Sequence sequence, int channel) {
        List<Integer> notes = new ArrayList<>();
        for (javax.sound.midi.Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                MidiMessage message = track.get(i).getMessage();
                if (message instanceof ShortMessage shortMessage
                    && shortMessage.getChannel() == channel
                    && shortMessage.getCommand() == ShortMessage.NOTE_ON
                    && shortMessage.getData2() > 0) {
                    notes.add(shortMessage.getData1());
                }
            }
        }
        return notes;
    }

    private static boolean hasRpn(Sequence sequence, int channel, int semitones) {
        for (javax.sound.midi.Track track : sequence.getTracks()) {
            boolean select = false;
            for (int i = 0; i < track.size(); i++) {
                MidiEvent event = track.get(i);
                if (!(event.getMessage() instanceof ShortMessage message) || message.getChannel() != channel) {
                    continue;
                }
                if (message.getCommand() != ShortMessage.CONTROL_CHANGE) {
                    continue;
                }
                if (message.getData1() == 101 && message.getData2() == 0) {
                    select = true;
                } else if (select && message.getData1() == 6 && message.getData2() == semitones) {
                    return true;
                } else if (message.getData1() == 101) {
                    select = false;
                }
            }
        }
        return false;
    }

    private Cli cli(String... args) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
        int code = HitsCli.run(args, new java.io.PrintStream(out), new java.io.PrintStream(err));
        return new Cli(
            code,
            out.toString(java.nio.charset.StandardCharsets.UTF_8),
            err.toString(java.nio.charset.StandardCharsets.UTF_8)
        );
    }

    private record Cli(int code, String out, String err) {}
}
