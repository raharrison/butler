package net.ryanh.butler.step.fs;

import net.ryanh.butler.spi.RunContext;
import net.ryanh.butler.spi.StepResult;
import net.ryanh.butler.spi.StepType;
import net.ryanh.butler.util.Literals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes a file whose {@code ${...}} holes are filled in from the run.
 */
public final class TemplateStep implements StepType<TemplateStep.Config> {

    /**
     * @param from    a template file, rendered by this step
     * @param content the template inline instead, already rendered by the time the step sees it
     *                like any other parameter
     */
    public record Config(Path from, String content, Path to, String mode, String owner,
                         String group, boolean mkdirs) {
    }

    @Override
    public String name() {
        return "fs.template";
    }

    @Override
    public List<String> required() {
        return List.of("to");
    }

    @Override
    public Class<Config> configType() {
        return Config.class;
    }

    @Override
    public String summary() {
        return "Write a file, filling in ${...} from the run";
    }

    @Override
    public List<String> locals() {
        return List.of("path", "bytes");
    }

    @Override
    public StepResult execute(Config c, RunContext ctx) throws IOException {
        String problem = problem(c);
        if (problem != null) {
            return StepResult.failed(problem);
        }
        String rendered = render(c, ctx);
        if (c.mkdirs() && c.to().getParent() != null) {
            Files.createDirectories(c.to().getParent());
        }
        Files.writeString(c.to(), rendered);
        Fs.applyMode(c.to(), c.mode());
        Fs.applyOwnership(c.to(), c.owner(), c.group());
        return StepResult.ok()
                .output("path", Literals.path(c.to()))
                .output("bytes", (long) rendered.length());
    }

    private static String render(Config c, RunContext ctx) throws IOException {
        return c.from() != null ? ctx.resolve(Files.readString(c.from()))
                : c.content() == null ? "" : c.content();
    }

    /**
     * @return why this step cannot run, or null
     */
    private static String problem(Config c) {
        if (c.to() == null) {
            return "fs.template needs a to:";
        }
        if (c.from() == null && c.content() == null) {
            return "fs.template needs either from: or content:";
        }
        if (c.from() != null && c.content() != null) {
            return "fs.template takes from: or content:, not both";
        }
        return null;
    }

    @Override
    public String describe(Config c, RunContext ctx) {
        String problem = problem(c);
        if (problem != null) {
            return "would fail: " + problem;
        }
        List<String> lines = new ArrayList<>();
        lines.add("would write  " + Literals.path(c.to()));
        if (c.from() != null) {
            lines.add("      from   " + Literals.path(c.from()));
        }
        try {
            for (String line : render(c, ctx).stripTrailing().split("\n")) {
                lines.add("      | " + line);
            }
        } catch (IOException e) {
            // The name only: the message carries a path in the platform's spelling.
            lines.add("      cannot be rendered yet: " + e.getClass().getSimpleName());
        }
        if (Fs.named(c.mode())) {
            lines.add("      mode " + c.mode());
        }
        String ownership = Fs.ownership(c.owner(), c.group());
        if (ownership != null) {
            lines.add("      " + ownership);
        }
        return String.join("\n", lines);
    }

    @Override
    public List<String> preflight(Config c, RunContext ctx) {
        if (c.to() == null) {
            return List.of();
        }
        List<String> warnings = new ArrayList<>();
        if (c.from() != null) {
            warnings.addAll(Fs.transferChecks(c.from(), c.to(), c.mkdirs()));
        }
        warnings.addAll(Fs.ownershipChecks(c.owner(), c.group()));
        return List.copyOf(warnings);
    }
}
