package net.ryanh.butler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The shaded jar, run as a subprocess. Gradle builds it before {@code test} and passes its path in
 * {@code butler.jar}.
 *
 * <p>The failure this exists for is {@code META-INF/services}: the whole vocabulary arrives by
 * {@link java.util.ServiceLoader}, so a shade that drops those files leaves a jar that starts,
 * reports every {@code uses:} as unknown, and passes every test on the class path.
 */
class PackagingTest {

    @TempDir
    Path dir;

    private static Path jar() {
        String path = System.getProperty("butler.jar");
        assertNotNull(path, "butler.jar was not set; run this through Gradle");
        Path jar = Path.of(path);
        assertTrue(Files.exists(jar), "no shadow jar at " + jar);
        return jar;
    }

    private record Result(int code, String out, String err) {
    }

    private static Result butler(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", jar().toString()));
        command.addAll(List.of(args));

        Process process = new ProcessBuilder(command).start();
        // Both pipes are drained at once: reading one to the end first would deadlock the jar as
        // soon as it filled the other.
        Reader out = read(process.getInputStream());
        Reader err = read(process.getErrorStream());
        assertTrue(process.waitFor(2, TimeUnit.MINUTES), "the jar never exited");
        return new Result(process.exitValue(), out.text(), err.text());
    }

    private record Reader(Thread thread, StringBuilder buffer) {

        String text() throws InterruptedException {
            thread.join();
            return buffer.toString();
        }
    }

    private static Reader read(InputStream stream) {
        StringBuilder buffer = new StringBuilder();
        Thread thread = Thread.ofVirtual().start(() -> {
            try (stream) {
                buffer.append(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        return new Reader(thread, buffer);
    }

    @Test
    @DisplayName("the shaded jar keeps every ServiceLoader registration")
    void theVocabularySurvivesShading() throws Exception {
        Result steps = butler("steps");
        assertEquals(0, steps.code(), steps.err());
        for (String type : List.of("control.log", "fs.symlink", "http.wait", "systemd.restart",
                "shell.run", "notify.send")) {
            assertTrue(steps.out().contains(type),
                    "missing " + type + " from the shaded jar:\n" + steps.out());
        }
    }

    @Test
    @DisplayName("the canonical config validates through the jar, so triggers and notifiers "
            + "survived too")
    void theAcceptanceConfigValidates() throws Exception {
        Path config = dir.resolve("canonical.yaml");
        try (var in = PackagingTest.class.getResourceAsStream("/configs/canonical.yaml")) {
            assertNotNull(in);
            Files.write(config, in.readAllBytes());
        }

        Result result = butler("validate", "-c", config.toString());
        assertEquals(0, result.code(), result.err());
        assertEquals("", result.err(), "zero errors and zero warnings, from the shipped artifact");
    }

    @Test
    @DisplayName("a job runs end to end from the jar, writing state and a run record")
    void aRunFromTheJar() throws Exception {
        Path state = dir.resolve("state");
        Path config = dir.resolve("butler.yaml");
        Files.writeString(config, """
                settings:
                  state_dir: %s
                jobs:
                  hello:
                    on: [{uses: manual}]
                    steps:
                      - name: Greet
                        uses: control.set
                        vars: {who: world}
                    persist:
                      greeted: ${vars.who}
                """.formatted(state.toString().replace('\\', '/')));

        Result result = butler("trigger", "hello", "-c", config.toString());
        assertEquals(0, result.code(), result.err());
        assertTrue(result.out().contains("SUCCESS in"), result.out());
        assertTrue(Files.readString(state.resolve("jobs/hello.json")).contains("world"));
        assertTrue(Files.exists(state.resolve("runs/index.jsonl")),
                "a real run leaves an audit record");
    }

    @Test
    @DisplayName("--version reads the version out of the manifest the build wrote")
    void versionComesFromTheManifest() throws Exception {
        Result result = butler("--version");
        assertEquals(0, result.code(), result.err());
        assertTrue(result.out().startsWith("butler "), result.out());
        assertFalse(result.out().contains("(dev)"),
                "a packaged jar knows its own version:\n" + result.out());
    }

    @Test
    @DisplayName("the completion script is generated from the real command tree")
    void completionScript() throws Exception {
        Result result = butler("generate-completion");
        assertEquals(0, result.code(), result.err());
        assertTrue(result.out().contains("complete -F _complete_butler"), result.out());
        for (String command : List.of("validate", "check", "trigger", "adopt", "steps")) {
            assertTrue(result.out().contains(command), "missing " + command + " from completion");
        }
    }
}
