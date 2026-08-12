package net.ryanh.butler.step.systemd;

/**
 * Asks a unit to reload its configuration without restarting it.
 */
public final class ReloadStep extends UnitAction {

    @Override
    public String name() {
        return "systemd.reload";
    }

    @Override
    String verb() {
        return "reload";
    }

    @Override
    public String summary() {
        return "Ask a unit to reload its configuration";
    }
}
