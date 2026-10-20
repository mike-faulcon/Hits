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
        return arrangement(beat, new ExportOptions(true, false, 1, true), null, 1, metronome, true);
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
            return arrangement(beat, new ExportOptions(true, false, 1, true), roll, passes, metronome, true);
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
            writeSetup(midiTracks[i], track, i, 0);
        }
        for (int pass = 0; pass < times; pass++) {
            for (int i = 0; i < tracks.length; i++) {
                if (midiTracks[i] == null) {
                    continue;
                }
                writeNotes(midiTracks[i], beat, tracks[i], i, steps, pass * loop, loop, random, true, (pass + 1L) * loop);
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
     * Song writes the chain once; each entry already carries its own repeats and can name any pattern A–H.
     * Probability uses {@link Chance#EXPORT_SEED}, so the same beat and options write the same notes.
     * Each section is rolled on its own. 100% always plays and 0% never does.
     * The metronome is never written.
     */
    public static Sequence export(Beat beat, ExportOptions options) throws InvalidMidiDataException {
        return arrangement(beat, options == null ? ExportOptions.matchingPlayback() : options, Chance.exportRandom(), 1, false, false);
    }

    private static Sequence arrangement(
        Beat beat,
        ExportOptions options,
        Random random,
        int passes,
        boolean metronome,
        boolean clipEnd
    ) throws InvalidMidiDataException {
        ExportOptions chosen = options == null ? ExportOptions.matchingPlayback() : options;
        boolean song = chosen.song() && beat.hasChain();
        Sequence sequence = new Sequence(Sequence.PPQ, PPQ);
        Track tempo = sequence.createTrack();
        int steps = beat.stepCount();
        long sectionTicks = loopTicks(steps);
        int sections = song ? Chain.sections(beat.chain()) : chosen.sections();
        int times = Math.max(1, passes);
        long sequenceEnd = sectionTicks * (long) sections * times;
        tempo.add(new MidiEvent(tempoMessage(beat.bpm()), 0));
        tempo.add(new MidiEvent(marker("end"), sequenceEnd));

        List<Character> spanSlots = spanSlots(beat, chosen, song);
        boolean span = spanSlots.size() > 1;
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
                        midiTracks[i].add(new MidiEvent(trackName(exportName(beat, chosen, spanSlots, i, span)), 0));
                    }
                    writeSetup(midiTracks[i], track, i, offset);
                    writeNotes(
                        midiTracks[i],
                        beat,
                        track,
                        i,
                        steps,
                        offset,
                        sectionTicks,
                        random,
                        false,
                        clipEnd ? sequenceEnd : -1
                    );
                }
            }
        }
        if (metronome) {
            addClicks(sequence, steps, sections * times);
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
            ? Chain.place(beat.chain(), tick, beat.stepCount(), beat.swing()).slot()
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

    /**
     * Writes the hits in one section.
     * {@code wrapStart} is looped pattern playback: a negative nudge on the first step
     * lands at the end of this pass, and every note-off stops at {@code clipTick}.
     * Song playback passes the end of the whole sequence as {@code clipTick} so a note can
     * cross into the next section and still release before the song loops.
     * Export passes {@code -1} and lets a held note cross a section or finish after the file.
     * One roll covers the whole note. A later hit on the same pitch shortens the earlier
     * note so its note-off does not silence the new one.
     */
    private static void writeNotes(
        Track midi,
        Beat beat,
        hits.Track track,
        int row,
        int steps,
        long offset,
        long sectionTicks,
        Random random,
        boolean wrapStart,
        long clipTick
    ) throws InvalidMidiDataException {
        int channel = Mix.channel(track, row);
        int swing = beat.swing();
        boolean legato = track.legato() && track.mode() == TrackMode.NOTE;
        boolean[] play = new boolean[steps];
        int[] sounding = new int[steps];
        int[] velocities = new int[steps];
        int[] nudges = new int[steps];
        for (int step = 0; step < steps; step++) {
            Step cell = track.step(step);
            if (!cell.on() || !Chance.plays(cell.probability(), random)) {
                continue;
            }
            int velocity = Mix.velocity(cell.velocity(), track.level(), beat.master());
            if (velocity <= 0) {
                continue;
            }
            play[step] = true;
            sounding[step] = cell.soundingNote(track.note());
            velocities[step] = velocity;
            nudges[step] = track.playedNudge(step);
        }
        List<Placed> placed = new ArrayList<>();
        for (int step = 0; step < steps; step++) {
            if (!play[step]) {
                continue;
            }
            int length = track.step(step).length();
            long tie = -1;
            if (legato) {
                int next = nextPlaying(play, step);
                if (next >= 0) {
                    tie = NoteTiming.startTick(next, steps, swing, nudges[next], offset, sectionTicks, false);
                } else {
                    length = Math.max(1, steps - step);
                }
            }
            NoteTiming.Sound sound = NoteTiming.place(
                step,
                steps,
                swing,
                nudges[step],
                length,
                track.gate(),
                tie,
                offset,
                sectionTicks,
                wrapStart,
                clipTick
            );
            if (sound != null) {
                placed.add(new Placed(sound, sounding[step], velocities[step]));
            }
        }
        // A negative nudge can put step 0 after a later step. Emit in time order so the
        // later note-on shortens a same-pitch note that would otherwise overlap it.
        placed.sort((left, right) -> Long.compare(left.sound.start(), right.sound.start()));
        for (Placed note : placed) {
            truncateOverlap(midi, channel, note.note, note.sound.start());
            ShortMessage on = new ShortMessage();
            on.setMessage(ShortMessage.NOTE_ON, channel, note.note, note.velocity);
            midi.add(new MidiEvent(on, note.sound.start()));
            ShortMessage off = new ShortMessage();
            off.setMessage(ShortMessage.NOTE_OFF, channel, note.note, 0);
            midi.add(new MidiEvent(off, note.sound.end()));
        }
    }

    private record Placed(NoteTiming.Sound sound, int note, int velocity) {}

    private static int nextPlaying(boolean[] play, int step) {
        for (int next = step + 1; next < play.length; next++) {
            if (play[next]) {
                return next;
            }
        }
        return -1;
    }

    /**
     * Moves an earlier note-off of the same pitch up to {@code start}.
     * The note-off is removed and added again so it stays in tick order, ahead of the new note-on.
     */
    private static void truncateOverlap(Track midi, int channel, int note, long start) throws InvalidMidiDataException {
        MidiEvent on = null;
        MidiEvent off = null;
        for (int i = 0; i < midi.size(); i++) {
            MidiEvent event = midi.get(i);
            if (!(event.getMessage() instanceof ShortMessage message)) {
                continue;
            }
            if (message.getChannel() != channel || message.getData1() != note) {
                continue;
            }
            boolean noteOff = message.getCommand() == ShortMessage.NOTE_OFF
                || (message.getCommand() == ShortMessage.NOTE_ON && message.getData2() == 0);
            boolean noteOn = message.getCommand() == ShortMessage.NOTE_ON && message.getData2() > 0;
            if (noteOn && event.getTick() < start && (on == null || event.getTick() >= on.getTick())) {
                on = event;
                off = null;
            } else if (noteOff && on != null && event.getTick() >= on.getTick() && off == null) {
                off = event;
            }
        }
        if (on == null || off == null || off.getTick() <= start) {
            return;
        }
        midi.remove(off);
        ShortMessage released = new ShortMessage();
        released.setMessage(ShortMessage.NOTE_OFF, channel, note, 0);
        midi.add(new MidiEvent(released, start));
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
     * The notes are claves and an open triangle, not the wood blocks a drum track can play.
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
