package net.ryanh.butler.spi;

import java.util.Map;

/**
 * Everything a step is allowed to know about the run it is part of.
 *
 * <p>Passed explicitly to every SPI method rather than held in ambient state, so a step has no way
 * to reach the run except through this interface. {@link #resolve} and {@link #evaluate} exist so
 * a step never touches the expression classes directly.
 */
public interface RunContext {

    /**
     * One context namespace - {@code vars}, {@code trigger}, {@code steps}, {@code state},
     * {@code env}, {@code secret}, {@code run} or {@code butler}.
     *
     * @return the namespace, or an empty map if there is no such name
     */
    Map<String, Object> namespace(String name);

    /**
     * Renders text containing {@code ${expr}} holes.
     */
    String resolve(String template);

    /**
     * As {@link #resolve}, but a template that is exactly one hole keeps the value's type.
     */
    Object resolveValue(String template);

    /**
     * Evaluates a bare condition, as {@code when:} and {@code until:} take.
     */
    boolean evaluate(String condition);

    /**
     * A view of this context with step-injected locals layered over the namespaces, winning where a
     * name collides. This is what {@code http.wait}'s {@code until:} sees its probe through and what
     * {@code extract:} evaluates against (DESIGN.md §4).
     */
    RunContext withLocals(Map<String, Object> locals);

    /**
     * How this step starts a process.
     */
    ProcessRunner processes();

    /**
     * This step's process settings as a command with no argv yet: {@code working_dir}, its
     * {@code env} merged over the job's, {@code run_as} and the time it is allowed. A step that
     * forks fills in the argv and hands it to {@link #processes()}.
     */
    ProcessRunner.Command command();

    /**
     * Whether this run is only being described. Steps rarely need it; the runtime decides.
     */
    boolean dryRun();
}
