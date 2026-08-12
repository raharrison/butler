package net.ryanh.butler.step.fs;

import net.ryanh.butler.config.ConfigLoader;
import net.ryanh.butler.runtime.*;
import net.ryanh.butler.spi.Event;
import net.ryanh.butler.testing.Fixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The {@code fs.*} vocabulary, through real runs against a temp directory and through plans.
 */
class FsStepsTest {

    @TempDir
    Path dir;

    @TempDir
    Path stateDir;

    private static final String JOB = """
            jobs:
              j:
                on: [{uses: manual}]
                steps:
            %s
            """;

    private ConfigLoader.Result config(String steps) {
        ConfigLoader.Result result = Fixture.config(JOB.formatted(steps), StepRegistry.discover());
        assertFalse(result.diagnostics().hasErrors(), result.diagnostics().render("test.yaml"));
        return result;
    }

    private Run run(String steps) {
        ConfigLoader.Result result = config(steps);
        return new JobRunner(Fixture.environment(result, StepRegistry.discover(), stateDir))
                .run(result.config().jobs().get("j"), new Event("manual", Map.of(), null));
    }

    private Plan plan(String steps) {
        ConfigLoader.Result result = config(steps);
        return PlanBuilder.build(Fixture.environment(result, StepRegistry.discover(), stateDir),
                result.config().jobs().get("j"), new Event("manual", Map.of(), null),
                result.diagnostics());
    }

    /**
     * A temp path spelled with forward slashes, the way a config writes one.
     */
    private String at(String name) {
        return dir.resolve(name).toString().replace('\\', '/');
    }

    private void write(String name, String content) throws IOException {
        Path file = dir.resolve(name);
        Files.createDirectories(file.getParent() == null ? dir : file.getParent());
        Files.writeString(file, content);
    }

