package hits;

import java.util.ArrayList;
import java.util.List;

/** A saved beat: tempo, swing, length, eight pattern slots, and an optional song chain. */
public final class Beat {
    public static final int VERSION = 1;
    /**
     * Written when any step stores its own pitch, the beat has a song chain,
     * or a pattern C–H is in use. Version 1 files stay version 1.
     */
    public static final int FORMAT_V2 = 2;
    public static final int TRACKS = 8;
    /** Patterns A through H. */
    public static final int SLOT_COUNT = 8;

    private static final String[] KIT_NAMES = {
        "Kick", "Snare", "Closed Hat", "Open Hat", "Clap", "Tom", "Crash", "Ride"
    };
    private static final int[] KIT_NOTES = {36, 38, 42, 46, 39, 45, 49, 51};

    private String name;
    private int bpm;
    private int swing;
    private int stepCount;
    private char active;
    private final Track[][] slots = new Track[SLOT_COUNT][TRACKS];
    private List<Chain.Part> chain = List.of();

    private Beat(String name) {
        this.name = name == null || name.isBlank() ? "untitled" : name.trim();
        this.bpm = 96;
        this.swing = 50;
        this.stepCount = 16;
        this.active = 'a';
        for (Track[] slot : slots) {
            fillKit(slot);
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
        copy.active = active;
        copy.chain = chain;
        for (int i = 0; i < SLOT_COUNT; i++) {
            copyTracks(slots[i], copy.slots[i]);
        }
        return copy;
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
        this.bpm = Math.max(40, Math.min(240, bpm));
    }

    /** 50 is straight. 75 is a hard shuffle. Applies to every other 16th. */
    public int swing() {
        return swing;
    }

    public void setSwing(int swing) {
        this.swing = Math.max(50, Math.min(75, swing));
    }

    public int stepCount() {
        return stepCount;
    }

    public void setStepCount(int stepCount) {
        this.stepCount = stepCount >= 32 ? 32 : 16;
    }

    public char activeSlot() {
        return active;
    }

    public void setActiveSlot(char slot) {
        this.active = normalizeSlot(slot);
    }

    public Track[] activeTracks() {
        return slot(active);
    }

    public Track[] slot(char slot) {
        return slots[slotIndex(slot)];
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

    /** Copies the first 16 steps of {@code slot} onto its second bar and switches to 32 steps. */
    public void copyBar(char slot) {
        stepCount = 32;
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
        for (int i = 0; i < TRACKS; i++) {
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

    /**
     * True when a step has its own pitch, the song chain is not empty,
     * or a pattern C–H is selected, named in the chain, or different from the empty kit.
     */
    public boolean usesFormatV2() {
        return hasStepPitch() || hasChain() || usesExtraSlots();
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

    /** Patterns C–H whose notes differ from the empty kit, in letter order. */
    List<Character> extraSlots() {
        List<Character> extra = new ArrayList<>();
        for (char slot = 'c'; slot <= 'h'; slot++) {
            if (!matchesKit(slot)) {
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

    public void replaceSlot(char slot, Track[] tracks) {
        Track[] target = slot(slot);
        for (int i = 0; i < TRACKS; i++) {
            target[i] = tracks[i];
        }
    }

    private boolean matchesKit(char slot) {
        Track[] kit = new Track[TRACKS];
        fillKit(kit);
        Track[] current = this.slot(slot);
        for (int i = 0; i < TRACKS; i++) {
            if (!sameTrack(current[i], kit[i])) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameTrack(Track left, Track right) {
        if (!left.name().equals(right.name())
            || left.mode() != right.mode()
            || left.program() != right.program()
            || left.note() != right.note()
            || left.mute() != right.mute()
            || left.solo() != right.solo()
            || left.gate() != right.gate()
            || left.velocity() != right.velocity()
            || left.accent() != right.accent()) {
            return false;
        }
        for (int i = 0; i < Track.CAPACITY; i++) {
            Step a = left.step(i);
            Step b = right.step(i);
            if (a.on() != b.on() || a.velocity() != b.velocity() || a.pitch() != b.pitch()) {
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

    private static void fillKit(Track[] tracks) {
        for (int i = 0; i < TRACKS; i++) {
            tracks[i] = new Track(KIT_NAMES[i], TrackMode.DRUM, 0, KIT_NOTES[i]);
        }
    }

    private static void copyTracks(Track[] from, Track[] to) {
        for (int i = 0; i < TRACKS; i++) {
            to[i] = from[i].copy();
        }
    }
}
