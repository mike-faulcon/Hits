package hits;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The 2001 .btf file: 8×16 on/off bytes, then eight pairs of instrument index and MIDI note.
 * Instrument indexes on the machine that wrote these files match General MIDI program numbers.
 */
public final class BtfFormat {
    public static final int COLS = 16;
    public static final int SIZE = Beat.TRACKS * COLS + Beat.TRACKS * 2;

    private BtfFormat() {}

    public static Beat read(Path path) throws IOException {
        String filename = path.getFileName().toString();
        String name = filename.toLowerCase().endsWith(".btf")
            ? filename.substring(0, filename.length() - 4)
            : filename;
        return read(Files.readAllBytes(path), name);
    }

    public static Beat read(byte[] data, String name) {
        if (data.length < SIZE) {
            throw new IllegalArgumentException("Beat file is too short (" + data.length + " bytes)");
        }
        Beat beat = Beat.drumKit(name);
        beat.setBpm(120);
        beat.setSwing(50);
        beat.setStepCount(16);
        Track[] tracks = beat.slot('a');
        int index = 0;
        for (int row = 0; row < Beat.TRACKS; row++) {
            for (int col = 0; col < COLS; col++) {
                int value = data[index++] & 0xff;
                tracks[row].step(col).setOn(value != 0);
                tracks[row].step(col).setVelocity(100);
            }
        }
        for (int row = 0; row < Beat.TRACKS; row++) {
            int program = data[index++] & 0xff;
            int note = data[index++] & 0xff;
            Track track = tracks[row];
            track.setMode(TrackMode.NOTE);
            track.setProgram(program > 127 ? program % 128 : program);
            track.setNote(note > 127 ? 60 : note);
            track.setGate(100);
            track.setVelocity(100);
            track.setAccent(120);
            track.setMute(false);
            track.setSolo(false);
            track.setName(Gm.programName(track.program()));
        }
        beat.copyActiveToOther();
        return beat;
    }
}
