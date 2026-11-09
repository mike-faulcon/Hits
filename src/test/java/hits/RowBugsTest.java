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
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Row defaults, channel warnings, and the melodic row that used to land on the drum channel.
 */
class RowBugsTest {
    @TempDir
    Path temp;

    @Test
    void defaultSubIsASteadySineAtTheLabelledPitch() throws Exception {
        Beat beat = Beat.drumKit("sub");
        beat.addTrack();
        Track sub = beat.track(Beat.TRACKS);
        assertEquals("Sub", sub.name());
        assertEquals(TrackMode.SAMPLE, sub.mode());
        assertEquals(BuiltinSamples.SUB, sub.sample().path());
        assertEquals(BuiltinSamples.SUB_NOTE, sub.note());
        assertEquals(BuiltinSamples.SUB_NOTE, sub.sample().root());
        assertEquals(beat.track(0).accent(), sub.accent());
        assertEquals("F#0", Notes.name(sub.playedNote()));
        double labelled = Notes.frequency(sub.playedNote());
        assertTrue(labelled >= 40 && labelled <= 55, "labelled pitch " + labelled);

        for (int row = 0; row < beat.trackCount(); row++) {
            beat.track(row).clearSteps();
        }
        sub.setSolo(true);
        sub.tap(0, false);
        sub.step(0).setVelocity(120);
        double[] audio = mono(render(beat, temp.resolve("sub.wav")));
        int rate = 44100;
        int onset = onset(audio, 0.2);
        double early = fundamental(audio, rate, onset, onset + (int) (0.04 * rate), 30, 80);
        double later = fundamental(audio, rate, onset + (int) (0.05 * rate), onset + (int) (0.10 * rate), 30, 80);
        double decayMs = decayTo(audio, rate, onset, -20);
        System.out.println(String.format(
            java.util.Locale.ROOT,
            "sub label %.2f Hz, early %.2f Hz, later %.2f Hz, −20 dB %.0f ms",
            labelled, early, later, decayMs
        ));
        assertTrue(Math.abs(early - labelled) < 3, "fundamental " + early + " vs label " + labelled);
        assertTrue(Math.abs(later - early) < 3, "the sine moved from " + early + " to " + later);
        assertTrue(decayMs >= 60 && decayMs <= 250, "decay " + decayMs + " ms");
    }

    @Test
    void addedRowMatchesTheLoudHitOfTheOtherRows() {
        Beat beat = Beat.drumKit("accent");
        for (int slot = 0; slot < Beat.SLOT_COUNT; slot++) {
            for (Track track : beat.slot(Beat.slotLetter(slot))) {
                track.setAccent(115);
            }
        }
        beat.addTrack();
        assertEquals(115, beat.track(Beat.TRACKS).accent());
        assertEquals(115, beat.slot('h')[Beat.TRACKS].accent());
    }

    @Test
    void aSecondAddDoesNotCreateAnotherSub() {
        Beat beat = Beat.drumKit("again");
        assertTrue(beat.addTrack());
        assertEquals("Sub", beat.track(8).name());
        assertTrue(beat.addTrack());
        assertEquals("Track 10", beat.track(9).name());
        assertTrue(beat.removeTrack(9));
        assertTrue(beat.addTrack());
        assertEquals("Track 10", beat.track(9).name());
        assertEquals(1, countNamed(beat, "Sub"));

        assertTrue(beat.removeTrack(8));
        assertTrue(beat.addTrack());
        assertEquals("Sub", beat.track(9).name());
        assertEquals(1, countNamed(beat, "Sub"));
    }

