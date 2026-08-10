package net.ryanh.butler.spi;

import java.util.List;

/**
 * One unit of work, identified by {@code uses:}.
 *
 * <p>A step is a record plus a class: {@link #configType()} names a record whose components are
 * the step's parameters, and the runtime binds the config's parameter keys to it. That record is
 * also where {@code butler steps} gets its schema, so documentation cannot drift from the code.
 *
 * <p>{@link #describe} has no default implementation on purpose. A step that cannot explain its
 * effect cannot be written, which is what makes {@code --dry-run} a property of the SPI rather
 * than a flag each step interprets for itself.
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
     * A fully resolved account of the effect, for {@code --dry-run}. Required, deliberately.
     */
    String describe(C config, RunContext ctx);

    /**
     * Read-only checks: does the source exist, is the target writable, is the unit known.
     */
    default List<String> preflight(C config, RunContext ctx) {
        return List.of();
    }

    /**
     * The result later steps should read when {@link #execute} was skipped for a dry run. Often
     * computable for real: reading a symlink to report its current target changes nothing, so the
     * rollback branch of a pipeline still describes correctly.
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
