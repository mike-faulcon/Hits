package hits;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import java.nio.charset.StandardCharsets;
import java.util.Random;

/** Turns a beat into a MIDI sequence. A 16th note is {@code PPQ / 4} ticks. */
public final class SequenceBuilder {
    public static final int PPQ = 96;
    public static final int STEP_TICKS = PPQ / 4;
    /** GM high wood block. Playback click on beat 1 of each bar. */
    public static final int CLICK_ACCENT_NOTE = 76;
    /** GM low wood block. Playback click on the other beats. */
    public static final int CLICK_NOTE = 77;
    public static final int CLICK_ACCENT_VELOCITY = 120;
    public static final int CLICK_VELOCITY = 80;
    /** Short enough that a click does not ring into the next beat. */
    public static final int CLICK_TICKS = 6;

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

    /** Playback of the song chain, as heard, one pass, without rolling probability. An empty chain is the active pattern. */
    public static Sequence buildSong(Beat beat) throws InvalidMidiDataException {
        return buildSong(beat, false);
    }

    /**
     * Playback of the song chain. {@code metronome} adds a click on each beat,
     * louder on beat 1 of each bar. An empty chain is the active pattern.
     * Export does not use this path, so the click is never written to a file.
     */
    public static Sequence buildSong(Beat beat, boolean metronome) throws InvalidMidiDataException {
        if (!beat.hasChain()) {
            return build(beat, metronome);
        }
        return arrangement(beat, new ExportOptions(true, false, 1, true), null, 1, metronome);
    }

    /**
     * One pass of the active slot, with mute and solo applied.
     * A step at 0% is silent. Other probabilities play; the roll happens in {@link #playback}.
     */
    public static Sequence build(Beat beat) throws InvalidMidiDataException {
        return build(beat, false);
    }

    /**
     * Playback sequence: the active slot, once, with mute and solo applied.
     * {@code metronome} adds clicks. {@link #export} never writes them.
     */
    public static Sequence build(Beat beat, boolean metronome) throws InvalidMidiDataException {
        return pattern(beat, null, 1, metronome);
    }

    /**
     * What the sequencer loops. Each pass through the pattern, or through the song, rolls
     * probability again. A beat whose sounding steps are all certain is one pass, same as {@link #build}.
     * {@code random} supplies the draws when a roll is needed. Null starts an unseeded roll.
     * The metronome is off. {@link Player} uses {@link #playback(Beat, boolean, Random, boolean)}.
     */
    public static Sequence playback(Beat beat, boolean song, Random random) throws InvalidMidiDataException {
        return playback(beat, song, random, false);
    }

    public static Sequence playback(Beat beat, boolean song) throws InvalidMidiDataException {
        return playback(beat, song, null, false);
    }

    /**
     * Playback, including every pre-rolled pass. {@code metronome} adds a click on each beat of
     * every pass. Export never calls this, so the click is not written to a file.
     */
    public static Sequence playback(Beat beat, boolean song, Random random, boolean metronome) throws InvalidMidiDataException {
        boolean chance = usesChance(beat, song);
        int passes = playbackPasses(beat, song);
        Random roll = chance ? (random != null ? random : new Random()) : null;
        if (song && beat.hasChain()) {
            return arrangement(beat, new ExportOptions(true, false, 1, true), roll, passes, metronome);
        }
        return pattern(beat, roll, passes, metronome);
    }

    /** Tick length of {@link #playback}, including every pre-rolled pass. */
    public static long playbackTicks(Beat beat, boolean song) {
        long one = song && beat.hasChain()
            ? Chain.ticks(beat.chain(), beat.stepCount())
            : loopTicks(beat.stepCount());
        return one * playbackPasses(beat, song);
    }

    /** 1 when nothing sounding can miss. Otherwise several pre-rolled passes, capped for long chains. */
    public static int playbackPasses(Beat beat, boolean song) {
        if (!usesChance(beat, song)) {
            return 1;
        }
        int sections = song && beat.hasChain() ? Math.max(1, Chain.sections(beat.chain())) : 1;
        int room = Math.max(1, Chance.MAX_PLAYBACK_SECTIONS / sections);
        return Math.max(1, Math.min(Chance.PLAYBACK_PASSES, room));
    }

    private static boolean usesChance(Beat beat, boolean song) {
        if (song && beat.hasChain()) {
            boolean slotA = false;
            boolean slotB = false;
            for (Chain.Part part : beat.chain()) {
                if (part.slot() == 'b') {
                    slotB = true;
                } else {
                    slotA = true;
                }
            }
            return (slotA && beat.playingChance('a')) || (slotB && beat.playingChance('b'));
        }
        return beat.playingChance(beat.activeSlot());
    }

