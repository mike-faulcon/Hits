package hits.ui;

import hits.Chain;

/** Text for chain buttons and the status line. Pattern letters are {@code A} through {@code H}. */
final class ChainLabels {
    private ChainLabels() {}

    static String slotName(char slot) {
        return hits.Beat.slotLabel(slot);
    }

    static String hold(char slot) {
        return "Holding pattern " + slotName(slot) + ". Click Song to follow the chain.";
    }

    /** {@code C ·1} is one bar. {@code C×3 ·2} repeats a two-bar pattern. A length that is not a whole bar shows the step count. */
    static String partText(Chain.Part part, int steps) {
        String name = slotName(part.slot());
        String body = part.repeats() == 1 ? name : name + "×" + part.repeats();
        return body + " " + barMark(steps);
    }

    static String partTip(Chain.Part part, int steps) {
        int length = hits.Beat.clampSteps(steps);
        String name = slotName(part.slot());
        String size = length + (length == 1 ? " step" : " steps");
        if (length % 16 == 0) {
            int bars = length / 16;
            size = size + " (" + bars + (bars == 1 ? " bar" : " bars") + ")";
        }
        String times = part.repeats() == 1 ? "once" : part.repeats() + " times";
        return name + " is " + size + " and plays " + times + ". Click to select.";
    }

    private static String barMark(int steps) {
        int length = hits.Beat.clampSteps(steps);
        if (length % 16 == 0) {
            return "·" + (length / 16);
        }
        return "·" + length;
    }

    static String added(char slot) {
        return "Added " + slotName(slot) + " to the chain";
    }

    static String full() {
        return "The chain holds " + Chain.MAX_PARTS + " entries";
    }

    static String playingEmpty(char slot) {
        return "The chain is empty. Playing pattern " + slotName(slot) + ".";
    }

    static String following() {
        return "Following the song";
    }

    static String needEntry() {
        return "Add a pattern to the chain";
    }
}
