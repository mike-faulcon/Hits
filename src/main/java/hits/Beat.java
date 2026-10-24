package hits;

import java.util.ArrayList;
import java.util.List;

/**
 * A saved beat: tempo, swing, length, eight pattern slots, one to sixteen rows,
 * and an optional song chain. A new beat still has the eight kit rows.
 * Every pattern has the same rows, so adding or reordering one updates the others.
 */
public final class Beat {
    public static final int VERSION = 1;
    /**
     * Written when a step stores its own pitch, a step stores a play probability
     * other than 100, the beat has a song chain, the mixer is off its defaults,
     * a pattern C–H is in use, a pattern's length differs from the root {@code steps},
     * a step is longer than one step, a note track is legato, a step is nudged
     * off the grid, a track shift is set, or a track chooses a bank, a drum kit,
     * or a pitched preset. Version 1 files stay version 1.
     */
    public static final int FORMAT_V2 = 2;
    /** Master fader at full. Track levels are not turned down any further. */
    public static final int MASTER = 127;
    public static final int TRACKS = 8;
    /** Fewest rows a beat can keep. One row still plays. */
    public static final int MIN_TRACKS = 1;
    /**
     * Most rows a beat can keep. MIDI has 16 channels and channel 10 stays the drum channel,
     * so a seventeenth distinct instrument cannot have a channel of its own.
     */
    public static final int MAX_TRACKS = 16;
    /** Shortest pattern. One step still loops. */
    public static final int MIN_STEPS = 1;
    /** Longest pattern. Matches {@link Track#CAPACITY}. The root length stays 16 or 32. */
    public static final int MAX_STEPS = Track.CAPACITY;
    /** Slowest tempo the spinner, tap tempo, and saved beats can store. */
    public static final int MIN_BPM = 40;
    /** Fastest tempo the spinner, tap tempo, and saved beats can store. */
    public static final int MAX_BPM = 240;
    /** Patterns A through H. */
    public static final int SLOT_COUNT = 8;

    private static final String[] KIT_NAMES = {
        "Kick", "Snare", "Closed Hat", "Open Hat", "Clap", "Tom", "Crash", "Ride"
    };
    private static final int[] KIT_NOTES = {36, 38, 42, 46, 39, 45, 49, 51};

    private String name;
    private int bpm;
    private int swing;
    /** Root length, 16 or 32. Old readers use this for every pattern. */
    private int stepCount;
    /**
     * Per-pattern length, or 0 to follow {@link #stepCount}. Written only when it differs.
     * Swing still pairs steps. On an odd length the last step is an even index, so it is not delayed.
     */
    private final int[] slotSteps = new int[SLOT_COUNT];
    private char active;
    private int master = MASTER;
    /** How many rows every pattern has. A new beat uses {@link #TRACKS}. */
    private int trackCount = TRACKS;
    private final Track[][] slots = new Track[SLOT_COUNT][];
    private List<Chain.Part> chain = List.of();
    /** Load-time notes, such as a sample gain that was clamped. Not written to the file. */
    private final List<String> loadWarnings = new ArrayList<>();

    private Beat(String name) {
        this.name = name == null || name.isBlank() ? "untitled" : name.trim();
        this.bpm = 96;
        this.swing = 50;
        this.stepCount = 16;
        this.active = 'a';
        for (int i = 0; i < SLOT_COUNT; i++) {
            slots[i] = new Track[TRACKS];
            fillKit(slots[i]);
        }
    }

    public static Beat drumKit(String name) {
        return new Beat(name);
    }

    /** {@code a} through {@code h}. Anything else is pattern A. */
    public static char normalizeSlot(char slot) {
        return slot >= 'a' && slot <= 'h' ? slot : 'a';
    }

    /** One letter, lowercase. Anything else is pattern A. */
    public static char parseSlot(String text) {
        if (text != null && text.length() == 1) {
            return normalizeSlot(text.charAt(0));
        }
        return 'a';
    }

