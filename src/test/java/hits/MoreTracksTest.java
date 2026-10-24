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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One to sixteen rows. An eight-row file keeps the bytes and the channels it had. */
class MoreTracksTest {
    private static final Path FIXTURE = Path.of("src/test/resources/fixtures/sparse-808-groove.json");

    @Test
    void eightTrackFilesRoundTripByteForByte() throws Exception {
        String original = Files.readString(FIXTURE);
        Beat loaded = BeatJson.read(original);
        assertEquals(Beat.TRACKS, loaded.trackCount());
        assertEquals(original, BeatJson.write(loaded));

        Beat kit = Beat.drumKit("plain");
        assertEquals(Beat.TRACKS, kit.trackCount());
        assertEquals(Beat.VERSION, BeatJson.documentVersion(kit));
        String written = BeatJson.write(kit);
        assertEquals(written, BeatJson.write(BeatJson.read(written)));

        kit.addTrack();
        assertEquals(Beat.TRACKS + 1, kit.trackCount());
        kit.removeTrack(Beat.TRACKS);
        assertEquals(written, BeatJson.write(kit));
    }

    @Test
    void sixteenRowsSaveAndLoad() throws Exception {
        Beat beat = Beat.drumKit("sixteen");
        beat.setTrackCount(0);
        assertEquals(Beat.MIN_TRACKS, beat.trackCount());
        beat.setTrackCount(20);
        assertEquals(Beat.MAX_TRACKS, beat.trackCount());
        assertFalse(beat.addTrack());

        Track layer = beat.track(15);
        layer.setName("Layer");
        layer.setMode(TrackMode.SAMPLE);
        layer.setSample(SampleRef.builtin(BuiltinSamples.EIGHT_OH_EIGHT, 40).withGain(3).withInvert(true));
        layer.setTune(250);
        layer.setDecay(40);
        layer.setReverb(30);
        layer.setChorus(15);
        layer.setCutoff(40);
        layer.setResonance(70);
        layer.setPan(20);
        layer.setLevel(90);
        layer.setSolo(true);
        layer.setLowCut(80, 24);
        layer.setPresence(3);
        layer.setEqBand(0, 200, -6, 1.2);
        layer.setHighCut(4000, 6);
        layer.tap(0, false);
        layer.step(0).setVelocity(110);
        layer.step(0).setPitch(40);
        layer.step(0).setProbability(50);
        layer.step(0).setLength(2);
        layer.step(0).setNudge(5);

        beat.slot('c')[15].setName("C layer");
        beat.slot('c')[15].setMode(TrackMode.NOTE);
        beat.slot('c')[15].setProgram(81);
        beat.slot('c')[15].setNote(48);
        beat.slot('c')[15].tap(6, false);
        beat.addChain('a');
        beat.addChain('c');
        beat.setChainRepeats(1, 2);

        assertEquals("Sub", beat.slot('b')[Beat.TRACKS].name());
        assertEquals(TrackMode.SAMPLE, beat.slot('h')[Beat.TRACKS].mode());
        assertEquals("Track 16", beat.slot('d')[15].name());

        String first = BeatJson.write(beat);
        Beat loaded = BeatJson.read(first);
        assertEquals(Beat.MAX_TRACKS, loaded.trackCount());
        assertEquals(2, loaded.chain().size());
        assertEquals('c', loaded.chain().get(1).slot());
        assertEquals(2, loaded.chain().get(1).repeats());
        for (int slot = 0; slot < Beat.SLOT_COUNT; slot++) {
            assertEquals(Beat.MAX_TRACKS, loaded.slot(Beat.slotLetter(slot)).length);
            for (int row = 0; row < Beat.MAX_TRACKS; row++) {
                assertTrue(
                    beat.slot(Beat.slotLetter(slot))[row].sameAs(loaded.slot(Beat.slotLetter(slot))[row]),
                    Beat.slotLabel(Beat.slotLetter(slot)) + " row " + (row + 1)
                );
            }
        }
        assertEquals(first, BeatJson.write(loaded));
        assertEquals(first, BeatJson.write(BeatJson.read(BeatJson.write(loaded))));
    }

