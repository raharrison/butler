package net.ryanh.butler.step.fs;

import net.ryanh.butler.spi.RunContext;
import net.ryanh.butler.spi.StepResult;
import net.ryanh.butler.spi.StepType;
import net.ryanh.butler.util.Literals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Reads a file's contents into {@code value}.
 *
 * <p>Contents arrive exactly as they are on disk, trailing newline included; a config that wants
 * otherwise writes {@code trim(value)}.
 */
public final class ReadStep implements StepType<ReadStep.Config> {

    /**
     * @param maxBytes refuse anything larger, since the contents reach the run's memory and its
     *                 state file
     */
    public record Config(Path path, Long maxBytes) {
        public Config {
            maxBytes = maxBytes == null ? 1024 * 1024 : maxBytes;
        }
    }

    @Override
    public String name() {
        return "fs.read";
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
        return "Read a file's contents";
    }

    @Override
    public List<String> locals() {
        return List.of("value", "bytes");
    }

    @Override
    public StepResult execute(Config c, RunContext ctx) throws IOException {
        if (c.path() == null) {
            return StepResult.failed("fs.read needs a path:");
        }
        if (!Files.isReadable(c.path())) {
            return StepResult.failed("cannot read " + Literals.path(c.path()));
        }
        long size = Files.size(c.path());
        if (size > c.maxBytes()) {
            return StepResult.failed(Literals.path(c.path()) + " is " + size
                    + " bytes, over the max_bytes of " + c.maxBytes());
        }
        return StepResult.ok()
                .output("value", Files.readString(c.path()))
                .output("bytes", size);
    }

    /**
     * Reading changes nothing, so a dry run reports what a later step would see.
     */
    @Override
    public StepResult simulate(Config c, RunContext ctx) {
        try {
            return execute(c, ctx);
        } catch (IOException e) {
            return StepResult.failed(e.toString());
        }
    }

    @Override
    public String describe(Config c, RunContext ctx) {
        if (c.path() == null) {
            return "would fail: fs.read needs a path:";
        }
        return "would read   " + Literals.path(c.path());
    }

    @Override
    public List<String> preflight(Config c, RunContext ctx) {
        return c.path() != null && !Files.isReadable(c.path())
                ? List.of("cannot read: " + Literals.path(c.path()))
                : List.of();
    }
}
