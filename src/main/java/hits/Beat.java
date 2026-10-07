package hits;

import java.util.ArrayList;
import java.util.List;

/** A saved beat: tempo, swing, length, two pattern slots, and an optional song chain. */
public final class Beat {
    public static final int VERSION = 1;
    /**
     * Written when any step stores its own pitch, or the beat has a song chain.
     * Version 1 files stay version 1.
     */
    public static final int FORMAT_V2 = 2;
    public static final int TRACKS = 8;

    private static final String[] KIT_NAMES = {
        "Kick", "Snare", "Closed Hat", "Open Hat", "Clap", "Tom", "Crash", "Ride"
    };
    private static final int[] KIT_NOTES = {36, 38, 42, 46, 39, 45, 49, 51};

    private String name;
    private int bpm;
    private int swing;
    private int stepCount;
    private char active;
    private final Track[] slotA = new Track[TRACKS];
    private final Track[] slotB = new Track[TRACKS];
    private List<Chain.Part> chain = List.of();

    private Beat(String name) {
        this.name = name == null || name.isBlank() ? "untitled" : name.trim();
        this.bpm = 96;
        this.swing = 50;
        this.stepCount = 16;
        this.active = 'a';
    }

    public static Beat drumKit(String name) {
        Beat beat = new Beat(name);
        fillKit(beat.slotA);
        fillKit(beat.slotB);
        return beat;
    }

    public Beat copy() {
        Beat copy = new Beat(name);
        copy.bpm = bpm;
        copy.swing = swing;
        copy.stepCount = stepCount;
        copy.active = active;
        copy.chain = chain;
        copyTracks(slotA, copy.slotA);
        copyTracks(slotB, copy.slotB);
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
        this.active = slot == 'b' ? 'b' : 'a';
    }

    public Track[] activeTracks() {
        return active == 'b' ? slotB : slotA;
    }

    public Track[] slot(char slot) {
        return slot == 'b' ? slotB : slotA;
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

    /** Copies {@code slot} onto the other pattern. The active slot stays put. */
    public void copySlotToOther(char slot) {
        char from = slot == 'b' ? 'b' : 'a';
        Track[] source = this.slot(from);
        Track[] target = from == 'a' ? slotB : slotA;
        for (int i = 0; i < TRACKS; i++) {
            target[i] = source[i].copy();
        }
    }

    public boolean hasStepPitch() {
        return pitched(slotA) || pitched(slotB);
    }

    /** True when a step has its own pitch or the song chain is not empty. */
    public boolean usesFormatV2() {
        return hasStepPitch() || hasChain();
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