    private static Sequence pattern(Beat beat, Random random, int passes, boolean metronome) throws InvalidMidiDataException {
        Sequence sequence = new Sequence(Sequence.PPQ, PPQ);
        Track tempo = sequence.createTrack();
        int steps = beat.stepCount();
        int times = Math.max(1, passes);
        long loop = loopTicks(steps);
        tempo.add(new MidiEvent(tempoMessage(beat.bpm()), 0));
        tempo.add(new MidiEvent(marker("end"), loop * times));

        hits.Track[] tracks = beat.activeTracks();
        Track[] midiTracks = new Track[tracks.length];
        for (int i = 0; i < tracks.length; i++) {
            hits.Track track = tracks[i];
            if (!beat.audible(track)) {
                continue;
            }
            midiTracks[i] = sequence.createTrack();
            midiTracks[i].add(new MidiEvent(trackName(track.name()), 0));
            writeSetup(midiTracks[i], track, i, 0, false);
        }
        for (int pass = 0; pass < times; pass++) {
            for (int i = 0; i < tracks.length; i++) {
                if (midiTracks[i] == null) {
                    continue;
                }
                writeNotes(midiTracks[i], beat, tracks[i], i, steps, pass * loop, loop, random);
            }
        }
        if (metronome) {
            addClicks(sequence, steps, times);
        }
        return sequence;
    }

    /**
     * File export. The default options match playback: muted tracks are left out and solo is respected.
     * {@link ExportOptions#allTracks()} writes every track and ignores mute and solo.
     * Pan and mixer levels are part of the file either way.
     * Repeats append the same notes later in the file. Both slots play A, then B, and that pair repeats.
     * Song writes the chain once; each entry already carries its own repeats.
     * Probability uses {@link Chance#EXPORT_SEED}, so the same beat and options write the same notes.
     * Each section is rolled on its own. 100% always plays and 0% never does.
     * The metronome is never written.
     */
    public static Sequence export(Beat beat, ExportOptions options) throws InvalidMidiDataException {
        return arrangement(beat, options == null ? ExportOptions.matchingPlayback() : options, Chance.exportRandom(), 1, false);
    }

    private static Sequence arrangement(
        Beat beat,
        ExportOptions options,
        Random random,
        int passes,
        boolean metronome
    ) throws InvalidMidiDataException {
        ExportOptions chosen = options == null ? ExportOptions.matchingPlayback() : options;
        boolean song = chosen.song() && beat.hasChain();
        Sequence sequence = new Sequence(Sequence.PPQ, PPQ);
        Track tempo = sequence.createTrack();
        int steps = beat.stepCount();
        long sectionTicks = loopTicks(steps);
        int sections = song ? Chain.sections(beat.chain()) : chosen.sections();
        int times = Math.max(1, passes);
        tempo.add(new MidiEvent(tempoMessage(beat.bpm()), 0));
        tempo.add(new MidiEvent(marker("end"), sectionTicks * sections * times));

        boolean span = song ? Chain.usesBoth(beat.chain()) : chosen.bothSlots();
        Track[] midiTracks = new Track[Beat.TRACKS];
        for (int pass = 0; pass < times; pass++) {
            for (int section = 0; section < sections; section++) {
                char slot = song ? Chain.slotAt(beat.chain(), section) : chosen.slotAt(section, beat.activeSlot());
                hits.Track[] tracks = beat.slot(slot);
                long offset = ((long) pass * sections + section) * sectionTicks;
                for (int i = 0; i < tracks.length; i++) {
                    hits.Track track = tracks[i];
                    if (chosen.asHeard() && !Beat.audibleIn(tracks, track)) {
                        continue;
                    }
                    if (midiTracks[i] == null) {
                        midiTracks[i] = sequence.createTrack();
                        midiTracks[i].add(new MidiEvent(trackName(exportName(beat, chosen, slot, i, span)), 0));
                    }
                    writeSetup(midiTracks[i], track, i, offset, resetDrumBank(beat, chosen, song, i, section));
                    writeNotes(midiTracks[i], beat, track, i, steps, offset, sectionTicks, random);
                }
            }
        }
        if (metronome) {
            addClicks(sequence, steps, sections * times);
        }
        return sequence;
    }