    @Test
    void jsonRejectsACountOutsideOneToSixteenAndAMismatch() {
        IllegalArgumentException empty = assertThrows(IllegalArgumentException.class, () -> BeatJson.read(beatJson(0, 0)));
        assertTrue(empty.getMessage().contains("1 to 16"), empty.getMessage());
        IllegalArgumentException wide = assertThrows(IllegalArgumentException.class, () -> BeatJson.read(beatJson(17, 17)));
        assertTrue(wide.getMessage().contains("1 to 16"), wide.getMessage());
        IllegalArgumentException mismatch = assertThrows(IllegalArgumentException.class, () -> BeatJson.read(beatJson(8, 7)));
        assertEquals("Every pattern needs 8 tracks", mismatch.getMessage());
        IllegalArgumentException longer = assertThrows(IllegalArgumentException.class, () -> BeatJson.read(beatJson(12, 11)));
        assertEquals("Every pattern needs 12 tracks", longer.getMessage());
    }

    @Test
    void rowsPastEightAreAudible(@TempDir Path directory) throws Exception {
        Beat sub = twelve();
        solo(sub, Beat.TRACKS);
        sub.track(Beat.TRACKS).tap(0, false);
        sub.track(Beat.TRACKS).step(0).setVelocity(120);
        int subPeak = peak(render(sub, directory.resolve("sub.wav")));

        Beat saw = twelve();
        solo(saw, 11);
        Track lead = saw.track(11);
        lead.setName("Saw");
        lead.setMode(TrackMode.NOTE);
        lead.setProgram(81);
        lead.setNote(60);
        lead.tap(0, false);
        lead.step(0).setVelocity(120);
        int sawPeak = peak(render(saw, directory.resolve("saw.wav")));

        Beat quiet = twelve();
        for (int row = 0; row < quiet.trackCount(); row++) {
            quiet.track(row).setMute(true);
        }
        int silent = peak(render(quiet, directory.resolve("silent.wav")));

        System.out.println("row 9 peak " + subPeak + ", row 12 peak " + sawPeak + ", muted peak " + silent);
        assertTrue(subPeak > 1000, "row 9 was quiet, peak " + subPeak);
        assertTrue(sawPeak > 1000, "row 12 was quiet, peak " + sawPeak);
        assertTrue(silent < 200, "muted rows still played, peak " + silent);
    }

    @Test
    void lowCutOnARowPastEightChangesTheBass(@TempDir Path directory) throws Exception {
        Beat open = filteredRow(track -> track.clearLowCut());
        Beat cut = filteredRow(track -> track.setLowCut(400, 24));
        Path openWav = render(open, directory.resolve("open.wav"));
        Path cutWav = render(cut, directory.resolve("cut.wav"));
        double[] openAudio = mono(openWav);
        double[] cutAudio = mono(cutWav);
        double openLow = bin(openAudio, 80);
        double cutLow = bin(cutAudio, 80);
        int openPeak = peak(openWav);
        int cutPeak = peak(cutWav);
        System.out.println(String.format(
            java.util.Locale.ROOT,
            "row 11 low cut 80 Hz %.4f -> %.4f, peak %d -> %d",
            openLow, cutLow, openPeak, cutPeak
        ));
        assertTrue(openPeak > 1000, "open row was quiet, peak " + openPeak);
        assertTrue(cutPeak > 500, "filtered row was quiet, peak " + cutPeak);
        assertTrue(openLow > 0.001, "open bass was silent at 80 Hz");
        assertTrue(cutLow < openLow * 0.5, "400 Hz low cut left 80 Hz in place");

        SampleLibrary.Ready ready = SampleLibrary.prepare(cut, null, List.of('a'));
        assertTrue(ready.use().plays(cut.track(10), 10), "the baked filter was not assigned a sample");
    }

