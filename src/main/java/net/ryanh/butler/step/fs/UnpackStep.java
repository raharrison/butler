package net.ryanh.butler.step.fs;

import net.ryanh.butler.spi.ProcessRunner;
import net.ryanh.butler.spi.RunContext;
import net.ryanh.butler.spi.StepResult;
import net.ryanh.butler.spi.StepType;
import net.ryanh.butler.util.Literals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Unpacks a tar archive into a directory, for a release that ships as a tarball rather than as one
 * file.
 *
 * <p>Runs {@code tar}, which detects the compression itself and refuses a member that would escape
 * the destination. That is the one {@code fs.*} step that starts a process, because a tar reader
 * of our own would be a second implementation of something every Linux host already has.
 */
public final class UnpackStep implements StepType<UnpackStep.Config> {

    /**
     * @param stripComponents leading path elements to drop, for an archive that wraps everything
     *                        in one directory named after the version
     * @param mkdirs          create {@code to} if it is missing; true unless the config says
     *                        otherwise
     */
    public record Config(Path from, Path to, Integer stripComponents, Boolean mkdirs) {
        public Config {
            mkdirs = mkdirs == null || mkdirs;
        }
    }

    @Override
    public String name() {
        return "fs.unpack";
    }

    @Override
    public List<String> required() {
        return List.of("from", "to");
    }

    @Override
    public Class<Config> configType() {
        return Config.class;
    }

    @Override
    public String summary() {
        return "Unpack a tar archive into a directory";
    }

    @Override
    public List<String> locals() {
        return List.of("path", "stdout", "stderr", "exit_code");
    }

    @Override
    public StepResult execute(Config c, RunContext ctx) throws IOException {
        String problem = problem(c);
        if (problem != null) {
            return StepResult.failed(problem);
        }
        if (c.mkdirs()) {
            Files.createDirectories(c.to());
        }
        List<String> argv = argv(c, c.from().toString(), c.to().toString());
        StepResult result = StepResult.of(ctx.processes().run(ctx.command().argv(argv)), "tar");
        return result.isFailed() ? result : result.output("path", Literals.path(c.to()));
    }

    /**
     * {@code --no-same-owner} because the uids in an archive built elsewhere mean nothing here:
     * extracting as root would otherwise scatter a build machine's numbering across the host.
     */
    private static List<String> argv(Config c, String from, String to) {
        List<String> argv = new ArrayList<>(List.of("tar", "--no-same-owner"));
        if (c.stripComponents() != null && c.stripComponents() > 0) {
            argv.add("--strip-components=" + c.stripComponents());
        }
        argv.addAll(List.of("-x", "-f", from, "-C", to));
        return List.copyOf(argv);
    }

    /**
     * @return why this step cannot run, or null
     */
    private static String problem(Config c) {
        if (c.from() == null || c.to() == null) {
            return "fs.unpack needs both from: and to:";
        }
        if (c.stripComponents() != null && c.stripComponents() < 0) {
            return "strip_components must not be negative, found " + c.stripComponents();
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
        lines.add("would unpack " + Literals.path(c.from()));
        lines.add("      into   " + Literals.path(c.to()));

        List<String> notes = new ArrayList<>();
        if (c.stripComponents() != null && c.stripComponents() > 0) {
            notes.add("dropping " + c.stripComponents()
                    + (c.stripComponents() == 1 ? " leading path element" : " leading path elements"));
        }
        if (c.mkdirs() && !Files.isDirectory(c.to())) {
            notes.add("creating the directory");
        }
        if (!notes.isEmpty()) {
            lines.add("      " + String.join(", ", notes));
        }
        // The paths as the config spelled them, so the command reads like the two lines above it.
        List<String> argv = argv(c, Literals.path(c.from()), Literals.path(c.to()));
        lines.add("      " + new ProcessRunner.Command(argv, null, null, null, null).display());
        return String.join("\n", lines);
    }

    @Override
    public List<String> preflight(Config c, RunContext ctx) {
        if (problem(c) != null) {
            return List.of();
        }
        List<String> warnings = new ArrayList<>();
        if (!Files.exists(c.from(), LinkOption.NOFOLLOW_LINKS)) {
            warnings.add("archive does not exist: " + Literals.path(c.from()));
        } else if (!Files.isReadable(c.from())) {
            warnings.add("archive is not readable: " + Literals.path(c.from()));
        }
        if (Files.exists(c.to()) && !Files.isDirectory(c.to())) {
            warnings.add("exists and is not a directory: " + Literals.path(c.to()));
        } else if (!Files.isDirectory(c.to()) && !c.mkdirs()) {
            warnings.add("destination does not exist and mkdirs is false: "
                    + Literals.path(c.to()));
        }
        return List.copyOf(warnings);
    }
}
