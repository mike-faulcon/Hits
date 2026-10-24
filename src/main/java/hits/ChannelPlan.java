package hits;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Soundbank;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which MIDI channel a row uses, stable for every pattern in an export.
 * Center drums with no kit, bank, pitched preset, tune, decay, or effect share channel 9.
 * Every other row gets a channel of its own from {@link #POOL}. Channel 15 is last
 * in that pool because the metronome already uses it. Notes, sample tracks, and pitched presets
 * are placed before drums, so a drum is the row that falls back to channel 9
 * when the pool runs out. Rows that still would not fit, and that send the same
 * bank, program, pan, tune, decay, and effects, share one channel instead.
 * A row that still cannot have a channel is named by {@link #channelWarning(Beat)}.
 * An eight-row beat that fits the pool keeps channel == row, the same as before.
 * A sample that loaded keeps its own channel.
 * A sample that falls back plays its drum note on channel 9, because Gervill
 * will not switch a melodic channel from the sample bank to the drum bank.
 */
public final class ChannelPlan {
    /**
     * Melodic channels, then the metronome channel. Channel 9 is the shared drum channel
     * and is not in the pool.
     */
    public static final int[] POOL = {0, 1, 2, 3, 4, 5, 6, 7, 8, 10, 11, 12, 13, 14, 15};

    /** GM2 release time. 64 is the instrument's own release. */
    public static final int RELEASE = 72;
    /** GM2 reverb send. */
    public static final int REVERB = 91;
    /** GM2 chorus send. */
    public static final int CHORUS = 93;
    /** GM2 brightness, the low-pass cutoff. */
    public static final int CUTOFF = 74;
    /** GM2 harmonic content, the filter resonance. */
    public static final int RESONANCE = 71;
    /** RPN 0, pitch-bend range, in semitones. Wide enough for {@link Track#TUNE_MAX}. */
    public static final int BEND_RANGE = 24;
    /** GS drum NRPN: pitch, reverb, chorus. The key is the NRPN LSB. */
    private static final int KEY_PITCH = 0x18;
    private static final int KEY_REVERB = 0x1D;
    private static final int KEY_CHORUS = 0x1E;

    private static final int SHAPE_TUNE = 1;
    private static final int SHAPE_DECAY = 2;
    private static final int SHAPE_REVERB = 4;
    private static final int SHAPE_CHORUS = 8;
    private static final int SHAPE_CUTOFF = 16;
    private static final int SHAPE_RESONANCE = 32;

    private final int[] reserved;
    private final boolean[] overflow;
    /** A row that plays a pitched preset in at least one pattern this plan covers. */
    private final boolean[] presetRow;
    /** Which shape controllers a row uses in any pattern this plan covers. */
    private final int[] shapeRow;
    /** Bank used to decide whether a kit program really exists. Null keeps the stored kit. */
    private final Soundbank kits;
    /** Which sample rows actually play a sample. Null or {@link SampleUse#none()} falls back. */
    private final SampleUse samples;
    /**
     * Extra channel for a sample-bank voice when the same row also has a plain
     * drum or note. {@code -1} keeps the rendered voice on {@link #reserved}.
     */
    private final int[] sampleChannel;
    /**
     * The plain drum could not get its own channel, so it plays on channel 9
     * with per-key NRPN. The sample-bank voice stays on {@link #reserved}.
     */
    private final boolean[] acousticNine;
    /**
     * The filtered voice and a plain melodic voice on this row could not be split,
     * so the plain hit plays the filtered recording.
     */
    private final boolean[] collided;

    private ChannelPlan(
        int[] reserved,
        boolean[] overflow,
        boolean[] presetRow,
        int[] shapeRow,
        Soundbank kits,
        SampleUse samples,
        int[] sampleChannel,
        boolean[] acousticNine,
        boolean[] collided
    ) {
        this.reserved = reserved;
        this.overflow = overflow;
        this.presetRow = presetRow;
        this.shapeRow = shapeRow;
        this.kits = kits;
        this.samples = samples == null ? SampleUse.none() : samples;
        this.sampleChannel = sampleChannel == null ? emptyChannels(reserved.length) : sampleChannel;
        this.acousticNine = acousticNine == null ? new boolean[reserved.length] : acousticNine;
        this.collided = collided == null ? new boolean[reserved.length] : collided;
    }

    private static int[] emptyChannels(int count) {
        int[] channels = new int[Math.max(0, count)];
        for (int i = 0; i < channels.length; i++) {
            channels[i] = -1;
        }
        return channels;
    }

    /**
     * A drum at center pan, kit 0, bank 0, no pitched preset, and no tune, decay, or effect.
     * It sends pan only, on the General MIDI drum channel.
     */
    public static boolean sharesDrumChannel(Track track) {
        return sharesDrumChannel(track, null);
    }

    /**
     * A drum at center pan, with no bank, pitched preset, tune, decay, or effect, whose kit
     * is Standard or is missing from {@code kits}. A null bank keeps a non-zero kit private,
     * so a MIDI file still names the kit the row asked for.
     * Tune, decay, a send, or a filter takes the drum off this shared channel and onto bank 120.
     */
    public static boolean sharesDrumChannel(Track track, Soundbank kits) {
        return track.mode() == TrackMode.DRUM
            && track.pan() == Track.PAN_CENTER
            && SoundCatalog.playedKit(kits, track.kit()) == 0
            && track.bank() == 0
            && track.preset() == null
            && !track.shapesSound();
    }

    /** Channels for the patterns this playback or export will actually visit. */
    public static ChannelPlan of(Beat beat, List<Character> slots) {
        return of(beat, slots, null);
    }

    /**
     * Same plan as {@link #of(Beat, List)}, using {@code kits} when a drum kit program
     * is not actually in that bank. Null keeps every stored kit number.
     */
    public static ChannelPlan of(Beat beat, List<Character> slots, Soundbank kits) {
        return of(beat, slots, kits, SampleUse.none());
    }

    /**
     * Same plan as {@link #of(Beat, List, Soundbank)}. {@code samples} selects the sample bank
     * for rows whose sample loaded. Every other sample row plays its General MIDI note.
     */
    public static ChannelPlan of(Beat beat, List<Character> slots, Soundbank kits, SampleUse samples) {
        if (samples == null) {
            samples = SampleUse.none();
        }
        int count = beat == null ? 0 : beat.trackCount();
        boolean[] needsPrivate = new boolean[count];
        boolean[] melodic = new boolean[count];
        boolean[] presetRow = new boolean[count];
        int[] shapeRow = new int[count];
        boolean[] sampleVoice = new boolean[count];
        boolean[] acousticPrivate = new boolean[count];
        boolean[] acousticMelodic = new boolean[count];
        if (beat != null && slots != null) {
            for (char slot : slots) {
                Track[] tracks = beat.slot(slot);
                int rows = Math.min(count, tracks.length);
                for (int row = 0; row < rows; row++) {
                    Track track = tracks[row];
                    if (track.mode() == TrackMode.DRUM && track.preset() != null) {
                        presetRow[row] = true;
                    }
                    shapeRow[row] |= shapeFlags(track);
                    if (track.mode() == TrackMode.SAMPLE) {
                        if (samples.plays(track, row)) {
                            needsPrivate[row] = true;
                            melodic[row] = true;
                        }
                        continue;
                    }
                    boolean rendered = samples.plays(track, row);
                    if (!rendered && sharesDrumChannel(track, kits)) {
                        continue;
                    }
                    needsPrivate[row] = true;
                    if (rendered
                        || track.mode() == TrackMode.NOTE
                        || (track.mode() == TrackMode.DRUM && track.preset() != null)) {
                        melodic[row] = true;
                    }
                    if (rendered) {
                        sampleVoice[row] = true;
                    } else {
                        acousticPrivate[row] = true;
                        if (track.mode() == TrackMode.NOTE
                            || (track.mode() == TrackMode.DRUM && track.preset() != null)) {
                            acousticMelodic[row] = true;
                        }
                    }
                }
            }
        }
        ChannelPlan assigned = assign(count, needsPrivate, melodic);
        if (overflows(assigned) && beat != null) {
            assigned = share(
                assigned,
                needsPrivate,
                melodic,
                signatures(beat, slots, count, kits, samples, needsPrivate)
            );
        }
        int[] sampleChannel = emptyChannels(count);
        boolean[] acousticNine = new boolean[count];
        boolean[] collided = new boolean[count];
        separate(assigned, sampleVoice, acousticPrivate, acousticMelodic, sampleChannel, acousticNine, collided);
        return new ChannelPlan(
            assigned.reserved,
            assigned.overflow,
            presetRow,
            shapeRow,
            kits,
            samples,
            sampleChannel,
            acousticNine,
            collided
        );
    }

    /**
     * Why this plan cannot play every row on its own channel, or null when it can.
     * Compatible rows already share. The named rows are the ones that still collide.
     */
    public String channelWarning(Beat beat) {
        if (beat == null) {
            return null;
        }
        List<String> crowded = new ArrayList<>();
        List<String> plain = new ArrayList<>();
        int rows = Math.min(reserved.length, beat.trackCount());
        Track[] tracks = beat.activeTracks();
        for (int row = 0; row < rows; row++) {
            String label = (row + 1) + " " + tracks[row].name();
            if (overflow(row) || collided(row)) {
                crowded.add(label);
            } else if (acousticNine(row)) {
                plain.add(label);
            }
        }
        if (crowded.isEmpty() && plain.isEmpty()) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        if (!crowded.isEmpty()) {
            text.append("Not enough MIDI channels. Channel 10 is drums, and the other 15 are already used. ");
            text.append(String.join(", ", crowded));
            text.append(crowded.size() == 1 ? " shares" : " share");
            text.append(" a channel, so the sound can be wrong. ");
            text.append("Rows with the same instrument, pan, tune, decay, and effects share one channel.");
        }
        if (!plain.isEmpty()) {
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(String.join(", ", plain));
            text.append(plain.size() == 1 ? " has" : " have");
            text.append(" a filtered hit and a plain hit and no free channel, so the plain hit uses the drum channel.");
        }
        return text.toString();
    }

    /** True when {@code row} cannot be rendered on a channel that matches its sound. */
    public boolean misrendered(int row) {
        return overflow(row) || acousticNine(row) || collided(row);
    }

    private boolean collided(int row) {
        return row >= 0 && row < collided.length && collided[row];
    }

    private static boolean overflows(ChannelPlan plan) {
        for (boolean flag : plan.overflow) {
            if (flag) {
                return true;
            }
        }
        return false;
    }

    /**
     * Puts rows that send the same channel state on one channel.
     * Used only after {@link #assign} ran out of channels, so a beat that already
     * fits keeps the channels it had, including channel == row on an eight-row beat.
     */
    private static ChannelPlan share(
        ChannelPlan assigned,
        boolean[] needsPrivate,
        boolean[] melodic,
        String[] signatures
    ) {
        int count = assigned.reserved.length;
        int[] leader = new int[count];
        for (int row = 0; row < count; row++) {
            leader[row] = -1;
        }
        Map<String, Integer> groups = new LinkedHashMap<>();
        for (int row = 0; row < count; row++) {
            if (row >= needsPrivate.length || !needsPrivate[row]) {
                continue;
            }
            String signature = signatures != null && row < signatures.length && signatures[row] != null
                ? signatures[row]
                : "row" + row;
            Integer existing = groups.get(signature);
            if (existing == null) {
                groups.put(signature, row);
                leader[row] = row;
            } else {
                leader[row] = existing;
            }
        }
        boolean[] groupNeeds = new boolean[count];
        boolean[] groupMelodic = new boolean[count];
        for (int row = 0; row < count; row++) {
            if (leader[row] < 0) {
                continue;
            }
            int lead = leader[row];
            groupNeeds[lead] = true;
            if (row < melodic.length && melodic[row]) {
                groupMelodic[lead] = true;
            }
        }
        ChannelPlan grouped = assign(count, groupNeeds, groupMelodic);
        int[] reserved = grouped.reserved.clone();
        boolean[] overflow = grouped.overflow.clone();
        for (int row = 0; row < count; row++) {
            int lead = leader[row];
            if (lead >= 0 && lead != row) {
                reserved[row] = reserved[lead];
                overflow[row] = overflow[lead];
            }
        }
        return new ChannelPlan(
            reserved, overflow, new boolean[count], new int[count], null, SampleUse.none(), null, null, null
        );
    }

    private static String[] signatures(
        Beat beat,
        List<Character> slots,
        int count,
        Soundbank kits,
        SampleUse samples,
        boolean[] needsPrivate
    ) {
        String[] keys = new String[count];
        for (int row = 0; row < count; row++) {
            if (row >= needsPrivate.length || !needsPrivate[row]) {
                continue;
            }
            StringBuilder key = new StringBuilder();
            if (slots != null) {
                for (char slot : slots) {
                    Track[] tracks = beat.slot(slot);
                    if (row >= tracks.length) {
                        continue;
                    }
                    key.append(slot).append('=').append(voiceSignature(tracks[row], row, kits, samples)).append(';');
                }
            }
            keys[row] = key.toString();
        }
        return keys;
    }

    /**
     * Channel-wide state. Drum notes and step pitches are not included, so two kicks
     * at the same pan can share, and two notes on the same program can share.
     * A different pan, tune, decay, or effect cannot.
     */
    private static String voiceSignature(Track track, int row, Soundbank kits, SampleUse samples) {
        boolean rendered = track.mode() != TrackMode.SAMPLE && samples.plays(track, row);
        String kind;
        int bank;
        int program;
        if (track.mode() == TrackMode.SAMPLE && samples.plays(track, row)) {
            kind = "sample";
            bank = SampleBank.MIDI_BANK;
            program = samples.program(track, row);
        } else if (rendered) {
            kind = "rendered";
            bank = SampleBank.MIDI_BANK;
            program = samples.program(track, row);
        } else if (track.mode() == TrackMode.DRUM && track.preset() != null) {
            kind = "preset";
            bank = track.bank();
            program = track.preset().program();
        } else if (track.mode() == TrackMode.NOTE) {
            kind = "note";
            bank = track.bank();
            program = track.program();
        } else {
            kind = "drum";
            bank = track.bank() == 0 ? Gm.DRUM_BANK : track.bank();
            program = SoundCatalog.playedKit(kits, track.kit());
        }
        String release = Integer.toString(track.decay());
        String reverb = track.hasReverb() ? Integer.toString(track.reverb()) : "-";
        if (track.mode() == TrackMode.DRUM && track.preset() != null) {
            if (track.decay() == Track.DECAY) {
                release = "g" + track.gate();
            }
            if (!track.hasReverb()) {
                reverb = "g" + track.gate();
            }
        }
        return kind + "|" + bank + "|" + program
            + "|p" + track.pan()
            + "|t" + track.tune()
            + "|d" + release
            + "|r" + reverb
            + "|c" + (track.hasChorus() ? track.chorus() : "-")
            + "|f" + (track.hasCutoff() ? track.cutoff() : "-")
            + "|q" + (track.hasResonance() ? track.resonance() : "-");
    }

    /**
     * A filtered hit and a plain hit on the same row cannot share a channel.
     * Gervill will not switch that channel from the sample bank back to the drum bank,
     * so the plain hit would play the filtered recording. The plain voice keeps the
     * channel it would have had on its own. The filtered voice takes the next free
     * pool channel. When none is left, a plain drum falls back to channel 9.
     */
    private static void separate(
        ChannelPlan assigned,
        boolean[] sampleVoice,
        boolean[] acousticPrivate,
        boolean[] acousticMelodic,
        int[] sampleChannel,
        boolean[] acousticNine,
        boolean[] collided
    ) {
        boolean[] taken = new boolean[16];
        taken[Gm.DRUM_CHANNEL] = true;
        for (int row = 0; row < assigned.reserved.length; row++) {
            int channel = assigned.reserved[row];
            if (channel >= 0 && channel < taken.length && !assigned.overflow[row]) {
                taken[channel] = true;
            }
        }
        int rows = Math.min(sampleChannel.length, assigned.reserved.length);
        for (int row = 0; row < rows; row++) {
            if (row >= sampleVoice.length || !sampleVoice[row]
                || row >= acousticPrivate.length || !acousticPrivate[row]) {
                continue;
            }
            int extra = nextFree(taken);
            if (extra >= 0) {
                sampleChannel[row] = extra;
                taken[extra] = true;
            } else if (row >= acousticMelodic.length || !acousticMelodic[row]) {
                acousticNine[row] = true;
            } else if (row < collided.length) {
                collided[row] = true;
            }
        }
    }

    private static int shapeFlags(Track track) {
        int flags = 0;
        if (track.tune() != 0) {
            flags |= SHAPE_TUNE;
        }
        if (track.decay() != Track.DECAY) {
            flags |= SHAPE_DECAY;
        }
        if (track.hasReverb()) {
            flags |= SHAPE_REVERB;
        }
        if (track.hasChorus()) {
            flags |= SHAPE_CHORUS;
        }
        if (track.hasCutoff()) {
            flags |= SHAPE_CUTOFF;
        }
        if (track.hasResonance()) {
            flags |= SHAPE_RESONANCE;
        }
        return flags;
    }

    /**
     * Assigns private channels. {@code melodic} rows are placed first so a note
     * keeps a channel when drums would otherwise fill the pool.
     * A row whose index is free in {@link #POOL} keeps that number, which is how
     * an eight-track beat stays on channel == row.
     */
    public static ChannelPlan assign(int rowCount, boolean[] needsPrivate, boolean[] melodic) {
        int count = Math.max(0, rowCount);
        int[] reserved = new int[count];
        boolean[] overflow = new boolean[count];
        for (int row = 0; row < count; row++) {
            reserved[row] = -1;
        }
        boolean[] taken = new boolean[16];
        place(reserved, overflow, taken, needsPrivate, melodic, true);
        place(reserved, overflow, taken, needsPrivate, melodic, false);
        return new ChannelPlan(reserved, overflow, new boolean[count], new int[count], null, SampleUse.none(), null, null, null);
    }

    private static void place(
        int[] reserved,
        boolean[] overflow,
        boolean[] taken,
        boolean[] needsPrivate,
        boolean[] melodic,
        boolean wantMelodic
    ) {
        for (int row = 0; row < reserved.length; row++) {
            if (row >= needsPrivate.length || !needsPrivate[row] || reserved[row] >= 0) {
                continue;
            }
            boolean rowMelodic = row < melodic.length && melodic[row];
            if (rowMelodic != wantMelodic) {
                continue;
            }
            int channel = -1;
            if (inPool(row) && !taken[row]) {
                channel = row;
            } else {
                channel = nextFree(taken);
            }
            if (channel < 0) {
                reserved[row] = Gm.DRUM_CHANNEL;
                overflow[row] = true;
            } else {
                reserved[row] = channel;
                taken[channel] = true;
            }
        }
    }

    private static boolean inPool(int channel) {
        for (int candidate : POOL) {
            if (candidate == channel) {
                return true;
            }
        }
        return false;
    }

    private static int nextFree(boolean[] taken) {
        for (int candidate : POOL) {
            if (!taken[candidate]) {
                return candidate;
            }
        }
        return -1;
    }

    /**
     * Channel for this pattern's row. A shared drum stays on channel 9 even when
     * another pattern on the same row reserved a private channel.
     */
    public int channel(Track track, int row) {
        if (track != null && track.mode() == TrackMode.SAMPLE && !samples.plays(track, row)) {
            return Gm.DRUM_CHANNEL;
        }
        boolean rendered = track != null && track.mode() != TrackMode.SAMPLE && samples.plays(track, row);
        if (rendered && row >= 0 && row < sampleChannel.length && sampleChannel[row] >= 0) {
            return sampleChannel[row];
        }
        if (!rendered && acousticNine(row)) {
            return Gm.DRUM_CHANNEL;
        }
        if (rendered) {
            int reservedChannel = reserved(row);
            if (reservedChannel >= 0 && !overflow(row)) {
                return reservedChannel;
            }
        }
        if (track != null && sharesDrumChannel(track, kits)) {
            return Gm.DRUM_CHANNEL;
        }
        if (row >= 0 && row < reserved.length && reserved[row] >= 0) {
            return reserved[row];
        }
        if (inPool(row)) {
            return row;
        }
        return Gm.DRUM_CHANNEL;
    }

    /** The private channel reserved for {@code row}, or -1 when the row shares channel 9. */
    public int reserved(int row) {
        if (row < 0 || row >= reserved.length) {
            return -1;
        }
        return reserved[row];
    }

    /** True when {@code row} needed its own channel and the pool was already full. */
    public boolean overflow(int row) {
        return row >= 0 && row < overflow.length && overflow[row];
    }

    private boolean acousticNine(int row) {
        return row >= 0 && row < acousticNine.length && acousticNine[row];
    }

    /**
     * Bank, program, and pan for one row, in the order a synth needs them.
     * A shared drum sends pan only. A pitched preset sends its melodic program
     * and ignores the kit. A private drum with no bank of its own uses the GM drum bank.
     */
    public List<ShortMessage> setup(Track track, int channel) throws InvalidMidiDataException {
        return setup(track, channel, -1);
    }

    /**
     * Bank, program, pan, and any tune or effect for one row.
     * {@code row} lets a later pattern clear a controller a previous pattern on this channel set.
     * <p>
     * An explicit decay replaces the release a pitched preset's gate would send, and an explicit
     * reverb replaces that gate's reverb. At decay 100 and with reverb unset, the gate is left
     * in charge, so a preset at gate 45 still asks for release 64 and reverb 40. One value is
     * chosen either way, so the two never fight. The sequence writer then skips a controller
     * the channel already has, including those neutrals when nothing has changed them.
     * Unset cutoff, resonance, chorus, and reverb send nothing on a row that never uses them.
     * CC 75 is not sent: the built-in synth's decay controller does not lengthen a kick and it
     * collapses an open hat. Decay is CC 72.
     * Tune is a pitch bend after RPN 0 is set to {@link #BEND_RANGE} semitones. It is not RPN 2,
     * which can pick a different drum key. A row that ran out of channels uses GS per-key NRPN
     * instead, and only for tune, reverb, and chorus. Filter and decay have no per-key message.
     */
    public List<ShortMessage> setup(Track track, int channel, int row) throws InvalidMidiDataException {
        List<ShortMessage> messages = new ArrayList<>();
        boolean rendered = samples.plays(track, row);
        // A plain drum that lost its channel to a filtered voice on the same row
        // plays on channel 9. Bank 120 there would retune every drum, so only the key moves.
        if (!rendered && acousticNine(row) && channel == Gm.DRUM_CHANNEL) {
            addKeyShape(messages, track, channel, row);
            messages.add(control(channel, 10, track.pan()));
            return messages;
        }
        // A low-cut drum still looks like a shared kit drum: no tune, decay, or effect.
        // Playback has already put it on its own channel so the sample bank can play.
        // Channel 9 stays pan-only, including when that filtered drum overflowed.
        if (!sharesDrumChannel(track, kits) || (rendered && channel != Gm.DRUM_CHANNEL)) {
            boolean preset = track.preset() != null && track.mode() == TrackMode.DRUM;
            int voice = samples.program(track, row);
            boolean sampleVoice = track.mode() == TrackMode.SAMPLE && voice >= 0;
            // A filtered drum that overflowed onto channel 9 stays on the kit note.
            // Channel 9 would play the sample bank as percussion.
            boolean renderedVoice = rendered && voice >= 0 && channel != Gm.DRUM_CHANNEL;
            if (sampleVoice || renderedVoice) {
                bankSelect(messages, channel, SampleBank.MIDI_BANK);
                messages.add(program(channel, voice));
            } else if (track.mode() == TrackMode.SAMPLE) {
                // Channel 9 is already the drum kit. Bank 120 on that channel would retune every drum.
                return messages;
            } else if (preset) {
                bankSelect(messages, channel, track.bank());
                messages.add(program(channel, track.preset().program()));
            } else if (track.mode() == TrackMode.NOTE) {
                bankSelect(messages, channel, track.bank());
                messages.add(program(channel, track.program()));
            } else if (track.bank() == 0) {
                messages.add(control(channel, 0, Gm.DRUM_BANK));
                messages.add(control(channel, 32, 0));
                messages.add(program(channel, SoundCatalog.playedKit(kits, track.kit())));
            } else {
                bankSelect(messages, channel, track.bank());
                messages.add(program(channel, SoundCatalog.playedKit(kits, track.kit())));
            }
            // A rendered voice on its own channel still gets bank, program, and bend,
            // even when the plain voice on this row overflowed onto channel 9.
            boolean keyShape = (overflow(row) || acousticNine(row)) && !(rendered && channel != Gm.DRUM_CHANNEL);
            if (keyShape) {
                addKeyShape(messages, track, channel, row);
            } else {
                addChannelShape(messages, track, channel, row, preset);
            }
        }
        messages.add(control(channel, 10, track.pan()));
        return messages;
    }

    /**
     * Channel controllers. Explicit decay and reverb win over the preset gate.
     * A row that used a controller in another pattern sends the neutral value here when this
     * pattern leaves it unset, so the earlier value does not stick. A shared drum never reaches
     * this, so those neutrals are not written onto channel 9.
     */
    private void addChannelShape(
        List<ShortMessage> messages,
        Track track,
        int channel,
        int row,
        boolean preset
    ) throws InvalidMidiDataException {
        if (track.tune() != 0 || shaped(row, SHAPE_TUNE)) {
            bendRange(messages, channel);
            messages.add(pitchBend(channel, track.tune()));
        }
        Integer release = releaseValue(track, row, preset);
        if (release != null) {
            messages.add(control(channel, RELEASE, release));
        }
        Integer reverb = reverbValue(track, row, preset);
        if (reverb != null) {
            messages.add(control(channel, REVERB, reverb));
        }
        Integer chorus = held(track.hasChorus(), track.chorus(), row, SHAPE_CHORUS, Track.CHORUS_NEUTRAL);
        if (chorus != null) {
            messages.add(control(channel, CHORUS, chorus));
        }
        Integer cutoff = held(track.hasCutoff(), track.cutoff(), row, SHAPE_CUTOFF, Track.FILTER_NEUTRAL);
        if (cutoff != null) {
            messages.add(control(channel, CUTOFF, cutoff));
        }
        Integer resonance = held(track.hasResonance(), track.resonance(), row, SHAPE_RESONANCE, Track.FILTER_NEUTRAL);
        if (resonance != null) {
            messages.add(control(channel, RESONANCE, resonance));
        }
    }

    /**
     * Per-key fallback for a drum that needed its own channel and did not get one.
     * Channel-wide bend, filter, and release would move every drum on channel 9, so they are not sent.
     */
    private void addKeyShape(List<ShortMessage> messages, Track track, int channel, int row) throws InvalidMidiDataException {
        int key = track.playedNote();
        if (track.tune() != 0 || shaped(row, SHAPE_TUNE)) {
            nrpn(messages, channel, KEY_PITCH, key, Track.tuneKey(track.tune()));
        }
        if (track.hasReverb() || shaped(row, SHAPE_REVERB)) {
            int value = track.hasReverb() ? track.reverb() : Track.REVERB_NEUTRAL;
            nrpn(messages, channel, KEY_REVERB, key, value);
        }
        if (track.hasChorus() || shaped(row, SHAPE_CHORUS)) {
            int value = track.hasChorus() ? track.chorus() : Track.CHORUS_NEUTRAL;
            nrpn(messages, channel, KEY_CHORUS, key, value);
        }
    }

    /**
     * Release for this pattern. An explicit decay wins. Otherwise a preset keeps the gate's
     * release, and a later pattern on that row puts the controller back to 64.
     */
    private Integer releaseValue(Track track, int row, boolean preset) {
        if (track.mode() == TrackMode.SAMPLE && samples.plays(track, row)) {
            // The sample preset already has this decay. CC 72 would add to that release.
            // A previous pattern on the channel may have set it, so put the neutral back.
            if (shaped(row, SHAPE_DECAY)) {
                return Track.FILTER_NEUTRAL;
            }
            return null;
        }
        if (track.decay() != Track.DECAY) {
            return Track.decayController(track.decay());
        }
        if (preset) {
            return presetRelease(track.gate());
        }
        if (presetRow(row) || shaped(row, SHAPE_DECAY)) {
            return Track.FILTER_NEUTRAL;
        }
        return null;
    }

    /**
     * Reverb for this pattern. An explicit send wins, including over a preset's gate.
     * A later pattern on a preset row sends 0, which is the value that row used to clear the gate.
     * A later pattern on a row that only used an explicit send sends {@link Track#REVERB_NEUTRAL}.
     */
    private Integer reverbValue(Track track, int row, boolean preset) {
        if (track.hasReverb()) {
            return track.reverb();
        }
        if (track.mode() == TrackMode.SAMPLE && samples.plays(track, row)) {
            // Gervill's channel starts at reverb 40. A sample row sends 0 unless the row sets a send,
            // so a one-shot does not ring on after the file.
            return 0;
        }
        if (preset) {
            return presetReverb(track.gate());
        }
        if (presetRow(row)) {
            return 0;
        }
        if (shaped(row, SHAPE_REVERB)) {
            return Track.REVERB_NEUTRAL;
        }
        return null;
    }

    private Integer held(boolean set, int value, int row, int flag, int neutral) {
        if (set) {
            return value;
        }
        if (shaped(row, flag)) {
            return neutral;
        }
        return null;
    }

    private boolean shaped(int row, int flag) {
        return row >= 0 && row < shapeRow.length && (shapeRow[row] & flag) != 0;
    }

    private boolean presetRow(int row) {
        return row >= 0 && row < presetRow.length && presetRow[row];
    }

    private static void bendRange(List<ShortMessage> messages, int channel) throws InvalidMidiDataException {
        messages.add(control(channel, 101, 0));
        messages.add(control(channel, 100, 0));
        messages.add(control(channel, 6, BEND_RANGE));
        messages.add(control(channel, 38, 0));
        messages.add(control(channel, 101, 127));
        messages.add(control(channel, 100, 127));
    }

    private static ShortMessage pitchBend(int channel, int cents) throws InvalidMidiDataException {
        int value = Track.pitchBend(cents);
        ShortMessage message = new ShortMessage();
        message.setMessage(ShortMessage.PITCH_BEND, channel, value & 0x7f, (value >> 7) & 0x7f);
        return message;
    }

    /** One GS NRPN, then the null NRPN so a later data entry does not keep writing it. */
    private static void nrpn(List<ShortMessage> messages, int channel, int msb, int key, int value) throws InvalidMidiDataException {
        messages.add(control(channel, 99, msb));
        messages.add(control(channel, 98, key));
        messages.add(control(channel, 6, value));
        messages.add(control(channel, 38, 0));
        messages.add(control(channel, 99, 127));
        messages.add(control(channel, 98, 127));
    }

    /**
     * Release for a pitched preset, scaled to the drum gate.
     * Gervill adds about {@code (value - 64) / 64 * 6000} ms to the release.
     * Below 54, a one-tick hit (gate 5 of a 16th at 120 bpm) is silent, because
     * note-on and note-off land in one control period. 64 is the built-in release.
     * 80 lets a full gate ring longer than that.
     */
    public static int presetRelease(int gate) {
        int clamped = Math.max(5, Math.min(100, gate));
        if (clamped <= 45) {
            return 54 + (clamped - 5) / 4;
        }
        return 64 + (clamped - 45) * 16 / 55;
    }

    /**
     * Reverb send for a pitched preset, scaled to the drum gate.
     * On the built-in bank the timpani crack's tail is reverb: 0 cuts it to the sample,
     * 40 matches the tail when this controller is left unset, and 80 lets a long gate bloom.
     * The xylophone rim barely moves.
     */
    public static int presetReverb(int gate) {
        int clamped = Math.max(5, Math.min(100, gate));
        if (clamped <= 45) {
            return clamped - 5;
        }
        return 40 + (clamped - 45) * 40 / 55;
    }

    private static void bankSelect(List<ShortMessage> messages, int channel, int bank) throws InvalidMidiDataException {
        int value = Math.max(0, Math.min(16383, bank));
        messages.add(control(channel, 0, value / 128));
        messages.add(control(channel, 32, value % 128));
    }

    private static ShortMessage control(int channel, int controller, int value) throws InvalidMidiDataException {
        ShortMessage message = new ShortMessage();
        message.setMessage(ShortMessage.CONTROL_CHANGE, channel, controller, Math.max(0, Math.min(127, value)));
        return message;
    }

    private static ShortMessage program(int channel, int program) throws InvalidMidiDataException {
        ShortMessage message = new ShortMessage();
        message.setMessage(ShortMessage.PROGRAM_CHANGE, channel, Math.max(0, Math.min(127, program)), 0);
        return message;
    }
}
