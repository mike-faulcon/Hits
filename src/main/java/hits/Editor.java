package hits;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Undo history and selection around a {@link Beat}. The UI listens; it does not own the pattern. */
public final class Editor {
    public interface Listener {
        void changed(boolean musical);
    }

    private Beat beat;
    private int trackIndex;
    private int stepIndex = -1;
    private boolean dirty;
    private boolean adjusting;
    /** Playback follows the chain. Not stored in the beat file. */
    private boolean songMode;
    /** The grid is held on one slot while a song plays, instead of following it. */
    private boolean holding;
    /** Slot the grid is showing while a song plays. Ignored until a song sets it. */
    private boolean playingKnown;
    private char playingSlot = 'a';
    private final ArrayDeque<Beat> undo = new ArrayDeque<>();
    private final ArrayDeque<Beat> redo = new ArrayDeque<>();
    private final List<Listener> listeners = new ArrayList<>();

    public Editor(Beat beat) {
        this.beat = beat;
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public Beat beat() {
        return beat;
    }

    public int trackIndex() {
        return trackIndex;
    }

    public int stepIndex() {
        return stepIndex;
    }

    public Track selectedTrack() {
        return viewTrack(trackIndex);
    }

    public boolean songMode() {
        return songMode;
    }

    public boolean isAdjusting() {
        return adjusting;
    }

    /** True while the grid is held on a slot instead of following the song. */
    public boolean holding() {
        return songMode && holding;
    }

    /**
     * Pattern mode shows the saved slot. Song playback shows the slot that is sounding,
     * unless the grid is held on a slot the user asked to see.
     */
    public char viewSlot() {
        if (songMode && (holding || playingKnown)) {
            return playingSlot;
        }
        return beat.activeSlot();
    }

    public Track[] viewTracks() {
        return beat.slot(viewSlot());
    }

    public Track viewTrack(int index) {
        return viewTracks()[index];
    }

    public void setSongMode(boolean song) {
        if (songMode == song) {
            return;
        }
        songMode = song;
        holding = false;
        playingKnown = false;
        fire(false);
    }

    /** Follow the chain again after the grid was held on one pattern. */
    public void followAgain() {
        if (!holding) {
            return;
        }
        holding = false;
        fire(false);
    }

    /** Keep the grid on {@code slot} while the song continues. Does not mark the beat dirty. */
    public void holdSlot(char slot) {
        holding = true;
        playingKnown = true;
        playingSlot = Beat.normalizeSlot(slot);
        fire(false);
    }

    /**
     * Point the grid at the slot that is playing. No undo entry and no dirty flag.
     * A held slot, or pattern mode, is left alone.
     */
    public void showPlayingSlot(char slot) {
        if (!songMode || holding) {
            return;
        }
        char next = Beat.normalizeSlot(slot);
        if (playingKnown && playingSlot == next) {
            return;
        }
        playingKnown = true;
        playingSlot = next;
        fire(false);
    }

    /** Drop the song view so the grid returns to the saved pattern. */
    public void stopped() {
        if (!playingKnown && !holding) {
            return;
        }
        playingKnown = false;
        holding = false;
        fire(false);
    }

    public boolean isDirty() {
        return dirty;
    }

    public void markClean() {
        dirty = false;
    }

    public void selectTrack(int index) {
        if (index < 0 || index >= beat.trackCount()) {
            return;
        }
        trackIndex = index;
        stepIndex = -1;
        fire(false);
    }

    /** Appends a row on every pattern and selects it. One undo step. No-op at 16 rows. */
    public void addRow() {
        if (beat.trackCount() >= Beat.MAX_TRACKS) {
            return;
        }
        int next = beat.trackCount();
        edit(Beat::addTrack);
        trackIndex = Math.min(next, beat.trackCount() - 1);
        stepIndex = -1;
        fire(false);
    }

    /** Drops the selected row from every pattern. One undo step. No-op when it is the only row. */
    public void removeRow() {
        int index = trackIndex;
        if (beat.trackCount() <= Beat.MIN_TRACKS) {
            return;
        }
        edit(current -> current.removeTrack(index));
        if (trackIndex > index) {
            trackIndex--;
        }
        if (trackIndex >= beat.trackCount()) {
            trackIndex = beat.trackCount() - 1;
        }
        stepIndex = -1;
        fire(false);
    }

    /** Copies the selected row onto the next row, in every pattern. One undo step. */
    public void duplicateRow() {
        int index = trackIndex;
        if (beat.trackCount() >= Beat.MAX_TRACKS) {
            return;
        }
        edit(current -> current.duplicateTrack(index));
        trackIndex = Math.min(index + 1, beat.trackCount() - 1);
        stepIndex = -1;
        fire(false);
    }

    /** Moves the selected row by {@code delta} positions in every pattern. One undo step. */
    public void moveRow(int delta) {
        int index = trackIndex;
        int target = index + delta;
        if (target < 0 || target >= beat.trackCount()) {
            return;
        }
        edit(current -> current.moveTrack(index, delta));
        trackIndex = target;
        stepIndex = -1;
        fire(false);
    }

    public void selectStep(int index) {
        stepIndex = index;
        fire(false);
    }

    /** Points the velocity slider at a step without toggling it. */
    public void selectStep(int row, int column) {
        if (row < 0 || row >= beat.trackCount() || column < 0 || column >= beat.gridColumns(viewSlot())) {
            return;
        }
        trackIndex = row;
        stepIndex = column;
        fire(false);
    }

    /** Sets the play probability of the selected step. No-op when no step is selected. */
    public void setStepProbability(int probability) {
        int column = stepIndex;
        if (column < 0 || column >= beat.gridColumns(viewSlot())) {
            return;
        }
        int row = trackIndex;
        char slot = viewSlot();
        edit(current -> current.slot(slot)[row].step(column).setProbability(probability));
    }

    /** Sets how many steps the selected hit lasts. No-op when no step is selected. */
    public void setStepLength(int length) {
        int column = stepIndex;
        if (column < 0 || column >= beat.gridColumns(viewSlot())) {
            return;
        }
        int row = trackIndex;
        char slot = viewSlot();
        edit(current -> current.slot(slot)[row].step(column).setLength(length));
    }

    /** Moves the selected step off the grid by one percent. No-op when no step is selected. */
    public void bumpStepNudge(int delta) {
        int column = stepIndex;
        if (column < 0 || column >= beat.gridColumns(viewSlot())) {
            return;
        }
        int row = trackIndex;
        char slot = viewSlot();
        edit(current -> {
            Step step = current.slot(slot)[row].step(column);
            step.setNudge(step.nudge() + delta);
        });
    }

    /** Sets the selected step's nudge in percent of a step. No-op when no step is selected. */
    public void setStepNudge(int nudge) {
        int column = stepIndex;
        if (column < 0 || column >= beat.gridColumns(viewSlot())) {
            return;
        }
        int row = trackIndex;
        char slot = viewSlot();
        edit(current -> current.slot(slot)[row].step(column).setNudge(nudge));
    }

    /** Note-track legato for the row on screen. One undo step. */
    public void setLegato(boolean legato) {
        int row = trackIndex;
        char slot = viewSlot();
        edit(current -> current.slot(slot)[row].setLegato(legato));
    }

    /** Track shift, in percent of a step, for the row on screen. One undo step. */
    public void setShift(int shift) {
        int row = trackIndex;
        char slot = viewSlot();
        edit(current -> current.slot(slot)[row].setShift(shift));
    }

    public void tap(int row, int column, boolean accent) {
        if (row < 0 || row >= beat.trackCount() || column < 0 || column >= beat.gridColumns(viewSlot())) {
            return;
        }
        trackIndex = row;
        stepIndex = column;
        char slot = viewSlot();
        edit(current -> current.slot(slot)[row].tap(column, accent));
    }

    public void toggleMute(int row) {
        if (row < 0 || row >= beat.trackCount()) {
            return;
        }
        trackIndex = row;
        char slot = viewSlot();
        edit(current -> {
            Track selected = current.slot(slot)[row];
            selected.setMute(!selected.mute());
        });
    }

    public void toggleSolo(int row) {
        if (row < 0 || row >= beat.trackCount()) {
            return;
        }
        trackIndex = row;
        char slot = viewSlot();
        edit(current -> {
            Track selected = current.slot(slot)[row];
            selected.setSolo(!selected.solo());
        });
    }

    public void edit(Consumer<Beat> action) {
        pushUndo();
        redo.clear();
        adjusting = false;
        action.accept(beat);
        dirty = true;
        fire(true);
    }

    public void beginAdjust() {
        if (adjusting) {
            return;
        }
        adjusting = true;
        pushUndo();
        redo.clear();
    }

    public void adjust(Consumer<Beat> action) {
        action.accept(beat);
        dirty = true;
        fire(true);
    }

    public void endAdjust() {
        if (!adjusting) {
            return;
        }
        adjusting = false;
        fire(false);
    }

    public void undo() {
        if (undo.isEmpty()) {
            return;
        }
        redo.addFirst(beat.copy());
        beat = undo.removeFirst();
        dirty = true;
        fire(true);
    }

    public void redo() {
        if (redo.isEmpty()) {
            return;
        }
        undo.addFirst(beat.copy());
        beat = redo.removeFirst();
        dirty = true;
        fire(true);
    }

    public void replace(Beat next, boolean dirty) {
        undo.clear();
        redo.clear();
        adjusting = false;
        beat = next;
        trackIndex = 0;
        stepIndex = -1;
        holding = false;
        playingKnown = false;
        this.dirty = dirty;
        fire(true);
    }

    private void pushUndo() {
        undo.addFirst(beat.copy());
        while (undo.size() > 50) {
            undo.removeLast();
        }
    }

    private void fire(boolean musical) {
        clampSelection();
        for (Listener listener : List.copyOf(listeners)) {
            listener.changed(musical);
        }
    }

    /** Keeps the selection on a row that still exists after undo, remove, or a shorter file. */
    private void clampSelection() {
        int count = Math.max(Beat.MIN_TRACKS, beat.trackCount());
        if (trackIndex >= count) {
            trackIndex = count - 1;
        }
        if (trackIndex < 0) {
            trackIndex = 0;
        }
        if (stepIndex >= beat.gridColumns(viewSlot())) {
            stepIndex = -1;
        }
    }
}
