package hits.ui;

import hits.BeatFolders;
import hits.LaunchPaths;

import java.io.IOException;
import java.nio.file.Path;

/** The folder Load and Save use. The absolute path is remembered. */
final class BeatsFolder {
    private Path directory;

    BeatsFolder() {
        String saved = AppPreferences.get(BeatFolders.PREF_DIRECTORY);
        Path base = LaunchPaths.baseDirectory();
        directory = BeatFolders.resolve(saved, base);
        if (LaunchPaths.packaged() && LaunchPaths.isDefaultFolder(saved, directory, base)) {
            try {
                LaunchPaths.ensureSamples(directory, LaunchPaths.bundledSamples(LaunchPaths.codeSource(HitsApp.class)));
            } catch (IOException exception) {
                // The folder chooser still works if the samples cannot be copied.
            }
        }
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