    @Test
    void channelsStayPutUntilThePoolRunsOut() {
        Beat eight = Beat.drumKit("panned");
        for (int row = 0; row < eight.trackCount(); row++) {
            eight.track(row).setPan(20);
        }
        ChannelPlan own = ChannelPlan.of(eight, List.of('a'));
        assertNull(own.channelWarning(eight));
        for (int row = 0; row < eight.trackCount(); row++) {
            assertEquals(row, own.reserved(row), "row " + row);
            assertEquals(row, own.channel(eight.track(row), row));
            assertFalse(own.misrendered(row));
        }

        Beat same = notes(16, true);
        ChannelPlan shared = ChannelPlan.of(same, List.of('a'));
        assertNull(shared.channelWarning(same));
        int channel = shared.channel(same.track(0), 0);
        for (int row = 1; row < 16; row++) {
            assertEquals(channel, shared.channel(same.track(row), row), "row " + row);
            assertFalse(shared.misrendered(row));
        }

        Beat distinct = notes(16, false);
        ChannelPlan crowded = ChannelPlan.of(distinct, List.of('a'));
        String warning = crowded.channelWarning(distinct);
        assertNotNull(warning);
        assertTrue(warning.contains("Not enough MIDI channels"), warning);
        assertTrue(warning.contains("16 V16"), warning);
        assertTrue(warning.contains("shares a channel"), warning);
        assertTrue(warning.contains("Rows with the same instrument, pan, tune, decay, and effects share one channel."), warning);
        assertTrue(crowded.misrendered(15));
        assertFalse(crowded.misrendered(0));

        Beat pair = notes(16, false);
        pair.track(14).setProgram(14);
        pair.track(15).setProgram(14);
        pair.track(15).setName("V15");
        ChannelPlan paired = ChannelPlan.of(pair, List.of('a'));
        assertNull(paired.channelWarning(pair));
        assertEquals(paired.channel(pair.track(14), 14), paired.channel(pair.track(15), 15));
        assertFalse(paired.misrendered(15));

        Beat drums = drums(16, 90);
        ChannelPlan oneKit = ChannelPlan.of(drums, List.of('a'));
        assertNull(oneKit.channelWarning(drums));
        int drumChannel = oneKit.channel(drums.track(0), 0);
        assertTrue(drumChannel != Gm.DRUM_CHANNEL);
        for (int row = 1; row < 16; row++) {
            assertEquals(drumChannel, oneKit.channel(drums.track(row), row));
        }

        Beat spread = drums(16, -1);
        ChannelPlan spreadPlan = ChannelPlan.of(spread, List.of('a'));
        String spreadWarning = spreadPlan.channelWarning(spread);
        assertNotNull(spreadWarning);
        assertTrue(spreadWarning.contains("Not enough MIDI channels"), spreadWarning);
        assertTrue(spreadPlan.misrendered(15));
    }

    @Test
    void aFilteredHitWarnsWhenThePlainHitHasNoChannel() throws Exception {
        Beat beat = Beat.drumKit("split");
        beat.setTrackCount(16);
        for (int row = 0; row < 14; row++) {
            note(beat.slot('a')[row], row + 1, "N" + (row + 1));
            note(beat.slot('b')[row], row + 1, "N" + (row + 1));
        }
        Track filtered = beat.slot('a')[15];
        filtered.setName("Layer");
        filtered.setNote(36);
        filtered.setLowCut(200, 24);
        Track plain = beat.slot('b')[15];
        plain.setName("Layer");
        plain.setNote(36);
        plain.setPan(20);

        SampleLibrary.Ready ready = SampleLibrary.prepare(beat, null, List.of('a', 'b'));
        assertTrue(ready.use().plays(filtered, 15));
        assertFalse(ready.use().plays(plain, 15));
        ChannelPlan plan = ChannelPlan.of(beat, List.of('a', 'b'), null, ready.use());
        String warning = plan.channelWarning(beat);
        assertNotNull(warning);
        assertTrue(warning.contains("16 Layer"), warning);
        assertTrue(warning.contains("filtered hit and a plain hit"), warning);
        assertTrue(plan.misrendered(15));
        assertEquals(Gm.DRUM_CHANNEL, plan.channel(plain, 15));
    }

