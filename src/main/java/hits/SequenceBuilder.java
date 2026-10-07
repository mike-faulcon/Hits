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

    /** Playback sequence: the active slot, once, with mute and solo applied. */
    public static Sequence build(Beat beat) throws InvalidMidiDataException {
        Sequence sequence = new Sequence(Sequence.PPQ, PPQ);
        Track tempo = sequence.createTrack();
        int steps = beat.stepCount();
        long loop = loopTicks(steps);
        tempo.add(new MidiEvent(tempoMessage(beat.bpm()), 0));
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
            writeNotes(midi, beat, track, channel, steps, 0, loop);
        }
        return sequence;
    }

    /**
     * File export. The default options write every track and ignore mute and solo.
     * Repeats append the same notes later in the file. Both slots play A, then B, and that pair repeats.
     */
    public static Sequence export(Beat beat, ExportOptions options) throws InvalidMidiDataException {
        ExportOptions chosen = options == null ? ExportOptions.allTracks() : options;
        Sequence sequence = new Sequence(Sequence.PPQ, PPQ);
        Track tempo = sequence.createTrack();
        int steps = beat.stepCount();
        long sectionTicks = loopTicks(steps);
        int sections = chosen.sections();
        tempo.add(new MidiEvent(tempoMessage(beat.bpm()), 0));
        tempo.add(new MidiEvent(marker("end"), sectionTicks * sections));

        Track[] midiTracks = new Track[Beat.TRACKS];
        for (int section = 0; section < sections; section++) {
            char slot = chosen.slotAt(section, beat.activeSlot());
            hits.Track[] tracks = beat.slot(slot);
            long offset = section * sectionTicks;
            for (int i = 0; i < tracks.length; i++) {
                hits.Track track = tracks[i];
                if (chosen.asHeard() && !Beat.audibleIn(tracks, track)) {
                    continue;
                }
                if (midiTracks[i] == null) {
                    midiTracks[i] = sequence.createTrack();
                    midiTracks[i].add(new MidiEvent(trackName(exportName(beat, chosen, slot, i)), 0));
                }
                int channel = track.mode() == TrackMode.DRUM ? Gm.DRUM_CHANNEL : i;
                if (track.mode() == TrackMode.NOTE) {
                    ShortMessage program = new ShortMessage();
                    program.setMessage(ShortMessage.PROGRAM_CHANGE, channel, track.program(), 0);
                    midiTracks[i].add(new MidiEvent(program, offset));
                }
                writeNotes(midiTracks[i], beat, track, channel, steps, offset, sectionTicks);
            }
        }
        return sequence;
    }

    private static String exportName(Beat beat, ExportOptions options, char slot, int row) {
        hits.Track current = beat.slot(slot)[row];
        if (!options.bothSlots()) {
            return current.name();
        }
        char otherSlot = slot == 'b' ? 'a' : 'b';
        hits.Track other = beat.slot(otherSlot)[row];
        boolean otherIncluded = !options.asHeard() || Beat.audibleIn(beat.slot(otherSlot), other);
        if (!otherIncluded || other.name().equals(current.name())) {
            return current.name();
        }
        return beat.slot('a')[row].name() + " / " + beat.slot('b')[row].name();
    }

    private static void writeNotes(
        Track midi,
        Beat beat,
        hits.Track track,
        int channel,
        int steps,
        long offset,
        long sectionTicks
    ) throws InvalidMidiDataException {
        for (int step = 0; step < steps; step++) {
            Step cell = track.step(step);
            if (!cell.on()) {
                continue;
            }
            long start = offset + tickForStep(step, beat.swing());
            long boundary = step + 1 < steps
                ? offset + tickForStep(step + 1, beat.swing())
                : offset + sectionTicks;
            long length = Math.max(1, Math.round((boundary - start) * (track.gate() / 100.0)));
            long end = offset + sectionTicks;
            if (start + length > end) {
                length = Math.max(1, end - start);
            }
            int note = cell.soundingNote(track.note());
            ShortMessage on = new ShortMessage();
            on.setMessage(ShortMessage.NOTE_ON, channel, note, cell.velocity());
            midi.add(new MidiEvent(on, start));
            ShortMessage off = new ShortMessage();
            off.setMessage(ShortMessage.NOTE_OFF, channel, note, 0);
            midi.add(new MidiEvent(off, start + length));
        }
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
