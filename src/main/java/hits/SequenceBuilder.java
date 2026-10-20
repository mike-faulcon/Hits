package hits;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.Receiver;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Turns a beat into a MIDI sequence. A 16th note is {@code PPQ / 4} ticks. */
public final class SequenceBuilder {
    public static final int PPQ = 96;
    public static final int STEP_TICKS = PPQ / 4;
    /**
     * GM claves. Playback click on beat 1 of each bar.
     * Not the high wood block (76), and not one of the kit notes, so a wood-block track
     * stays separate from the click. GM2 metronome keys 33 and 34 are silent on the JDK's
     * generated drum kit, which has no samples below note 35.
     */
    public static final int CLICK_ACCENT_NOTE = 75;
    /**
     * GM open triangle. Playback click on the other beats.
     * Not the low wood block (77), and not one of the kit notes.
     */
    public static final int CLICK_NOTE = 81;
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
        // Even steps sit on the pair boundary. An odd-length pattern ends on an even step, so that step is not delayed.
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
            ? Chain.ticks(beat)
            : loopTicks(beat.stepCount(beat.activeSlot()));
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
            for (char slot : Chain.distinctSlots(beat.chain())) {
                if (beat.playingChance(slot)) {
                    return true;
                }
            }
            return false;
        }
        return beat.playingChance(beat.activeSlot());
    }

    private static Sequence pattern(Beat beat, Random random, int passes, boolean metronome) throws InvalidMidiDataException {
        Sequence sequence = new Sequence(Sequence.PPQ, PPQ);
        Track tempo = sequence.createTrack();
        int steps = beat.stepCount(beat.activeSlot());
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
            writeSetup(midiTracks[i], track, i, 0);
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
            addClicks(sequence, Timeline.pattern(beat), times);
        }
        return sequence;
    }

    /**
     * File export. The default options match playback: muted tracks are left out and solo is respected.
     * {@link ExportOptions#allTracks()} writes every track and ignores mute and solo.
     * Pan and mixer levels are part of the file either way.
     * Repeats append the same notes later in the file. Both slots play A, then B, and that pair repeats.
     * Song writes the chain once; each entry already carries its own repeats and can name any pattern A–H.
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
        List<Timeline.Section> sections = Timeline.export(beat, chosen);
        long span = Timeline.ticks(sections);
        int times = Math.max(1, passes);
        tempo.add(new MidiEvent(tempoMessage(beat.bpm()), 0));
        tempo.add(new MidiEvent(marker("end"), span * times));

        List<Character> spanSlots = spanSlots(beat, chosen, song);
        boolean joined = spanSlots.size() > 1;
        Track[] midiTracks = new Track[Beat.TRACKS];
        for (int pass = 0; pass < times; pass++) {
            long origin = pass * span;
            for (Timeline.Section section : sections) {
                char slot = section.slot();
                hits.Track[] tracks = beat.slot(slot);
                long offset = origin + section.start();
                for (int i = 0; i < tracks.length; i++) {
                    hits.Track track = tracks[i];
                    if (chosen.asHeard() && !Beat.audibleIn(tracks, track)) {
                        continue;
                    }
                    if (midiTracks[i] == null) {
                        midiTracks[i] = sequence.createTrack();
                        midiTracks[i].add(new MidiEvent(trackName(exportName(beat, chosen, spanSlots, i, joined)), 0));
                    }
                    writeSetup(midiTracks[i], track, i, offset);
                    writeNotes(midiTracks[i], beat, track, i, section.steps(), offset, section.ticks(), random);
                }
            }
        }
        if (metronome) {
            addClicks(sequence, sections, times);
        }
        return sequence;
    }

    private static List<Character> spanSlots(Beat beat, ExportOptions options, boolean song) {
        if (song) {
            return Chain.distinctSlots(beat.chain());
        }
        if (options.bothSlots()) {
            return List.of('a', 'b');
        }
        return List.of(Beat.normalizeSlot(beat.activeSlot()));
    }

    /** One name, or the included pattern names joined in letter order. */
    private static String exportName(Beat beat, ExportOptions options, List<Character> slots, int row, boolean span) {
        if (!span) {
            return beat.slot(slots.get(0))[row].name();
        }
        List<String> names = new ArrayList<>();
        for (char slot : slots) {
            hits.Track track = beat.slot(slot)[row];
            boolean included = !options.asHeard() || Beat.audibleIn(beat.slot(slot), track);
            if (included && !names.contains(track.name())) {
                names.add(track.name());
            }
        }
        if (names.size() <= 1) {
            return names.isEmpty() ? beat.slot(slots.get(0))[row].name() : names.get(0);
        }
        return String.join(" / ", names);
    }

    /**
     * Bank, program, and pan for one row, in the order a synth needs them.
     * Pan is written even at center, so a later pattern does not keep an earlier left or right.
     * A note always selects bank 0 first. A panned drum leaves the channel on the drum bank,
     * and a program change alone would then look up a silent drum-kit preset.
     * Level is not a controller. Each note-on already carries the fader, so it cannot stick.
     */
    private static List<ShortMessage> channelSetup(hits.Track track, int row) throws InvalidMidiDataException {
        List<ShortMessage> messages = new ArrayList<>();
        int channel = Mix.channel(track, row);
        if (Mix.usesDrumBank(track)) {
            messages.add(controlMessage(channel, 0, Gm.DRUM_BANK));
            messages.add(controlMessage(channel, 32, 0));
            messages.add(programMessage(channel, 0));
        } else if (track.mode() == TrackMode.NOTE) {
            messages.add(controlMessage(channel, 0, 0));
            messages.add(controlMessage(channel, 32, 0));
            messages.add(programMessage(channel, track.program()));
        }
        messages.add(controlMessage(channel, 10, track.pan()));
        return messages;
    }

    private static void writeSetup(Track midi, hits.Track track, int row, long offset) throws InvalidMidiDataException {
        for (ShortMessage message : channelSetup(track, row)) {
            midi.add(new MidiEvent(message, offset));
        }
    }

    /**
     * Pushes the slot sounding at {@code tick} onto an open synth.
     * Reloading a sequence does not by itself move pan or bank. Playback calls this on every load,
     * including a live edit, so center pan and a note after a panned drum are heard immediately.
     * Looping back to the start replays the same setup from the sequence.
     */
    static void playChannelState(Receiver receiver, Beat beat, boolean song, long tick) throws InvalidMidiDataException {
        if (receiver == null || beat == null) {
            return;
        }
        char slot = song && beat.hasChain()
            ? Chain.place(beat, tick).slot()
            : beat.activeSlot();
        hits.Track[] tracks = beat.slot(slot);
        for (int row = 0; row < tracks.length; row++) {
            hits.Track track = tracks[row];
            if (!Beat.audibleIn(tracks, track)) {
                continue;
            }
            for (ShortMessage message : channelSetup(track, row)) {
                receiver.send(message, -1);
            }
        }
    }

    private static ShortMessage controlMessage(int channel, int controller, int value) throws InvalidMidiDataException {
        ShortMessage message = new ShortMessage();
        message.setMessage(ShortMessage.CONTROL_CHANGE, channel, controller, value);
        return message;
    }

    private static ShortMessage programMessage(int channel, int program) throws InvalidMidiDataException {
        ShortMessage message = new ShortMessage();
        message.setMessage(ShortMessage.PROGRAM_CHANGE, channel, program, 0);
        return message;
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
     * A click on every quarter note inside each section, on every pass.
     * Beat 1 of each bar is the accent. A bar is 16 steps, so a 32-step pattern accents
     * at its start and again halfway through, and a shorter pattern accents only at its start.
     * The notes are claves and an open triangle, not the wood blocks a drum track can play.
     * Quarter notes fall on even 16ths, which swing does not move, so the click stays on the beat.
     */
    private static void addClicks(Sequence sequence, List<Timeline.Section> sections, int passes) throws InvalidMidiDataException {
        if (passes <= 0 || sections == null || sections.isEmpty()) {
            return;
        }
        boolean any = false;
        for (Timeline.Section section : sections) {
            if (section.steps() >= 4) {
                any = true;
                break;
            }
        }
        if (!any) {
            return;
        }
        Track click = sequence.createTrack();
        click.add(new MidiEvent(trackName("Click"), 0));
        long span = Timeline.ticks(sections);
        for (int pass = 0; pass < passes; pass++) {
            long origin = pass * span;
            for (Timeline.Section section : sections) {
                int steps = section.steps();
                if (steps < 4) {
                    continue;
                }
                int quarters = steps / 4;
                for (int quarter = 0; quarter < quarters; quarter++) {
                    int step = quarter * 4;
                    boolean downbeat = step % 16 == 0;
                    long start = origin + section.start() + (long) step * STEP_TICKS;
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
    }

    private static MetaMessage marker(String text) throws InvalidMidiDataException {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        MetaMessage message = new MetaMessage();
        message.setMessage(0x06, data, data.length);
        return message;
    }
}
