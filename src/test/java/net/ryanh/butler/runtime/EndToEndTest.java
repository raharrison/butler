package net.ryanh.butler.runtime;

import net.ryanh.butler.config.ConfigLoader;
import net.ryanh.butler.config.Diagnostics;
import net.ryanh.butler.spi.Event;
import net.ryanh.butler.spi.ProcessRunner;
import net.ryanh.butler.spi.TriggerType;
import net.ryanh.butler.testing.FakeProcessRunner;
import net.ryanh.butler.testing.Fixture;
import net.ryanh.butler.testing.StubServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A deployment end to end: an artifact appears, the job asks the running service what it is,
 * decides, stages, repoints, restarts, waits for health and reports.
 *
 * <p>Only two things are stubbed. The process runner, because a test must not fork
 * {@code systemctl}, and the service, which is a real HTTP server that starts answering with the
 * new version once the restart command is issued.
 */
class EndToEndTest {

    @TempDir
    Path root;

    private Path artifacts;
    private Path srv;
    private Path state;

    /**
     * A temp path spelled with forward slashes, the way a config writes one.
     */
    private static String at(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static void eventually(String what, BooleanSupplier done) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(deadline)) {
            if (done.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        fail("timed out waiting for " + what);
    }

    private String config(StubServer service, StubServer ops) throws IOException {
        artifacts = Files.createDirectories(root.resolve("artifacts"));
        srv = Files.createDirectories(root.resolve("srv"));
        state = root.resolve("state");

        return """
                settings:
                  state_dir: %s
                  poll_interval: 30ms
                
                notifiers:
                  ops:
                    uses: notify.webhook
                    url: %s
                
                vars:
                  root: %s
                
                jobs:
                  api:
                    description: Rolling deploy of the API on new artifact
                
                    on:
                      - uses: file.appeared
                        dir: %s
                        match: 'api-(?<version>\\d+\\.\\d+\\.\\d+)\\.jar'
                        settle: 50ms
                        order_by: semver(version)
                        on_startup: latest
                
                    timeout: 30s
                
                    discover:
                      - name: Ask the running service what it is
                        uses: http.request
                        url: %s
                        timeout: 3s
                        extract:
                          deployed_version: json.version
                
                    when: semver(trigger.version) > semver(default(state.deployed_version, "0.0.0"))
                
                    steps:
                      - name: Stage the release
                        uses: fs.copy
                        from: ${trigger.path}
                        to: ${vars.root}/releases/${trigger.version}/api.jar
                        mkdirs: true
                
                      - name: Point current at the new release
                        uses: fs.template
                        content: ${trigger.version}
                        to: ${vars.root}/current
                
                      - name: Restart the service
                        uses: systemd.restart
                        unit: api.service
                        wait_active: 5s
                
                      - name: Wait for health
                        uses: http.wait
                        url: %s
                        until: status == 200 and json.version == ${trigger.version}
                        interval: 30ms
                        timeout: 3s
                        register: health
                
                    on_failure:
                      - name: Roll back
                        uses: fs.template
                        content: ${state.deployed_version}
                        to: ${vars.root}/current
                        when: exists(state.deployed_version)
                
                    persist:
                      deployed_version: ${trigger.version}
                
                    notify:
                      to: ops
                      on: [ success, failure ]
                      success: "api ${trigger.version} deployed"
                      failure: "api ${trigger.version} FAILED at ${run.failed_step}"
                """.formatted(at(state), ops.url("/hook"), at(srv), at(artifacts),
                service.url("/health"), service.url("/health"));
    }

    /**
     * A service that answers with the version it is running, and picks up whatever was staged when
     * {@code systemctl restart} is issued.
     */
    private record Service(StubServer server, AtomicReference<String> running,
                           AtomicReference<String> staged) implements AutoCloseable {

        static Service serving(String version) {
            AtomicReference<String> running = new AtomicReference<>(version);
            AtomicReference<String> staged = new AtomicReference<>();
            return new Service(StubServer.serving(request ->
                    new StubServer.Answer(200, "{\"version\": \"" + running.get() + "\"}")),
                    running, staged);
        }

        /**
         * The runner {@code systemd.restart} reaches, wired so a restart actually changes what the
         * service answers.
         */
        @Override
        public void close() {
            server.close();
        }

        ProcessRunner restartingProcesses() {
            return new FakeProcessRunner().replying(command -> {
                if (command.argv().contains("restart") && staged.get() != null) {
                    running.set(staged.get());
                }
                String stdout = command.argv().contains("is-active") ? "active\n" : "";
                return new ProcessRunner.Completed(0, stdout, "", Duration.ZERO, false);
            });
        }
    }

    private RunEnvironment environment(String yaml, ProcessRunner processes) {
        ConfigLoader.Result result = Fixture.config(yaml, StepRegistry.discover());
        assertFalse(result.diagnostics().hasErrors(), result.diagnostics().render("e2e.yaml"));
        RegistryValidator.validate(result.config(), StepRegistry.discover(),
                TriggerRegistry.discover(), NotifierRegistry.discover(), result.diagnostics());
        assertFalse(result.diagnostics().hasErrors(), result.diagnostics().render("e2e.yaml"));
        return Fixture.environment(result, StepRegistry.discover(), state, processes);
    }

    private void artifact(String version) throws IOException {
        Files.writeString(artifacts.resolve("api-" + version + ".jar"), "jar for " + version);
    }

    @Test
    @DisplayName("a new artifact appears and the daemon deploys it")
    void theWholeThing() throws Exception {
        Service service = Service.serving("1.2.3");
        try (service; StubServer ops = StubServer.serving(200, "ok")) {
            String yaml = config(service.server(), ops);
            service.staged().set("1.2.4");
            artifact("1.2.4");

            RunEnvironment env = environment(yaml, service.restartingProcesses());
            Butler butler = new Butler(env, TriggerRegistry.discover(), false,
                    new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
            butler.start();
            try {
                eventually("the release to be staged and recorded",
                        () -> Files.exists(srv.resolve("releases/1.2.4/api.jar"))
                                && !ops.received().isEmpty());
            } finally {
                butler.stop();
            }

            assertEquals("jar for 1.2.4",
                    Files.readString(srv.resolve("releases/1.2.4/api.jar")));
            assertEquals("1.2.4", Files.readString(srv.resolve("current")));
            assertTrue(Files.readString(state.resolve("jobs/api.json")).contains("1.2.4"));
            assertEquals("{\"text\": \"api 1.2.4 deployed\"}", ops.received().getFirst().body());
        }
    }

    @Test
    @DisplayName("a fresh install on a live host discovers what is running and does nothing")
    void freshInstallOnALiveHost() throws Exception {
        Service service = Service.serving("1.2.4");
        try (service; StubServer ops = StubServer.serving(200, "ok")) {
            String yaml = config(service.server(), ops);
            artifact("1.2.4");

            Run run = run(yaml, service);

            assertEquals(Run.Status.SKIPPED, run.status());
            assertFalse(Files.exists(srv.resolve("releases/1.2.4/api.jar")),
                    "nothing is staged for a host that is already correct");
            assertEquals(List.of("state.deployed_version = \"1.2.4\""),
                    run.discover().getFirst().body(), "discovery asked the host, and it answered");
            assertTrue(Files.readString(state.resolve("jobs/api.json")).contains("1.2.4"),
                    "the state directory is seeded even though nothing ran");
        }
    }

    @Test
    @DisplayName("a health check that never passes fails the run and the rollback restores the "
            + "pointer")
    void rollsBackWhenHealthNeverPasses() throws Exception {
        Service service = Service.serving("1.2.3");
        try (service; StubServer ops = StubServer.serving(200, "ok")) {
            String yaml = config(service.server(), ops);
            Files.writeString(srv.resolve("current"), "1.2.3");
            // Nothing staged, so the restart brings back 1.2.3 and the health check never passes.
            artifact("1.2.4");

            Run run = run(yaml, service);

            assertEquals(Run.Status.FAILED, run.status());
            assertEquals("Wait for health", run.failedStep());
            assertEquals("1.2.3", Files.readString(srv.resolve("current")),
                    "on_failure: put the pointer back");
            assertEquals("{\"text\": \"api 1.2.4 FAILED at Wait for health\"}",
                    ops.received().getFirst().body());
            assertFalse(Files.readString(state.resolve("jobs/api.json")).contains("\"1.2.4\""),
                    "persist: is written only on success");
        }
    }

    @Test
    @DisplayName("the dry run reports the same decision without touching anything")
    void theDryRunAgrees() throws Exception {
        Service service = Service.serving("1.2.3");
        try (service; StubServer ops = StubServer.serving(200, "ok")) {
            String yaml = config(service.server(), ops);
            artifact("1.2.4");

            RunEnvironment env = environment(yaml, service.restartingProcesses());
            Plan plan = PlanBuilder.build(env, env.config().jobs().get("api"), event(env),
                    new Diagnostics());
            String rendered = PlanRenderer.render(plan);

            assertTrue(rendered.contains("state.deployed_version = \"1.2.3\""), rendered);
            assertTrue(rendered.contains("semver(\"1.2.4\") > semver(\"1.2.3\")   ->   true"),
                    rendered);
            assertTrue(rendered.contains("would run    sudo systemctl restart api.service"),
                    rendered);
            assertFalse(rendered.contains("${"), "a dry run resolves every value:\n" + rendered);
            assertFalse(Files.exists(srv.resolve("releases")), "and changes nothing");
            assertTrue(ops.received().isEmpty(), "and sends nothing");
        }
    }

    /**
     * Runs the job once against the event its own trigger would produce, as {@code butler trigger}
     * does.
     */
    private Run run(String yaml, Service service) {
        RunEnvironment env = environment(yaml, service.restartingProcesses());
        return new JobRunner(env).run(env.config().jobs().get("api"), event(env));
    }

    @SuppressWarnings("unchecked")
    private static Event event(RunEnvironment env) {
        var job = env.config().jobs().get("api");
        var def = job.on().getFirst();
        TriggerType<Object> type = (TriggerType<Object>) TriggerRegistry.discover().find(def.uses());
        Object params = Params.bind(type.configType(), def.params());
        // current() judges settle from the modification time's age, so an artifact just written is
        // not a candidate until settle: has passed.
        Triggering ctx = new Triggering(job.name(), Duration.ofMillis(30), true);
        Instant deadline = Instant.now().plusSeconds(5);
        while (Instant.now().isBefore(deadline)) {
            List<Event> candidates = type.current(params, ctx);
            if (!candidates.isEmpty()) {
                return candidates.getLast();
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return fail("the trigger never saw the artifact that was written");
    }
}
