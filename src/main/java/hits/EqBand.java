package hits;

/**
 * One EQ band. A gain of 0 is unused and is left out of the file.
 * Peaking is the default type, and a band is on unless it is bypassed,
 * so an older file that stores only Hertz, gain, and Q is unchanged.
 */
public record EqBand(int hz, int gainTenths, int qTenths, Type type, boolean enabled) {
    public enum Type {
        PEAK,
        LOW_SHELF,
        HIGH_SHELF;

        public String json() {
            return switch (this) {
                case LOW_SHELF -> "lowShelf";
                case HIGH_SHELF -> "highShelf";
                case PEAK -> "peak";
            };
        }

        /** {@code peak}, {@code low shelf}, or {@code high shelf}, for the info line and warnings. */
        public String label() {
            return switch (this) {
                case LOW_SHELF -> "low shelf";
                case HIGH_SHELF -> "high shelf";
                case PEAK -> "peak";
            };
        }

        /**
         * {@code peak}, {@code lowShelf}, and {@code highShelf}.
         * A blank value is peaking. Anything else is unknown and returns null.
         */
        public static Type parse(String text) {
            if (text == null || text.isBlank()) {
                return PEAK;
            }
            if (text.equalsIgnoreCase("peak") || text.equalsIgnoreCase("peaking")) {
                return PEAK;
            }
            if (text.equalsIgnoreCase("lowShelf") || text.equalsIgnoreCase("low-shelf") || text.equalsIgnoreCase("lowshelf")) {
                return LOW_SHELF;
            }
            if (text.equalsIgnoreCase("highShelf") || text.equalsIgnoreCase("high-shelf") || text.equalsIgnoreCase("highshelf")) {
                return HIGH_SHELF;
            }
            return null;
        }
    }

    public EqBand(int hz, int gainTenths, int qTenths) {
        this(hz, gainTenths, qTenths, Type.PEAK, true);
    }

    public EqBand {
        if (type == null) {
            type = Type.PEAK;
        }
    }

    public double gain() {
        return Db.fromTenths(gainTenths);
    }

    public double q() {
        return Db.fromTenths(qTenths);
    }

    /** A non-zero gain. A bypassed band is still stored. */
    public boolean active() {
        return gainTenths != 0;
    }

    /** Heard: stored, and this band's own bypass is off. */
    public boolean audible() {
        return active() && enabled;
    }
}
