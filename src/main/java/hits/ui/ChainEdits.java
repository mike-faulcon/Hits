package hits.ui;

import hits.Chain;
import hits.Editor;

/**
 * Chain edits from the song row. Append and move select the entry before the edit is recorded,
 * so a refresh that runs from the editor listener already shows the new selection.
 * Remove updates the selection after the edit, matching the row's previous order.
 */
final class ChainEdits {
    private final Editor editor;
    private final ChainSelection selection = new ChainSelection();

    ChainEdits(Editor editor) {
        this.editor = editor;
    }

    int index() {
        return selection.index();
    }

    void select(int index) {
        selection.set(index);
    }

    void clamp(int size) {
        selection.clamp(size);
    }

    boolean selected() {
        int size = editor.beat().chain().size();
        return selection.index() >= 0 && selection.index() < size;
    }

    boolean canAdd() {
        return editor.beat().chain().size() < Chain.MAX_PARTS;
    }

    boolean canMoveUp() {
        return selected() && selection.index() > 0;
    }

    boolean canMoveDown() {
        return selected() && selection.index() + 1 < editor.beat().chain().size();
    }

    /** Status text. The chain is left unchanged when it is already full. */
    String append(char slot) {
        if (editor.beat().chain().size() >= Chain.MAX_PARTS) {
            return ChainLabels.full();
        }
        selection.set(editor.beat().chain().size());
        editor.edit(beat -> beat.addChain(slot));
        return ChainLabels.added(slot);
    }

    boolean remove() {
        int index = selection.index();
        if (index < 0 || index >= editor.beat().chain().size()) {
            return false;
        }
        editor.edit(beat -> beat.removeChain(index));
        selection.set(Math.min(selection.index(), editor.beat().chain().size() - 1));
        return true;
    }

    boolean move(int delta) {
        int index = selection.index();
        int target = index + delta;
        if (index < 0 || target < 0 || target >= editor.beat().chain().size()) {
            return false;
        }
        selection.set(target);
        editor.edit(beat -> beat.moveChain(index, delta));
        return true;
    }

    void setRepeats(int repeats) {
        int index = selection.index();
        if (index < 0 || index >= editor.beat().chain().size()) {
            return;
        }
        if (editor.beat().chain().get(index).repeats() == repeats) {
            return;
        }
        editor.edit(beat -> beat.setChainRepeats(index, repeats));
    }
}
