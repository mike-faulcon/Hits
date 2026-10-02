package hits;

/** Starting patterns. Reggae follows the off-beat grid in beats/reggae1.btf. */
public final class Kits {
    private Kits() {}

    public static void boomBap(Beat beat) {
        beat.setBpm(96);
        beat.setSwing(54);
        beat.setStepCount(16);
        resetDrums(beat);
        Track kick = beat.track(0);
        Track snare = beat.track(1);
        Track hat = beat.track(2);
        Track open = beat.track(3);
        Track clap = beat.track(4);
        Track bass = beat.track(6);
        Track stab = beat.track(7);

        hit(kick, 0, 120);
        hit(kick, 8, 115);
        hit(kick, 10, 100);

        hit(snare, 4, 122);
        hit(snare, 7, 48);
        hit(snare, 12, 122);

        for (int step = 0; step < 16; step += 2) {
            if (step != 6) {
                hit(hat, step, 96);
            }
        }
        hit(open, 6, 90);
        hit(clap, 4, 72);
        hit(clap, 12, 72);

        asNote(bass, "Bass", 33, 36, 75);
        hit(bass, 0, 110);
        hit(bass, 8, 100);
        hit(bass, 10, 90);

        asNote(stab, "Stab", 63, 60, 40);
        hit(stab, 0, 78);
    }

    public static void reggae(Beat beat) {
        beat.setBpm(86);
        beat.setSwing(52);
        beat.setStepCount(16);
        resetDrums(beat);

        Track kick = beat.track(0);
        Track stick = beat.track(1);
        stick.setNote(37);
        stick.setName("Stick");
        Track hat = beat.track(2);
        Track skank = beat.track(5);
        Track bass = beat.track(6);
        Track xylo = beat.track(7);

        hit(kick, 8, 120);
        hit(stick, 8, 100);
        hit(hat, 2, 85);
        hit(hat, 6, 85);
        hit(hat, 10, 85);
        hit(hat, 14, 85);

        asNote(skank, "Skank", 18, 67, 35);
        hit(skank, 2, 100);
        hit(skank, 6, 100);
        hit(skank, 10, 100);
        hit(skank, 14, 100);

        asNote(bass, "Bass", 33, 36, 80);
        hit(bass, 0, 110);
        hit(bass, 8, 120);
        hit(bass, 11, 80);

        asNote(xylo, "Xylo", 13, 60, 40);
        hit(xylo, 4, 90);
        hit(xylo, 12, 90);
    }

    private static void resetDrums(Beat beat) {
        Track[] fresh = Beat.drumKit(beat.name()).slot('a');
        beat.replaceSlot(beat.activeSlot(), fresh);
    }

    private static void asNote(Track track, String name, int program, int note, int gate) {
        track.setMode(TrackMode.NOTE);
        track.setName(name);
        track.setProgram(program);
        track.setNote(note);
        track.setGate(gate);
    }

    private static void hit(Track track, int step, int velocity) {
        track.step(step).setOn(true);
        track.step(step).setVelocity(velocity);
    }
}