    @Test
    void infoAndRenderNameARowThatCannotHaveAChannel(@TempDir Path directory) throws Exception {
        Beat beat = notes(16, false);
        Path file = directory.resolve("crowded.json");
        Files.writeString(file, BeatJson.write(beat));
        Path midi = directory.resolve("crowded.mid");

        Result info = run("info", file.toString());
        assertEquals(HitsCli.OK, info.code, info.err);
        assertTrue(info.err.contains("Not enough MIDI channels"), info.err);
        assertTrue(info.err.contains("16 V16"), info.err);
        assertTrue(info.out.contains("V16"), info.out);

        Result rendered = run("render", file.toString(), "--out", midi.toString());
        assertEquals(HitsCli.OK, rendered.code, rendered.err);
        assertTrue(rendered.err.contains("Not enough MIDI channels"), rendered.err);
        assertTrue(Files.size(midi) > 0);

        Beat fitted = Beat.drumKit("fits");
        Path plain = directory.resolve("plain.json");
        Files.writeString(plain, BeatJson.write(fitted));
        Result quiet = run("info", plain.toString());
        assertEquals(HitsCli.OK, quiet.code, quiet.err);
        assertEquals("", quiet.err);
    }

    @Test
    void addRemoveAndReorderUpdateEveryPatternAndUndo() {
        Beat beat = Beat.drumKit("edit");
        beat.slot('b')[0].tap(3, false);
        beat.slot('b')[0].step(3).setVelocity(42);
        beat.slot('d')[7].setName("Slot D ride");
        beat.slot('d')[7].tap(2, true);
        Editor editor = new Editor(beat);
        String beforeAdd = BeatJson.write(editor.beat());

        editor.addRow();
        assertEquals(9, editor.beat().trackCount());
        assertEquals("Sub", editor.beat().slot('a')[8].name());
        assertEquals("Sub", editor.beat().slot('h')[8].name());
        assertEquals(TrackMode.SAMPLE, editor.beat().slot('c')[8].mode());
        assertEquals(42, editor.beat().slot('b')[0].step(3).velocity());
        assertEquals(8, editor.trackIndex());

        editor.undo();
        assertEquals(8, editor.beat().trackCount());
        assertEquals(beforeAdd, BeatJson.write(editor.beat()));
        editor.redo();
        assertEquals(9, editor.beat().trackCount());
        assertEquals("Sub", editor.beat().track(8).name());

        editor.selectTrack(0);
        editor.duplicateRow();
        assertEquals(10, editor.beat().trackCount());
        assertEquals("Kick", editor.beat().slot('a')[1].name());
        assertEquals("Kick", editor.beat().slot('f')[1].name());
        assertTrue(editor.beat().slot('b')[1].step(3).on());
        assertEquals(42, editor.beat().slot('b')[1].step(3).velocity());
        assertEquals("Snare", editor.beat().slot('a')[2].name());
        assertEquals(1, editor.trackIndex());

        editor.moveRow(1);
        assertEquals("Snare", editor.beat().slot('a')[1].name());
        assertEquals("Kick", editor.beat().slot('b')[2].name());
        assertEquals(42, editor.beat().slot('b')[2].step(3).velocity());
        assertEquals("Slot D ride", editor.beat().slot('d')[8].name());
        assertTrue(editor.beat().slot('d')[8].step(2).on());
        assertEquals(2, editor.trackIndex());

        editor.removeRow();
        assertEquals(9, editor.beat().trackCount());
        assertEquals("Kick", editor.beat().slot('a')[0].name());
        assertEquals("Snare", editor.beat().slot('a')[1].name());
        assertTrue(editor.beat().slot('b')[0].step(3).on());
        assertEquals(42, editor.beat().slot('b')[0].step(3).velocity());
        assertEquals("Slot D ride", editor.beat().slot('d')[7].name());
        for (int slot = 0; slot < Beat.SLOT_COUNT; slot++) {
            assertEquals(9, editor.beat().slot(Beat.slotLetter(slot)).length);
        }

        editor.undo();
        assertEquals(10, editor.beat().trackCount());
        assertEquals("Kick", editor.beat().slot('b')[2].name());
        assertEquals(42, editor.beat().slot('b')[2].step(3).velocity());

        Beat direct = Beat.drumKit("move");
        direct.setTrackCount(10);
        direct.slot('e')[9].setName("Tail");
        direct.slot('e')[9].tap(2, false);
        assertTrue(direct.moveTrack(9, -9));
        assertEquals("Tail", direct.slot('e')[0].name());
        assertTrue(direct.slot('e')[0].step(2).on());
        assertEquals("Track 10", direct.slot('a')[0].name());
        assertEquals("Kick", direct.slot('a')[1].name());
        assertEquals("Sub", direct.slot('a')[9].name());

        Beat full = Beat.drumKit("full");
        full.setTrackCount(Beat.MAX_TRACKS);
        Editor capped = new Editor(full);
        String frozen = BeatJson.write(capped.beat());
        capped.addRow();
        capped.duplicateRow();
        assertEquals(frozen, BeatJson.write(capped.beat()));
        Beat only = Beat.drumKit("one");
        only.setTrackCount(Beat.MIN_TRACKS);
        Editor last = new Editor(only);
        last.removeRow();
        assertEquals(Beat.MIN_TRACKS, last.beat().trackCount());
    }

