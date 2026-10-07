package hits.ui;

import hits.BeatFolders;

import java.nio.file.Path;

/** The folder Load and Save use. The absolute path is remembered. */
final class BeatsFolder {
    private Path directory;

    BeatsFolder() {
        String saved = AppPreferences.get(BeatFolders.PREF_DIRECTORY);
        directory = BeatFolders.resolve(saved, Path.of("").toAbsolutePath());
        String absolute = BeatFolders.remember(directory);
        if (!absolute.equals(saved)) {
            AppPreferences.put(BeatFolders.PREF_DIRECTORY, absolute);
        }
    }

    Path get() {
        return directory;
    }

    void choose(Path folder) {
        directory = folder.toAbsolutePath().normalize();
        AppPreferences.put(BeatFolders.PREF_DIRECTORY, BeatFolders.remember(directory));
    }
}
