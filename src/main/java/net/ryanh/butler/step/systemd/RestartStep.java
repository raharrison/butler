package net.ryanh.butler.step.systemd;

/**
 * Restarts a unit, and waits for it to come back if {@code wait_active:} says how long to give it.
 */
public final class RestartStep extends UnitAction {

    @Override
    public String name() {
        return "systemd.restart";
    }

    @Override
    String verb() {
        return "restart";
    }

    @Override
    public String summary() {
        return "Restart a unit, waiting for it to become active";
    }
}
