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
        return beat.track(trackIndex);
    }

    public boolean isDirty() {
        return dirty;
    }

    public void markClean() {
        dirty = false;
    }

    public void selectTrack(int index) {
        if (index < 0 || index >= Beat.TRACKS) {
            return;
        }
        trackIndex = index;
        stepIndex = -1;
        fire(false);
    }

    public void selectStep(int index) {
        stepIndex = index;
        fire(false);
    }

    public void tap(int row, int column, boolean accent) {
        if (row < 0 || row >= Beat.TRACKS || column < 0 || column >= beat.stepCount()) {
            return;
        }
        trackIndex = row;
        stepIndex = column;
        edit(current -> current.track(row).tap(column, accent));
    }

    public void toggleMute(int row) {
        trackIndex = row;
        edit(current -> {
            Track selected = current.track(row);
            selected.setMute(!selected.mute());
        });
    }

    public void toggleSolo(int row) {
        trackIndex = row;
        edit(current -> {
            Track selected = current.track(row);
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
        adjusting = false;
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
        for (Listener listener : List.copyOf(listeners)) {
            listener.changed(musical);
        }
    }
}
