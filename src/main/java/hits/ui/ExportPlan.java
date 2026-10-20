package hits.ui;

import hits.BeatFiles;
import hits.ExportOptions;

import java.nio.file.Path;
import java.util.Locale;

/** How export choices become an {@link ExportOptions}, a path, and a drag file name. */
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
     * The ignore box is unchecked by default, so the file matches playback.
     * Song wins over A then B. A song checkbox with no chain is ignored, so A then B still applies.
     */
    static ExportOptions fromDialog(boolean ignoreMuteSolo, boolean bothSlots, int repeats, boolean songSelected, boolean hasChain) {
        return options(!ignoreMuteSolo, bothSlots, repeats, songSelected, hasChain);
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

    /**
     * What a drag writes. Song mode with a chain writes the song, so A then B and repeats do not apply.
     * Otherwise the active pattern uses the current export choices. A song choice with no chain is ignored.
     */
    static ExportOptions dragOptions(boolean asHeard, boolean bothSlots, int repeats, boolean songMode, boolean hasChain) {
        if (songMode && hasChain) {
            return new ExportOptions(asHeard, false, 1, true);
        }
        return options(asHeard, bothSlots, repeats, false, hasChain);
    }

    /**
     * File name for a drag. The stem matches Save. A qualifier says which material is in the file:
     * {@code -A} or {@code -B}, {@code -song}, {@code -A-then-B}, {@code -x3}, and {@code -as-heard}.
     */
    static String dragFileName(String displayName, ExportOptions options, char activeSlot, String extension) {
        StringBuilder name = new StringBuilder(suggestedStem(displayName));
        if (options.song()) {
            name.append("-song");
        } else if (options.bothSlots()) {
            name.append("-A-then-B");
        } else {
            name.append('-').append(activeSlot == 'b' ? 'B' : 'A');
        }
        if (!options.song() && options.repeats() > 1) {
            name.append("-x").append(options.repeats());
        }
        if (options.asHeard()) {
            name.append("-as-heard");
        }
        String suffix = extension == null ? "" : extension.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (!suffix.isEmpty()) {
            name.append('.').append(suffix);
        }
        return name.toString();
    }
}
