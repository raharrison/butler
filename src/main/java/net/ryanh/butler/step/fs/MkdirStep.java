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
 * Creates a directory, and the ones above it unless told otherwise.
 */
public final class MkdirStep implements StepType<MkdirStep.Config> {

    public record Config(Path path, String mode, Boolean parents) {
        public Config {
            parents = parents == null || parents;
        }
    }

    @Override
    public String name() {
        return "fs.mkdir";
    }

    @Override
    public List<String> required() {
        return List.of("path");
    }

    @Override
    public Class<Config> configType() {
        return Config.class;
    }

    @Override
    public String summary() {
        return "Create a directory";
    }

    @Override
    public List<String> locals() {
        return List.of("path", "created");
    }

    @Override
    public StepResult execute(Config c, RunContext ctx) throws IOException {
        if (c.path() == null) {
            return StepResult.failed("fs.mkdir needs a path:");
        }
        boolean existed = Files.isDirectory(c.path());
        if (c.parents()) {
            Files.createDirectories(c.path());
        } else if (!existed) {
            Files.createDirectory(c.path());
        }
        Fs.applyMode(c.path(), c.mode());
        return StepResult.ok()
                .output("path", Literals.path(c.path()))
                .output("created", !existed);
    }

    @Override
    public String describe(Config c, RunContext ctx) {
        if (c.path() == null) {
            return "would fail: fs.mkdir needs a path:";
        }
        if (Files.isDirectory(c.path())) {
            return "would leave  " + Literals.path(c.path()) + " alone: it already exists";
        }
        List<String> lines = new ArrayList<>();
        lines.add("would create " + Literals.path(c.path()));
        int missing = Fs.missingParents(c.path());
        if (c.parents() && missing > 0) {
            lines.add("      along with " + Fs.parents(missing));
        }
        if (c.mode() != null && !c.mode().isBlank()) {
            lines.add("      mode " + c.mode());
        }
        return String.join("\n", lines);
    }

    @Override
    public List<String> preflight(Config c, RunContext ctx) {
        if (c.path() == null) {
            return List.of();
        }
        List<String> warnings = new ArrayList<>();
        if (Files.exists(c.path()) && !Files.isDirectory(c.path())) {
            warnings.add("exists and is not a directory: " + Literals.path(c.path()));
        }
        if (!c.parents() && Fs.missingParents(c.path()) > 0) {
            warnings.add("the directory above it does not exist and parents is false: "
                    + Literals.path(c.path()));
        }
        return List.copyOf(warnings);
    }
}
