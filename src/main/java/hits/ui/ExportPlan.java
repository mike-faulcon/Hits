package hits.ui;

import hits.BeatFiles;
import hits.ExportOptions;

import java.nio.file.Path;
import java.util.Locale;

/** How the export dialog's checkboxes and file name become an {@link ExportOptions} and a path. */
final class ExportPlan {
    private ExportPlan() {}

    static String suggestedStem(String displayName) {
        String stem = BeatFiles.safeName(displayName);
        if (stem.isEmpty()) {
            return "untitled";
        }
        return stem;
    }

    static Path withExtension(Path path, String extension) {
        String suffix = "." + extension;
        if (!path.toString().toLowerCase(Locale.ROOT).endsWith(suffix)) {
            return path.resolveSibling(path.getFileName() + suffix);
        }
        return path;
    }

    /**
     * Song wins over A then B. A song checkbox with no chain is ignored, so A then B still applies.
     */
    static ExportOptions options(boolean asHeard, boolean bothSlots, int repeats, boolean songSelected, boolean hasChain) {
        boolean songChain = songSelected && hasChain;
        return new ExportOptions(asHeard, bothSlots && !songChain, repeats, songChain);
    }

    static String summary(ExportOptions options) {
        String heard = options.asHeard() ? "as heard" : "all tracks";
        if (options.song()) {
            return " (" + heard + ", song)";
        }
        String span = options.bothSlots() ? ", A then B" : "";
        String times = options.repeats() == 1 ? "" : ", " + options.repeats() + " times";
        return " (" + heard + span + times + ")";
    }
}
