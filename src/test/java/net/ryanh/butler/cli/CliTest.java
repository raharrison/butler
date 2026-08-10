package net.ryanh.butler.cli;

import net.ryanh.butler.Main;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CliTest {

    @TempDir
    Path dir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private PrintStream originalOut;
    private PrintStream originalErr;

    @BeforeEach
    void captureStreams() {
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restoreStreams() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String stderr() {
        return err.toString(StandardCharsets.UTF_8);
    }

    private Path write(String name, String content) throws IOException {
        Path p = dir.resolve(name);
        Files.writeString(p, content);
        return p;
    }

    private Path canonical() throws IOException {
        try (InputStream in = CliTest.class.getResourceAsStream("/configs/canonical.yaml")) {
            assertNotNull(in);
            return write("canonical.yaml", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static final String MINIMAL = """
            jobs:
              hello:
                on: [{uses: manual}]
                steps: [{uses: control.log, message: hi}]
            """;

    @Nested
    @DisplayName("validate")
    class Validate {

        @Test
        void goodConfigExitsZero() throws IOException {
            int code = Main.run("validate", "-c", canonical().toString());
            assertEquals(0, code);
            assertTrue(stdout().contains("ok"), stdout());
        }

        @Test
        void badConfigExitsOneAndReportsEverything() throws IOException {
            Path p = write("bad.yaml", """
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps: [{uses: control.log}]
                        tiemout: 30s
                        when: triger.x == 1
                    """);
            int code = Main.run("validate", "-c", p.toString());
            assertEquals(1, code);
            assertTrue(stderr().contains("tiemout"), stderr());
            assertTrue(stderr().contains("triger"), stderr());
            assertTrue(stderr().contains("2 errors"), stderr());
        }

        @Test
        void warningsAloneStillExitZero() throws IOException {
            Path p = write("warn.yaml", """
                    jobs:
                      j:
                        on: [{uses: manual}]
                        when: state.v != "1"
                        steps: [{uses: control.log}]
                    """);
            int code = Main.run("validate", "-c", p.toString());
            assertEquals(0, code, "a warning is not a failure");
            assertTrue(stderr().contains("warning"), stderr());
        }

        @Test
        void missingFileIsReportedClearly() {
            int code = Main.run("validate", "-c", dir.resolve("nope.yaml").toString());
            assertEquals(1, code);
            assertTrue(stderr().contains("no such config file"), stderr());
        }

        @Test
        void directoryInsteadOfFile() {
            int code = Main.run("validate", "-c", dir.toString());
            assertEquals(1, code);
            assertTrue(stderr().contains("is a directory"), stderr());
        }
    }

    @Nested
    @DisplayName("check")
    class Check {

        @Test
        void printsResolvedConfigWithDefaultsFilledIn() throws IOException {
            Path p = write("min.yaml", MINIMAL);
            assertEquals(0, Main.run("check", "-c", p.toString()));
            String s = stdout();
            assertTrue(s.contains("max_concurrent_runs: 4"), s);
            assertTrue(s.contains("group=hello"), s);
            assertTrue(s.contains("poll_interval: 5s"), s);
        }

        @Test
        void rendersServerPathsWithForwardSlashes() throws IOException {
            Path p = write("min.yaml", MINIMAL);
            Main.run("check", "-c", p.toString());
            assertTrue(stdout().contains("state_dir: /var/lib/butler"),
                    "a Linux config checked on any OS should read back the same:\n" + stdout());
        }

        @Test
        void quotesScalarsThatWouldOtherwiseReparseWrong() throws IOException {
            Path p = write("quoting.yaml", """
                    notifiers:
                      ops:
                        uses: notify.slack
                        channel: "#deploys"
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps:
                          - uses: fs.copy
                            mode: "0640"
                            version: "1.2"
                            flag: "true"
                            plain: hello
                    """);
            Main.run("check", "-c", p.toString());
            String s = stdout();
            assertTrue(s.contains("channel: \"#deploys\""), "# would read back as a comment:\n" + s);
            assertTrue(s.contains("mode: \"0640\""), "0640 would read back as octal 416:\n" + s);
            assertTrue(s.contains("version: \"1.2\""), "1.2 would read back as a float:\n" + s);
            assertTrue(s.contains("flag: \"true\""), "true would read back as a boolean:\n" + s);
            assertTrue(s.contains("plain: hello"), "ordinary text needs no quotes:\n" + s);
        }

        @Test
        void doesNotQuoteGenuineNumbersAndBooleans() throws IOException {
            Path p = write("types.yaml", MINIMAL);
            Main.run("check", "-c", p.toString());
            String s = stdout();
            assertTrue(s.contains("max_concurrent_runs: 4"), s);
            assertTrue(s.contains("from_env: true"), s);
        }

        @Test
        void refusesToPrintAnInvalidConfig() throws IOException {
            Path p = write("bad.yaml", "jobs:\n  j:\n    steps: [{uses: control.log}]\n");
            assertEquals(1, Main.run("check", "-c", p.toString()));
            assertTrue(stdout().isEmpty(), "nothing should be printed for an invalid config");
        }
    }

    @Nested
    @DisplayName("daemon startup")
    class Daemon {

        @Test
        void refusesToStartWithAnInvalidConfig() throws IOException {
            Path p = write("bad.yaml", "jobs:\n  j:\n    on: [{uses: manual}]\n");
            int code = Main.run("-c", p.toString());
            assertEquals(1, code);
            assertTrue(stderr().contains("refusing to start"), stderr());
        }

        @Test
        void acceptsAValidConfig() throws IOException {
            // --check-only stands in for the daemon loop, which lands in M4.
            assertEquals(0, Main.run("-c", canonical().toString(), "--check-only"));
        }
    }

    @Nested
    @DisplayName("usage")
    class Usage {

        @Test
        void badOptionExitsTwo() {
            assertEquals(2, Main.run("validate", "--nonsense"),
                    "picocli reserves exit 2 for usage errors, and CI relies on the distinction");
        }

        @Test
        void helpExitsZeroAndListsSubcommands() {
            assertEquals(0, Main.run("--help"));
            String s = stdout();
            for (String cmd : new String[]{"validate", "check", "trigger", "adopt", "steps"}) {
                assertTrue(s.contains(cmd), "missing " + cmd + " in help:\n" + s);
            }
        }

        @Test
        void versionExitsZero() {
            assertEquals(0, Main.run("--version"));
            assertTrue(stdout().contains("butler"), stdout());
        }

        @Test
        void unimplementedCommandsSayWhichMilestone() throws IOException {
            Path p = write("min.yaml", MINIMAL);
            assertEquals(1, Main.run("trigger", "hello", "-c", p.toString()));
            assertTrue(stderr().contains("M3"), stderr());
        }

        @Test
        void triggerRejectsAnUnknownJobBeforeComplainingAboutTheMilestone() throws IOException {
            Path p = write("min.yaml", MINIMAL);
            assertEquals(1, Main.run("trigger", "nope", "-c", p.toString()));
            assertTrue(stderr().contains("no job named \"nope\""), stderr());
            assertTrue(stderr().contains("hello"), stderr());
        }
    }
}
