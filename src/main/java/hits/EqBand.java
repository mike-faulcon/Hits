package hits;

/** One peaking band. A gain of 0 is unused and is left out of the file. */
public record EqBand(int hz, int gainTenths, int qTenths) {
    public double gain() {
        return Db.fromTenths(gainTenths);
    }

    public double q() {
        return Db.fromTenths(qTenths);
    }

    public boolean active() {
        return gainTenths != 0;
    }
}
