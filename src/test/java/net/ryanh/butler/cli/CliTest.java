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

    private Path fixture(String name) throws IOException {
        try (InputStream in = CliTest.class.getResourceAsStream("/configs/" + name)) {
            assertNotNull(in, "missing fixture: " + name);
            return write(name, new String(in.readAllBytes(), StandardCharsets.UTF_8));
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
            int code = Main.run("validate", "-c", fixture("plan.yaml").toString());
            assertEquals(0, code);
            assertTrue(stdout().contains("ok"), stdout());
        }

        @Test
        @DisplayName("the canonical config names the step types this build cannot run")
        void reportsStepTypesThatDoNotExistYet() throws IOException {
            // The acceptance config is written against the full v1 vocabulary, which arrives with
            // the fs, systemd and http steps. Until then validate says so, per step, with a line.
            int code = Main.run("validate", "-c", fixture("canonical.yaml").toString());
            assertEquals(1, code);
            String out = stderr();
            assertTrue(out.contains("unknown step type \"fs.copy\""), out);
            assertTrue(out.contains("unknown trigger type \"file.appeared\""), out);
            assertTrue(out.contains("canonical.yaml:65:9"), out);
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
                    vars:
                      mode: "0640"
                      version: "1.2"
                      flag: "true"
                      plain: hello
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps: [{uses: control.log, message: hi}]
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
            assertEquals(0, Main.run("-c", fixture("plan.yaml").toString(), "--check-only"));
        }
    }

    @Nested
    @DisplayName("steps")
    class Steps {

        @Test
        void listsEveryRegisteredTypeWithItsParameters() {
            assertEquals(0, Main.run("steps"));
            String s = stdout();
            assertTrue(s.contains("control.log   Write a message into the run log"), s);
            assertTrue(s.contains("message"), s);
            assertTrue(s.contains("debug | info | warn | error"), s);
            assertTrue(s.contains("control.set"), s);
        }

        @Test
        void oneTypeAtATime() {
            assertEquals(0, Main.run("steps", "control.set"));
            assertTrue(stdout().contains("control.set"), stdout());
            assertFalse(stdout().contains("control.log"), stdout());
        }

        @Test
        void anUnknownNameSuggestsTheCloseOne() {
            assertEquals(1, Main.run("steps", "control.lg"));
            assertTrue(stderr().contains("did you mean \"control.log\""), stderr());
        }
    }

    @Nested
    @DisplayName("trigger --dry-run")
    class DryRun {

        @Test
        void printsTheResolvedPlan() throws IOException {
            int code = Main.run("trigger", "deploy", "-c", fixture("plan.yaml").toString(),
                    "--dry-run", "--set", "version=1.2.4");
            assertEquals(0, code);
            String s = stdout();
            assertTrue(s.startsWith("DRY RUN  job=deploy  trigger=manual  version=1.2.4"), s);
            assertTrue(s.contains("would log [info] deploying demo 1.2.4"), s);
            assertFalse(s.contains("${"), "a dry run resolves every value:\n" + s);
        }

        @Test
        void reportsAProblemThatOnlyAppearsOnceValuesAreReal() throws IOException {
            Path p = write("late.yaml", """
                    vars: {shouting: shout}
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps:
                          - uses: control.log
                            message: hi
                            level: ${vars.shouting}
                    """);
            assertEquals(1, Main.run("trigger", "j", "-c", p.toString(), "--dry-run"));
            assertTrue(stderr().contains("shout"), stderr());
            assertTrue(stdout().contains("    !  "),
                    "the step that could not be resolved is marked in the plan:\n" + stdout());
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
            assertTrue(stderr().contains("--dry-run"), "the thing that does work:\n" + stderr());
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
