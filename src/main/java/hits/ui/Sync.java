package hits.ui;

/** True while the window is pushing model values into controls, so listeners do not edit. */
final class Sync {
    private boolean on;

    boolean on() {
        return on;
    }

    void set(boolean on) {
        this.on = on;
    }

    void run(Runnable action) {
        on = true;
        try {
            action.run();
        } finally {
            on = false;
        }
    }
}
