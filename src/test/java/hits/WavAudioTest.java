package hits;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Soundbank;
import javax.sound.midi.Track;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WavAudioTest {
    @Test
    void headerIs44100StereoPcm() {
        byte[] header = WavAudio.header(176_400);
        assertEquals(WavAudio.HEADER_BYTES, header.length);
        assertEquals("RIFF", text(header, 0, 4));
        assertEquals(36 + 176_400 * WavAudio.BYTES_PER_FRAME, leInt(header, 4));
        assertEquals("WAVE", text(header, 8, 4));
        assertEquals("fmt ", text(header, 12, 4));
        assertEquals(16, leInt(header, 16));
        assertEquals(1, leShort(header, 20));
        assertEquals(WavAudio.CHANNELS, leShort(header, 22));
        assertEquals(WavAudio.SAMPLE_RATE, leInt(header, 24));
        assertEquals(WavAudio.SAMPLE_RATE * WavAudio.BYTES_PER_FRAME, leInt(header, 28));
        assertEquals(WavAudio.BYTES_PER_FRAME, leShort(header, 32));
        assertEquals(WavAudio.BITS, leShort(header, 34));
        assertEquals("data", text(header, 36, 4));
        assertEquals(176_400 * WavAudio.BYTES_PER_FRAME, leInt(header, 40));
        assertEquals(176_400, WavAudio.frameCount(4_000_000));
    }

    @Test
    void lengthFollowsTempoAndExportOptions() throws Exception {
        assertEquals(0, WavAudio.microsecondsAt(tempoMap(), 0));
        assertEquals(500_000, WavAudio.microsecondsAt(tempoMap(), 96));
        assertEquals(1_500_000, WavAudio.microsecondsAt(tempoMap(), 192));

        Beat beat = Beat.drumKit("Take 1");
        beat.setBpm(96);
        beat.track(0).tap(0, false);
        beat.track(0).setMute(true);
        beat.track(1).tap(4, false);
        beat.setActiveSlot('b');
        beat.track(1).tap(0, false);
        beat.setActiveSlot('a');

        Sequence once = SequenceBuilder.export(beat, ExportOptions.allTracks());
        assertEquals(2_500_000L, WavAudio.musicalMicros(once));
        assertEquals(2_500_000L + WavAudio.TAIL_MICROS, WavAudio.renderMicros(once));
        assertEquals(WavAudio.frameCount(4_500_000), WavAudio.frameCount(WavAudio.renderMicros(once)));

        assertEquals(WavAudio.musicalMicros(once) * 2,
            WavAudio.musicalMicros(SequenceBuilder.export(beat, new ExportOptions(false, false, 2))));
        assertEquals(WavAudio.musicalMicros(once) * 2,
            WavAudio.musicalMicros(SequenceBuilder.export(beat, new ExportOptions(false, true, 1))));
        assertEquals(WavAudio.musicalMicros(once) * 6,
            WavAudio.musicalMicros(SequenceBuilder.export(beat, new ExportOptions(true, true, 3))));

        List<WavAudio.ChannelEvent> all = noteOns(WavAudio.channelEvents(once), 36);
        List<WavAudio.ChannelEvent> heard = noteOns(
            WavAudio.channelEvents(SequenceBuilder.export(beat, ExportOptions.matchingPlayback())),
            36
        );
        assertEquals(1, all.size());
        assertEquals(Gm.DRUM_CHANNEL, all.get(0).channel());
        assertEquals(0, all.get(0).micros());
        assertTrue(heard.isEmpty());
        List<WavAudio.ChannelEvent> snare = noteOns(
            WavAudio.channelEvents(SequenceBuilder.export(beat, ExportOptions.matchingPlayback())),
            38
        );
        assertEquals(1, snare.size());
        assertEquals(625_000L, snare.get(0).micros());

        List<WavAudio.ChannelEvent> repeated = noteOns(
            WavAudio.channelEvents(SequenceBuilder.export(beat, new ExportOptions(false, false, 2))),
            36
        );
        assertEquals(List.of(0L, 2_500_000L), repeated.stream().map(WavAudio.ChannelEvent::micros).toList());
    }

    @Test
    void programChangeIsSentBeforeTheNoteAtTheSameTick() throws Exception {
        Beat beat = Beat.drumKit("m");
        beat.track(6).setMode(TrackMode.NOTE);
        beat.track(6).setProgram(33);
        beat.track(6).setNote(60);
        beat.track(6).tap(0, false);
        List<WavAudio.ChannelEvent> events = WavAudio.channelEvents(SequenceBuilder.export(beat, ExportOptions.allTracks()));
        int program = indexOf(events, ShortMessage.PROGRAM_CHANGE, 6);
        int note = indexOf(events, ShortMessage.NOTE_ON, 6);
        assertTrue(program >= 0);
        assertTrue(program < note);
        assertEquals(33, events.get(program).data1());
        assertEquals(60, events.get(note).data1());
    }

    @Test
    void cancelLeavesTheDestinationUntouched(@TempDir Path directory) throws Exception {
        Path wav = directory.resolve("keep.wav");
        Files.write(wav, new byte[] {1, 2, 3});
        Sequence sequence = SequenceBuilder.export(Beat.drumKit("t"), ExportOptions.allTracks());
        assertThrows(WavRenderer.Cancelled.class, () -> WavRenderer.render(sequence, wav, null, null, () -> true));
        assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(wav));
        try (var stream = Files.list(directory)) {
            assertTrue(stream.noneMatch(path -> path.getFileName().toString().endsWith(".part")));
        }
    }

    @Test
    void rendersAStereoWavWhenTheSynthCan(@TempDir Path directory) throws Exception {
        Beat beat = Beat.drumKit("Take 1");
        beat.setBpm(120);
        beat.track(0).tap(0, false);
        Path wav = directory.resolve("Take1.wav");
        List<Integer> progress = new ArrayList<>();
        try {
            BeatFiles.exportWav(beat, wav, ExportOptions.allTracks(), null, progress::add);
        } catch (Exception exception) {
            if (WavRenderer.unavailable(exception)) {
                Assumptions.abort("offline render is not available: " + exception.getMessage());
            }
            throw exception;
        }
        long frames = WavAudio.frameCount(WavAudio.renderMicros(SequenceBuilder.export(beat, ExportOptions.allTracks())));
        byte[] data = Files.readAllBytes(wav);
        assertEquals(WavAudio.HEADER_BYTES + frames * WavAudio.BYTES_PER_FRAME, data.length);
        assertArrayEquals(WavAudio.header(frames), java.util.Arrays.copyOf(data, WavAudio.HEADER_BYTES));
        AudioFileFormat file = AudioSystem.getAudioFileFormat(wav.toFile());
        assertEquals(AudioFileFormat.Type.WAVE, file.getType());
        AudioFormat format = file.getFormat();
        assertEquals(WavAudio.SAMPLE_RATE, Math.round(format.getSampleRate()));
        assertEquals(WavAudio.BITS, format.getSampleSizeInBits());
        assertEquals(WavAudio.CHANNELS, format.getChannels());
        assertFalse(format.isBigEndian());
        assertEquals(AudioFormat.Encoding.PCM_SIGNED, format.getEncoding());
        assertEquals(frames, file.getFrameLength());
        assertTrue(peak(data) > 500, "rendered audio was silent");
        assertFalse(progress.isEmpty());
        assertEquals(100, progress.get(progress.size() - 1));
    }

    @Test
    void rendersWithTheLoadedSoundFontWhenTheSynthCan(@TempDir Path directory) throws Exception {
        Soundbank bank = SoundFonts.read(Path.of("src/test/resources/hits-test.sf2"));
        Beat beat = Beat.drumKit("piano");
        beat.setBpm(120);
        beat.track(0).setMode(TrackMode.NOTE);
        beat.track(0).setProgram(0);
        beat.track(0).setNote(60);
        beat.track(0).tap(0, false);
        Path wav = directory.resolve("piano.wav");
        try {
            BeatFiles.exportWav(beat, wav, ExportOptions.matchingPlayback(), bank, null);
        } catch (Exception exception) {
            if (WavRenderer.unavailable(exception)) {
                Assumptions.abort("offline render is not available: " + exception.getMessage());
            }
            throw exception;
        }
        long frames = WavAudio.frameCount(
            WavAudio.renderMicros(SequenceBuilder.export(beat, ExportOptions.matchingPlayback()))
        );
        assertEquals(WavAudio.HEADER_BYTES + frames * WavAudio.BYTES_PER_FRAME, Files.size(wav));
        assertEquals(AudioFileFormat.Type.WAVE, AudioSystem.getAudioFileFormat(wav.toFile()).getType());
    }

    private static Sequence tempoMap() throws Exception {
        Sequence sequence = new Sequence(Sequence.PPQ, SequenceBuilder.PPQ);
        Track track = sequence.createTrack();
        track.add(new MidiEvent(tempo(500_000), 0));
        track.add(new MidiEvent(tempo(1_000_000), 96));
        return sequence;
    }

    private static MetaMessage tempo(int microseconds) throws Exception {
        byte[] data = {
            (byte) (microseconds >> 16),
            (byte) (microseconds >> 8),
            (byte) microseconds
        };
        MetaMessage message = new MetaMessage();
        message.setMessage(0x51, data, data.length);
        return message;
    }

    private static List<WavAudio.ChannelEvent> noteOns(List<WavAudio.ChannelEvent> events, int note) {
        List<WavAudio.ChannelEvent> matches = new ArrayList<>();
        for (WavAudio.ChannelEvent event : events) {
            if (event.command() == ShortMessage.NOTE_ON && event.data2() > 0 && event.data1() == note) {
                matches.add(event);
            }
        }
        return matches;
    }

    private static int indexOf(List<WavAudio.ChannelEvent> events, int command, int channel) {
        for (int i = 0; i < events.size(); i++) {
            WavAudio.ChannelEvent event = events.get(i);
            if (event.command() == command && event.channel() == channel) {
                return i;
            }
        }
        return -1;
    }

    private static int peak(byte[] wav) {
        int peak = 0;
        for (int i = WavAudio.HEADER_BYTES; i + 1 < wav.length; i += 2) {
            int sample = (wav[i] & 0xff) | (wav[i + 1] << 8);
            peak = Math.max(peak, Math.abs(sample));
        }
        return peak;
    }

    private static String text(byte[] data, int offset, int length) {
        return new String(data, offset, length, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static int leInt(byte[] data, int offset) {
        return (data[offset] & 0xff)
            | ((data[offset + 1] & 0xff) << 8)
            | ((data[offset + 2] & 0xff) << 16)
            | ((data[offset + 3] & 0xff) << 24);
    }

    private static int leShort(byte[] data, int offset) {
        return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8);
    }
}
