package net.ryanh.butler.step.systemd;

/**
 * Starts a unit, and waits for it to be active if {@code wait_active:} says how long to give it.
 */
public final class StartStep extends UnitAction {

    @Override
    public String name() {
        return "systemd.start";
    }

    @Override
    String verb() {
        return "start";
    }

    @Override
    public String summary() {
        return "Start a unit, waiting for it to become active";
    }
}
