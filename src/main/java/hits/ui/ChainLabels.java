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

    static String partText(Chain.Part part) {
        String name = slotName(part.slot());
        return part.repeats() == 1 ? name : name + "×" + part.repeats();
    }

    static String partTip(Chain.Part part) {
        String name = slotName(part.slot());
        return name + " plays " + part.repeats() + (part.repeats() == 1 ? " time" : " times") + ". Click to select.";
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