    /**
     * Skips rather than fails where the filesystem will not create a symlink, which Windows will
     * not without a privilege it does not grant by default.
     */
    private void assumeSymlinks() {
        Path probe = dir.resolve("symlink-probe");
        try {
            Files.createSymbolicLink(probe, dir);
            Files.delete(probe);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "this filesystem will not create symlinks: " + e);
        }
    }

    @Nested
    @DisplayName("copy and move")
    class Transfers {

        @Test
        @DisplayName("copies, creating the directories above the destination")
        void copiesAndCreatesParents() throws IOException {
            write("artifacts/api-1.2.4.jar", "the jar");
            Run run = run("""
                          - uses: fs.copy
                            from: %s
                            to: %s
                            mkdirs: true
                    """.formatted(at("artifacts/api-1.2.4.jar"),
                    at("releases/1.2.4/api.jar")));

            assertEquals(Run.Status.SUCCESS, run.status(), run.message());
            assertEquals("the jar", Files.readString(dir.resolve("releases/1.2.4/api.jar")));
        }

        @Test
        @DisplayName("refuses to overwrite when told not to, and says which file")
        void refusesToOverwrite() throws IOException {
            write("from.txt", "new");
            write("to.txt", "old");
            Run run = run("""
                          - uses: fs.copy
                            from: %s
                            to: %s
                            overwrite: false
                    """.formatted(at("from.txt"), at("to.txt")));

            assertEquals(Run.Status.FAILED, run.status());
            assertTrue(run.message().contains("to.txt"), run.message());
            assertEquals("old", Files.readString(dir.resolve("to.txt")));
        }

        @Test
        @DisplayName("the plan says what it would copy, with mode and parents")
        void describesTheCopy() throws IOException {
            write("from.txt", "x");
            List<String> body = plan("""
                          - uses: fs.copy
                            from: %s
                            to: %s
                            mode: "0640"
                            mkdirs: true
                    """.formatted(at("from.txt"), at("a/b/to.txt"))).steps().getFirst().body();

            assertEquals("would copy   " + at("from.txt"), body.get(0));
            assertEquals("      to     " + at("a/b/to.txt"), body.get(1));
            assertEquals("      mode 0640, creating 2 parent directories", body.get(2));
        }

        @Test
        @DisplayName("preflight warns about a source that is not there")
        void preflightWarnsOnMissingSource() {
            assertEquals(List.of("source does not exist: " + at("nope.jar")),
                    plan("""
                                  - uses: fs.copy
                                    from: %s
                                    to: %s
                            """.formatted(at("nope.jar"), at("to.jar")))
                            .steps().getFirst().warnings());
        }

        @Test
        void moves() throws IOException {
            write("from.txt", "moved");
            assertEquals(Run.Status.SUCCESS, run("""
                          - uses: fs.move
                            from: %s
                            to: %s
                            mkdirs: true
                    """.formatted(at("from.txt"), at("out/to.txt"))).status());

            assertFalse(Files.exists(dir.resolve("from.txt")));
            assertEquals("moved", Files.readString(dir.resolve("out/to.txt")));
        }
    }

    @Nested
    @DisplayName("symlink")
    class Symlinks {

        @Test
        @DisplayName("repoints, and reports the target it replaced")
        void repointsAndReportsThePrevious() throws IOException {
            assumeSymlinks();
            Files.createDirectories(dir.resolve("releases/1.2.3"));
            Files.createDirectories(dir.resolve("releases/1.2.4"));
            Files.createSymbolicLink(dir.resolve("current"), dir.resolve("releases/1.2.3"));

            Run run = run("""
                          - uses: fs.symlink
                            link: %s
                            target: %s
                            register: symlink
                          - uses: control.assert
                            that: steps.symlink.previous_target == "%s"
                    """.formatted(at("current"), at("releases/1.2.4"), at("releases/1.2.3")));

            assertEquals(Run.Status.SUCCESS, run.status(), run.message());
            assertEquals(dir.resolve("releases/1.2.4"),
                    Files.readSymbolicLink(dir.resolve("current")));
        }

        @Test
        @DisplayName("a reader following the link never finds it missing")
        void isAtomic() throws Exception {
            assumeSymlinks();
            Files.createDirectories(dir.resolve("releases/1.2.3"));
            Files.createDirectories(dir.resolve("releases/1.2.4"));
            Path link = dir.resolve("current");
            Files.createSymbolicLink(link, dir.resolve("releases/1.2.3"));

            AtomicBoolean watching = new AtomicBoolean(true);
            AtomicInteger missing = new AtomicInteger();
            AtomicInteger reads = new AtomicInteger();
            Thread reader = Thread.ofVirtual().start(() -> {
                while (watching.get()) {
                    reads.incrementAndGet();
                    if (!Files.exists(link)) {
                        missing.incrementAndGet();
                    }
                }
            });

            for (int i = 0; i < 50; i++) {
                String target = at("releases/1.2." + (i % 2 == 0 ? "4" : "3"));
                assertEquals(Run.Status.SUCCESS, run("""
                              - uses: fs.symlink
                                link: %s
                                target: %s
                        """.formatted(at("current"), target)).status());
            }
            watching.set(false);
            reader.join();

            assertTrue(reads.get() > 0, "the reader never got to look");
            assertEquals(0, missing.get(),
                    "an atomic repoint must never leave the link absent, even for an instant");
        }

        @Test
        @DisplayName("the plan reads the current target for real, so the rollback describes right")
        void simulateReadsTheRealLink() throws IOException {
            assumeSymlinks();
            Files.createDirectories(dir.resolve("releases/1.2.3"));
            Files.createSymbolicLink(dir.resolve("current"), dir.resolve("releases/1.2.3"));

            Plan plan = plan("""
                          - uses: fs.symlink
                            link: %s
                            target: %s
                            register: symlink
                          - uses: control.log
                            message: rollback would restore ${steps.symlink.previous_target}
                    """.formatted(at("current"), at("releases/1.2.4")));

            assertEquals(List.of("would repoint (atomic) " + at("current"),
                            "      from   " + at("releases/1.2.3"),
                            "      to     " + at("releases/1.2.4")),
                    plan.steps().getFirst().body());
            assertEquals(List.of("would log [info] rollback would restore "
                    + at("releases/1.2.3")), plan.steps().get(1).body());
            assertFalse(Files.readSymbolicLink(dir.resolve("current")).endsWith("1.2.4"),
                    "a plan changes nothing");
        }

        @Test
        void readlinkReportsTheTargetAndFailsWhenThereIsNoLink() throws IOException {
            assumeSymlinks();
            Files.createDirectories(dir.resolve("releases/1.2.3"));
            Files.createSymbolicLink(dir.resolve("current"), dir.resolve("releases/1.2.3"));

            assertEquals(Run.Status.SUCCESS, run("""
                          - uses: fs.readlink
                            path: %s
                            register: link
                          - uses: control.assert
                            that: basename(steps.link.value) == "1.2.3"
                    """.formatted(at("current"))).status());

            Run missing = run("""
                          - uses: fs.readlink
                            path: %s
                    """.formatted(at("nothing-here")));
            assertEquals(Run.Status.FAILED, missing.status());
            assertTrue(missing.message().contains("not a symlink"), missing.message());
        }
    }

    @Nested
    @DisplayName("reading a host")
    class Reading {

        @Test
        void readsAFileIntoValue() throws IOException {
            write("VERSION", "1.2.3\n");
            assertEquals(Run.Status.SUCCESS, run("""
                          - uses: fs.read
                            path: %s
                            register: v
                          - uses: control.assert
                            that: trim(steps.v.value) == "1.2.3"
                    """.formatted(at("VERSION"))).status());
        }

        @Test
        @DisplayName("refuses a file over max_bytes rather than reading it into the run record")
        void refusesSomethingTooBig() throws IOException {
            write("big.txt", "x".repeat(100));
            Run run = run("""
                          - uses: fs.read
                            path: %s
                            max_bytes: 10
                    """.formatted(at("big.txt")));
            assertEquals(Run.Status.FAILED, run.status());
            assertTrue(run.message().contains("max_bytes"), run.message());
        }

        @Test
        @DisplayName("lists a releases directory in version order, so last is the newest")
        void listsInSemverOrder() throws IOException {
            for (String v : List.of("1.9.0", "1.10.0", "1.2.3", "notes.txt")) {
                Files.createDirectories(dir.resolve("releases").resolve(v));
            }
            assertEquals(Run.Status.SUCCESS, run("""
                          - uses: fs.list
                            dir: %s
                            order_by: semver
                            register: releases
                          - uses: control.assert
                            that: steps.releases.last == "1.10.0"
                          - uses: control.assert
                            that: steps.releases.count == 4
                    """.formatted(at("releases"))).status());
        }

        @Test
        void filtersByMatchAndLimit() throws IOException {
            for (String v : List.of("a-1.jar", "a-2.jar", "a-3.jar", "readme.md")) {
                write("bin/" + v, "x");
            }
            assertEquals(Run.Status.SUCCESS, run("""
                          - uses: fs.list
                            dir: %s
                            match: 'a-\\d\\.jar'
                            limit: 2
                            register: jars
                          - uses: control.assert
                            that: steps.jars.count == 2
                          - uses: control.assert
                            that: steps.jars.first == "a-2.jar"
                    """.formatted(at("bin"))).status());
        }

        @Test
        void reportsWhatAPathIs() throws IOException {
            write("thing.txt", "x");
            assertEquals(Run.Status.SUCCESS, run("""
                          - uses: fs.exists
                            path: %s
                            register: e
                          - uses: control.assert
                            that: steps.e.exists and steps.e.type == "file"
                          - uses: fs.exists
                            path: %s
                            register: gone
                          - uses: control.assert
                            that: not steps.gone.exists and steps.gone.type == "missing"
                    """.formatted(at("thing.txt"), at("nope.txt"))).status());
        }
    }

    @Nested
    @DisplayName("writing")
    class Writing {

        @Test
        void makesADirectoryAndItsParents() {
            assertEquals(Run.Status.SUCCESS, run("""
                          - uses: fs.mkdir
                            path: %s
                    """.formatted(at("a/b/c"))).status());
            assertTrue(Files.isDirectory(dir.resolve("a/b/c")));
        }

        @Test
        @DisplayName("renders a template file's holes from the run")
        void rendersATemplateFile() throws IOException {
            write("unit.tmpl", "ExecStart=/srv/${vars.app}/current/bin/run\n");
            ConfigLoader.Result result = Fixture.config("""
                            vars: {app: api}
                            jobs:
                              j:
                                on: [{uses: manual}]
                                steps:
                                  - uses: fs.template
                                    from: %s
                                    to: %s
                                    mkdirs: true
                            """.formatted(at("unit.tmpl"), at("out/api.service")),
                    StepRegistry.discover());
            Run run = new JobRunner(Fixture.environment(result, StepRegistry.discover(), stateDir))
                    .run(result.config().jobs().get("j"), new Event("manual", Map.of(), null));

            assertEquals(Run.Status.SUCCESS, run.status(), run.message());
            assertEquals("ExecStart=/srv/api/current/bin/run\n",
                    Files.readString(dir.resolve("out/api.service")));
        }

        @Test
        void refusesBothFromAndContent() throws IOException {
            write("t.tmpl", "x");
            Run run = run("""
                          - uses: fs.template
                            from: %s
                            content: hello
                            to: %s
                    """.formatted(at("t.tmpl"), at("out.txt")));
            assertEquals(Run.Status.FAILED, run.status());
            assertTrue(run.message().contains("not both"), run.message());
        }
    }

    @Nested
    @DisplayName("prune")
    class Prune {

        private void releases(String... names) throws IOException {
            for (String name : names) {
                Files.createDirectories(dir.resolve("releases").resolve(name));
                // A distinct mtime each, so the default ordering is deterministic too.
                Files.setLastModifiedTime(dir.resolve("releases").resolve(name),
                        FileTime.fromMillis(1_700_000_000_000L + name.hashCode() % 1000));
            }
        }

        @Test
        @DisplayName("keeps the newest N and deletes the rest")
        void keepsTheNewest() throws IOException {
            releases("1.0.0", "1.1.0", "1.2.0", "1.3.0");
            assertEquals(Run.Status.SUCCESS, run("""
                          - uses: fs.prune
                            dir: %s
                            keep: 2
                            order_by: semver
                    """.formatted(at("releases"))).status());

            assertFalse(Files.exists(dir.resolve("releases/1.0.0")));
            assertFalse(Files.exists(dir.resolve("releases/1.1.0")));
            assertTrue(Files.isDirectory(dir.resolve("releases/1.2.0")));
            assertTrue(Files.isDirectory(dir.resolve("releases/1.3.0")));
        }

        @Test
        @DisplayName("never deletes what the current symlink points at, whatever keep says")
        void refusesToDeleteTheCurrentRelease() throws IOException {
            assumeSymlinks();
            releases("1.0.0", "1.1.0", "1.2.0", "1.3.0");
            // Someone rolled back by hand: current points at an old release that keep: 2 would
            // otherwise delete, taking the running application with it.
            Files.createSymbolicLink(dir.resolve("current"), dir.resolve("releases/1.0.0"));

            Run run = run("""
                          - uses: fs.prune
                            dir: %s
                            keep: 2
                            order_by: semver
                    """.formatted(at("releases")));

            assertEquals(Run.Status.SUCCESS, run.status(), run.message());
            assertTrue(Files.isDirectory(dir.resolve("releases/1.0.0")),
                    "the release current points at must survive");
            assertFalse(Files.exists(dir.resolve("releases/1.1.0")));
            assertTrue(run.steps().getFirst().message().contains("1.0.0"),
                    run.steps().getFirst().message());
        }

        @Test
        @DisplayName("never deletes what protect: names either, which is the same rule without "
                + "needing a symlink")
        void refusesToDeleteWhatProtectNames() throws IOException {
            releases("1.0.0", "1.1.0", "1.2.0", "1.3.0");
            Run run = run("""
                          - uses: fs.prune
                            dir: %s
                            keep: 2
                            order_by: semver
                            protect: [%s]
                    """.formatted(at("releases"), at("releases/1.0.0")));

            assertEquals(Run.Status.SUCCESS, run.status(), run.message());
            assertTrue(Files.isDirectory(dir.resolve("releases/1.0.0")));
            assertFalse(Files.exists(dir.resolve("releases/1.1.0")));
            assertTrue(run.steps().getFirst().message().contains("still in use"),
                    run.steps().getFirst().message());
        }

        @Test
        @DisplayName("no keep: fails the step, because the one deleting step must not read a "
                + "missing number as zero")
        void keepIsRequired() throws IOException {
            releases("1.0.0", "1.1.0");
            Run run = run("""
                          - uses: fs.prune
                            dir: %s
                    """.formatted(at("releases")));

            assertEquals(Run.Status.FAILED, run.status());
            assertEquals("fs.prune needs a keep:", run.steps().getFirst().message());
            assertTrue(Files.isDirectory(dir.resolve("releases/1.0.0")), "and deletes nothing");
        }

        @Test
        void thePlanSaysExactlyWhatWouldGo() throws IOException {
            releases("1.0.0", "1.1.0", "1.2.0");
            List<String> body = plan("""
                          - uses: fs.prune
                            dir: %s
                            keep: 2
                            order_by: semver
                    """.formatted(at("releases"))).steps().getFirst().body();

            assertEquals(List.of("would delete " + at("releases/1.0.0"),
                    "      keeping the newest 2 of 3"), body);
            assertTrue(Files.isDirectory(dir.resolve("releases/1.0.0")), "a plan deletes nothing");
        }
    }
}