    @Test
    void addFillsTheMissingKickBeforeTheOtherKitPieces() {
        Beat beat = Beat.drumKit("one");
        while (beat.trackCount() > 1) {
            beat.removeTrack(beat.trackCount() - 1);
        }
        assertEquals("Kick", beat.track(0).name());
        beat.track(0).setName("Snare");
        beat.track(0).setNote(38);
        for (int slot = 1; slot < Beat.SLOT_COUNT; slot++) {
            beat.slot(Beat.slotLetter(slot))[0].setName("Snare");
            beat.slot(Beat.slotLetter(slot))[0].setNote(38);
        }
        assertTrue(beat.addTrack());
        assertEquals("Kick", beat.track(1).name());
        assertEquals(36, beat.track(1).note());
        assertTrue(beat.addTrack());
        assertEquals("Closed Hat", beat.track(2).name());
    }

    @Test
    void aLonePannedDrumDoesNotWarn() {
        Beat beat = Beat.drumKit("pans");
        beat.setTrackCount(16);
        for (int row = 0; row < 16; row++) {
            Track track = beat.track(row);
            track.setMode(TrackMode.DRUM);
            track.setNote(36 + (row % 12));
            track.setName(row == 15 ? "Perc 15" : "Perc " + (row + 1));
            track.setPan(row + 1);
            track.clearSteps();
        }
        ChannelPlan plan = ChannelPlan.of(beat, List.of('a'));
        assertNull(plan.channelWarning(beat));
        assertFalse(plan.misrendered(15));
        assertEquals(Gm.DRUM_CHANNEL, plan.channel(beat.track(15), 15));
        for (int row = 0; row < 15; row++) {
            assertTrue(plan.channel(beat.track(row), row) != Gm.DRUM_CHANNEL);
            assertFalse(plan.misrendered(row));
        }
    }

    @Test
    void aRealPanConflictNamesTheRows() throws Exception {
        Beat beat = Beat.drumKit("share");
        beat.setTrackCount(16);
        for (char slot : List.of('a', 'b')) {
            for (int row = 0; row < 14; row++) {
                note(beat.slot(slot)[row], row + 1, "N" + (row + 1));
                beat.slot(slot)[row].tap(0, false);
            }
            Track clap = beat.slot(slot)[14];
            clap.setName("Clap");
            clap.setMode(TrackMode.DRUM);
            clap.setNote(39);
            clap.setPan(Track.PAN_CENTER);
            clap.clearSteps();
        }
        Track filtered = beat.slot('a')[15];
        filtered.setName("Perc 15");
        filtered.setMode(TrackMode.DRUM);
        filtered.setNote(56);
        filtered.setPan(40);
        filtered.setLowCut(200, 24);
        filtered.tap(0, false);
        Track plain = beat.slot('b')[15];
        plain.setName("Perc 15");
        plain.setMode(TrackMode.DRUM);
        plain.setNote(56);
        plain.setPan(40);
        plain.clearSteps();
        plain.tap(0, false);

        SampleLibrary.Ready ready = SampleLibrary.prepare(beat, null, List.of('a', 'b'));
        ChannelPlan plan = ChannelPlan.of(beat, List.of('a', 'b'), null, ready.use());
        String warning = plan.channelWarning(beat);
        assertNotNull(warning);
        assertTrue(warning.contains("Row 16 \"Perc 15\""), warning);
        assertTrue(warning.contains("row 15 \"Clap\""), warning);
        assertTrue(warning.contains("MIDI channel 10"), warning);
        assertTrue(warning.contains("their pan differs"), warning);
        assertTrue(warning.contains("row 15's pan"), warning);
        assertTrue(warning.contains("pattern"), warning);

        Path midi = temp.resolve("share.mid");
        BeatFiles.exportMidi(beat, midi, ExportOptions.matchingPlayback());
        Sequence sequence = javax.sound.midi.MidiSystem.getSequence(midi.toFile());
        assertTrue(hasControl(sequence, Gm.DRUM_CHANNEL, 10, Track.PAN_CENTER), "owner pan");
        assertFalse(hasControl(sequence, Gm.DRUM_CHANNEL, 10, 40), "the other row's pan replaced it");
    }

