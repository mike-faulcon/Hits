package hits.ui;

import hits.BeatFiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/**
 * Which file the open beat belongs to, plus the save-prompt text.
 * A {@code .btf} load is not a saved file: the next Save writes {@code .json} beside it.
 */
final class SaveSession {
    private Path savedFile;

    Path file() {
        return savedFile;
    }

    void forget() {
        savedFile = null;
    }

    void rememberWritten(Path path) {
        savedFile = path.toAbsolutePath().normalize();
    }

    void loaded(Path path) {
        String filename = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (filename.endsWith(".json")) {
            rememberWritten(path);
        } else {
            savedFile = null;
        }
    }

    static String nameTooltip(String displayName) {
        String safe = BeatFiles.safeName(displayName);
        if (safe.isEmpty()) {
            return "Add a letter or number. This name cannot be a file yet.";
        }
        return "Name stored in the file. Saves as " + safe + ".json";
    }

    static String windowTitle(String name, boolean dirty, boolean midiOpen) {
        StringBuilder title = new StringBuilder("Hits — ");
        title.append(name);
        if (dirty) {
            title.append(" •");
        }
        if (!midiOpen) {
            title.append(" — MIDI unavailable");
        }
        return title.toString();
    }

    static String saveAsCaption(String typed, Path beatsDirectory) {
        String display = typed == null ? "" : typed.trim();
        String safe = BeatFiles.safeName(display);
        if (safe.isEmpty()) {
            return "Add a letter or number. This name cannot be a file.";
        }
        boolean exists = Files.exists(beatsDirectory.resolve(safe + ".json"));
        String kept = safe.equals(display) ? "" : "  Name in the file stays \"" + display + "\".";
        if (exists) {
            return "Replaces " + safe + ".json." + kept;
        }
        return "File: " + safe + ".json." + kept;
    }

    static String confirmMessage(BeatFiles.SaveChoice choice, String safe, String display) {
        String message = choice == BeatFiles.SaveChoice.CONFIRM_REPLACE
            ? "Replace " + safe + ".json?"
            : "Save as " + safe + ".json?";
        if (!safe.equals(display)) {
            message += "\nThe name stored in the file stays \"" + display + "\".";
        }
        return message;
    }

    static String[] confirmOptions(BeatFiles.SaveChoice choice) {
        if (choice == BeatFiles.SaveChoice.CONFIRM_REPLACE) {
            return new String[]{"Replace", "Cancel"};
        }
        return new String[]{"Save", "Cancel"};
    }

    /** Answer from the unsaved-changes dialog. Anything other than Save or Don't Save cancels. */
    enum Discard {
        SAVE, DISCARD, CANCEL
    }

    static Discard choice(int dialogResult) {
        if (dialogResult == 0) {
            return Discard.SAVE;
        }
        if (dialogResult == 1) {
            return Discard.DISCARD;
        }
        return Discard.CANCEL;
    }

    static boolean proceed(boolean dirty, Discard choice, BooleanSupplier save) {
        if (!dirty) {
            return true;
        }
        if (choice == Discard.SAVE) {
            return save.getAsBoolean();
        }
        return choice == Discard.DISCARD;
    }
}
