package hits;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LaunchPathsTest {
    @Test
    void packagedPropertyIsTheLauncherFlag() {
        assertEquals("hits.packaged", LaunchPaths.PACKAGED_PROPERTY);
        String previous = System.getProperty(LaunchPaths.PACKAGED_PROPERTY);
        try {
            System.setProperty(LaunchPaths.PACKAGED_PROPERTY, "true");
            assertTrue(LaunchPaths.packaged());
            System.setProperty(LaunchPaths.PACKAGED_PROPERTY, "false");
            assertFalse(LaunchPaths.packaged());
        } finally {
            if (previous == null) {
                System.clearProperty(LaunchPaths.PACKAGED_PROPERTY);
            } else {
                System.setProperty(LaunchPaths.PACKAGED_PROPERTY, previous);
            }
        }
    }

    @Test
    void developmentLaunchUsesTheWorkingDirectory() {
        Path project = Path.of("/tmp/hits-project");
        assertEquals(project, LaunchPaths.baseDirectory(false, "/home/musician", project));
        assertEquals(project.resolve("beats"), BeatFolders.resolve(null, LaunchPaths.baseDirectory(false, "/home/musician", project)));
    }

    @Test
    void packagedLaunchUsesHitsInTheHomeDirectory(@TempDir Path home) {
        Path base = LaunchPaths.baseDirectory(true, home.toString(), Path.of("/opt/hits"));
        assertEquals(home.resolve("Hits"), base);
        assertEquals(home.resolve("Hits").resolve("beats"), BeatFolders.resolve("  ", base));
        assertEquals(home.resolve("Hits"), LaunchPaths.baseDirectory(true, "  ", home));
    }

    @Test
    void defaultFolderIsTheResolvedBeatsDirectory(@TempDir Path home) {
        Path base = home.resolve("Hits");
        Path beats = base.resolve("beats");
        assertTrue(LaunchPaths.isDefaultFolder(null, beats, base));
        assertTrue(LaunchPaths.isDefaultFolder("  ", beats, base));
        assertTrue(LaunchPaths.isDefaultFolder(beats.toString(), beats, base));
        assertFalse(LaunchPaths.isDefaultFolder(home.resolve("other").toString(), home.resolve("other"), base));
        assertFalse(LaunchPaths.isDefaultFolder("kept", null, base));
    }

    @Test
    void bundledSamplesSitBesideTheApplicationJar(@TempDir Path appDir) {
        Path jar = appDir.resolve("Hits-2.0.jar");
        assertEquals(appDir.resolve("beats"), LaunchPaths.bundledSamples(jar));
        assertEquals(appDir.resolve("beats"), LaunchPaths.bundledSamples(appDir));
        assertNull(LaunchPaths.bundledSamples(null));
    }

    @Test
    void emptyFolderReceivesSampleBeats(@TempDir Path root) throws Exception {
        Path bundled = root.resolve("bundled");
        Files.createDirectories(bundled);
        Files.writeString(bundled.resolve("raw.btf"), "raw");
        Files.writeString(bundled.resolve("notes.json"), "{}");
        Files.writeString(bundled.resolve("readme.txt"), "skip");
        Files.createDirectories(bundled.resolve("nested.btf"));

        Path destination = root.resolve("Hits").resolve("beats");
        LaunchPaths.ensureSamples(destination, bundled);

        assertEquals("raw", Files.readString(destination.resolve("raw.btf")));
        assertEquals("{}", Files.readString(destination.resolve("notes.json")));
        assertFalse(Files.exists(destination.resolve("readme.txt")));
        assertFalse(Files.exists(destination.resolve("nested.btf")));

        Files.writeString(bundled.resolve("raw.btf"), "changed");
        Files.writeString(destination.resolve("mine.json"), "{\"name\":\"mine\"}");
        LaunchPaths.ensureSamples(destination, bundled);
        assertEquals("raw", Files.readString(destination.resolve("raw.btf")));
        assertEquals("{\"name\":\"mine\"}", Files.readString(destination.resolve("mine.json")));
    }

    @Test
    void missingSamplesStillCreateTheFolder(@TempDir Path root) throws Exception {
        Path destination = root.resolve("Hits").resolve("beats");
        LaunchPaths.ensureSamples(destination, root.resolve("gone"));
        assertTrue(Files.isDirectory(destination));
        try (var stream = Files.list(destination)) {
            assertEquals(0, stream.count());
        }
        assertThrows(java.io.IOException.class, () -> LaunchPaths.ensureSamples(null, null));
    }
}