    public static int slotIndex(char slot) {
        return normalizeSlot(slot) - 'a';
    }

    public static char slotLetter(int index) {
        if (index < 0 || index >= SLOT_COUNT) {
            return 'a';
        }
        return (char) ('a' + index);
    }

    /** Display letter, {@code A} through {@code H}. */
    public static String slotLabel(char slot) {
        return String.valueOf(Character.toUpperCase(normalizeSlot(slot)));
    }

    public Beat copy() {
        Beat copy = new Beat(name);
        copy.bpm = bpm;
        copy.swing = swing;
        copy.stepCount = stepCount;
        System.arraycopy(slotSteps, 0, copy.slotSteps, 0, SLOT_COUNT);
        copy.active = active;
        copy.master = master;
        copy.chain = chain;
        if (trackCount != TRACKS) {
            copy.resize(trackCount);
        }
        for (int i = 0; i < SLOT_COUNT; i++) {
            copyTracks(slots[i], copy.slots[i]);
        }
        copy.loadWarnings.addAll(loadWarnings);
        return copy;
    }

    /** A note from loading, such as a sample gain outside −24 to +24 dB. */
    public void addLoadWarning(String warning) {
        if (warning != null && !warning.isBlank()) {
            loadWarnings.add(warning);
        }
    }

    public List<String> loadWarnings() {
        return List.copyOf(loadWarnings);
    }

    /**
     * The clamp warning for this pattern's row, or null. The inspector shows it
     * so a gain above +24 dB is not only stored as 24.
     */
    public String gainWarning(char slot, String name) {
        return rowWarning(slot, name, "sample gain");
    }

    /**
     * A load warning for this pattern's row that mentions {@code phrase}, or null.
     * The inspector uses it so a clamped presence or EQ gain is not only stored.
     */
    public String rowWarning(char slot, String name, String phrase) {
        String prefix = slotLabel(slot) + " " + (name == null ? "" : name) + ":";
        for (String warning : loadWarnings) {
            if (warning.startsWith(prefix) && phrase != null && warning.contains(phrase)) {
                return warning;
            }
        }
        return null;
    }

    /**
     * Rows that use low cut, presence, or EQ, as {@code A Kick, B Bass}.
     * A high cut also names its slope, as {@code A Kick high cut 12 dB/oct}.
     * Empty when the beat does not use any of them.
     */
    public String filterRowNames() {
        StringBuilder names = new StringBuilder();
        for (char slot = 'a'; slot <= 'h'; slot++) {
            for (Track track : slot(slot)) {
                if (!track.hasTone()) {
                    continue;
                }
                if (names.length() > 0) {
                    names.append(", ");
                }
                names.append(slotLabel(slot)).append(' ').append(track.name());
                if (track.hasHighCut()) {
                    names.append(" high cut ").append(track.highCutSlope()).append(" dB/oct");
                }
            }
        }
        return names.toString();
    }

    /**
     * MIDI export and a companion SoundFont drop low cut, presence, and EQ.
     * Null when this beat does not use them.
     */
    public String midiFilterWarning() {
        String rows = filterRowNames();
        if (rows.isEmpty()) {
            return null;
        }
        return "Low cut, presence, and EQ are not in the MIDI file or the companion SoundFont: "
            + rows
            + ". Playback and WAV export use them.";
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        if (name != null && !name.isBlank()) {
            this.name = name.trim();
        }
    }

    public int bpm() {
        return bpm;
    }

    public void setBpm(int bpm) {
        this.bpm = clampBpm(bpm);
    }

    public static int clampBpm(int bpm) {
        return Math.max(MIN_BPM, Math.min(MAX_BPM, bpm));
    }

    /** 50 is straight. 75 is a hard shuffle. Applies to every other 16th. */
    public int swing() {
        return swing;
    }

    public void setSwing(int swing) {
        this.swing = Math.max(50, Math.min(75, swing));
    }