    private static String exportName(Beat beat, ExportOptions options, char slot, int row, boolean both) {
        hits.Track current = beat.slot(slot)[row];
        if (!both) {
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

    /** An earlier section put this row's channel on the drum bank, and this section is a pitched instrument. */
    private static boolean resetDrumBank(Beat beat, ExportOptions options, boolean song, int row, int section) {
        char slot = song ? Chain.slotAt(beat.chain(), section) : options.slotAt(section, beat.activeSlot());
        if (beat.slot(slot)[row].mode() != TrackMode.NOTE) {
            return false;
        }
        for (int earlier = 0; earlier < section; earlier++) {
            char earlierSlot = song ? Chain.slotAt(beat.chain(), earlier) : options.slotAt(earlier, beat.activeSlot());
            if (Mix.usesDrumBank(beat.slot(earlierSlot)[row])) {
                return true;
            }
        }
        return false;
    }

    private static void writeSetup(
        Track midi,
        hits.Track track,
        int row,
        long offset,
        boolean resetBank
    ) throws InvalidMidiDataException {
        int channel = Mix.channel(track, row);
        if (Mix.usesDrumBank(track)) {
            control(midi, channel, offset, 0, Gm.DRUM_BANK);
            control(midi, channel, offset, 32, 0);
            program(midi, channel, offset, 0);
        } else if (track.mode() == TrackMode.NOTE) {
            if (resetBank) {
                control(midi, channel, offset, 0, 0);
                control(midi, channel, offset, 32, 0);
            }
            program(midi, channel, offset, track.program());
        }
        if (track.pan() != hits.Track.PAN_CENTER) {
            control(midi, channel, offset, 10, track.pan());
        }
    }

    private static void control(Track midi, int channel, long tick, int controller, int value) throws InvalidMidiDataException {
        ShortMessage message = new ShortMessage();
        message.setMessage(ShortMessage.CONTROL_CHANGE, channel, controller, value);
        midi.add(new MidiEvent(message, tick));
    }

    private static void program(Track midi, int channel, long tick, int program) throws InvalidMidiDataException {
        ShortMessage message = new ShortMessage();
        message.setMessage(ShortMessage.PROGRAM_CHANGE, channel, program, 0);
        midi.add(new MidiEvent(message, tick));
    }

    private static void writeNotes(
        Track midi,
        Beat beat,
        hits.Track track,
        int row,
        int steps,
        long offset,
        long sectionTicks,
        Random random
    ) throws InvalidMidiDataException {
        int channel = Mix.channel(track, row);
        for (int step = 0; step < steps; step++) {
            Step cell = track.step(step);
            if (!cell.on() || !Chance.plays(cell.probability(), random)) {
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
            int velocity = Mix.velocity(cell.velocity(), track.level(), beat.master());
            if (velocity <= 0) {
                continue;
            }
            ShortMessage on = new ShortMessage();
            on.setMessage(ShortMessage.NOTE_ON, channel, note, velocity);
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

    /**
     * A click on every quarter note of every bar in the sequence. Beat 1 of each bar is the accent.
     * {@code bars} is pattern sections times playback passes, so a later pass still clicks.
     * Quarter notes fall on even 16ths, which swing does not move, so the click stays on the beat.
     */
    private static void addClicks(Sequence sequence, int stepCount, int bars) throws InvalidMidiDataException {
        if (bars <= 0 || stepCount < 4) {
            return;
        }
        Track click = sequence.createTrack();
        click.add(new MidiEvent(trackName("Click"), 0));
        int quarters = stepCount / 4;
        long bar = loopTicks(stepCount);
        for (int section = 0; section < bars; section++) {
            for (int quarter = 0; quarter < quarters; quarter++) {
                int step = quarter * 4;
                boolean downbeat = step % 16 == 0;
                long start = section * bar + (long) step * STEP_TICKS;
                int note = downbeat ? CLICK_ACCENT_NOTE : CLICK_NOTE;
                int velocity = downbeat ? CLICK_ACCENT_VELOCITY : CLICK_VELOCITY;
                ShortMessage on = new ShortMessage();
                on.setMessage(ShortMessage.NOTE_ON, Gm.DRUM_CHANNEL, note, velocity);
                click.add(new MidiEvent(on, start));
                ShortMessage off = new ShortMessage();
                off.setMessage(ShortMessage.NOTE_OFF, Gm.DRUM_CHANNEL, note, 0);
                click.add(new MidiEvent(off, start + CLICK_TICKS));
            }
        }
    }

    private static MetaMessage marker(String text) throws InvalidMidiDataException {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        MetaMessage message = new MetaMessage();
        message.setMessage(0x06, data, data.length);
        return message;
    }
}
