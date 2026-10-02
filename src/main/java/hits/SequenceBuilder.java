package hits;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import java.nio.charset.StandardCharsets;

/** Turns a beat into a MIDI sequence. A 16th note is {@code PPQ / 4} ticks. */
public final class SequenceBuilder {
    public static final int PPQ = 96;
    public static final int STEP_TICKS = PPQ / 4;

    private SequenceBuilder() {}

    public static int loopTicks(int stepCount) {
        return stepCount * STEP_TICKS;
    }

    public static long tickForStep(int step, int swing) {
        int pairTicks = STEP_TICKS * 2;
        int pair = step / 2;
        long pairStart = (long) pair * pairTicks;
        if (step % 2 == 0) {
            return pairStart;
        }
        int amount = Math.max(50, Math.min(75, swing));
        return pairStart + Math.round(pairTicks * (amount / 100.0));
    }

    public static int stepForTick(long tick, int stepCount, int swing) {
        int loop = loopTicks(stepCount);
        if (loop <= 0) {
            return 0;
        }
        long wrapped = Math.floorMod(tick, loop);
        int found = 0;
        for (int step = 0; step < stepCount; step++) {
            if (tickForStep(step, swing) <= wrapped) {
                found = step;
            } else {
                break;
            }
        }
        return found;
    }

    public static Sequence build(Beat beat) throws InvalidMidiDataException {
        Sequence sequence = new Sequence(Sequence.PPQ, PPQ);
        Track tempo = sequence.createTrack();
        tempo.add(new MidiEvent(tempoMessage(beat.bpm()), 0));
        int loop = loopTicks(beat.stepCount());
        tempo.add(new MidiEvent(marker("end"), loop));

        hits.Track[] tracks = beat.activeTracks();
        for (int i = 0; i < tracks.length; i++) {
            hits.Track track = tracks[i];
            if (!beat.audible(track)) {
                continue;
            }
            Track midi = sequence.createTrack();
            midi.add(new MidiEvent(trackName(track.name()), 0));
            int channel = track.mode() == TrackMode.DRUM ? Gm.DRUM_CHANNEL : i;
            if (track.mode() == TrackMode.NOTE) {
                ShortMessage program = new ShortMessage();
                program.setMessage(ShortMessage.PROGRAM_CHANGE, channel, track.program(), 0);
                midi.add(new MidiEvent(program, 0));
            }
            for (int step = 0; step < beat.stepCount(); step++) {
                Step cell = track.step(step);
                if (!cell.on()) {
                    continue;
                }
                long start = tickForStep(step, beat.swing());
                long boundary = step + 1 < beat.stepCount()
                    ? tickForStep(step + 1, beat.swing())
                    : loop;
                long length = Math.max(1, Math.round((boundary - start) * (track.gate() / 100.0)));
                if (start + length > loop) {
                    length = Math.max(1, loop - start);
                }
                ShortMessage on = new ShortMessage();
                on.setMessage(ShortMessage.NOTE_ON, channel, track.note(), cell.velocity());
                midi.add(new MidiEvent(on, start));
                ShortMessage off = new ShortMessage();
                off.setMessage(ShortMessage.NOTE_OFF, channel, track.note(), 0);
                midi.add(new MidiEvent(off, start + length));
            }
        }
        return sequence;
    }

    private static MetaMessage tempoMessage(int bpm) throws InvalidMidiDataException {
        int microseconds = 60_000_000 / bpm;
        byte[] data = {
            (byte) (microseconds >> 16),
            (byte) (microseconds >> 8),
            (byte) microseconds
        };
        MetaMessage message = new MetaMessage();
        message.setMessage(0x51, data, data.length);
        return message;
    }

    private static MetaMessage trackName(String name) throws InvalidMidiDataException {
        byte[] data = name.getBytes(StandardCharsets.UTF_8);
        MetaMessage message = new MetaMessage();
        message.setMessage(0x03, data, data.length);
        return message;
    }

    private static MetaMessage marker(String text) throws InvalidMidiDataException {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        MetaMessage message = new MetaMessage();
        message.setMessage(0x06, data, data.length);
        return message;
    }
}
