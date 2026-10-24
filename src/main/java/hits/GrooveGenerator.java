package hits;

import java.util.Arrays;
import java.util.Random;

/**
 * Starter grooves for the eight kit rows.
 * The same seed and style always build the same hits: kick and snare stay on
 * that style's anchor steps, and hats, ghosts, tempo, and swing move inside a
 * small range. {@link #generate} and {@link #shuffle} do not read the clock
 * and do not change a {@link Beat}. {@link #write} and {@link #shuffleTrack}
 * put the result on the pattern on screen as one undo step.
 */
public final class GrooveGenerator {
    /** Kit rows, matching {@link Beat#drumKit}. */
    static final int KICK = 0;
    static final int SNARE = 1;
    static final int HAT = 2;
    static final int OPEN = 3;
    static final int CLAP = 4;
    static final int TOM = 5;
    static final int CRASH = 6;
    static final int RIDE = 7;

    public enum Genre {
        BOOM_BAP("Boom bap"),
        TRAP("Trap"),
        HOUSE("House"),
        ROCK("Rock"),
        REGGAETON("Reggaeton");

        private final String label;

        Genre(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Tempo, swing, and a velocity grid. Zero velocity is a rest. */
    public static final class Groove {
        private final Genre genre;
        private final int bpm;
        private final int swing;
        private final int[][] hits;

        Groove(Genre genre, int bpm, int swing, int[][] hits) {
            this.genre = genre;
            this.bpm = bpm;
            this.swing = swing;
            this.hits = hits;
        }

        public Genre genre() {
            return genre;
        }

        public int bpm() {
            return bpm;
        }

        public int swing() {
            return swing;
        }

        public int steps() {
            return hits[0].length;
        }

        public int velocity(int track, int step) {
            return hits[track][step];
        }

        public boolean on(int track, int step) {
            return hits[track][step] > 0;
        }

        public int hitCount(int track) {
            int count = 0;
            for (int velocity : hits[track]) {
                if (velocity > 0) {
                    count++;
                }
            }
            return count;
        }

        public int[] hits(int track) {
            return hits[track].clone();
        }
    }

    private final Random random;
    private int[][] grid;
    private int steps;
    private int bpm;
    private int swing;
    private Genre genre;

    public GrooveGenerator(long seed) {
        this.random = new Random(seed);
    }

    /** A starter groove for {@code stepCount} steps. Lengths past 16 keep a second bar up to that length. */
    public Groove generate(Genre genre, int stepCount) {
        if (genre == null) {
            throw new IllegalArgumentException("A style is required");
        }
        this.genre = genre;
        this.steps = Beat.clampSteps(stepCount);
        this.grid = new int[Beat.TRACKS][steps];
        switch (genre) {
            case BOOM_BAP -> boomBap();
            case TRAP -> trap();
            case HOUSE -> house();
            case ROCK -> rock();
            case REGGAETON -> reggaeton();
        }
        secondBar();
        return new Groove(genre, bpm, swing, copyGrid());
    }

    /**
     * A new rhythm for one kit role, using the same rules as {@link #generate}.
     * An empty role still gets a few hits, so Shuffle always changes the row.
     */
    public int[] shuffle(Genre genre, int role, int stepCount) {
        int length = Beat.clampSteps(stepCount);
        if (role < 0 || role >= Beat.TRACKS) {
            return new int[length];
        }
        int[] hits = generate(genre, stepCount).hits(role);
        if (count(hits) == 0) {
            fillEmpty(hits, role);
        }
        return hits;
    }

    /**
     * Which kit role a track should shuffle as. Drum notes use the sound
     * (a snare moved to another row still shuffles as a snare). Note tracks
     * keep the row index.
     */
    public static int role(Track track, int fallbackIndex) {
        int index = fallbackIndex < 0 || fallbackIndex >= Beat.TRACKS ? KICK : fallbackIndex;
        if (track == null || track.mode() != TrackMode.DRUM) {
            return index;
        }
        return switch (track.note()) {
            case 35, 36 -> KICK;
            case 37, 38, 40 -> SNARE;
            case 42, 44 -> HAT;
            case 46 -> OPEN;
            case 39 -> CLAP;
            case 41, 43, 45, 47, 48, 50 -> TOM;
            case 49, 57 -> CRASH;
            case 51, 59 -> RIDE;
            default -> index;
        };
    }

    /** True when any step inside the audible length of {@code slot} is on. */
    public static boolean patternHasHits(Beat beat, char slot) {
        int limit = beat.stepCount(slot);
        for (Track track : beat.slot(slot)) {
            for (int step = 0; step < limit; step++) {
                if (track.step(step).on()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * A style to use when Shuffle is pressed before Make me a beat.
     * Swung tempos stay boom bap. Straight tempos follow the BPM.
     * A slow straight tempo stays boom bap, which is the friendly default.
     */
    public static Genre infer(int bpm, int swing) {
        if (swing >= 52) {
            return Genre.BOOM_BAP;
        }
        if (bpm >= 132) {
            return Genre.TRAP;
        }
        if (bpm >= 118) {
            return Genre.HOUSE;
        }
        if (bpm >= 104) {
            return Genre.ROCK;
        }
        return Genre.BOOM_BAP;
    }

    /** Writes {@code genre} onto the pattern on screen. Tempo, swing, and hits are one undo. */
    public static void write(Editor editor, Genre genre, long seed) {
        char slot = editor.viewSlot();
        Groove groove = new GrooveGenerator(seed).generate(genre, editor.beat().stepCount(slot));
        editor.edit(beat -> {
            beat.setActiveSlot(slot);
            apply(beat, groove);
        });
    }

    /**
     * Replaces the selected track's hits. Tempo, swing, and the other tracks stay. One undo.
     * If the new rhythm would light the same steps, one offbeat is flipped so Shuffle is visible.
     */
    public static void shuffleTrack(Editor editor, Genre genre, long seed) {
        int index = editor.trackIndex();
        char slot = editor.viewSlot();
        int steps = editor.beat().stepCount(slot);
        int[] hits = new GrooveGenerator(seed).shuffle(genre, role(editor.viewTrack(index), index), steps);
        int[] changed = ensureAudibleChange(editor.viewTrack(index), hits, steps, seed);
        editor.edit(beat -> {
            beat.setActiveSlot(slot);
            applyTrack(beat, index, changed);
        });
    }

    /** Flips one offbeat when {@code hits} would leave the row looking the same. */
    static int[] ensureAudibleChange(Track current, int[] hits, int steps, long seed) {
        int limit = Math.min(steps, hits.length);
        for (int step = 0; step < limit; step++) {
            if (current.step(step).on() != (hits[step] > 0)) {
                return hits;
            }
        }
        int[] next = hits.clone();
        int[] spots = {14, 10, 6, 2, 15, 11, 7, 3};
        int start = (int) Math.floorMod(seed, spots.length);
        for (int i = 0; i < spots.length; i++) {
            int spot = spots[(start + i) % spots.length];
            if (spot >= limit) {
                continue;
            }
            next[spot] = next[spot] > 0 ? 0 : 96;
            return next;
        }
        return next;
    }

    /**
     * Writes a groove onto the kit rows of the active pattern.
     * Names, sounds, and the other patterns stay. Rows past the eight kit voices stay too.
     */
    public static void apply(Beat beat, Groove groove) {
        beat.setBpm(groove.bpm());
        beat.setSwing(groove.swing());
        int limit = beat.stepCount(beat.activeSlot());
        int rows = Math.min(Beat.TRACKS, beat.trackCount());
        for (int track = 0; track < rows; track++) {
            writeSteps(beat.activeTracks()[track], groove.hits(track), limit);
        }
    }

    /** Writes one row of velocities onto the active pattern. A row past the kit still takes the hits. */
    public static void applyTrack(Beat beat, int track, int[] velocities) {
        if (beat == null || track < 0 || track >= beat.trackCount() || velocities == null) {
            return;
        }
        writeSteps(beat.activeTracks()[track], velocities, beat.stepCount(beat.activeSlot()));
    }

    private static void writeSteps(Track track, int[] velocities, int limit) {
        for (int step = 0; step < limit; step++) {
            Step cell = track.step(step);
            cell.clearPitch();
            cell.setProbability(100);
            int velocity = step < velocities.length ? velocities[step] : 0;
            if (velocity > 0) {
                cell.setOn(true);
                cell.setVelocity(velocity);
            } else {
                cell.setOn(false);
            }
        }
    }

    private void boomBap() {
        bpm = range(86, 100);
        swing = range(52, 58);
        hit(KICK, 0, vary(118, 6));
        hit(KICK, 8, vary(112, 6));
        for (int step : pickSome(range(1, 2), 3, 6, 10, 11, 14)) {
            hit(KICK, step, vary(96, 10));
        }
        hit(SNARE, 4, vary(120, 4));
        hit(SNARE, 12, vary(120, 4));
        for (int step : pickSome(range(0, 2), 2, 3, 7, 11, 15)) {
            if (grid[SNARE][step] == 0) {
                hit(SNARE, step, vary(52, 10));
            }
        }
        boolean[] skip = new boolean[16];
        for (int step : pickSome(range(1, 2), 2, 6, 10, 14)) {
            skip[step] = true;
        }
        int opened = -1;
        for (int step = 0; step < 16; step += 2) {
            if (skip[step]) {
                if (opened < 0) {
                    opened = step;
                }
                continue;
            }
            hit(HAT, step, step % 4 == 0 ? vary(100, 8) : vary(82, 10));
        }
        openHat(opened < 0 ? 10 : opened, vary(90, 8));
        if (chance(50)) {
            int ghost = pick(1, 3, 5, 9, 11, 13);
            if (grid[OPEN][ghost] == 0) {
                hit(HAT, ghost, vary(60, 10));
            }
        }
        if (chance(75)) {
            hit(CLAP, 4, vary(72, 8));
            hit(CLAP, 12, vary(72, 8));
        }
    }

    private void trap() {
        bpm = range(134, 150);
        swing = 50;
        hit(KICK, 0, vary(120, 4));
        for (int step : pickSome(range(1, 3), 3, 6, 7, 10, 11, 14, 15)) {
            hit(KICK, step, vary(108, 8));
        }
        hit(SNARE, 4, vary(122, 4));
        hit(SNARE, 12, vary(122, 4));
        if (chance(40)) {
            hit(SNARE, 8, vary(70, 8));
        }
        hit(CLAP, 4, vary(110, 6));
        hit(CLAP, 12, vary(110, 6));
        for (int step = 0; step < 16; step += 2) {
            hit(HAT, step, vary(96, 10));
        }
        for (int step = 1; step < 16; step += 2) {
            if (chance(55)) {
                hit(HAT, step, vary(64, 12));
            }
        }
        hit(HAT, 13, vary(70, 8));
        hit(HAT, 14, vary(88, 8));
        hit(HAT, 15, vary(104, 8));
        if (chance(45)) {
            openHat(pick(6, 10), vary(96, 8));
        }
    }

    private void house() {
        bpm = range(120, 128);
        swing = range(50, 51);
        for (int step = 0; step < 16; step += 4) {
            hit(KICK, step, vary(120, 4));
        }
        hit(CLAP, 4, vary(112, 6));
        hit(CLAP, 12, vary(112, 6));
        for (int step = 0; step < 16; step += 2) {
            boolean offbeat = step % 4 == 2;
            hit(HAT, step, offbeat ? vary(100, 6) : vary(72, 8));
        }
        openHat(14, vary(104, 6));
        if (chance(45)) {
            openHat(6, vary(90, 6));
        }
        for (int step : pickSome(range(0, 3), 1, 3, 5, 9, 11, 13)) {
            if (grid[OPEN][step] == 0) {
                hit(HAT, step, vary(58, 8));
            }
        }
    }

    private void rock() {
        bpm = range(104, 128);
        swing = 50;
        hit(KICK, 0, vary(120, 4));
        hit(KICK, 8, vary(116, 4));
        if (chance(70)) {
            hit(KICK, 10, vary(104, 8));
        }
        if (chance(35)) {
            hit(KICK, 6, vary(90, 8));
        }
        hit(SNARE, 4, vary(122, 4));
        hit(SNARE, 12, vary(122, 4));
        hit(CRASH, 0, vary(112, 6));
        if (chance(30)) {
            for (int step = 0; step < 16; step += 2) {
                hit(RIDE, step, step % 4 == 0 ? vary(100, 6) : vary(78, 8));
            }
        } else {
            for (int step = 0; step < 16; step += 2) {
                hit(HAT, step, step % 4 == 0 ? vary(102, 6) : vary(80, 8));
            }
        }
        if (chance(50)) {
            hit(CLAP, 4, vary(64, 8));
            hit(CLAP, 12, vary(64, 8));
        }
    }

    /** Dembow: tresillo kicks, and the snare answer on the offbeats. */
    private void reggaeton() {
        bpm = range(90, 100);
        swing = 50;
        for (int step : new int[] {0, 3, 6, 8, 11, 14}) {
            hit(KICK, step, vary(116, 6));
        }
        for (int step : new int[] {2, 4, 5, 7, 10, 12, 13, 15}) {
            int center = step == 4 || step == 12 ? 118 : 100;
            hit(SNARE, step, vary(center, 6));
        }
        for (int step : new int[] {2, 6, 10, 14}) {
            hit(HAT, step, vary(84, 8));
        }
        if (chance(50)) {
            for (int step : new int[] {0, 4, 8, 12}) {
                hit(HAT, step, vary(64, 8));
            }
        }
        if (chance(40)) {
            hit(CLAP, 4, vary(90, 8));
            hit(CLAP, 12, vary(90, 8));
        }
    }

    /** Copies the bar up to this pattern's length, then adds a little hat movement and, for boom bap and rock, a tom fill. */
    private void secondBar() {
        if (steps <= 16) {
            return;
        }
        for (int track = 0; track < Beat.TRACKS; track++) {
            for (int step = 0; step < 16; step++) {
                int velocity = grid[track][step];
                if (velocity > 0 && (track == HAT || track == OPEN || track == CLAP)) {
                    velocity = vary(velocity, 5);
                }
                int target = 16 + step;
                if (target < steps) {
                    grid[track][target] = velocity;
                }
            }
        }
        int extra = 16 + pick(1, 3, 5, 9, 11, 13);
        if (extra < steps && grid[HAT][extra] == 0 && grid[OPEN][extra] == 0 && chance(80)) {
            grid[HAT][extra] = vary(68, 8);
        }
        if ((genre == Genre.ROCK || genre == Genre.BOOM_BAP) && chance(55)) {
            int low = vary(78, 8);
            int mid = vary(98, 8);
            int high = vary(114, 6);
            if (29 < steps) {
                grid[TOM][29] = low;
            }
            if (30 < steps) {
                grid[TOM][30] = mid;
            }
            if (31 < steps) {
                grid[TOM][31] = high;
            }
        }
    }

    private void fillEmpty(int[] hits, int role) {
        int length = hits.length;
        switch (role) {
            case OPEN -> hits[Math.min(14, length - 1)] = vary(96, 6);
            case CLAP -> {
                hits[Math.min(4, length - 1)] = vary(100, 6);
                hits[Math.min(12, length - 1)] = vary(100, 6);
            }
            case TOM -> {
                hits[Math.min(12, length - 1)] = vary(90, 6);
                if (length > 14) {
                    hits[14] = vary(104, 6);
                }
                if (length > 15) {
                    hits[15] = vary(116, 4);
                }
            }
            case CRASH -> hits[0] = vary(112, 4);
            case RIDE, HAT -> {
                for (int step = 0; step < length; step += 2) {
                    hits[step] = step % 4 == 0 ? vary(96, 6) : vary(76, 8);
                }
            }
            case SNARE -> {
                hits[Math.min(4, length - 1)] = vary(118, 4);
                hits[Math.min(12, length - 1)] = vary(118, 4);
            }
            default -> {
                hits[0] = vary(118, 4);
                if (length > 8) {
                    hits[8] = vary(110, 4);
                }
            }
        }
    }

    private void hit(int track, int step, int velocity) {
        if (step < 0 || step >= steps) {
            return;
        }
        grid[track][step] = clamp(velocity);
    }

    private void openHat(int step, int velocity) {
        hit(OPEN, step, velocity);
        if (step >= 0 && step < steps) {
            grid[HAT][step] = 0;
        }
    }

    private int[][] copyGrid() {
        int[][] copy = new int[Beat.TRACKS][steps];
        for (int track = 0; track < Beat.TRACKS; track++) {
            System.arraycopy(grid[track], 0, copy[track], 0, steps);
        }
        return copy;
    }

    private int[] pickSome(int count, int... values) {
        int[] bag = values.clone();
        for (int i = bag.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int swap = bag[i];
            bag[i] = bag[j];
            bag[j] = swap;
        }
        int n = Math.max(0, Math.min(count, bag.length));
        return Arrays.copyOf(bag, n);
    }

    private int pick(int... values) {
        return values[random.nextInt(values.length)];
    }

    private boolean chance(int percent) {
        return random.nextInt(100) < percent;
    }

    private int range(int min, int max) {
        return min + random.nextInt(max - min + 1);
    }

    private int vary(int center, int spread) {
        if (spread <= 0) {
            return clamp(center);
        }
        return clamp(center - spread + random.nextInt(spread * 2 + 1));
    }

    private static int clamp(int velocity) {
        return Math.max(1, Math.min(127, velocity));
    }

    private static int count(int[] hits) {
        int count = 0;
        for (int velocity : hits) {
            if (velocity > 0) {
                count++;
            }
        }
        return count;
    }
}
