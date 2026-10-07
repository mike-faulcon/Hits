package hits.ui;

/**
 * Which chain entry is selected. The beat does not store this, and undo does not move it.
 * An index past the end is pulled back onto the last entry. An empty chain uses {@code -1}.
 */
final class ChainSelection {
    private int index = -1;

    int index() {
        return index;
    }

    void set(int index) {
        this.index = index;
    }

    void clamp(int size) {
        if (index >= size) {
            index = size - 1;
        }
    }
}
