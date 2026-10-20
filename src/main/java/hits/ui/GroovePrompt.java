package hits.ui;

import hits.GrooveGenerator;

/** Wording for Make me a beat and Shuffle. */
final class GroovePrompt {
    private GroovePrompt() {}

    static String makeBeat(char slot, boolean occupied) {
        String name = ChainLabels.slotName(slot);
        String tempo = "Tempo and swing change for the whole beat, not just this pattern.";
        if (occupied) {
            return "Pattern " + name + " already has hits. Pick a style to replace them. "
                + tempo + " The other patterns' hits stay. Undo brings this pattern back.";
        }
        return "Pick a style. A starter groove lands on pattern " + name + ". " + tempo;
    }

    static String groove(GrooveGenerator.Genre genre, char slot) {
        return genre.label() + " groove on pattern " + ChainLabels.slotName(slot);
    }

    static String shuffled(String track) {
        String name = track == null || track.isBlank() ? "track" : track.trim();
        return "Shuffled " + name;
    }
}
