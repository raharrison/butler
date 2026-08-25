package net.ryanh.butler.spi;

import java.util.List;

/**
 * One unit of work, identified by {@code uses:}.
 *
 * <p>A step is a record plus a class: {@link #configType()} names a record whose components are
 * the step's parameters, and the runtime binds the config's parameter keys to it. That record is
 * also where {@code butler steps} gets its schema, so documentation cannot drift from the code.
 *
 * <p>{@link #describe} has no default: a step that cannot explain its effect cannot be written,
 * so {@code --dry-run} is a property of the SPI rather than a flag per step.
 *
 * @param <C> the step's own parameter record
 */
public interface StepType<C> {

    /**
     * The namespaced type name, e.g. {@code fs.symlink}.
     */
    String name();

    /**
     * The parameter record. Must be a record; the registry rejects anything else.
     */
    Class<C> configType();

    StepResult execute(C config, RunContext ctx) throws Exception;

    /**
     * A fully resolved account of the effect, for {@code --dry-run}.
     */
    String describe(C config, RunContext ctx);

    /**
     * Parameters this step reads as a bare condition rather than as a string template, such as
     * {@code until:} on a probe or {@code that:} on an assertion. The runtime hands those through
     * unrendered, because a condition is parsed rather than interpolated (DESIGN.md §4).
     */
    default List<String> conditions() {
        return List.of();
    }

    /**
     * Parameters the step cannot run without, so {@code butler validate} refuses a config missing
     * one rather than leaving it to fail at 3am on the fifth step (DESIGN.md §1).
     *
     * <p>Presence is all that is checked; the value may be a {@code ${...}} only a run resolves.
     * A step still guards them in {@link #execute}, where subtler rules - {@code fs.template}
     * taking {@code from:} or {@code content:} but not both - belong anyway.
     */
    default List<String> required() {
        return List.of();
    }

    /**
     * Names this step injects into its own expressions, beside the context namespaces: what a
     * condition parameter sees of the probe in flight, and what {@code extract:} reads from the
     * result. {@code status}, {@code headers}, {@code body} and {@code json} for an HTTP step.
     *
     * <p>Validation judges expressions against what can reach them, so omitting one here turns a
     * valid config into an error.
     */
    default List<String> locals() {
        return List.of();
    }

    /**
     * Read-only checks: does the source exist, is the target writable, is the unit known.
     */
    default List<String> preflight(C config, RunContext ctx) {
        return List.of();
    }

    /**
     * The result later steps read when {@link #execute} was skipped for a dry run. Often
     * computable for real: reading a symlink to report its target changes nothing.
     */
    default StepResult simulate(C config, RunContext ctx) {
        return StepResult.ok();
    }

    /**
     * One-liner for {@code butler steps}.
     */
    default String summary() {
        return "";
    }
}