    @Test
    void grooveAndKitsLeaveExtraRowsAlone() {
        Beat beat = Beat.drumKit("groove");
        beat.addTrack();
        beat.track(8).tap(0, false);
        beat.track(8).step(0).setVelocity(61);
        beat.addTrack();
        GrooveGenerator.apply(beat, new GrooveGenerator(4).generate(GrooveGenerator.Genre.HOUSE, 16));
        assertEquals(10, beat.trackCount());
        assertTrue(beat.track(8).step(0).on());
        assertEquals(61, beat.track(8).step(0).velocity());
        assertEquals("Sub", beat.track(8).name());

        int[] hits = new int[16];
        hits[5] = 77;
        GrooveGenerator.applyTrack(beat, 9, hits);
        assertTrue(beat.track(9).step(5).on());
        assertEquals(77, beat.track(9).step(5).velocity());
        assertFalse(beat.slot('b')[9].step(5).on());

        Beat small = Beat.drumKit("small");
        small.setTrackCount(7);
        GrooveGenerator.apply(small, new GrooveGenerator(1).generate(GrooveGenerator.Genre.ROCK, 16));
        assertEquals(7, small.trackCount());
        assertEquals("Kick", small.track(0).name());

        Beat kept = Beat.drumKit("kept");
        kept.setTrackCount(12);
        kept.track(11).setName("Keep");
        kept.track(11).tap(1, false);
        Kits.boomBap(kept);
        assertEquals(12, kept.trackCount());
        assertEquals("Keep", kept.track(11).name());
        assertTrue(kept.track(11).step(1).on());
        assertEquals("Sub", kept.track(8).name());
    }

    @Test
    void aNoteOnRowTwelveIsInTheSequenceAndTheSong() throws Exception {
        Beat beat = Beat.drumKit("midi");
        beat.setBpm(120);
        beat.setStepCount(4);
        beat.setTrackCount(12);
        for (int row = 0; row < beat.trackCount(); row++) {
            beat.track(row).clearSteps();
        }
        Track layer = beat.track(11);
        layer.setMode(TrackMode.NOTE);
        layer.setProgram(12);
        layer.setNote(72);
        layer.setName("Top");
        layer.tap(0, false);
        beat.addChain('a');
        beat.addChain('a');

        assertTrue(hasNote(SequenceBuilder.build(beat), 72));
        assertEquals(2, countNotes(SequenceBuilder.buildSong(beat), 72));
        layer.setMute(true);
        assertFalse(hasNote(SequenceBuilder.build(beat), 72));
    }

    @Test
    void btfImportStillHasEightRows() throws Exception {
        Beat beat = BtfFormat.read(Path.of("beats/raw.btf"));
        assertEquals(Beat.TRACKS, beat.trackCount());
        assertEquals(Beat.TRACKS, beat.slot('b').length);
    }

