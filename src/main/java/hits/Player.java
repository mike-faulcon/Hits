package hits;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Receiver;
import javax.sound.midi.Sequencer;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Synthesizer;
import javax.sound.midi.MidiSystem;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Desktop playback through javax.sound.midi. Web and mobile would play the same document another way. */
public final class Player implements AutoCloseable {
    private Sequencer sequencer;
    private Synthesizer synthesizer;
    private Receiver playback;
    private Receiver receiver;
    private boolean open;
    private String failure;
    private ScheduledFuture<?> noteOff;
    private final ScheduledExecutorService audition = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "hits-audition");
        thread.setDaemon(true);
        return thread;
    });

    public void open() {
        if (open) {
            return;
        }
        try {
            sequencer = MidiSystem.getSequencer(false);
            sequencer.open();
            synthesizer = MidiSystem.getSynthesizer();
            synthesizer.open();
            playback = synthesizer.getReceiver();
            receiver = synthesizer.getReceiver();
            sequencer.getTransmitter().setReceiver(playback);
            open = true;
            failure = null;
        } catch (MidiUnavailableException exception) {
            failure = exception.getMessage() == null ? "MIDI is unavailable" : exception.getMessage();
            closeQuietly();
        }
    }

    public boolean isOpen() {
        return open;
    }

    public String failure() {
        return failure;
    }

    public boolean isPlaying() {
        return open && sequencer.isRunning();
    }

    public void play(Beat beat) {
        if (!open) {
            return;
        }
        try {
            load(beat, 0);
            sequencer.start();
        } catch (InvalidMidiDataException exception) {
            failure = exception.getMessage();
        }
    }

    public void stop() {
        if (!open) {
            return;
        }
        sequencer.stop();
        sequencer.setTickPosition(0);
    }

    /** Rebuilds the playing sequence and keeps the playhead near where it was. */
    public void update(Beat beat) {
        if (!open || !sequencer.isRunning()) {
            return;
        }
        long position = sequencer.getTickPosition();
        try {
            load(beat, position);
            sequencer.start();
            int loop = SequenceBuilder.loopTicks(beat.stepCount());
            if (loop > 0) {
                sequencer.setTickPosition(Math.floorMod(position, loop));
            }
        } catch (InvalidMidiDataException exception) {
            failure = exception.getMessage();
        }
    }

    public int currentStep(Beat beat) {
        if (!isPlaying()) {
            return -1;
        }
        return SequenceBuilder.stepForTick(sequencer.getTickPosition(), beat.stepCount(), beat.swing());
    }

    public void audition(Track track) {
        if (!open || receiver == null) {
            return;
        }
        int channel = track.mode() == TrackMode.DRUM ? Gm.DRUM_CHANNEL : 0;
        int note = track.note();
        try {
            if (track.mode() == TrackMode.NOTE) {
                ShortMessage program = new ShortMessage();
                program.setMessage(ShortMessage.PROGRAM_CHANGE, channel, track.program(), 0);
                receiver.send(program, -1);
            }
            ShortMessage on = new ShortMessage();
            on.setMessage(ShortMessage.NOTE_ON, channel, note, track.velocity());
            receiver.send(on, -1);
            if (noteOff != null) {
                noteOff.cancel(false);
            }
            noteOff = audition.schedule(() -> silence(channel, note), 160, TimeUnit.MILLISECONDS);
        } catch (InvalidMidiDataException ignored) {
            // The note is already clamped into the MIDI range.
        }
    }

    private void silence(int channel, int note) {
        if (receiver == null) {
            return;
        }
        try {
            ShortMessage off = new ShortMessage();
            off.setMessage(ShortMessage.NOTE_OFF, channel, note, 0);
            receiver.send(off, -1);
        } catch (InvalidMidiDataException ignored) {
            // Nothing useful to surface for a preview note.
        }
    }

    private void load(Beat beat, long position) throws InvalidMidiDataException {
        var sequence = SequenceBuilder.build(beat);
        sequencer.setSequence(sequence);
        int loop = SequenceBuilder.loopTicks(beat.stepCount());
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
        audition.shutdownNow();
        closeQuietly();
    }

    private void closeQuietly() {
        open = false;
        if (sequencer != null && sequencer.isOpen()) {
            sequencer.stop();
            sequencer.close();
        }
        if (receiver != null) {
            receiver.close();
            receiver = null;
        }
        if (playback != null) {
            playback.close();
            playback = null;
        }
        if (synthesizer != null && synthesizer.isOpen()) {
            synthesizer.close();
        }
    }
}
