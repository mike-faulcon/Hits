package hits;

import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.Receiver;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Timing and WAV framing for an offline render of a MIDI sequence.
 * A 16th note stays {@link SequenceBuilder#STEP_TICKS} ticks; tempo meta events set the clock.
 */
public final class WavAudio {
    public static final int SAMPLE_RATE = 44_100;
    public static final int BITS = 16;
    public static final int CHANNELS = 2;
    public static final int BYTES_PER_FRAME = CHANNELS * (BITS / 8);
    /** Release and reverb after the last tick, so the file does not chop the tail off. */
    public static final long TAIL_MICROS = 2_000_000L;
    public static final int HEADER_BYTES = 44;
    private static final long DEFAULT_US_PER_QUARTER = 500_000L;

    private WavAudio() {}

    public static AudioFormat format() {
        return new AudioFormat(SAMPLE_RATE, BITS, CHANNELS, true, false);
    }

    /** Microseconds from the start of {@code sequence} to {@code tick}, following tempo changes. */
    public static long microsecondsAt(Sequence sequence, long tick) {
        return microsecondsAt(sequence, tick, Double.NaN);
    }

    /**
     * Same clock as {@link #microsecondsAt(Sequence, long)}.
     * A fractional {@code bpm} uses that exact tempo and the absolute tick, so a long
     * song does not pick up a microsecond of error on every step. A whole-number tempo
     * keeps the integer meta-event clock, which is what an older file renders.
     */
    public static long microsecondsAt(Sequence sequence, long tick, double bpm) {
        long at = Math.max(0, tick);
        if (Tempo.fractional(bpm)) {
            if (sequence == null || sequence.getDivisionType() != Sequence.PPQ || sequence.getResolution() <= 0) {
                throw new IllegalArgumentException("WAV export expects a tick-based MIDI sequence.");
            }
            return Tempo.microsecondsAt(at, bpm, sequence.getResolution());
        }
        return clock(sequence).microsAt(at);
    }

    /** Time of the last tick. Export places an end marker there, which is the loop end. */
    public static long musicalMicros(Sequence sequence) {
        return musicalMicros(sequence, Double.NaN);
    }

    /** Loop end at {@code bpm} when that tempo is fractional. Otherwise {@link #musicalMicros(Sequence)}. */
    public static long musicalMicros(Sequence sequence, double bpm) {
        return microsecondsAt(sequence, sequence.getTickLength(), bpm);
    }

    public static long renderMicros(Sequence sequence) {
        return renderMicros(sequence, Double.NaN);
    }

    public static long renderMicros(Sequence sequence, double bpm) {
        return musicalMicros(sequence, bpm) + TAIL_MICROS;
    }

    public static long frameCount(long micros) {
        if (micros <= 0) {
            return 0;
        }
        return Math.round(micros * (SAMPLE_RATE / 1_000_000.0));
    }

    /** Canonical 44-byte PCM header: 44.1 kHz, 16-bit, stereo, little-endian. */
    public static byte[] header(long frames) {
        if (frames < 0) {
            throw new IllegalArgumentException("frame count");
        }
        long dataBytes = frames * (long) BYTES_PER_FRAME;
        if (dataBytes > 0xFFFF_FFFFL) {
            throw new IllegalArgumentException("The recording is too long for a WAV file");
        }
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt((int) (36 + dataBytes));
        buffer.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        buffer.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(16);
        buffer.putShort((short) 1);
        buffer.putShort((short) CHANNELS);
        buffer.putInt(SAMPLE_RATE);
        buffer.putInt(SAMPLE_RATE * BYTES_PER_FRAME);
        buffer.putShort((short) BYTES_PER_FRAME);
        buffer.putShort((short) BITS);
        buffer.put("data".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt((int) dataBytes);
        return buffer.array();
    }

    /**
     * Channel messages in the order they are sent to the synth.
     * Program and controller changes at a tick go out before note events at that tick.
     */
    public static List<ChannelEvent> channelEvents(Sequence sequence) {
        return channelEvents(sequence, Double.NaN);
    }

    /**
     * Channel messages in the order they are sent to the synth.
     * {@code bpm} is the song tempo. A fractional value places each event from its tick
     * and that tempo. A whole number, or a non-finite value, follows the tempo meta events.
     */
    public static List<ChannelEvent> channelEvents(Sequence sequence, double bpm) {
        boolean exact = Tempo.fractional(bpm);
        if (exact && (sequence == null || sequence.getDivisionType() != Sequence.PPQ || sequence.getResolution() <= 0)) {
            throw new IllegalArgumentException("WAV export expects a tick-based MIDI sequence.");
        }
        Clock clock = exact ? null : clock(sequence);
        int resolution = exact ? sequence.getResolution() : 0;
        List<Pending> pending = new ArrayList<>();
        javax.sound.midi.Track[] tracks = sequence.getTracks();
        for (int trackIndex = 0; trackIndex < tracks.length; trackIndex++) {
            javax.sound.midi.Track track = tracks[trackIndex];
            for (int index = 0; index < track.size(); index++) {
                MidiEvent event = track.get(index);
                if (event.getMessage() instanceof ShortMessage message) {
                    int command = message.getCommand();
                    int priority = command == ShortMessage.NOTE_ON || command == ShortMessage.NOTE_OFF ? 1 : 0;
                    pending.add(new Pending(event.getTick(), priority, trackIndex, index, message));
                }
            }
        }
        pending.sort(Comparator
            .comparingLong(Pending::tick)
            .thenComparingInt(Pending::priority)
            .thenComparingInt(Pending::track)
            .thenComparingInt(Pending::index));
        List<ChannelEvent> events = new ArrayList<>(pending.size());
        for (Pending item : pending) {
            long micros = exact
                ? Tempo.microsecondsAt(item.tick, bpm, resolution)
                : clock.microsAt(item.tick);
            events.add(new ChannelEvent(micros, item.message));
        }
        return events;
    }

    static void send(Receiver receiver, Sequence sequence) throws javax.sound.midi.InvalidMidiDataException {
        send(receiver, sequence, Double.NaN);
    }

    static void send(Receiver receiver, Sequence sequence, double bpm) throws javax.sound.midi.InvalidMidiDataException {
        for (ChannelEvent event : channelEvents(sequence, bpm)) {
            MidiMessage message = (MidiMessage) event.message().clone();
            receiver.send(message, event.micros());
        }
    }

    private static Clock clock(Sequence sequence) {
        if (sequence == null) {
            throw new IllegalArgumentException("sequence");
        }
        if (sequence.getDivisionType() != Sequence.PPQ) {
            throw new IllegalArgumentException("WAV export expects a tick-based MIDI sequence.");
        }
        int resolution = sequence.getResolution();
        if (resolution <= 0) {
            throw new IllegalArgumentException("WAV export expects a tick-based MIDI sequence.");
        }
        List<TempoMark> tempos = new ArrayList<>();
        javax.sound.midi.Track[] tracks = sequence.getTracks();
        for (int trackIndex = 0; trackIndex < tracks.length; trackIndex++) {
            javax.sound.midi.Track track = tracks[trackIndex];
            for (int index = 0; index < track.size(); index++) {
                MidiEvent event = track.get(index);
                if (event.getMessage() instanceof MetaMessage meta && meta.getType() == 0x51) {
                    byte[] data = meta.getData();
                    if (data.length >= 3) {
                        long micros = ((data[0] & 0xffL) << 16) | ((data[1] & 0xffL) << 8) | (data[2] & 0xffL);
                        if (micros > 0) {
                            tempos.add(new TempoMark(event.getTick(), trackIndex, index, micros));
                        }
                    }
                }
            }
        }
        tempos.sort(Comparator
            .comparingLong(TempoMark::tick)
            .thenComparingInt(TempoMark::track)
            .thenComparingInt(TempoMark::index));
        return new Clock(resolution, tempos);
    }

    /** One channel message and the microsecond it should sound, measured from the start. */
    public record ChannelEvent(long micros, ShortMessage message) {
        public int command() {
            return message.getCommand();
        }

        public int channel() {
            return message.getChannel();
        }

        public int data1() {
            return message.getData1();
        }

        public int data2() {
            return message.getData2();
        }
    }

    private record TempoMark(long tick, int track, int index, long microsPerQuarter) {}

    private record Pending(long tick, int priority, int track, int index, ShortMessage message) {}

    private record Clock(int resolution, List<TempoMark> tempos) {
        long microsAt(long tick) {
            long usPerQuarter = DEFAULT_US_PER_QUARTER;
            long cursorTick = 0;
            long cursorUs = 0;
            for (TempoMark tempo : tempos) {
                if (tempo.tick > tick) {
                    break;
                }
                if (tempo.tick > cursorTick) {
                    cursorUs += (tempo.tick - cursorTick) * usPerQuarter / resolution;
                    cursorTick = tempo.tick;
                }
                usPerQuarter = tempo.microsPerQuarter;
            }
            if (tick > cursorTick) {
                cursorUs += (tick - cursorTick) * usPerQuarter / resolution;
            }
            return cursorUs;
        }
    }
}
