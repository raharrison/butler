package net.ryanh.butler.step.http;

import net.ryanh.butler.spi.RunContext;
import net.ryanh.butler.spi.StepResult;
import net.ryanh.butler.spi.StepType;
import net.ryanh.butler.step.fs.Fs;
import net.ryanh.butler.util.Literals;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Fetches a file over HTTP.
 *
 * <p>The body is streamed to a temporary file beside the destination and moved over it once the
 * whole of it has arrived and any {@code checksum:} has held. A status that is not 2xx writes no
 * file at all.
 */
public final class DownloadStep implements StepType<DownloadStep.Config> {

    /**
     * @param checksum the sha256 the file must have, as {@code sha256:<hex>} or a bare hex digest
     * @param mkdirs   create the directories above {@code to} if they are missing
     */
    public record Config(String url, Path to, Map<String, String> headers, String checksum,
                         String mode, String owner, String group, boolean mkdirs) {
        public Config {
            headers = headers == null ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        }
    }

    @Override
    public String name() {
        return "http.download";
    }

    @Override
    public List<String> required() {
        return List.of("url", "to");
    }

    @Override
    public Class<Config> configType() {
        return Config.class;
    }

    @Override
    public String summary() {
        return "Download a file, checking what arrived before it lands";
    }

    @Override
    public List<String> locals() {
        return List.of("path", "bytes", "sha256", "status");
    }

    @Override
    public StepResult execute(Config c, RunContext ctx) throws IOException {
        String problem = problem(c);
        if (problem != null) {
            return StepResult.failed(problem);
        }
        String expected;
        try {
            expected = Fs.named(c.checksum()) ? Http.expectedHex(c.checksum()) : null;
        } catch (IllegalArgumentException e) {
            return StepResult.failed(e.getMessage());
        }
        if (c.mkdirs() && c.to().getParent() != null) {
            Files.createDirectories(c.to().getParent());
        }
        Path directory = c.to().toAbsolutePath().getParent();
        if (directory == null || !Files.isDirectory(directory)) {
            return StepResult.failed("no directory to write into: "
                    + Literals.path(c.to().getParent()));
        }

        HttpResponse<InputStream> response = Http.open(c.url(), c.headers(),
                ctx.command().timeout());
        if (!Http.acceptable(response.statusCode(), List.of())) {
            String said = Http.errorExcerpt(response);
            return StepResult.failed("GET " + c.url() + " answered " + response.statusCode()
                            + ", expected 2xx" + (said.isEmpty() ? "" : ": " + said))
                    .output("status", (long) response.statusCode());
        }

        // Beside the destination, so the move onto it stays within one filesystem.
        Path temp = Files.createTempFile(directory, c.to().getFileName().toString(), ".part");
        try {
            Http.Downloaded downloaded = Http.stream(response, temp);
            if (expected != null && !expected.equals(downloaded.sha256())) {
                return StepResult.failed("checksum mismatch: expected " + expected + ", got "
                        + downloaded.sha256());
            }
            Fs.applyMode(temp, c.mode());
            Fs.applyOwnership(temp, c.owner(), c.group());
            Http.replace(temp, c.to());
            return StepResult.ok()
                    .output("path", Literals.path(c.to()))
                    .output("bytes", downloaded.bytes())
                    .output("sha256", downloaded.sha256())
                    .output("status", (long) response.statusCode());
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * @return why this step cannot run, or null
     */
    private static String problem(Config c) {
        if (c.url() == null || c.url().isBlank()) {
            return "http.download needs a url:";
        }
        if (c.to() == null || c.to().toString().isBlank()) {
            return "http.download needs a to:";
        }
        if (Files.isDirectory(c.to())) {
            return "to: is a directory; name the file to write: " + Literals.path(c.to());
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
        lines.add("would fetch  " + c.url());
        lines.add("      to     " + Literals.path(c.to()));

        List<String> notes = new ArrayList<>();
        if (Fs.named(c.checksum())) {
            notes.add("checking sha256");
        }
        if (Fs.named(c.mode())) {
            notes.add("mode " + c.mode());
        }
        String ownership = Fs.ownership(c.owner(), c.group());
        if (ownership != null) {
            notes.add(ownership);
        }
        int missing = Fs.missingParents(c.to());
        if (c.mkdirs() && missing > 0) {
            notes.add("creating " + Fs.parents(missing));
        }
        if (Files.exists(c.to())) {
            notes.add("replacing what is there");
        }
        if (!notes.isEmpty()) {
            lines.add("      " + String.join(", ", notes));
        }
        return String.join("\n", lines);
    }

    @Override
    public List<String> preflight(Config c, RunContext ctx) {
        if (problem(c) != null) {
            return List.of();
        }
        List<String> warnings = new ArrayList<>();
        try {
            URI uri = URI.create(c.url());
            if (uri.getScheme() == null || uri.getHost() == null) {
                warnings.add("url is not absolute: " + c.url());
            }
        } catch (IllegalArgumentException e) {
            warnings.add("url does not parse: " + c.url());
        }
        if (Fs.named(c.checksum())) {
            try {
                Http.expectedHex(c.checksum());
            } catch (IllegalArgumentException e) {
                warnings.add(e.getMessage());
            }
        }
        Path parent = c.to().getParent();
        if (parent != null && !Files.isDirectory(parent) && !c.mkdirs()) {
            warnings.add("destination directory does not exist and mkdirs is false: "
                    + Literals.path(parent));
        }
        warnings.addAll(Fs.ownershipChecks(c.owner(), c.group()));
        return List.copyOf(warnings);
    }
}