    /** Root length, 16 or 32. Patterns without their own length use this. */
    public int stepCount() {
        return stepCount;
    }

    /** How many steps {@code slot} plays, from {@link #MIN_STEPS} to {@link #MAX_STEPS}. */
    public int stepCount(char slot) {
        int custom = slotSteps[slotIndex(slot)];
        return custom == 0 ? stepCount : custom;
    }

    /**
     * Columns drawn for {@code slot}. Steps at and after {@link #stepCount(char)} stay in the grid,
     * dimmed, so a shorter pattern does not throw away the notes past its length.
     */
    public int gridColumns(char slot) {
        int length = stepCount(slot);
        int bars = length <= 16 ? 16 : MAX_STEPS;
        return Math.min(MAX_STEPS, Math.max(stepCount(), Math.max(bars, length)));
    }

    /** Root length. 32 and above become 32. Anything smaller becomes 16, which is what old files store. */
    public void setStepCount(int stepCount) {
        this.stepCount = stepCount >= 32 ? 32 : 16;
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (slotSteps[i] == this.stepCount) {
                slotSteps[i] = 0;
            }
        }
    }

    /**
     * Length of one pattern, clamped to {@link #MIN_STEPS}–{@link #MAX_STEPS}.
     * A value equal to the root length is not stored, so the file omits it.
     */
    public void setSlotSteps(char slot, int steps) {
        int clamped = clampSteps(steps);
        slotSteps[slotIndex(slot)] = clamped == stepCount ? 0 : clamped;
    }

    public static int clampSteps(int steps) {
        return Math.max(MIN_STEPS, Math.min(MAX_STEPS, steps));
    }

    /** True when any pattern plays a different number of steps from the root length. */
    public boolean hasCustomLengths() {
        for (int steps : slotSteps) {
            if (steps != 0) {
                return true;
            }
        }
        return false;
    }

    public char activeSlot() {
        return active;
    }

    public void setActiveSlot(char slot) {
        this.active = normalizeSlot(slot);
    }

    public int master() {
        return master;
    }

    public void setMaster(int master) {
        this.master = Math.max(0, Math.min(127, master));
    }

    public Track[] activeTracks() {
        return slot(active);
    }

    public Track[] slot(char slot) {
        return slots[slotIndex(slot)];
    }

    /** Rows in every pattern. A new kit has {@link #TRACKS}. The file stores this many track objects. */
    public int trackCount() {
        return trackCount;
    }

    /**
     * Gives every pattern {@code count} rows, from {@link #MIN_TRACKS} to {@link #MAX_TRACKS}.
     * New rows are the kit voice, then a sine 808 named Sub, then plain drums.
     * Dropped rows leave every pattern.
     */
    public void setTrackCount(int count) {
        resize(count);
    }

    /** Appends one row to every pattern. False when the beat already has {@link #MAX_TRACKS}. */
    public boolean addTrack() {
        if (trackCount >= MAX_TRACKS) {
            return false;
        }
        resize(trackCount + 1);
        return true;
    }

    /** Drops row {@code index} from every pattern. False at {@link #MIN_TRACKS} or a bad index. */
    public boolean removeTrack(int index) {
        if (trackCount <= MIN_TRACKS || index < 0 || index >= trackCount) {
            return false;
        }
        int next = trackCount - 1;
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            Track[] current = slots[slot];
            Track[] shrunk = new Track[next];
            int write = 0;
            for (int row = 0; row < current.length; row++) {
                if (row != index) {
                    shrunk[write++] = current[row];
                }
            }
            slots[slot] = shrunk;
        }
        trackCount = next;
        return true;
    }

    /**
     * Inserts a copy of row {@code index} just after it, in every pattern.
     * False when the beat already has {@link #MAX_TRACKS} or the index is bad.
     */
    public boolean duplicateTrack(int index) {
        if (trackCount >= MAX_TRACKS || index < 0 || index >= trackCount) {
            return false;
        }
        int next = trackCount + 1;
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            Track[] current = slots[slot];
            Track[] grown = new Track[next];
            System.arraycopy(current, 0, grown, 0, index + 1);
            grown[index + 1] = current[index].copy();
            System.arraycopy(current, index + 1, grown, index + 2, current.length - index - 1);
            slots[slot] = grown;
        }
        trackCount = next;
        return true;
    }

    /**
     * Moves row {@code index} by {@code delta} positions in every pattern.
     * False when that would leave the list.
     */
    public boolean moveTrack(int index, int delta) {
        int target = index + delta;
        if (delta == 0 || index < 0 || target < 0 || index >= trackCount || target >= trackCount) {
            return false;
        }
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            Track[] current = slots[slot];
            Track[] next = current.clone();
            Track moved = next[index];
            if (target > index) {
                System.arraycopy(current, index + 1, next, index, target - index);
            } else {
                System.arraycopy(current, target, next, target + 1, index - target);
            }
            next[target] = moved;
            slots[slot] = next;
        }
        return true;
    }

    public Track track(int index) {
        return activeTracks()[index];
    }

    public boolean audible(Track track) {
        return audibleIn(activeTracks(), track);
    }

    /** Mute and solo judged against one pattern slot, not whichever slot is on screen. */
    public static boolean audibleIn(Track[] slot, Track track) {
        if (track.mute()) {
            return false;
        }
        for (Track candidate : slot) {
            if (candidate.solo()) {
                return track.solo();
            }
        }
        return true;
    }

    /** Copies the first 16 steps of the active pattern onto its second bar and switches to 32 steps. */
    public void copyBar() {
        copyBar(active);
    }

    /** Copies the first 16 steps of {@code slot} onto its second bar and sets that pattern to 32 steps. */
    public void copyBar(char slot) {
        setSlotSteps(slot, 32);
        for (Track track : this.slot(slot)) {
            for (int i = 0; i < 16; i++) {
                track.step(16 + i).copyFrom(track.step(i));
            }
        }
    }

    public void copyActiveToOther() {
        copySlotToOther(active);
    }

    /** Copies {@code slot} onto the other of A and B. The active slot stays put. */
    public void copySlotToOther(char slot) {
        char from = slot == 'b' ? 'b' : 'a';
        copySlot(from, from == 'a' ? 'b' : 'a');
    }

    /** Copies pattern {@code from} onto {@code to}. The active pattern stays put. */
    public void copySlot(char from, char to) {
        char source = normalizeSlot(from);
        char dest = normalizeSlot(to);
        if (source == dest) {
            return;
        }
        Track[] tracks = slot(source);
        Track[] target = slot(dest);
        for (int i = 0; i < trackCount; i++) {
            target[i] = tracks[i].copy();
        }
    }

    public boolean hasStepPitch() {
        for (Track[] slot : slots) {
            if (pitched(slot)) {
                return true;
            }
        }
        return false;
    }

    /** True when a step in any pattern stores a probability other than 100, including a step that is off. */
    public boolean hasChance() {
        for (Track[] slot : slots) {
            if (storedChance(slot)) {
                return true;
            }
        }
        return false;
    }

    /** True when a step that is on in {@code slot} can be skipped or included by a roll. */
    public boolean playingChance(char slot) {
        return rollingChance(this.slot(slot));
    }

    /**
     * True when a version 2 field is in use. Each check is one feature, so a later
     * change adds a line instead of rewriting the expression.
     */
    public boolean usesFormatV2() {
        return hasStepPitch()
            || hasChance()
            || hasChain()
            || hasMixer()
            || usesExtraSlots()
            || hasCustomLengths()
            || hasNoteLength()
            || hasNudge()
            || hasSoundChoice()
            || hasShape()
            || hasSamples()
            || hasTone();
    }

    /** Low cut, presence, or EQ on any row, in any pattern. */
    public boolean hasTone() {
        for (Track[] slot : slots) {
            for (Track track : slot) {
                if (track.hasTone()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** A sample track in any pattern. The sample object is version 2. */
    public boolean hasSamples() {
        for (Track[] slot : slots) {
            for (Track track : slot) {
                if (track.mode() == TrackMode.SAMPLE) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Tune, decay, or a send or filter, in any pattern. */
    public boolean hasShape() {
        for (Track[] slot : slots) {
            for (Track track : slot) {
                if (track.shapesSound()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** A bank, a drum kit, or a pitched preset, in any pattern. */
    public boolean hasSoundChoice() {
        for (Track[] slot : slots) {
            for (Track track : slot) {
                if (track.bank() != 0 || track.kit() != 0 || track.preset() != null) {
                    return true;
                }
            }
        }
        return false;
    }

    /** A step longer than one step, or a legato note track, in any pattern. */
    public boolean hasNoteLength() {
        for (Track[] slot : slots) {
            if (held(slot)) {
                return true;
            }
        }
        return false;
    }

    /** A step nudge, or a track shift, in any pattern. Off steps count. */
    public boolean hasNudge() {
        for (Track[] slot : slots) {
            if (shifted(slot)) {
                return true;
            }
        }
        return false;
    }

    /** Pan, track level, or the master fader differs from the value a new kit uses, in any pattern. */
    public boolean hasMixer() {
        if (master != MASTER) {
            return true;
        }
        for (Track[] slot : slots) {
            if (mixed(slot)) {
                return true;
            }
        }
        return false;
    }

    /** Pattern C–H is selected, named in the chain, or different from the empty kit. */
    public boolean usesExtraSlots() {
        if (active >= 'c' && active <= 'h') {
            return true;
        }
        for (Chain.Part part : chain) {
            if (part.slot() >= 'c') {
                return true;
            }
        }
        return !extraSlots().isEmpty();
    }

    /** Patterns C–H that differ from the empty kit, in letter order. */
    List<Character> extraSlots() {
        List<Character> extra = new ArrayList<>();
        for (char slot = 'c'; slot <= 'h'; slot++) {
            if (!matchesKit(slot) || stepCount(slot) != stepCount) {
                extra.add(slot);
            }
        }
        return extra;
    }

    public List<Chain.Part> chain() {
        return chain;
    }

    public boolean hasChain() {
        return !chain.isEmpty();
    }

    public void setChain(List<Chain.Part> parts) {
        chain = Chain.normalize(parts);
    }

    /** Appends one play of {@code slot}. Returns false when the chain is already full. */
    public boolean addChain(char slot) {
        if (chain.size() >= Chain.MAX_PARTS) {
            return false;
        }
        List<Chain.Part> next = new ArrayList<>(chain);
        next.add(new Chain.Part(slot, 1));
        chain = List.copyOf(next);
        return true;
    }

    public void removeChain(int index) {
        if (index < 0 || index >= chain.size()) {
            return;
        }
        List<Chain.Part> next = new ArrayList<>(chain);
        next.remove(index);
        chain = List.copyOf(next);
    }

    /** Moves the entry at {@code index} by {@code delta} positions. */
    public void moveChain(int index, int delta) {
        int target = index + delta;
        if (index < 0 || index >= chain.size() || target < 0 || target >= chain.size()) {
            return;
        }
        List<Chain.Part> next = new ArrayList<>(chain);
        next.add(target, next.remove(index));
        chain = List.copyOf(next);
    }

    public void setChainRepeats(int index, int repeats) {
        if (index < 0 || index >= chain.size()) {
            return;
        }
        List<Chain.Part> next = new ArrayList<>(chain);
        Chain.Part part = next.get(index);
        next.set(index, new Chain.Part(part.slot(), repeats));
        chain = List.copyOf(next);
    }

    /**
     * Replaces one pattern's rows. A different length resizes every pattern first,
     * so a file with sixteen tracks does not leave pattern C on eight.
     */
    public void replaceSlot(char slot, Track[] tracks) {
        if (tracks == null || tracks.length < MIN_TRACKS || tracks.length > MAX_TRACKS) {
            throw new IllegalArgumentException(
                "A slot needs " + MIN_TRACKS + " to " + MAX_TRACKS + " tracks"
            );
        }
        if (tracks.length != trackCount) {
            resize(tracks.length);
        }
        Track[] target = slot(slot);
        for (int i = 0; i < trackCount; i++) {
            target[i] = tracks[i];
        }
    }

    private static boolean mixed(Track[] tracks) {
        for (Track track : tracks) {
            if (track.level() != Track.LEVEL || track.pan() != Track.PAN_CENTER) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesKit(char slot) {
        Track[] kit = new Track[trackCount];
        fillKit(kit);
        Track[] current = this.slot(slot);
        if (current.length != kit.length) {
            return false;
        }
        for (int i = 0; i < trackCount; i++) {
            if (!current[i].sameAs(kit[i])) {
                return false;
            }
        }
        return true;
    }

    private static boolean pitched(Track[] tracks) {
        for (Track track : tracks) {
            for (int i = 0; i < Track.CAPACITY; i++) {
                if (track.step(i).hasPitch()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean held(Track[] tracks) {
        for (Track track : tracks) {
            if (track.legato()) {
                return true;
            }
            for (int i = 0; i < Track.CAPACITY; i++) {
                if (track.step(i).extended()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean shifted(Track[] tracks) {
        for (Track track : tracks) {
            if (track.shift() != 0) {
                return true;
            }
            for (int i = 0; i < Track.CAPACITY; i++) {
                if (track.step(i).nudged()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean storedChance(Track[] tracks) {
        for (Track track : tracks) {
            for (int i = 0; i < Track.CAPACITY; i++) {
                if (track.step(i).uncertain()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 0 and 100 need no roll. Anything between them does, and only while the step is on. */
    private static boolean rollingChance(Track[] tracks) {
        for (Track track : tracks) {
            for (int i = 0; i < Track.CAPACITY; i++) {
                Step step = track.step(i);
                int probability = step.probability();
                if (step.on() && probability > 0 && probability < 100) {
                    return true;
                }
            }
        }
        return false;
    }

    private void resize(int count) {
        int next = clampTrackCount(count);
        if (next == trackCount && slots[0] != null && slots[0].length == next) {
            return;
        }
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            Track[] current = slots[slot];
            Track[] resized = new Track[next];
            for (int row = 0; row < next; row++) {
                if (current != null && row < current.length) {
                    resized[row] = current[row];
                } else {
                    resized[row] = freshRow(row);
                }
            }
            slots[slot] = resized;
        }
        trackCount = next;
    }

    /** Keeps a requested row count inside {@link #MIN_TRACKS} and {@link #MAX_TRACKS}. */
    public static int clampTrackCount(int count) {
        return Math.max(MIN_TRACKS, Math.min(MAX_TRACKS, count));
    }

    private static void fillKit(Track[] tracks) {
        for (int i = 0; i < tracks.length; i++) {
            tracks[i] = freshRow(i);
        }
    }

    /**
     * Kit voice for the first eight rows. Row nine is a sine 808 under the kick.
     * Later rows are empty drums the user can retune.
     */
    private static Track freshRow(int index) {
        if (index >= 0 && index < TRACKS) {
            return new Track(KIT_NAMES[index], TrackMode.DRUM, 0, KIT_NOTES[index]);
        }
        if (index == TRACKS) {
            Track sub = new Track("Sub", TrackMode.SAMPLE, 0, 36);
            sub.setSample(SampleRef.builtin(BuiltinSamples.EIGHT_OH_EIGHT, 36));
            return sub;
        }
        return new Track("Track " + (index + 1), TrackMode.DRUM, 0, 36);
    }

    private static void copyTracks(Track[] from, Track[] to) {
        int rows = Math.min(from.length, to.length);
        for (int i = 0; i < rows; i++) {
            to[i] = from[i].copy();
        }
    }
}
