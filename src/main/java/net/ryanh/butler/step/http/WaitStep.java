package net.ryanh.butler.step.http;

import net.ryanh.butler.spi.RunContext;
import net.ryanh.butler.spi.StepResult;
import net.ryanh.butler.spi.StepType;
import net.ryanh.butler.util.Durations;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Polls a URL until {@code until:} holds, and fails when it runs out of time.
 *
 * <p>There is no timeout parameter: the step's own {@code timeout:} is the limit, and the runtime
 * enforces it by interrupting this thread. The interrupt is caught rather than allowed to escape,
 * so the failure can say how far the polling got.
 */
public final class WaitStep implements StepType<WaitStep.Config> {

    public record Config(String url, String method, Map<String, String> headers, String until,
                         Duration interval) {
        public Config {
            method = method == null || method.isBlank() ? "GET" : method;
            headers = headers == null ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
            interval = interval == null ? Duration.ofSeconds(2) : interval;
        }
    }

    @Override
    public String name() {
        return "http.wait";
    }

    @Override
    public List<String> required() {
        return List.of("url", "until");
    }

    @Override
    public Class<Config> configType() {
        return Config.class;
    }

    @Override
    public String summary() {
        return "Poll a URL until a condition holds";
    }

    @Override
    public List<String> conditions() {
        return List.of("until");
    }

    @Override
    public List<String> locals() {
        return List.of("status", "headers", "body", "json");
    }

    @Override
    public StepResult execute(Config c, RunContext ctx) {
        if (c.url() == null || c.url().isBlank()) {
            return StepResult.failed("http.wait needs a url:");
        }
        if (c.until() == null || c.until().isBlank()) {
            return StepResult.failed("http.wait needs a condition in until:");
        }

        Instant started = Instant.now();
        int probes = 0;
        while (true) {
            probes++;
            Probe last = probe(c, ctx);
            if (last.satisfied()) {
                return last.result()
                        .output("probes", (long) probes)
                        .output("elapsed", Duration.between(started, Instant.now()));
            }
            try {
                Thread.sleep(c.interval());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return gaveUp(c, last, probes, started);
            }
        }
    }

    /**
     * @param problem why the probe could not be judged, or null if it simply did not hold
     */
    private record Probe(Map<String, Object> facts, boolean satisfied, StepResult result,
                         String problem) {
    }

    /**
     * Never shorter than the default, so a 2s cadence does not mean 2s of patience with a service
     * that is slow to answer while it starts.
     */
    private static Duration probeTimeout(Config c) {
        return c.interval().compareTo(Http.DEFAULT_TIMEOUT) > 0
                ? c.interval() : Http.DEFAULT_TIMEOUT;
    }

    private Probe probe(Config c, RunContext ctx) {
        Map<String, Object> facts;
        try {
            HttpResponse<String> response = Http.send(c.url(), c.method(), c.headers(), null,
                    probeTimeout(c));
            facts = Http.facts(response);
        } catch (IOException e) {
            // A service being restarted refuses connections, so this is a probe that did not
            // hold rather than a failure.
            return new Probe(Map.of(), false, StepResult.ok(), e.getMessage());
        }
        boolean held;
        try {
            held = ctx.withLocals(facts).evaluate(c.until());
        } catch (RuntimeException e) {
            return new Probe(facts, false, StepResult.ok(), "until: " + e.getMessage());
        }
        return new Probe(facts, held, StepResult.ok().outputs(facts), null);
    }

    /**
     * The failure for a step that ran out of time: how many probes, how long, and what the last
     * one came back with.
     */
    private static StepResult gaveUp(Config c, Probe last, int probes, Instant started) {
        Duration elapsed = Duration.between(started, Instant.now());
        String tail = last.problem() != null ? last.problem()
                : "last status " + last.facts().get("status")
                  + excerpt(String.valueOf(last.facts().get("body")));

        return StepResult.failed(c.url() + " never satisfied `" + c.until() + "` in " + probes
                        + " probe" + (probes == 1 ? "" : "s") + " over "
                        + Durations.format(elapsed) + "; " + tail)
                .outputs(last.facts())
                .output("probes", (long) probes)
                .output("elapsed", elapsed);
    }

    private static String excerpt(String body) {
        String excerpt = Http.excerpt(body);
        return excerpt.isEmpty() ? "" : ", body: " + excerpt;
    }

    @Override
    public String describe(Config c, RunContext ctx) {
        if (c.url() == null || c.url().isBlank()) {
            return "would fail: http.wait needs a url:";
        }
        Duration budget = ctx.command().timeout();
        List<String> lines = new ArrayList<>();
        lines.add("would poll   " + c.method().toUpperCase(Locale.ROOT) + " " + c.url()
                + " every " + Durations.format(c.interval())
                + (budget == null ? "" : ", up to " + Durations.format(budget)));
        lines.add("      until  " + ctx.resolveCondition(c.until()));
        return String.join("\n", lines);
    }

    @Override
    public List<String> preflight(Config c, RunContext ctx) {
        List<String> warnings = new ArrayList<>();
        if (c.url() != null && !c.url().isBlank()) {
            try {
                URI uri = URI.create(c.url());
                if (uri.getScheme() == null || uri.getHost() == null) {
                    warnings.add("url is not absolute: " + c.url());
                }
            } catch (IllegalArgumentException e) {
                warnings.add("url does not parse: " + c.url());
            }
        }
        if (ctx.command().timeout() == null) {
            warnings.add("no timeout: this would poll until the condition holds, however long "
                    + "that takes. Give the step a timeout: or the job one");
        }
        return List.copyOf(warnings);
    }
}