    private static void note(Track track, int program, String name) {
        track.setName(name);
        track.setMode(TrackMode.NOTE);
        track.setProgram(program);
        track.setNote(60);
    }

    private static Beat notes(int count, boolean sameProgram) {
        Beat beat = Beat.drumKit("notes");
        beat.setTrackCount(count);
        for (int row = 0; row < count; row++) {
            Track track = beat.slot('a')[row];
            note(track, sameProgram ? 4 : row, "V" + (row + 1));
        }
        return beat;
    }

    /** {@code pan} below 0 gives each row its own pan. */
    private static Beat drums(int count, int pan) {
        Beat beat = Beat.drumKit("drums");
        beat.setTrackCount(count);
        for (int row = 0; row < count; row++) {
            Track track = beat.slot('a')[row];
            track.setMode(TrackMode.DRUM);
            track.setNote(36);
            track.setName("D" + (row + 1));
            track.setPan(pan < 0 ? row + 1 : pan);
        }
        return beat;
    }

    private static Beat twelve() {
        Beat beat = Beat.drumKit("twelve");
        beat.setBpm(120);
        beat.setStepCount(2);
        beat.setTrackCount(12);
        for (int row = 0; row < beat.trackCount(); row++) {
            beat.track(row).clearSteps();
        }
        return beat;
    }

    private static void solo(Beat beat, int row) {
        beat.track(row).setSolo(true);
    }

    private static Beat filteredRow(java.util.function.Consumer<Track> shape) {
        Beat beat = twelve();
        solo(beat, 10);
        Track track = beat.track(10);
        track.setName("Bass");
        track.setMode(TrackMode.NOTE);
        track.setProgram(38);
        track.setNote(36);
        track.tap(0, false);
        track.step(0).setVelocity(120);
        shape.accept(track);
        return beat;
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

    private static int peak(Path wav) throws Exception {
        byte[] data = Files.readAllBytes(wav);
        int peak = 0;
        for (int i = WavAudio.HEADER_BYTES; i + 1 < data.length; i += 2) {
            int sample = (data[i] & 0xff) | (data[i + 1] << 8);
            if (sample > 32767) {
                sample -= 65536;
            }
            peak = Math.max(peak, Math.abs(sample));
        }
        return peak;
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

    /** Magnitude of one frequency, from the start of a 44.1 kHz clip. */
    private static double bin(double[] audio, double hz) {
        int rate = 44100;
        int count = Math.min(audio.length, rate / 2);
        double real = 0;
        double imag = 0;
        double step = 2 * Math.PI * hz / rate;
        for (int i = 0; i < count; i++) {
            real += audio[i] * Math.cos(step * i);
            imag -= audio[i] * Math.sin(step * i);
        }
        return count == 0 ? 0 : Math.hypot(real, imag) / count;
    }

    private static boolean hasNote(Sequence sequence, int note) {
        return countNotes(sequence, note) > 0;
    }

    private static int countNotes(Sequence sequence, int note) {
        int count = 0;
        for (javax.sound.midi.Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                MidiEvent event = track.get(i);
                MidiMessage message = event.getMessage();
                if (message instanceof ShortMessage shortMessage
                    && shortMessage.getCommand() == ShortMessage.NOTE_ON
                    && shortMessage.getData1() == note
                    && shortMessage.getData2() > 0) {
                    count++;
                }
            }
        }
        return count;
    }

    private static String beatJson(int aCount, int bCount) {
        return "{\n"
            + "  \"version\": 1,\n"
            + "  \"name\": \"count\",\n"
            + "  \"bpm\": 96,\n"
            + "  \"swing\": 50,\n"
            + "  \"steps\": 16,\n"
            + "  \"active\": \"a\",\n"
            + "  \"slots\": {\n"
            + "    \"a\": {\"tracks\": [" + tracks(aCount) + "]},\n"
            + "    \"b\": {\"tracks\": [" + tracks(bCount) + "]}\n"
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
