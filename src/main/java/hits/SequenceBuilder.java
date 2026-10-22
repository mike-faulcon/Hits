package hits;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.Receiver;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Soundbank;
import javax.sound.midi.Track;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/** Turns a beat into a MIDI sequence. A 16th note is {@code PPQ / 4} ticks. */
public final class SequenceBuilder {
    public static final int PPQ = 96;
    public static final int STEP_TICKS = PPQ / 4;
    /**
     * Metronome channel. Not a track row and not the drum channel, so the click is a pitched
     * instrument. On the JDK's built-in bank, drum keys 75 and 81 are the same sample as the
     * wood blocks, which made the old claves click indistinguishable from a wood-block track.
     */
    public static final int CLICK_CHANNEL = 15;
    /** Syn Square Wave. A short pitched click, distinct from the drum kit on the built-in bank. */
    public static final int CLICK_PROGRAM = 80;
    /** Downbeat pitch, C6. Louder than the other beats. */
    public static final int CLICK_ACCENT_NOTE = 84;
    /** Other beats, C5. A different pitch from the downbeat, still not a kit note. */
    public static final int CLICK_NOTE = 72;
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
        return arrangement(beat, new ExportOptions(true, false, 1, true), null, 1, metronome, true, null);
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
        return pattern(beat, null, 1, metronome, null);
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
        return playback(beat, song, random, metronome, null);
    }

    /**
     * Playback, including every pre-rolled pass. {@code kits} is the bank that will play.
     * A drum kit that bank does not contain plays Standard. Null keeps the kit number,
     * which is what a MIDI file does.
     */
    public static Sequence playback(Beat beat, boolean song, Random random, boolean metronome, Soundbank kits)
        throws InvalidMidiDataException {
        boolean chance = usesChance(beat, song);
        int passes = playbackPasses(beat, song);
        Random roll = chance ? (random != null ? random : new Random()) : null;
        if (song && beat.hasChain()) {
            return arrangement(beat, new ExportOptions(true, false, 1, true), roll, passes, metronome, true, kits);
        }
        return pattern(beat, roll, passes, metronome, kits);
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

    private static Sequence pattern(Beat beat, Random random, int passes, boolean metronome, Soundbank kits)
        throws InvalidMidiDataException {
        Sequence sequence = new Sequence(Sequence.PPQ, PPQ);
        Track tempo = sequence.createTrack();
        int steps = beat.stepCount(beat.activeSlot());
        int times = Math.max(1, passes);
        long loop = loopTicks(steps);
        tempo.add(new MidiEvent(tempoMessage(beat.bpm()), 0));
        tempo.add(new MidiEvent(marker("end"), loop * times));

        hits.Track[] tracks = beat.activeTracks();
        ChannelPlan plan = ChannelPlan.of(beat, List.of(beat.activeSlot()), kits);
        Track[] midiTracks = new Track[tracks.length];
        ChannelMemory memory = new ChannelMemory();
        for (int i = 0; i < tracks.length; i++) {
            hits.Track track = tracks[i];
            if (!beat.audible(track)) {
                continue;
            }
            midiTracks[i] = sequence.createTrack();
            midiTracks[i].add(new MidiEvent(trackName(track.name()), 0));
            writeSetup(midiTracks[i], track, i, 0, plan, memory);
        }
        for (int pass = 0; pass < times; pass++) {
            for (int i = 0; i < tracks.length; i++) {
                if (midiTracks[i] == null) {
                    continue;
                }
                writeNotes(midiTracks[i], beat, tracks[i], i, steps, pass * loop, loop, random, true, (pass + 1L) * loop, null, plan);
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
        return export(beat, options, null);
    }

    /**
     * File export heard on {@code kits}. A drum kit that bank does not contain plays Standard.
     * Null keeps the kit number, which is what a MIDI file and an external output do.
     */
    public static Sequence export(Beat beat, ExportOptions options, Soundbank kits) throws InvalidMidiDataException {
        return arrangement(
            beat,
            options == null ? ExportOptions.matchingPlayback() : options,
            Chance.exportRandom(),
            1,
            false,
            false,
            kits
        );
    }

    private static Sequence arrangement(
        Beat beat,
        ExportOptions options,
        Random random,
        int passes,
        boolean metronome,
        boolean clipEnd,
        Soundbank kits
    ) throws InvalidMidiDataException {
        ExportOptions chosen = options == null ? ExportOptions.matchingPlayback() : options;
        boolean song = chosen.song() && beat.hasChain();
        Sequence sequence = new Sequence(Sequence.PPQ, PPQ);
        Track tempo = sequence.createTrack();
        List<Timeline.Section> sections = Timeline.export(beat, chosen);
        long span = Timeline.ticks(sections);
        int times = Math.max(1, passes);
        long sequenceEnd = span * times;
        tempo.add(new MidiEvent(tempoMessage(beat.bpm()), 0));
        tempo.add(new MidiEvent(marker("end"), sequenceEnd));

        int[] lengths = new int[sections.size()];
        for (int s = 0; s < sections.size(); s++) {
            lengths[s] = sections.get(s).steps();
        }
        List<Character> spanSlots = spanSlots(beat, chosen, song);
        ChannelPlan plan = ChannelPlan.of(beat, spanSlots, kits);
        boolean joined = spanSlots.size() > 1;
        Track[] midiTracks = new Track[Beat.TRACKS];
        ChannelMemory memory = new ChannelMemory();
        for (int pass = 0; pass < times; pass++) {
            long origin = pass * span;
            for (int s = 0; s < sections.size(); s++) {
                Timeline.Section section = sections.get(s);
                char slot = section.slot();
                hits.Track[] tracks = beat.slot(slot);
                long offset = origin + section.start();
                int[] ahead = ahead(lengths, s, pass, times);
                for (int i = 0; i < tracks.length; i++) {
                    hits.Track track = tracks[i];
                    if (chosen.asHeard() && !Beat.audibleIn(tracks, track)) {
                        continue;
                    }
                    if (midiTracks[i] == null) {
                        midiTracks[i] = sequence.createTrack();
                        midiTracks[i].add(new MidiEvent(trackName(exportName(beat, chosen, spanSlots, i, joined)), 0));
                    }
                    writeSection(
                        midiTracks[i],
                        beat,
                        track,
                        i,
                        section.steps(),
                        offset,
                        section.ticks(),
                        random,
                        clipEnd ? sequenceEnd : -1,
                        ahead,
                        plan,
                        memory
                    );
                }
            }
        }
        memory.restoreLoop();
        if (metronome) {
            addClicks(sequence, sections, times);
        }
        return sequence;
    }

    /** Step counts after {@code sectionIndex} in this pass, then each later pass in full. */
    private static int[] ahead(int[] lengths, int sectionIndex, int pass, int passes) {
        int rest = lengths.length - sectionIndex - 1;
        int laterPasses = Math.max(0, passes - pass - 1);
        int count = rest + laterPasses * lengths.length;
        int[] following = new int[count];
        int cursor = 0;
        for (int i = sectionIndex + 1; i < lengths.length; i++) {
            following[cursor++] = lengths[i];
        }
        for (int later = 0; later < laterPasses; later++) {
            for (int length : lengths) {
                following[cursor++] = length;
            }
        }
        return following;
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
     * A shared drum sends pan only. A private drum selects the drum bank before its kit,
     * because a program change alone would look up a silent drum-kit preset.
     * Level is not a controller. Each note-on already carries the fader, so it cannot stick.
     * Release and reverb are skipped when the channel already has that value.
     * Pitch, filter, and the sends wait until a note still sounding from the previous
     * pattern has ended, so the tail is not bent or opened mid-note.
     */
    private static void writeSetup(
        Track midi,
        hits.Track track,
        int row,
        long offset,
        ChannelPlan plan,
        ChannelMemory memory
    ) throws InvalidMidiDataException {
        int channel = plan.channel(track, row);
        List<ShortMessage> messages = plan.setup(track, channel, row);
        memory.noteFirst(midi, channel, offset, messages);
        long hold = overlappingHold(midi, channel, offset);
        for (ShortMessage message : messages) {
            if (memory.unchangedReleaseOrReverb(message)) {
                continue;
            }
            long tick = hold > offset && affectsHeldNote(message) ? hold : offset;
            midi.add(new MidiEvent(message, tick));
            memory.remember(message);
        }
    }

    /**
     * Latest note-off on {@code channel} for a note that started before {@code at} and is still
     * held there. Returns {@code at} when nothing crosses that tick.
     */
    private static long overlappingHold(Track midi, int channel, long at) {
        long[] started = new long[128];
        Arrays.fill(started, -1);
        long until = at;
        for (int i = 0; i < midi.size(); i++) {
            MidiEvent event = midi.get(i);
            if (!(event.getMessage() instanceof ShortMessage message) || message.getChannel() != channel) {
                continue;
            }
            int pitch = message.getData1();
            boolean noteOn = message.getCommand() == ShortMessage.NOTE_ON && message.getData2() > 0;
            boolean noteOff = message.getCommand() == ShortMessage.NOTE_OFF
                || (message.getCommand() == ShortMessage.NOTE_ON && message.getData2() == 0);
            if (noteOn) {
                started[pitch] = event.getTick();
            } else if (noteOff && started[pitch] >= 0) {
                if (started[pitch] < at && event.getTick() > at) {
                    until = Math.max(until, event.getTick());
                }
                started[pitch] = -1;
            }
        }
        return until;
    }

    /** Pitch, filter, release, and the effect sends. Bank, program, and pan stay at the pattern start. */
    private static boolean affectsHeldNote(ShortMessage message) {
        if (message.getCommand() == ShortMessage.PITCH_BEND) {
            return true;
        }
        if (message.getCommand() != ShortMessage.CONTROL_CHANGE) {
            return false;
        }
        return switch (message.getData1()) {
            case ChannelPlan.RELEASE, ChannelPlan.REVERB, ChannelPlan.CHORUS, ChannelPlan.CUTOFF, ChannelPlan.RESONANCE -> true;
            default -> false;
        };
    }

    /**
     * Pushes the slot sounding at {@code tick} onto an open synth.
     * Reloading a sequence does not by itself move pan or bank. Playback calls this on every load,
     * including a live edit, so center pan and a note after a panned drum are heard immediately.
     * Looping back to the start replays the same setup from the sequence.
     */
    static void playChannelState(Receiver receiver, Beat beat, boolean song, long tick) throws InvalidMidiDataException {
        playChannelState(receiver, beat, song, tick, null);
    }

    /**
     * Pushes the slot sounding at {@code tick} onto an open synth.
     * {@code kits} is the bank that will play. A missing drum kit plays Standard.
     * Null keeps the kit number.
     */
    static void playChannelState(Receiver receiver, Beat beat, boolean song, long tick, Soundbank kits)
        throws InvalidMidiDataException {
        if (receiver == null || beat == null) {
            return;
        }
        List<Character> slots = song && beat.hasChain()
            ? Chain.distinctSlots(beat.chain())
            : List.of(beat.activeSlot());
        char slot = song && beat.hasChain()
            ? Chain.place(beat, tick).slot()
            : beat.activeSlot();
        ChannelPlan plan = ChannelPlan.of(beat, slots, kits);
        hits.Track[] tracks = beat.slot(slot);
        for (int row = 0; row < tracks.length; row++) {
            hits.Track track = tracks[row];
            if (!Beat.audibleIn(tracks, track)) {
                continue;
            }
            int channel = plan.channel(track, row);
            for (ShortMessage message : plan.setup(track, channel, row)) {
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
     * One section of a song or export. Bank, program, and pan are written at the section start,
     * or earlier when a step-0 nudge pulls a hit into the previous section. That hit then uses
     * this track's sound and pan, not the pattern it overlaps.
     */
    private static void writeSection(
        Track midi,
        Beat beat,
        hits.Track track,
        int row,
        int steps,
        long offset,
        long sectionTicks,
        Random random,
        long clipTick,
        int[] ahead,
        ChannelPlan plan,
        ChannelMemory memory
    ) throws InvalidMidiDataException {
        List<Placed> notes = placedNotes(
            beat, track, steps, offset, sectionTicks, random, false, clipTick, ahead
        );
        long setupAt = offset;
        for (Placed note : notes) {
            if (note.sound.start() < setupAt) {
                setupAt = note.sound.start();
            }
        }
        writeSetup(midi, track, row, setupAt, plan, memory);
        emitNotes(midi, plan.channel(track, row), notes);
    }

    /**
     * Writes the hits in one section.
     * {@code wrapStart} is looped pattern playback: a nudge that falls outside the loop
     * comes back in at the other end, and every note-off stops at {@code clipTick}.
     * Song playback passes the end of the whole sequence as {@code clipTick} so a note can
     * cross into the next section and still release before the song loops.
     * {@code ahead} is each later section's step count, including later passes, so the
     * continuation uses that pattern's length. Export passes {@code -1} and lets a held
     * note cross a section or finish after the file.
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
        long clipTick,
        int[] ahead,
        ChannelPlan plan
    ) throws InvalidMidiDataException {
        int channel = plan.channel(track, row);
        emitNotes(midi, channel, placedNotes(
            beat, track, steps, offset, sectionTicks, random, wrapStart, clipTick, ahead
        ));
    }

    private static List<Placed> placedNotes(
        Beat beat,
        hits.Track track,
        int steps,
        long offset,
        long sectionTicks,
        Random random,
        boolean wrapStart,
        long clipTick,
        int[] ahead
    ) {
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
            sounding[step] = cell.soundingNote(track.playedNote());
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
                    tie = NoteTiming.startTick(next, steps, swing, nudges[next], offset, sectionTicks, false, ahead);
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
                clipTick,
                ahead
            );
            if (sound != null) {
                placed.add(new Placed(sound, sounding[step], velocities[step]));
            }
        }
        return placed;
    }

    private static void emitNotes(Track midi, int channel, List<Placed> placed) throws InvalidMidiDataException {
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
            if (noteOn && event.getTick() <= start && (on == null || event.getTick() >= on.getTick())) {
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
     * A click on every quarter note inside each section, on every pass.
     * Beat 1 of each bar is the accent. A bar is 16 steps, so a 32-step pattern accents
     * at its start and again halfway through, and a shorter pattern accents only at its start.
     * The click is a short square-wave note on its own channel. Drum claves and wood blocks
     * share one sample on the JDK's built-in bank, so a drum-channel click cannot be told apart
     * from a wood-block track. Quarter notes fall on even 16ths, which swing does not move,
     * so the click stays on the beat.
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
        click.add(new MidiEvent(controlMessage(CLICK_CHANNEL, 0, 0), 0));
        click.add(new MidiEvent(controlMessage(CLICK_CHANNEL, 32, 0), 0));
        click.add(new MidiEvent(programMessage(CLICK_CHANNEL, CLICK_PROGRAM), 0));
        click.add(new MidiEvent(controlMessage(CLICK_CHANNEL, 10, 64), 0));
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
                    on.setMessage(ShortMessage.NOTE_ON, CLICK_CHANNEL, note, velocity);
                    click.add(new MidiEvent(on, start));
                    ShortMessage off = new ShortMessage();
                    off.setMessage(ShortMessage.NOTE_OFF, CLICK_CHANNEL, note, 0);
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

    /**
     * Release and reverb already on a channel. Gervill starts at release 64 and reverb 40,
     * so a preset that asks for those neutrals is not written again.
     * When the song ends on a different value, the first pattern's value is put back at
     * its setup tick so a loop does not keep the last pattern's send.
     */
    private static final class ChannelMemory {
        private final int[] release = new int[16];
        private final int[] reverb = new int[16];
        private final boolean[] seen = new boolean[16];
        private final Integer[] firstRelease = new Integer[16];
        private final Integer[] firstReverb = new Integer[16];
        private final long[] firstTick = new long[16];
        private final Track[] firstTrack = new Track[16];

        private ChannelMemory() {
            Arrays.fill(release, 64);
            Arrays.fill(reverb, hits.Track.REVERB_NEUTRAL);
        }

        private void noteFirst(Track midi, int channel, long tick, List<ShortMessage> messages) {
            if (channel < 0 || channel > 15 || seen[channel]) {
                return;
            }
            seen[channel] = true;
            firstTick[channel] = tick;
            firstTrack[channel] = midi;
            firstRelease[channel] = controller(messages, channel, ChannelPlan.RELEASE);
            firstReverb[channel] = controller(messages, channel, ChannelPlan.REVERB);
        }

        private boolean unchangedReleaseOrReverb(ShortMessage message) {
            if (message.getCommand() != ShortMessage.CONTROL_CHANGE) {
                return false;
            }
            int channel = message.getChannel();
            if (channel < 0 || channel > 15) {
                return false;
            }
            if (message.getData1() == ChannelPlan.RELEASE) {
                return message.getData2() == release[channel];
            }
            if (message.getData1() == ChannelPlan.REVERB) {
                return message.getData2() == reverb[channel];
            }
            return false;
        }

        private void remember(ShortMessage message) {
            if (message.getCommand() != ShortMessage.CONTROL_CHANGE) {
                return;
            }
            int channel = message.getChannel();
            if (channel < 0 || channel > 15) {
                return;
            }
            if (message.getData1() == ChannelPlan.RELEASE) {
                release[channel] = message.getData2();
            } else if (message.getData1() == ChannelPlan.REVERB) {
                reverb[channel] = message.getData2();
            }
        }

        private void restoreLoop() throws InvalidMidiDataException {
            for (int channel = 0; channel < 16; channel++) {
                if (!seen[channel] || firstTrack[channel] == null) {
                    continue;
                }
                restore(channel, ChannelPlan.RELEASE, firstRelease[channel], release[channel]);
                restore(channel, ChannelPlan.REVERB, firstReverb[channel], reverb[channel]);
            }
        }

        private void restore(int channel, int controller, Integer desired, int actual) throws InvalidMidiDataException {
            if (desired == null || desired == actual) {
                return;
            }
            if (already(firstTrack[channel], channel, controller, desired, firstTick[channel])) {
                return;
            }
            firstTrack[channel].add(new MidiEvent(controlMessage(channel, controller, desired), firstTick[channel]));
        }

        private static boolean already(Track midi, int channel, int controller, int value, long tick) {
            for (int i = 0; i < midi.size(); i++) {
                MidiEvent event = midi.get(i);
                if (event.getTick() != tick || !(event.getMessage() instanceof ShortMessage message)) {
                    continue;
                }
                if (message.getCommand() == ShortMessage.CONTROL_CHANGE
                    && message.getChannel() == channel
                    && message.getData1() == controller
                    && message.getData2() == value) {
                    return true;
                }
            }
            return false;
        }

        private static Integer controller(List<ShortMessage> messages, int channel, int controller) {
            Integer value = null;
            for (ShortMessage message : messages) {
                if (message.getCommand() == ShortMessage.CONTROL_CHANGE
                    && message.getChannel() == channel
                    && message.getData1() == controller) {
                    value = message.getData2();
                }
            }
            return value;
        }
    }
}