    @Test
    void sixteenMelodicInstrumentsDoNotPlayTheBanjoAsADrum() throws Exception {
        Beat beat = melodic(true);
        Track banjo = beat.track(15);
        assertEquals("Banjo", banjo.name());
        assertEquals(105, banjo.program());
        ChannelPlan plan = ChannelPlan.of(beat, List.of('a'));
        assertTrue(plan.omitted(15), "banjo should not take the drum channel");
        assertEquals(-1, plan.channel(banjo, 15));
        assertTrue(plan.channel(banjo, 15) != Gm.DRUM_CHANNEL);
        String warning = plan.channelWarning(beat);
        assertNotNull(warning);
        assertTrue(warning.contains("Row 16 \"Banjo\""), warning);
        assertTrue(warning.contains("left out of the MIDI file"), warning);
        assertTrue(warning.contains("WAV export still plays it"), warning);

        Path midi = temp.resolve("banjo.mid");
        BeatFiles.exportMidi(beat, midi, ExportOptions.matchingPlayback());
        Sequence sequence = javax.sound.midi.MidiSystem.getSequence(midi.toFile());
        assertFalse(noteOnChannel(sequence, Gm.DRUM_CHANNEL, 60), "banjo note was written on the drum channel");
        assertFalse(programOnChannel(sequence, Gm.DRUM_CHANNEL, 105), "banjo program was sent on the drum channel");

        banjo.setSolo(true);
        double[] audio = mono(render(beat, temp.resolve("banjo.wav")));
        int onset = onset(audio, 0.15);
        double hz = fundamental(audio, 44100, onset, onset + 44100 / 5, 150, 500);
        double expected = Notes.frequency(60);
        System.out.println(String.format(java.util.Locale.ROOT, "banjo peak %.1f Hz, expected %.1f Hz", hz, expected));
        assertTrue(Math.abs(hz - expected) < 8, "WAV peak " + hz + " is not the banjo pitch " + expected);
    }

    @Test
    void nonOverlappingMelodicRowsShareAChannelWithAProgramChange() throws Exception {
        Beat beat = melodic(false);
        for (int row = 0; row < 15; row++) {
            beat.track(row).tap(0, false);
        }
        beat.track(15).tap(8, false);
        ChannelPlan plan = ChannelPlan.of(beat, List.of('a'));
        assertFalse(plan.omitted(15));
        assertTrue(plan.timeShared(15));
        int channel = plan.channel(beat.track(15), 15);
        assertTrue(channel != Gm.DRUM_CHANNEL && channel >= 0, "channel " + channel);

        Sequence sequence = SequenceBuilder.export(beat, ExportOptions.matchingPlayback());
        assertTrue(programOnChannel(sequence, channel, 105), "program change for the banjo");
        assertTrue(noteOnChannel(sequence, channel, 60), "banjo note on the shared channel");
        assertFalse(noteOnChannel(sequence, Gm.DRUM_CHANNEL, 60));

        for (int row = 0; row < 15; row++) {
            beat.track(row).setMute(true);
        }
        double[] audio = mono(render(beat, temp.resolve("late-banjo.wav")));
        int from = (int) (0.9 * 44100);
        double hz = fundamental(audio, 44100, from, from + 44100 / 4, 150, 500);
        double expected = Notes.frequency(60);
        System.out.println(String.format(java.util.Locale.ROOT, "shared banjo peak %.1f Hz", hz));
        assertTrue(Math.abs(hz - expected) < 8, "shared WAV peak " + hz);
    }

