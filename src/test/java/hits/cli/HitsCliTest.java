package hits.cli;

import hits.Beat;
import hits.BeatFiles;
import hits.BeatJson;
import hits.ExportOptions;
import hits.SequenceBuilder;
import hits.Track;
import hits.TrackMode;
import hits.WavAudio;
import hits.ui.HitsApp;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Headless render and info. The fixture is an original one-bar groove, not a recording. */
class HitsCliTest {
    private static final Path FIXTURE = Path.of("src/test/resources/fixtures/sparse-808-groove.json");
    private static final Path PATTERN_GOLDEN = Path.of("src/test/resources/fixtures/sparse-808-groove.mid.txt");
    private static final Path SONG_GOLDEN = Path.of("src/test/resources/fixtures/sparse-808-groove-song.mid.txt");

    @BeforeAll
    static void writeFixtureWhenUpdating() throws Exception {
        if (Boolean.getBoolean("hits.updateGoldens")) {
            Files.createDirectories(FIXTURE.getParent());
            Files.writeString(FIXTURE, BeatJson.write(referenceBeat()));
        }
    }

    @Test
    void noArgumentsLaunchTheDesktop() {
        assertTrue(HitsCli.launchesDesktop(null));
        assertTrue(HitsCli.launchesDesktop(new String[0]));
        assertTrue(HitsCli.launchesDesktop(new String[] {"-psn_0_12345"}));
        assertFalse(HitsCli.launchesDesktop(new String[] {"info", "beat.json"}));
        assertFalse(HitsCli.launchesDesktop(new String[] {"render", "beat.json", "--out", "out.mid"}));
    }

