package hits;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
     * off the grid, a step slides, a row sets a slide time or curve, a track shift is set, or a track chooses a bank, a drum kit,
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
    /** A new beat. Whole numbers and hundredths share this range. */
    public static final double DEFAULT_BPM = 96;
    /** Patterns A through H. */
    public static final int SLOT_COUNT = 8;

    private static final String[] KIT_NAMES = {
        "Kick", "Snare", "Closed Hat", "Open Hat", "Clap", "Tom", "Crash", "Ride"
    };
    private static final int[] KIT_NOTES = {36, 38, 42, 46, 39, 45, 49, 51};

    private String name;
    /** Tempo in hundredths of a BPM. {@code 9600} is 96, {@code 9250} is 92.5. */
    private int centiBpm;
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
        this.centiBpm = Tempo.centi(DEFAULT_BPM);
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
        copy.centiBpm = centiBpm;
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
     * Active pattern, any pattern with a hit, and any pattern named by the chain.
     * {@code hits info} lists these. The MIDI filter warning uses {@link #songSlots()}
     * and only the patterns where that row sounds.
     */
    public List<Character> patternsInUse() {
        List<Character> used = new ArrayList<>();
        for (char slot = 'a'; slot <= 'h'; slot++) {
            if (slot == active || patternHasNotes(slot) || chainHas(slot)) {
                used.add(slot);
            }
        }
        return used;
    }

    /** True when any step inside this pattern's length is on. */
    public boolean patternHasNotes(char slot) {
        int limit = stepCount(slot);
        for (Track track : this.slot(slot)) {
            if (trackHasNotes(track, limit)) {
                return true;
            }
        }
        return false;
    }

    private boolean chainHas(char slot) {
        for (Chain.Part part : chain) {
            if (part.slot() == slot) {
                return true;
            }
        }
        return false;
    }

    /**
     * One line per row that has a note in a pattern this song actually uses,
     * listing the filters MIDI will not carry. Empty when nothing qualifies.
     */
    public String filterRowNames() {
        return midiFilterWarning() == null ? "" : midiFilterWarning();
    }

    /**
     * Patterns the song plays. The chain when there is one, otherwise the active pattern.
     */
    public List<Character> songSlots() {
        if (hasChain()) {
            return Chain.distinctSlots(chain);
        }
        return List.of(active);
    }

    /**
     * MIDI export drops low cut, EQ, high cut, presence, and invert.
     * The companion SoundFont drops the same filters except invert, which it keeps.
     * A row is named only for a pattern in {@link #songSlots()} where that row sounds.
     * Null when nothing qualifies. One line per row.
     */
    public String midiFilterWarning() {
        return midiFilterWarning(songSlots());
    }

    /**
     * Same warning as {@link #midiFilterWarning()}, limited to {@code slots}.
     * A pattern is listed only when this row has a hit there that playback will sound.
     * An empty pattern, a muted row, and a pattern outside {@code slots} are left out.
     */
    public String midiFilterWarning(List<Character> slots) {
        List<Character> scope = slots == null || slots.isEmpty() ? songSlots() : slots;
        StringBuilder lines = new StringBuilder();
        for (int row = 0; row < trackCount; row++) {
            List<String> filters = new ArrayList<>();
            List<String> patterns = new ArrayList<>();
            String name = null;
            for (char slot : scope) {
                Track[] tracks = slot(slot);
                if (row >= tracks.length) {
                    continue;
                }
                Track track = tracks[row];
                if (!rowSounds(slot, row)) {
                    continue;
                }
                List<String> dropped = droppedFilters(track);
                if (dropped.isEmpty()) {
                    continue;
                }
                if (name == null) {
                    name = track.name();
                }
                String label = slotLabel(slot);
                if (!patterns.contains(label)) {
                    patterns.add(label);
                }
                for (String filter : dropped) {
                    if (!filters.contains(filter)) {
                        filters.add(filter);
                    }
                }
            }
            if (filters.isEmpty()) {
                continue;
            }
            if (lines.length() > 0) {
                lines.append('\n');
            }
            lines.append("Row ").append(row + 1).append(" \"").append(name).append("\" drops ")
                .append(englishList(filters))
                .append(patterns.size() == 1 ? " in pattern " : " in patterns ")
                .append(englishList(patterns))
                .append('.');
        }
        if (lines.length() == 0) {
            return null;
        }
        return "Low cut, EQ, high cut, and presence are not in the MIDI file or the companion SoundFont. "
            + "Invert is not in the MIDI file.\n"
            + lines
            + "\nPlayback and WAV export use them.";
    }

    private static boolean trackHasNotes(Track track, int steps) {
        int limit = Math.min(steps, Track.CAPACITY);
        for (int step = 0; step < limit; step++) {
            if (track.step(step).on()) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when {@code row} produces a note in {@code slot}.
     * A step that is off, past the pattern length, muted, soloed out, at velocity 0,
     * or at chance 0 does not sound.
     */
    private boolean rowSounds(char slot, int row) {
        Track[] tracks = this.slot(slot);
        if (row < 0 || row >= tracks.length) {
            return false;
        }
        Track track = tracks[row];
        if (!audibleIn(tracks, track)) {
            return false;
        }
        int limit = Math.min(stepCount(slot), Track.CAPACITY);
        for (int step = 0; step < limit; step++) {
            Step cell = track.step(step);
            if (!cell.on() || cell.probability() <= 0) {
                continue;
            }
            if (Mix.velocity(cell.velocity(), track.level(), master) > 0) {
                return true;
            }
        }
        return false;
    }

    /** Filters a MIDI file will not carry, in a stable order. */
    private static List<String> droppedFilters(Track track) {
        List<String> filters = new ArrayList<>();
        if (track.hasLowCut()) {
            filters.add("low cut");
        }
        track.collectEqWarnings(filters);
        if (track.hasHighCut()) {
            filters.add("high cut");
        }
        if (track.hasPresence()) {
            filters.add("presence");
        }
        if (track.mode() == TrackMode.SAMPLE && track.sample() != null && track.sample().invert()) {
            filters.add("invert");
        }
        return filters;
    }

    private static String englishList(List<String> items) {
        if (items.size() == 1) {
            return items.get(0);
        }
        if (items.size() == 2) {
            return items.get(0) + " and " + items.get(1);
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                text.append(i + 1 == items.size() ? ", and " : ", ");
            }
            text.append(items.get(i));
        }
        return text.toString();
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        if (name != null && !name.isBlank()) {
            this.name = name.trim();
        }
    }

    /** Tempo in BPM, in steps of {@link Tempo#STEP}. {@code 96} and {@code 92.5} are both exact. */
    public double bpm() {
        return Tempo.fromCenti(centiBpm);
    }

    public void setBpm(double bpm) {
        this.centiBpm = Tempo.centi(bpm);
    }

    /** Whole-number tempo. {@code 92} is stored as {@code 92.00}. */
    public void setBpm(int bpm) {
        setBpm((double) bpm);
    }

    public static int clampBpm(int bpm) {
        return (int) Tempo.fromCenti(Tempo.centi(bpm));
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
     * A new row fills a missing kit piece, Kick first, then a 46 Hz sine named Sub if the beat
     * does not already have one, then an empty drum. Dropped rows leave every pattern.
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
            || hasSlides()
            || hasSoundChoice()
            || hasShape()
            || hasSamples()
            || hasTone()
            || hasStoredEq();
    }

    /** A stored band, high cut, or EQ bypass on any row. Playback may still be plain when EQ is bypassed. */
    public boolean hasStoredEq() {
        for (Track[] slot : slots) {
            for (Track track : slot) {
                if (track.hasEq()) {
                    return true;
                }
            }
        }
        return false;
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

    /**
     * A slide step on a note or sample row, or a slide time or curve other than 80 ms linear.
     * A slide flag on a drum row does not count: drums do not glide, and the file drops it.
     */
    public boolean hasSlides() {
        for (Track[] slot : slots) {
            for (Track track : slot) {
                if (track.slideMs() != Slide.DEFAULT_MS || track.slideCurve() != Slide.Curve.LINEAR) {
                    return true;
                }
                if (Slide.applies(track) && track.slideSteps() > 0) {
                    return true;
                }
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
        int old = slots[0] == null ? 0 : Math.min(trackCount, slots[0].length);
        List<Track> known = new ArrayList<>();
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            Track[] current = slots[slot];
            if (current == null) {
                continue;
            }
            int kept = Math.min(old, Math.min(next, current.length));
            for (int row = 0; row < kept; row++) {
                known.add(current[row]);
            }
        }
        Track[] added = new Track[Math.max(0, next - old)];
        for (int i = 0; i < added.length; i++) {
            Track created = chooseDefault(known, old + i + 1);
            added[i] = created;
            known.add(created);
        }
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            Track[] current = slots[slot];
            Track[] resized = new Track[next];
            for (int row = 0; row < next; row++) {
                if (current != null && row < current.length && row < old) {
                    resized[row] = current[row];
                } else {
                    resized[row] = added[row - old].copy();
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
        List<Track> known = new ArrayList<>();
        for (int i = 0; i < tracks.length; i++) {
            Track row = chooseDefault(known, i + 1);
            tracks[i] = row;
            known.add(row);
        }
    }

    /**
     * The next row, from what {@code present} already contains.
     * Missing kit pieces come first, Kick before Snare and the rest.
     * Sub is next, and only when no row is already a sub.
     * Anything further is an empty drum named Track N, where N is the row number.
     */
    private static Track chooseDefault(List<Track> present, int rowNumber) {
        for (int i = 0; i < TRACKS; i++) {
            if (!kitPresent(present, i)) {
                return accented(new Track(KIT_NAMES[i], TrackMode.DRUM, 0, KIT_NOTES[i]), present);
            }
        }
        if (!subPresent(present)) {
            Track sub = new Track("Sub", TrackMode.SAMPLE, 0, BuiltinSamples.SUB_NOTE);
            sub.setSample(SampleRef.builtin(BuiltinSamples.SUB, BuiltinSamples.SUB_NOTE));
            return accented(sub, present);
        }
        Track drum = new Track(nextTrackName(present, rowNumber), TrackMode.DRUM, 0, 37);
        return accented(drum, present);
    }

    private static boolean kitPresent(List<Track> present, int index) {
        String name = KIT_NAMES[index];
        int note = KIT_NOTES[index];
        for (Track row : present) {
            if (row.mode() != TrackMode.DRUM || row.preset() != null) {
                continue;
            }
            if (name.equals(row.name()) || row.note() == note) {
                return true;
            }
        }
        return false;
    }

    private static boolean subPresent(List<Track> present) {
        for (Track row : present) {
            if ("Sub".equals(row.name())) {
                return true;
            }
            if (row.mode() == TrackMode.SAMPLE && row.sample() != null && BuiltinSamples.SUB.equals(row.sample().path())) {
                return true;
            }
        }
        return false;
    }

    /** {@code Track N} for this row number, or the next free number when that name is taken. */
    private static String nextTrackName(List<Track> present, int rowNumber) {
        int n = Math.max(1, rowNumber);
        String name = "Track " + n;
        while (nameTaken(present, name)) {
            n++;
            name = "Track " + n;
        }
        return name;
    }

    private static boolean nameTaken(List<Track> present, String name) {
        for (Track row : present) {
            if (name.equals(row.name())) {
                return true;
            }
        }
        return false;
    }

    /** Loud-hit value shared by the rows already on the beat, so a new row matches them. */
    private static Track accented(Track track, List<Track> present) {
        if (present.isEmpty()) {
            return track;
        }
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        int best = present.get(0).accent();
        int bestCount = 0;
        for (Track row : present) {
            int count = counts.merge(row.accent(), 1, Integer::sum);
            if (count > bestCount) {
                best = row.accent();
                bestCount = count;
            }
        }
        track.setAccent(best);
        return track;
    }

    private static void copyTracks(Track[] from, Track[] to) {
        int rows = Math.min(from.length, to.length);
        for (int i = 0; i < rows; i++) {
            to[i] = from[i].copy();
        }
    }
}
