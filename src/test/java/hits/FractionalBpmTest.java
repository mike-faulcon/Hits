package hits;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fractional tempo, and the promise that a whole-number tempo still renders
 * the same MIDI clock an older file used.
 */
class FractionalBpmTest {
    private static final double BPM = 92.5;
    private static final int BARS = 64;

    @TempDir
    Path temp;

    @Test
    void wholeNumbersStayIntegersAndFractionsRoundTrip() throws Exception {
        String golden = Files.readString(Path.of("src/test/resources/fixtures/sparse-808-groove.json"));
        Beat plain = BeatJson.read(golden);
        assertEquals(golden, BeatJson.write(plain));
        assertEquals("120", Tempo.format(plain.bpm()));
        assertEquals(120.0, plain.bpm(), 0.0);

        String slots = Files.readString(Path.of("src/test/resources/slots/ab-plain.json"));
        assertEquals(slots, BeatJson.write(BeatJson.read(slots)));

        for (int bpm : new int[] {40, 86, 90, 92, 96, 100, 120, 140, 240}) {
            Beat beat = Beat.drumKit("whole");
            beat.setBpm(bpm);
            beat.setSwing(50);
            String json = BeatJson.write(beat);
            assertTrue(json.contains("\"bpm\": " + bpm + ","), json);
            assertFalse(json.contains("\"bpm\": " + bpm + "."), json);
            Beat loaded = BeatJson.read(json);
            assertEquals(json, BeatJson.write(loaded));
            assertEquals(0, loaded.loadWarnings().size(), loaded.loadWarnings().toString());
            Sequence sequence = SequenceBuilder.export(beat, ExportOptions.allTracks());
            assertEquals(60_000_000 / bpm, tempoUs(sequence), "whole BPM keeps the truncating tempo");
            long ticks = sequence.getTickLength();
            assertEquals(ticks * (60_000_000L / bpm) / SequenceBuilder.PPQ, WavAudio.musicalMicros(sequence));
            assertEquals(WavAudio.musicalMicros(sequence), WavAudio.musicalMicros(sequence, bpm));
        }

        Beat beat = Beat.drumKit("fine");
        beat.setBpm(92.5);
        beat.setSwing(50);
        String half = BeatJson.write(beat);
        assertTrue(half.contains("\"bpm\": 92.5,"), half);
        assertEquals(half, BeatJson.write(BeatJson.read(half)));
        assertEquals(92.5, BeatJson.read(half).bpm(), 0.0);

        beat.setBpm(98.33);
        String fine = BeatJson.write(beat);
        assertTrue(fine.contains("\"bpm\": 98.33,"), fine);
        assertEquals(98.33, BeatJson.read(fine).bpm(), 0.0);
        assertEquals("98.33", Tempo.format(BeatJson.read(fine).bpm()));

        beat.setBpm(92.555);
        assertEquals("92.56", Tempo.format(beat.bpm()));
        Beat clamped = BeatJson.read(fine.replace("\"bpm\": 98.33", "\"bpm\": 300.4"));
        assertEquals(240.0, clamped.bpm(), 0.0);
        assertTrue(clamped.loadWarnings().get(0).contains("240"), clamped.loadWarnings().toString());
        Beat slow = BeatJson.read(fine.replace("\"bpm\": 98.33", "\"bpm\": 12"));
        assertEquals(40.0, slow.bpm(), 0.0);
        assertTrue(slow.loadWarnings().get(0).contains("40"), slow.loadWarnings().toString());
    }

