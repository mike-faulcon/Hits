package hits;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Receiver;
import javax.sound.midi.Sequencer;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Soundbank;
import javax.sound.midi.Synthesizer;
import javax.sound.midi.Transmitter;
import javax.sound.midi.MidiSystem;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Desktop playback through javax.sound.midi. Web and mobile would play the same document another way. */
public final class Player implements AutoCloseable {
    private Sequencer sequencer;
    private Transmitter transmitter;
    private Synthesizer synthesizer;
    private MidiDevice external;
    private Receiver receiver;
    private String outputId = MidiOutputs.BUILTIN_ID;
    private String outputLabel = MidiOutputs.BUILTIN_LABEL;
    private String failure;
    private Soundbank defaultBank;
    private Soundbank loadedBank;
    private boolean song;
    private Path soundFont;
    private String soundFontName;
    private String soundFontNote;
    private ScheduledFuture<?> noteOff;
    private final Object midiLock = new Object();
    private final ScheduledExecutorService audition = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "hits-audition");
        thread.setDaemon(true);
        return thread;
    });

    public void open() {
        if (!ensureSequencer()) {
            return;
        }
        if (receiver == null) {
            useOutput(outputId);
        }
    }

    public boolean isOpen() {
        return sequencer != null && sequencer.isOpen() && receiver != null;
    }

    public String failure() {
        return failure;
    }

    public String outputId() {
        return outputId;
    }

    public String outputLabel() {
        return outputLabel;
    }

    public boolean isBuiltIn() {
        return synthesizer != null && synthesizer.isOpen();
    }

    /** Why the last SoundFont did not load, or null. */
    public String soundFontNote() {
        return soundFontNote;
    }

    /** Absolute path of the bank currently loaded on the built-in synth. */
    public Path soundFont() {
        return loadedBank == null || soundFont == null ? null : soundFont;
    }

    public String soundFontName() {
        return soundFontName == null ? "" : soundFontName;
    }

    /**
     * Points the open sequencer at a MIDI output. The built-in synth is {@link MidiOutputs#BUILTIN_ID}.
     * A failure leaves the previous output connected.
     */
    public boolean useOutput(String requested) {
        String id = requested == null || requested.isBlank() ? MidiOutputs.BUILTIN_ID : requested;
        if (!ensureSequencer()) {
            return false;
        }
        if (isOpen() && id.equals(outputId)) {
            return true;
        }
        boolean wasPlaying = isPlaying();
        if (wasPlaying) {
            sequencer.stop();
        }
        try {
            Opened opened = openDevice(id);
            switchTo(opened, id);
            if (wasPlaying) {
                sequencer.start();
            }
            return true;
        } catch (MidiUnavailableException exception) {
            failure = exception.getMessage() == null ? "MIDI output is unavailable" : exception.getMessage();
            if (wasPlaying && isOpen()) {
                sequencer.start();
            }
            return false;
        }
    }

    public String loadSoundFont(Path path) throws IOException, InvalidMidiDataException {
        if (!isBuiltIn()) {
            throw new IllegalStateException(SoundFonts.NEEDS_BUILTIN);
        }
        Soundbank bank = SoundFonts.read(path);
        Soundbank previous = loadedBank != null ? loadedBank : defaultBank;
        SoundFonts.install(synthesizer, bank, previous);
        loadedBank = bank;
        soundFont = path.toAbsolutePath().normalize();
        soundFontName = SoundFonts.displayName(bank, soundFont);
        soundFontNote = null;
        return soundFontName;
    }

    public void useDefaultSounds() {
        if (!isBuiltIn()) {
            throw new IllegalStateException(SoundFonts.NEEDS_BUILTIN);
        }
        if (loadedBank != null) {
            synthesizer.unloadAllInstruments(loadedBank);
            loadedBank = null;
        }
        if (defaultBank != null) {
            synthesizer.loadAllInstruments(defaultBank);
        }
        clearSoundFont();
    }

    /** Drops a remembered bank without touching a synth that is not open. */
    public void clearSoundFont() {
        soundFont = null;
        soundFontName = null;
        soundFontNote = null;
        loadedBank = null;
    }

    public boolean isPlaying() {
        return isOpen() && sequencer.isRunning();
    }

    /** Starts pattern playback, or the song chain when {@code songMode} is set and the chain is not empty. */
    public void play(Beat beat, boolean songMode) {
        play(beat, songMode, 0);
    }

    public void play(Beat beat, boolean songMode, long tick) {
        if (!isOpen()) {
            return;
        }
        song = songMode && beat.hasChain();
        try {
            load(beat, Math.max(0, tick));
            sequencer.start();
        } catch (InvalidMidiDataException exception) {
            failure = exception.getMessage();
        }
    }

    public void stop() {
        if (!isOpen()) {
            return;
        }
        sequencer.stop();
        sequencer.setTickPosition(0);
    }

    /**
     * Rebuilds the playing sequence and keeps the playhead near where it was.
     * {@code songMode} follows the chain when the beat has one.
     */
    public void update(Beat beat, boolean songMode) {
        if (!isOpen() || !sequencer.isRunning()) {
            return;
        }
        long position = sequencer.getTickPosition();
        song = songMode && beat.hasChain();
        try {
            load(beat, position);
            sequencer.start();
            long loop = song
                ? Chain.ticks(beat.chain(), beat.stepCount())
                : SequenceBuilder.loopTicks(beat.stepCount());
            if (loop > 0) {
                sequencer.setTickPosition(Math.floorMod(position, loop));
            }
        } catch (InvalidMidiDataException exception) {
            failure = exception.getMessage();
        }
    }

    public int currentStep(Beat beat) {
        Chain.Place place = place(beat);
        return place == null ? -1 : place.step();
    }

    /** Slot and step under the playhead, or null when nothing is playing. */
    public Chain.Place place(Beat beat) {
        if (!isPlaying()) {
            return null;
        }
        long tick = sequencer.getTickPosition();
        if (song && beat.hasChain()) {
            return Chain.place(beat.chain(), tick, beat.stepCount(), beat.swing());
        }
        return new Chain.Place(
            beat.activeSlot(),
            SequenceBuilder.stepForTick(tick, beat.stepCount(), beat.swing()),
            0
        );
    }

    public void audition(Track track) {
        audition(track, track.note());
    }

    public void audition(Track track, int note) {
        Receiver current = receiver();
        if (current == null) {
            return;
        }
        int channel = track.mode() == TrackMode.DRUM ? Gm.DRUM_CHANNEL : 0;
        int sounding = Notes.clamp(note);
        try {
            if (track.mode() == TrackMode.NOTE) {
                ShortMessage program = new ShortMessage();
                program.setMessage(ShortMessage.PROGRAM_CHANGE, channel, track.program(), 0);
                current.send(program, -1);
            }
            ShortMessage on = new ShortMessage();
            on.setMessage(ShortMessage.NOTE_ON, channel, sounding, track.velocity());
            current.send(on, -1);
            if (noteOff != null) {
                noteOff.cancel(false);
            }
            noteOff = audition.schedule(() -> silence(channel, sounding), 160, TimeUnit.MILLISECONDS);
        } catch (InvalidMidiDataException | RuntimeException ignored) {
            // The note is already clamped, or the output closed before the preview was sent.
        }
    }

    private void silence(int channel, int note) {
        Receiver current = receiver();
        if (current == null) {
            return;
        }
        try {
            ShortMessage off = new ShortMessage();
            off.setMessage(ShortMessage.NOTE_OFF, channel, note, 0);
            current.send(off, -1);
        } catch (InvalidMidiDataException | RuntimeException ignored) {
            // The preview note is already gone, or the output closed.
        }
    }

    private boolean ensureSequencer() {
        if (sequencer != null && sequencer.isOpen()) {
            return true;
        }
        try {
            sequencer = MidiSystem.getSequencer(false);
            sequencer.open();
            transmitter = sequencer.getTransmitter();
            failure = null;
            return true;
        } catch (MidiUnavailableException exception) {
            failure = exception.getMessage() == null ? "MIDI is unavailable" : exception.getMessage();
            closeQuietly();
            return false;
        }
    }

    private void switchTo(Opened opened, String id) {
        Receiver previous;
        MidiDevice previousExternal;
        Synthesizer previousSynth;
        synchronized (midiLock) {
            previous = receiver;
            previousExternal = external;
            previousSynth = synthesizer;
            transmitter.setReceiver(opened.receiver);
            receiver = opened.receiver;
            external = opened.device;
            synthesizer = opened.synth;
            defaultBank = opened.defaultBank;
            loadedBank = null;
            outputId = id;
            outputLabel = opened.label;
            failure = null;
        }
        quiet(previous);
        if (previous != null && previous != opened.receiver) {
            previous.close();
        }
        if (previousExternal != null && previousExternal != opened.device && previousExternal.isOpen()) {
            previousExternal.close();
        }
        if (previousSynth != null && previousSynth != opened.synth && previousSynth.isOpen()) {
            previousSynth.close();
        }
        if (synthesizer != null && soundFont != null) {
            reapplySoundFont();
        }
    }

    private void reapplySoundFont() {
        soundFontNote = null;
        try {
            Soundbank bank = SoundFonts.read(soundFont);
            SoundFonts.install(synthesizer, bank, defaultBank);
            loadedBank = bank;
            soundFontName = SoundFonts.displayName(bank, soundFont);
        } catch (RuntimeException | IOException | InvalidMidiDataException exception) {
            loadedBank = null;
            soundFontNote = exception.getMessage() == null ? "SoundFont did not load" : exception.getMessage();
        }
    }

    private Opened openDevice(String id) throws MidiUnavailableException {
        if (MidiOutputs.BUILTIN_ID.equals(id)) {
            Synthesizer synth = MidiSystem.getSynthesizer();
            synth.open();
            return new Opened(synth, null, synth.getReceiver(), synth.getDefaultSoundbank(), MidiOutputs.BUILTIN_LABEL);
        }
        MidiDevice.Info info = MidiOutputs.find(id);
        if (info == null) {
            throw new MidiUnavailableException("That MIDI output is not connected");
        }
        MidiDevice device = MidiSystem.getMidiDevice(info);
        if (!MidiOutputs.isExternalOutput(device)) {
            throw new MidiUnavailableException("That device cannot receive notes");
        }
        device.open();
        try {
            return new Opened(null, device, device.getReceiver(), null, MidiOutputs.labelFor(info));
        } catch (MidiUnavailableException exception) {
            device.close();
            throw exception;
        }
    }

    private Receiver receiver() {
        synchronized (midiLock) {
            return receiver;
        }
    }

    private void load(Beat beat, long position) throws InvalidMidiDataException {
        var sequence = song ? SequenceBuilder.buildSong(beat) : SequenceBuilder.build(beat);
        sequencer.setSequence(sequence);
        long loop = song
            ? Chain.ticks(beat.chain(), beat.stepCount())
            : SequenceBuilder.loopTicks(beat.stepCount());
        long end = Math.min(loop, sequence.getTickLength());
        sequencer.setLoopStartPoint(0);
        if (end > 0) {
            sequencer.setLoopEndPoint(end);
        }
        sequencer.setLoopCount(Sequencer.LOOP_CONTINUOUSLY);
        sequencer.setTempoInBPM(beat.bpm());
        if (loop > 0) {
            sequencer.setTickPosition(Math.floorMod(position, loop));
        }
    }

    @Override
    public void close() {
        if (noteOff != null) {
            noteOff.cancel(false);
        }
        audition.shutdownNow();
        closeQuietly();
    }

    private void closeQuietly() {
        Receiver current = receiver();
        quiet(current);
        synchronized (midiLock) {
            receiver = null;
        }
        if (current != null) {
            current.close();
        }
        if (external != null && external.isOpen()) {
            external.close();
        }
        external = null;
        if (synthesizer != null && synthesizer.isOpen()) {
            synthesizer.close();
        }
        synthesizer = null;
        loadedBank = null;
        if (sequencer != null && sequencer.isOpen()) {
            sequencer.stop();
            sequencer.close();
        }
        sequencer = null;
        transmitter = null;
    }

    private static void quiet(Receiver target) {
        if (target == null) {
            return;
        }
        try {
            for (int channel = 0; channel < 16; channel++) {
                ShortMessage off = new ShortMessage();
                off.setMessage(ShortMessage.CONTROL_CHANGE, channel, 120, 0);
                target.send(off, -1);
            }
        } catch (InvalidMidiDataException | RuntimeException ignored) {
            // All-sound-off is a fixed control message. A closed port has nothing to silence.
        }
    }

    private record Opened(Synthesizer synth, MidiDevice device, Receiver receiver, Soundbank defaultBank, String label) {}
}
