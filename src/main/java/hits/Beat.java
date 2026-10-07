package hits;

/** A saved beat: tempo, swing, length, and two pattern slots. */
public final class Beat {
    public static final int VERSION = 1;
    /** Written when any step stores its own pitch. Version 1 files stay version 1. */
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

    /** Copies the first 16 steps onto the second bar and switches to 32 steps. */
    public void copyBar() {
        stepCount = 32;
        for (Track track : activeTracks()) {
            for (int i = 0; i < 16; i++) {
                track.step(16 + i).copyFrom(track.step(i));
            }
        }
    }

    public void copyActiveToOther() {
        Track[] from = activeTracks();
        Track[] to = active == 'a' ? slotB : slotA;
        for (int i = 0; i < TRACKS; i++) {
            to[i] = from[i].copy();
        }
    }

    public boolean hasStepPitch() {
        return pitched(slotA) || pitched(slotB);
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
