package net.ryanh.butler.step.control;

import net.ryanh.butler.spi.RunContext;
import net.ryanh.butler.spi.StepResult;
import net.ryanh.butler.spi.StepType;

import java.util.List;

/**
 * Fails the run unless a condition holds.
 */
public final class AssertStep implements StepType<AssertStep.Config> {

    /**
     * @param that    the condition, in the condition context of DESIGN.md §4
     * @param message what to say when it does not hold
     */
    public record Config(String that, String message) {
    }

    @Override
    public String name() {
        return "control.assert";
    }

    @Override
    public List<String> required() {
        return List.of("that");
    }

    @Override
    public Class<Config> configType() {
        return Config.class;
    }

    @Override
    public String summary() {
        return "Fail the run unless a condition holds";
    }

    @Override
    public List<String> conditions() {
        return List.of("that");
    }

    @Override
    public StepResult execute(Config c, RunContext ctx) {
        if (c.that() == null || c.that().isBlank()) {
            return StepResult.failed("control.assert needs a condition in \"that\"");
        }
        return ctx.evaluate(c.that())
                ? StepResult.ok()
                : StepResult.failed(c.message() == null
                                    ? "assertion failed: " + c.that() : c.message());
    }

    @Override
    public String describe(Config c, RunContext ctx) {
        if (c.that() == null || c.that().isBlank()) {
            return "would fail: control.assert needs a condition in \"that\"";
        }
        // Evaluating a condition changes nothing, so a plan can say what it currently comes to.
        String shown = ctx.resolveCondition(c.that());
        try {
            return "would assert  " + shown + "\n      currently " + ctx.evaluate(c.that());
        } catch (RuntimeException e) {
            return "would assert  " + shown + "\n      cannot be judged yet: " + e.getMessage();
        }
    }
}