    @Test
    void anOldIntegerFileRendersTheSameAfterASave() throws Exception {
        Beat beat = Beat.drumKit("ninety-two");
        beat.setBpm(92);
        beat.setSwing(50);
        beat.track(0).tap(0, false);
        byte[] midi = midiBytes(beat);
        byte[] again = midiBytes(BeatJson.read(BeatJson.write(beat)));
        assertArrayEquals(midi, again);
        assertEquals(60_000_000 / 92, tempoUs(SequenceBuilder.export(beat, ExportOptions.allTracks())));

        Path first = temp.resolve("first.wav");
        Path second = temp.resolve("second.wav");
        try {
            BeatFiles.exportWav(beat, first, ExportOptions.allTracks(), null, null);
            BeatFiles.exportWav(BeatJson.read(BeatJson.write(beat)), second, ExportOptions.allTracks(), null, null);
        } catch (Exception exception) {
            if (WavRenderer.unavailable(exception)) {
                Assumptions.abort("offline render is not available: " + exception.getMessage());
            }
            throw exception;
        }
        assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second));
    }

    @Test
    void aLongFractionalSongDoesNotDrift() throws Exception {
        Beat beat = longSong();
        ExportOptions song = new ExportOptions(true, false, 1, true);
        Sequence sequence = SequenceBuilder.export(beat, song);
        long idealTicks = BARS * 4L * SequenceBuilder.PPQ;
        assertTrue(Math.abs(sequence.getTickLength() - idealTicks) <= 1, "ticks " + sequence.getTickLength());

        int usPerQuarter = tempoUs(sequence);
        assertEquals((int) Math.round(60_000_000.0 * Tempo.SCALE / Tempo.centi(BPM)), usPerQuarter);
        long midiUs = sequence.getTickLength() * (long) usPerQuarter / SequenceBuilder.PPQ;
        double idealUs = idealTicks * 60_000_000.0 / BPM / SequenceBuilder.PPQ;
        double oneTick = usPerQuarter / (double) SequenceBuilder.PPQ;
        assertTrue(Math.abs(midiUs - idealUs) <= oneTick, "midi us " + midiUs + " ideal " + idealUs);

        long musical = WavAudio.musicalMicros(sequence, BPM);
        long idealMicros = Math.round(idealTicks * 60_000_000.0 / BPM / SequenceBuilder.PPQ);
        assertEquals(idealMicros, musical);
        long idealSamples = Math.round(idealTicks * 60.0 / BPM * WavAudio.SAMPLE_RATE / SequenceBuilder.PPQ);
        assertTrue(Math.abs(WavAudio.frameCount(musical) - idealSamples) <= 1);

        int steps = BARS * 16;
        long stepMicros = Math.round(
            SequenceBuilder.STEP_TICKS * (60_000_000.0 * Tempo.SCALE) / (Tempo.centi(BPM) * (double) SequenceBuilder.PPQ)
        );
        long summed = stepMicros * steps;
        long summedSamples = Math.round(summed * (WavAudio.SAMPLE_RATE / 1_000_000.0));
        assertTrue(Math.abs(summedSamples - idealSamples) > 1, "summing rounded steps should drift");

        for (int bar = 0; bar <= BARS; bar++) {
            long tick = bar * 4L * SequenceBuilder.PPQ;
            long at = WavAudio.microsecondsAt(sequence, tick, BPM);
            assertEquals(Tempo.microsecondsAt(tick, BPM, SequenceBuilder.PPQ), at, "bar " + bar);
            long samples = Math.round(at * (WavAudio.SAMPLE_RATE / 1_000_000.0));
            long ideal = Math.round(tick * 60.0 / BPM * WavAudio.SAMPLE_RATE / SequenceBuilder.PPQ);
            assertTrue(Math.abs(samples - ideal) <= 1, "bar " + bar + " samples " + samples + " ideal " + ideal);
        }

        List<Long> hits = noteMicros(WavAudio.channelEvents(sequence, BPM), 36);
        assertEquals(BARS, hits.size(), "one kick each bar");
        for (int bar = 0; bar < BARS; bar++) {
            long tick = bar * 4L * SequenceBuilder.PPQ + 4L * SequenceBuilder.STEP_TICKS;
            assertEquals(Tempo.microsecondsAt(tick, BPM, SequenceBuilder.PPQ), hits.get(bar));
        }

        Path wav = temp.resolve("long.wav");
        try {
            BeatFiles.exportWav(beat, wav, song, null, null);
        } catch (Exception exception) {
            if (WavRenderer.unavailable(exception)) {
                Assumptions.abort("offline render is not available: " + exception.getMessage());
            }
            throw exception;
        }
        long fileFrames = (Files.size(wav) - WavAudio.HEADER_BYTES) / WavAudio.BYTES_PER_FRAME;
        long tail = WavAudio.frameCount(WavAudio.TAIL_MICROS);
        assertTrue(Math.abs(fileFrames - tail - idealSamples) <= 1, "file " + fileFrames + " ideal " + idealSamples);
        long expected = WavAudio.frameCount(WavAudio.renderMicros(sequence, BPM));
        assertEquals(expected, fileFrames);
    }

    private static Beat longSong() {
        Beat beat = Beat.drumKit("long");
        beat.setBpm(BPM);
        beat.setSwing(50);
        beat.setStepCount(16);
        for (int row = 0; row < beat.trackCount(); row++) {
            beat.track(row).clearSteps();
        }
        beat.track(0).tap(4, false);
        beat.setChain(List.of(new Chain.Part('a', 32), new Chain.Part('a', 32)));
        return beat;
    }

    private static byte[] midiBytes(Beat beat) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MidiSystem.write(SequenceBuilder.export(beat, ExportOptions.allTracks()), 1, out);
        return out.toByteArray();
    }

    private static int tempoUs(Sequence sequence) throws Exception {
        for (javax.sound.midi.Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                MidiEvent event = track.get(i);
                if (event.getMessage() instanceof MetaMessage meta && meta.getType() == 0x51) {
                    byte[] data = meta.getData();
                    return ((data[0] & 0xff) << 16) | ((data[1] & 0xff) << 8) | (data[2] & 0xff);
                }
            }
        }
        throw new AssertionError("no tempo");
    }

    private static List<Long> noteMicros(List<WavAudio.ChannelEvent> events, int note) {
        List<Long> times = new ArrayList<>();
        for (WavAudio.ChannelEvent event : events) {
            if (event.command() == ShortMessage.NOTE_ON && event.data2() > 0 && event.data1() == note) {
                times.add(event.micros());
            }
        }
        return times;
    }
}