    @Test
    void aPatternCountErrorNamesThePatternAndTheCount() {
        IllegalArgumentException one = org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> BeatJson.read(patterns(9, 8, 9))
        );
        assertEquals("Pattern B has 8 tracks, not 9.", one.getMessage());
        IllegalArgumentException both = org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> BeatJson.read(patterns(9, 8, 7))
        );
        assertEquals("Pattern B has 8 tracks and pattern C has 7 tracks, not 9.", both.getMessage());
        assertTrue(both.getMessage().contains("Pattern B"));
        assertTrue(both.getMessage().contains("pattern C"));
    }

    @Test
    void filterWarningsFollowTheSongAndGroupEachRow() throws Exception {
        Beat beat = Beat.drumKit("filters");
        Track kick = beat.track(0);
        kick.setName("Kick");
        kick.setLowCut(80, 24);
        kick.setEqBand(0, 200, -3, 1.2);
        kick.setHighCut(5000, 6);
        kick.setPresence(3);
        kick.tap(0, false);
        beat.track(1).setName("Snare");
        beat.track(1).setHighCut(4000, 12);
        Track flip = beat.track(2);
        flip.setName("Flip");
        flip.setMode(TrackMode.SAMPLE);
        flip.setSample(SampleRef.builtin(BuiltinSamples.SUB, BuiltinSamples.SUB_NOTE).withInvert(true));
        flip.tap(4, false);
        beat.slot('b')[3].setName("LC");
        beat.slot('b')[3].setLowCut(100, 24);
        beat.addChain('a');

        String warning = beat.midiFilterWarning();
        assertNotNull(warning);
        assertTrue(warning.contains("Row 1 \"Kick\" drops low cut, EQ, high cut, and presence in pattern A."), warning);
        assertTrue(warning.contains("Row 3 \"Flip\" drops invert in pattern A."), warning);
        assertFalse(warning.contains("Snare"), warning);
        assertFalse(warning.contains("LC"), warning);
        assertFalse(warning.contains("pattern B"), warning);
        assertEquals(2, warning.split("Row ", -1).length - 1, warning);

        Path file = temp.resolve("filters.json");
        Files.writeString(file, BeatJson.write(beat));
        Result info = run("info", file.toString());
        assertEquals(HitsCli.OK, info.code, info.err);
        assertTrue(info.out.contains("patterns: A\n") || info.out.contains("patterns: A,"), info.out);
        assertFalse(info.out.startsWith("patterns: A, B"), info.out);
        assertTrue(info.out.contains("patterns: A"), info.out);
        assertFalse(info.err.contains("LC"), info.err);
        assertFalse(info.err.contains("pattern B"), info.err);
        assertTrue(info.err.contains("Row 1 \"Kick\""), info.err);
        assertTrue(info.err.contains("invert"), info.err);
        String patterns = info.out.lines().filter(line -> line.startsWith("patterns:")).findFirst().orElse("");
        assertFalse(patterns.contains("B"), patterns);
    }

    @Test
    void filterWarningSkipsPatternsWhereTheRowIsSilent() {
        Beat beat = Beat.drumKit("silent");
        Track snare = beat.track(1);
        snare.setName("Snare");
        snare.setHighCut(4000, 12);
        snare.tap(0, false);
        beat.slot('b')[1].setName("Snare");
        beat.slot('b')[1].setHighCut(4000, 12);
        beat.slot('c')[1].setName("Snare stop");
        beat.slot('c')[1].setHighCut(3000, 12);
        beat.slot('c')[1].tap(2, false);
        beat.addChain('a');
        beat.addChain('b');

        String warning = beat.midiFilterWarning();
        assertNotNull(warning);
        assertTrue(warning.contains("in pattern A"), warning);
        assertFalse(warning.contains("pattern B"), warning);
        assertFalse(warning.contains("pattern C"), warning);
        assertFalse(warning.contains("Snare stop"), warning);
    }

    private static int countNamed(Beat beat, String name) {
        int count = 0;
        for (Track track : beat.slot('a')) {
            if (name.equals(track.name())) {
                count++;
            }
        }
        return count;
    }

    private static void note(Track track, int program, String name) {
        track.setName(name);
        track.setMode(TrackMode.NOTE);
        track.setProgram(program);
        track.setNote(60);
    }

    /** Sixteen distinct note rows. {@code together} puts every row on step 1, including Banjo. */
    private static Beat melodic(boolean together) {
        Beat beat = Beat.drumKit("melodic");
        beat.setBpm(120);
        beat.setStepCount(16);
        beat.setTrackCount(16);
        for (int row = 0; row < 16; row++) {
            Track track = beat.track(row);
            track.clearSteps();
            boolean banjo = row == 15;
            note(track, banjo ? 105 : row, banjo ? "Banjo" : "M" + (row + 1));
            track.setGate(80);
            if (together) {
                track.tap(0, false);
                track.step(0).setVelocity(110);
            }
        }
        return beat;
    }

    private static String patterns(int aCount, int bCount, int cCount) {
        return "{\n"
            + "  \"version\": 1,\n"
            + "  \"name\": \"count\",\n"
            + "  \"bpm\": 96,\n"
            + "  \"swing\": 50,\n"
            + "  \"steps\": 16,\n"
            + "  \"active\": \"a\",\n"
            + "  \"slots\": {\n"
            + "    \"a\": {\"tracks\": [" + tracks(aCount) + "]},\n"
            + "    \"b\": {\"tracks\": [" + tracks(bCount) + "]},\n"
            + "    \"c\": {\"tracks\": [" + tracks(cCount) + "]}\n"
            + "  }\n"
            + "}\n";
    }

    private static String tracks(int count) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append("{\"name\":\"T").append(i)
                .append("\",\"mode\":\"drum\",\"program\":0,\"note\":36,\"mute\":false,\"solo\":false,")
                .append("\"gate\":50,\"velocity\":100,\"accent\":120,\"steps\":[]}");
        }
        return out.toString();
    }

    private static Path render(Beat beat, Path wav) throws Exception {
        try {
            BeatFiles.exportWav(beat, wav, ExportOptions.matchingPlayback(), null, null, () -> false, null);
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

    /** Goertzel peak between {@code low} and {@code high}, over {@code from} inclusive to {@code to} exclusive. */
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

    /** Milliseconds from {@code origin} until the wave last stays at or above {@code db} relative to its peak. */
    private static double decayTo(double[] audio, int rate, int origin, double db) {
        int start = Math.max(0, origin);
        double peak = 0;
        for (int i = start; i < audio.length; i++) {
            peak = Math.max(peak, Math.abs(audio[i]));
        }
        double gate = peak * Math.pow(10, db / 20.0);
        int last = start;
        for (int i = start; i < audio.length; i++) {
            if (Math.abs(audio[i]) >= gate) {
                last = i;
            }
        }
        return (last - start) * 1000.0 / rate;
    }

    private static boolean hasControl(Sequence sequence, int channel, int controller, int value) {
        for (javax.sound.midi.Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                MidiMessage message = track.get(i).getMessage();
                if (message instanceof ShortMessage shortMessage
                    && shortMessage.getChannel() == channel
                    && shortMessage.getCommand() == ShortMessage.CONTROL_CHANGE
                    && shortMessage.getData1() == controller
                    && shortMessage.getData2() == value) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean noteOnChannel(Sequence sequence, int channel, int note) {
        for (javax.sound.midi.Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                MidiEvent event = track.get(i);
                MidiMessage message = event.getMessage();
                if (message instanceof ShortMessage shortMessage
                    && shortMessage.getChannel() == channel
                    && shortMessage.getCommand() == ShortMessage.NOTE_ON
                    && shortMessage.getData1() == note
                    && shortMessage.getData2() > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean programOnChannel(Sequence sequence, int channel, int program) {
        for (javax.sound.midi.Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                MidiMessage message = track.get(i).getMessage();
                if (message instanceof ShortMessage shortMessage
                    && shortMessage.getChannel() == channel
                    && shortMessage.getCommand() == ShortMessage.PROGRAM_CHANGE
                    && shortMessage.getData1() == program) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Result run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = HitsCli.run(
            args,
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8)
        );
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private record Result(int code, String out, String err) {}
}