    @Test
    void noArgumentsOnAHeadlessMachinePrintUsageAndExit() throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        ProcessBuilder builder = new ProcessBuilder(
            java.toString(),
            "-Djava.awt.headless=true",
            "-cp",
            System.getProperty("java.class.path"),
            HitsApp.class.getName()
        );
        builder.environment().remove("DISPLAY");
        builder.environment().remove("WAYLAND_DISPLAY");
        Process process = builder.start();
        boolean finished = process.waitFor(20, TimeUnit.SECONDS);
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!finished) {
            process.destroyForcibly();
            fail("hits with no arguments hung without a display\n" + out + "\n" + err);
        }
        assertEquals(HitsCli.USAGE, process.exitValue(), out + err);
        assertTrue(out.contains("Usage"), out);
        assertTrue(out.contains("hits info"), out);
    }

    @Test
    void helpAndUnknownCommands() {
        Result help = run("--help");
        assertEquals(HitsCli.OK, help.code);
        assertTrue(help.out.contains("hits render"));
        assertTrue(help.out.contains("--song"));
        assertEquals("", help.err);

        Result missing = run();
        assertEquals(HitsCli.USAGE, missing.code);
        assertTrue(missing.out.contains("hits info"));

        Result unknown = run("dance");
        assertEquals(HitsCli.USAGE, unknown.code);
        assertTrue(unknown.err.contains("Unknown command \"dance\""));

        Result shot = run("shot", "beat.json", "--out", "out.png");
        assertEquals(HitsCli.USAGE, shot.code);
        assertTrue(shot.err.contains("needs a display"));
        assertFalse(shot.err.contains("Usage:"));
    }

    @Test
    void badPathInvalidJsonAndUnknownExtension(@TempDir Path directory) throws Exception {
        Result missing = run("info", directory.resolve("no-such-beat.json").toString());
        assertEquals(HitsCli.FAIL, missing.code);
        assertTrue(missing.err.contains("No such beat file"));
        assertTrue(missing.out.isEmpty());

        Result missingRender = run(
            "render",
            directory.resolve("missing.json").toString(),
            "--out",
            directory.resolve("missing.mid").toString()
        );
        assertEquals(HitsCli.FAIL, missingRender.code);
        assertTrue(missingRender.err.contains("No such beat file"));
        assertFalse(Files.exists(directory.resolve("missing.mid")));

        Result directoryBeat = run("render", directory.toString(), "--out", directory.resolve("out.mid").toString());
        assertEquals(HitsCli.FAIL, directoryBeat.code);
        assertTrue(directoryBeat.err.contains("Not a beat file"));

        Path broken = directory.resolve("broken.json");
        Files.writeString(broken, "{");
        Result invalid = run("info", broken.toString());
        assertEquals(HitsCli.FAIL, invalid.code);
        assertTrue(invalid.err.contains("Invalid beat file"));
        assertTrue(invalid.err.contains(broken.toString()));

        Result noOut = run("render", broken.toString());
        assertEquals(HitsCli.USAGE, noOut.code);
        assertTrue(noOut.err.contains("--out"));

        Path mp3 = directory.resolve("out.mp3");
        Result extension = run("render", broken.toString(), "--out", mp3.toString());
        assertEquals(HitsCli.USAGE, extension.code);
        assertTrue(extension.err.contains("Unknown output extension"));
        assertTrue(extension.err.contains(".mp3"));
        assertFalse(Files.exists(mp3));

        Result bare = run("render", broken.toString(), "--out", directory.resolve("out").toString());
        assertEquals(HitsCli.USAGE, bare.code);
        assertTrue(bare.err.contains("Unknown output extension"));

        String missingPattern = BeatJson.write(Beat.drumKit("plain"));
        int slotB = missingPattern.indexOf("\n    \"b\":");
        assertTrue(slotB > 0);
        String withoutB = missingPattern.substring(0, slotB).replaceAll(",\\s*$", "") + "\n  }\n}\n";
        Path missingB = directory.resolve("missing-b.json");
        Files.writeString(missingB, withoutB);
        Result missingSlot = run("info", missingB.toString());
        assertEquals(HitsCli.FAIL, missingSlot.code);
        assertTrue(missingSlot.err.contains("\"b\""), missingSlot.err);
        assertFalse(missingSlot.err.contains("Expected an object"), missingSlot.err);
    }

    @Test
    void anUnwritableWavNamesTheOutputNotTheTempFile(@TempDir Path directory) throws Exception {
        Path folder = directory.resolve("ro");
        Files.createDirectories(folder);
        Path wav = folder.resolve("song.wav");
        Assumptions.assumeTrue(folder.toFile().setWritable(false, false), "could not make the folder read-only");
        try {
            Result rendered = run("render", FIXTURE.toString(), "--out", wav.toString());
            assertEquals(HitsCli.FAIL, rendered.code, rendered.err);
            assertTrue(rendered.err.contains(wav.toString()), rendered.err);
            assertFalse(rendered.err.contains(".part"), rendered.err);
            assertFalse(rendered.err.contains("hits-wav"), rendered.err);
        } finally {
            folder.toFile().setWritable(true, false);
        }
    }

    @Test
    void wavPermissionsFollowTheUmaskLikeMidi(@TempDir Path directory) throws Exception {
        Assumptions.assumeTrue(
            Files.getFileStore(directory).supportsFileAttributeView(PosixFileAttributeView.class),
            "POSIX permissions"
        );
        Beat beat = Beat.drumKit("perms");
        beat.track(0).tap(0, false);
        Path wav = directory.resolve("out.wav");
        Path mid = directory.resolve("out.mid");
        try {
            BeatFiles.exportWav(beat, wav, ExportOptions.matchingPlayback(), null, null);
        } catch (Exception exception) {
            if (synthUnavailable(exception.getMessage())) {
                Assumptions.abort("offline render is not available: " + exception.getMessage());
            }
            throw exception;
        }
        BeatFiles.exportMidi(beat, mid, ExportOptions.matchingPlayback());
        assertEquals(Files.getPosixFilePermissions(mid), Files.getPosixFilePermissions(wav));
    }

    @Test
    void infoShowsTuneDecayAndEffects() throws Exception {
        Beat beat = Beat.drumKit("shaped");
        beat.track(0).setTune(700);
        beat.track(0).setDecay(25);
        beat.track(1).setCutoff(12);
        beat.track(1).setResonance(80);
        beat.track(2).setReverb(90);
        beat.track(2).setChorus(40);
        String described = HitsCli.describe(beat);
        assertTrue(described.contains("tune +700 cents, decay 25%"), described);
        assertTrue(described.contains("cutoff 12, resonance 80"), described);
        assertTrue(described.contains("reverb 90, chorus 40"), described);
        assertFalse(HitsCli.describe(Beat.drumKit("plain")).contains("tune "));
    }

    @Test
    void fixtureRoundTripsAndInfoDescribesIt() throws Exception {
        String text = Files.readString(FIXTURE);
        assertEquals(BeatJson.write(referenceBeat()), text);
        assertEquals(text, BeatJson.write(BeatJson.read(text)));

        Result info = run("info", FIXTURE.toString());
        assertEquals(HitsCli.OK, info.code, info.err);
        assertEquals("", info.err);
        assertTrue(info.out.contains("Sparse groove"));
        assertTrue(info.out.contains("tempo: 120 bpm"));
        assertTrue(info.out.contains("swing: 58%"));
        assertTrue(info.out.contains("patterns: A, D"));
        assertTrue(info.out.contains("lengths: A 16, D 16"));
        assertTrue(info.out.contains("chain: A x1, D x1"));
        assertTrue(info.out.contains("Kick"));
        assertTrue(info.out.contains("Bass"));
        assertTrue(info.out.contains("Clap, muted"));
        assertTrue(info.out.contains("length: 1 bar, 2.000 s"));
        assertTrue(info.out.contains("song: 2 bars, 4.000 s"));
        assertEquals("true", System.getProperty("java.awt.headless"));
    }

    @Test
    void renderedMidiMatchesTheGoldenEvents(@TempDir Path directory) throws Exception {
        Path pattern = directory.resolve("pattern.mid");
        Path direct = directory.resolve("direct.mid");
        Result rendered = run("render", FIXTURE.toString(), "--out", pattern.toString());
        assertEquals(HitsCli.OK, rendered.code, rendered.err);
        assertTrue(rendered.out.contains(pattern.toAbsolutePath().toString()));
        assertTrue(Files.size(pattern) > 0);

        Beat beat = BeatFiles.load(FIXTURE);
        BeatFiles.exportMidi(beat, direct, ExportOptions.matchingPlayback());
        String events = dump(MidiSystem.getSequence(pattern.toFile()));
        assertEquals(dump(MidiSystem.getSequence(direct.toFile())), events);
        assertTrue(events.contains("\t76\tnote-on ch=9 d1=42"), "swing should delay the hat on step 3");
        assertFalse(events.contains("d1=39"), "a muted clap is left out, same as playback");
        assertGolden(PATTERN_GOLDEN, events);

        Path song = directory.resolve("song.midi");
        Result songRender = run("render", FIXTURE.toString(), "--song", "--out", song.toString());
        assertEquals(HitsCli.OK, songRender.code, songRender.err);
        Path songDirect = directory.resolve("song-direct.mid");
        BeatFiles.exportMidi(beat, songDirect, new ExportOptions(true, false, 1, true));
        String songEvents = dump(MidiSystem.getSequence(song.toFile()));
        assertEquals(dump(MidiSystem.getSequence(songDirect.toFile())), songEvents);
        assertGolden(SONG_GOLDEN, songEvents);
        assertTrue(MidiSystem.getSequence(song.toFile()).getTickLength()
            > MidiSystem.getSequence(pattern.toFile()).getTickLength());
    }

    @Test
    void renderedWavHasDurationRateChannelsAndLevel(@TempDir Path directory) throws Exception {
        Path wav = directory.resolve("pattern.wav");
        Result rendered = run("render", FIXTURE.toString(), "--out", wav.toString());
        if (rendered.code != HitsCli.OK && synthUnavailable(rendered.err)) {
            Assumptions.abort("offline render is not available: " + rendered.err);
        }
        assertEquals(HitsCli.OK, rendered.code, rendered.err);
        assertTrue(Files.size(wav) > WavAudio.HEADER_BYTES);

        Beat beat = BeatFiles.load(FIXTURE);
        Sequence sequence = SequenceBuilder.export(beat, ExportOptions.matchingPlayback());
        long expectedFrames = WavAudio.frameCount(WavAudio.renderMicros(sequence));
        AudioFileFormat file = AudioSystem.getAudioFileFormat(wav.toFile());
        assertEquals(AudioFileFormat.Type.WAVE, file.getType());
        AudioFormat format = file.getFormat();
        assertEquals(WavAudio.SAMPLE_RATE, Math.round(format.getSampleRate()));
        assertEquals(WavAudio.CHANNELS, format.getChannels());
        assertEquals(WavAudio.BITS, format.getSampleSizeInBits());
        assertFalse(format.isBigEndian());
        assertEquals(AudioFormat.Encoding.PCM_SIGNED, format.getEncoding());

        long frames = file.getFrameLength();
        double actualSeconds = frames / (double) WavAudio.SAMPLE_RATE;
        double expectedSeconds = expectedFrames / (double) WavAudio.SAMPLE_RATE;
        assertEquals(expectedSeconds, actualSeconds, 0.05, "duration");
        assertEquals(4.0, actualSeconds, 0.05, "one bar at 120 bpm plus the release tail");

        byte[] data = Files.readAllBytes(wav);
        double rms = rms(data);
        int peak = peak(data);
        assertTrue(peak > 500, "rendered audio was silent, peak " + peak);
        assertEquals(0.049, rms, 0.020, "rms " + rms + " peak " + peak);
    }

    @Test
    void renderAndInfoFollowAMixedLengthChain(@TempDir Path directory) throws Exception {
        Beat beat = Beat.drumKit("mixed");
        beat.setBpm(120);
        beat.setSwing(50);
        beat.setSlotSteps('b', 32);
        beat.slot('a')[0].tap(0, false);
        beat.slot('b')[1].tap(0, false);
        beat.slot('b')[1].tap(16, false);
        beat.addChain('a');
        beat.addChain('b');
        Path json = directory.resolve("mixed.json");
        Files.writeString(json, BeatJson.write(beat));

        Result info = run("info", json.toString());
        assertEquals(HitsCli.OK, info.code, info.err);
        assertTrue(info.out.contains("lengths: A 16, B 32"));
        assertTrue(info.out.contains("length: 1 bar, 2.000 s"));
        assertTrue(info.out.contains("song: 3 bars, 6.000 s"));

        Path mid = directory.resolve("mixed.mid");
        Result rendered = run("render", json.toString(), "--song", "--out", mid.toString());
        assertEquals(HitsCli.OK, rendered.code, rendered.err);
        String events = dump(MidiSystem.getSequence(mid.toFile()));
        Path direct = directory.resolve("direct.mid");
        BeatFiles.exportMidi(beat, direct, new ExportOptions(true, false, 1, true));
        assertEquals(dump(MidiSystem.getSequence(direct.toFile())), events);
        assertTrue(events.contains("\t0\tnote-on ch=9 d1=36"));
        assertTrue(events.contains("\t384\tnote-on ch=9 d1=38"));
        assertTrue(events.contains("\t768\tnote-on ch=9 d1=38"));
        assertTrue(events.contains("\t1152\tmeta 06"));
        assertFalse(events.contains("\t1152\tnote-on"));

        Path wav = directory.resolve("mixed.wav");
        Result wavRender = run("render", json.toString(), "--song", "--out", wav.toString());
        if (wavRender.code != HitsCli.OK && synthUnavailable(wavRender.err)) {
            Assumptions.abort("offline render is not available: " + wavRender.err);
        }
        assertEquals(HitsCli.OK, wavRender.code, wavRender.err);
        Sequence song = SequenceBuilder.export(beat, new ExportOptions(true, false, 1, true));
        long expectedFrames = WavAudio.frameCount(WavAudio.renderMicros(song));
        AudioFileFormat file = AudioSystem.getAudioFileFormat(wav.toFile());
        double actualSeconds = file.getFrameLength() / (double) WavAudio.SAMPLE_RATE;
        double expectedSeconds = expectedFrames / (double) WavAudio.SAMPLE_RATE;
        assertEquals(expectedSeconds, actualSeconds, 0.05);
        assertEquals(8.0, actualSeconds, 0.05, "three bars at 120 bpm plus the release tail");
    }

    /** Original pattern: swung hats, a muted clap that export leaves out, and a two-slot chain. */
    static Beat referenceBeat() {
        Beat beat = Beat.drumKit("Sparse groove");
        beat.setBpm(120);
        beat.setSwing(58);
        beat.setStepCount(16);
        beat.setActiveSlot('a');

        beat.slot('a')[0].tap(0, false);
        beat.slot('a')[0].tap(8, true);
        beat.slot('a')[1].tap(4, false);
        beat.slot('a')[1].tap(12, false);
        beat.slot('a')[2].tap(3, false);
        beat.slot('a')[2].tap(7, false);
        beat.slot('a')[2].tap(11, false);
        beat.slot('a')[2].tap(15, false);
        beat.slot('a')[4].tap(0, false);
        beat.slot('a')[4].setMute(true);

        Track bass = beat.slot('a')[6];
        bass.setName("Bass");
        bass.setMode(TrackMode.NOTE);
        bass.setProgram(38);
        bass.setNote(40);
        bass.tap(0, false);
        bass.tap(11, false);

        beat.slot('d')[0].tap(0, false);
        beat.slot('d')[1].tap(8, false);
        beat.slot('d')[3].tap(14, true);
        Track turnaround = beat.slot('d')[6];
        turnaround.setName("Bass");
        turnaround.setMode(TrackMode.NOTE);
        turnaround.setProgram(38);
        turnaround.setNote(43);
        turnaround.tap(0, false);
        turnaround.tap(12, false);

        beat.addChain('a');
        beat.addChain('d');
        return beat;
    }

    private static void assertGolden(Path golden, String actual) throws Exception {
        if (Boolean.getBoolean("hits.updateGoldens")) {
            Files.createDirectories(golden.getParent());
            Files.writeString(golden, actual);
        }
        assertTrue(Files.isRegularFile(golden),
            "missing " + golden + " — regenerate with -Dhits.updateGoldens=true");
        String expected = Files.readString(golden).replace("\r\n", "\n");
        assertEquals(expected, actual.replace("\r\n", "\n"));
    }

    private static String dump(Sequence sequence) {
        String division = sequence.getDivisionType() == Sequence.PPQ
            ? "ppq"
            : Float.toString(sequence.getDivisionType());
        StringBuilder out = new StringBuilder();
        out.append("division=").append(division)
            .append(" resolution=").append(sequence.getResolution())
            .append(" tracks=").append(sequence.getTracks().length)
            .append('\n');
        javax.sound.midi.Track[] tracks = sequence.getTracks();
        for (int trackIndex = 0; trackIndex < tracks.length; trackIndex++) {
            javax.sound.midi.Track track = tracks[trackIndex];
            for (int index = 0; index < track.size(); index++) {
                MidiEvent event = track.get(index);
                out.append(trackIndex).append('\t').append(event.getTick()).append('\t');
                MidiMessage message = event.getMessage();
                if (message instanceof ShortMessage shortMessage) {
                    out.append(shortText(shortMessage));
                } else if (message instanceof MetaMessage meta) {
                    out.append(metaText(meta));
                } else {
                    out.append("sysex ").append(hex(message.getMessage()));
                }
                out.append('\n');
            }
        }
        return out.toString();
    }

    private static String shortText(ShortMessage message) {
        String kind = switch (message.getCommand()) {
            case ShortMessage.NOTE_ON -> "note-on";
            case ShortMessage.NOTE_OFF -> "note-off";
            case ShortMessage.CONTROL_CHANGE -> "cc";
            case ShortMessage.PROGRAM_CHANGE -> "program";
            case ShortMessage.PITCH_BEND -> "bend";
            default -> "short";
        };
        return kind
            + " ch=" + message.getChannel()
            + " d1=" + message.getData1()
            + " d2=" + message.getData2();
    }

    private static String metaText(MetaMessage meta) {
        byte[] data = meta.getData();
        StringBuilder text = new StringBuilder();
        text.append("meta ").append(String.format(Locale.ROOT, "%02x", meta.getType()));
        text.append(' ').append(data.length == 0 ? "-" : hex(data));
        if (meta.getType() == 0x03 || meta.getType() == 0x06) {
            text.append(' ').append(new String(data, StandardCharsets.UTF_8));
        } else if (meta.getType() == 0x51 && data.length == 3) {
            int microseconds = ((data[0] & 0xff) << 16) | ((data[1] & 0xff) << 8) | (data[2] & 0xff);
            text.append(" tempo=").append(microseconds);
        } else if (meta.getType() == 0x2F) {
            text.append(" eot");
        }
        return text.toString();
    }

    private static String hex(byte[] data) {
        StringBuilder text = new StringBuilder();
        for (byte value : data) {
            text.append(String.format(Locale.ROOT, "%02x", value));
        }
        return text.toString();
    }

    private static double rms(byte[] wav) {
        long sum = 0;
        int samples = 0;
        for (int i = WavAudio.HEADER_BYTES; i + 1 < wav.length; i += 2) {
            int sample = (wav[i] & 0xff) | (wav[i + 1] << 8);
            if (sample > 32767) {
                sample -= 65536;
            }
            sum += (long) sample * sample;
            samples++;
        }
        if (samples == 0) {
            return 0;
        }
        return Math.sqrt(sum / (double) samples) / 32768.0;
    }

    private static int peak(byte[] wav) {
        int peak = 0;
        for (int i = WavAudio.HEADER_BYTES; i + 1 < wav.length; i += 2) {
            int sample = (wav[i] & 0xff) | (wav[i + 1] << 8);
            if (sample > 32767) {
                sample -= 65536;
            }
            peak = Math.max(peak, Math.abs(sample));
        }
        return peak;
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

    private static Result run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream stdout = new PrintStream(out, true, StandardCharsets.UTF_8);
        PrintStream stderr = new PrintStream(err, true, StandardCharsets.UTF_8);
        int code = HitsCli.run(args, stdout, stderr);
        return new Result(
            code,
            out.toString(StandardCharsets.UTF_8),
            err.toString(StandardCharsets.UTF_8)
        );
    }

    private record Result(int code, String out, String err) {}
}
