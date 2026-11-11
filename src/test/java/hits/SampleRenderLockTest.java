package hits;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * A beat that uses a file sample and a built-in sample.
 * The JSON, MIDI, WAV, and SoundFont were written before the bounded loader.
 */
class SampleRenderLockTest {
    private static final Path DIR = Path.of("src/test/resources/fixtures/sample-lock");

    @TempDir
    Path temp;

    @Test
    void sampleBeatLoadsSavesAndRendersTheSameBytes() throws Exception {
        Path beatFile = DIR.resolve("take.json");
        byte[] json = Files.readAllBytes(beatFile);
        Beat beat = BeatFiles.load(beatFile);
        assertArrayEquals(json, BeatJson.write(beat).getBytes(StandardCharsets.UTF_8));

        Path midi = temp.resolve("take.mid");
        BeatFiles.exportMidi(beat, midi, ExportOptions.matchingPlayback(), beatFile, true);
        assertArrayEquals(Files.readAllBytes(DIR.resolve("take.mid")), Files.readAllBytes(midi));
        assertArrayEquals(
            Files.readAllBytes(DIR.resolve("take-samples.sf2")),
            Files.readAllBytes(BeatFiles.samplesFont(midi))
        );

        Path wav = temp.resolve("take.wav");
        try {
            BeatFiles.exportWav(
                beat,
                wav,
                ExportOptions.matchingPlayback(),
                null,
                null,
                () -> false,
                beatFile
            );
        } catch (Exception exception) {
            if (WavRenderer.unavailable(exception)) {
                Assumptions.abort("offline render is not available: " + exception.getMessage());
            }
            throw exception;
        }
        assertArrayEquals(Files.readAllBytes(DIR.resolve("take.wav")), Files.readAllBytes(wav));
    }
}
