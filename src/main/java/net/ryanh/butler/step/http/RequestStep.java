package net.ryanh.butler.step.http;

import net.ryanh.butler.spi.RunContext;
import net.ryanh.butler.spi.StepResult;
import net.ryanh.butler.spi.StepType;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.util.*;

/**
 * Makes one HTTP request and reports what came back.
 *
 * <p>The request is given whatever the step's own {@code timeout:} allows, so there is one timeout
 * to set rather than two that can disagree.
 */
public final class RequestStep implements StepType<RequestStep.Config> {

    /**
     * @param expectStatus statuses to treat as success; any 2xx when the config says nothing
     */
    public record Config(String url, String method, Map<String, String> headers, String body,
                         List<Integer> expectStatus) {
        public Config {
            method = method == null || method.isBlank() ? "GET" : method;
            headers = headers == null ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
            expectStatus = expectStatus == null ? List.of() : List.copyOf(expectStatus);
        }
    }

    @Override
    public String name() {
        return "http.request";
    }

    @Override
    public Class<Config> configType() {
        return Config.class;
    }

    @Override
    public String summary() {
        return "Make one HTTP request and report the response";
    }

    @Override
    public List<String> locals() {
        return List.of("status", "headers", "body", "json");
    }

    @Override
    public StepResult execute(Config c, RunContext ctx) throws IOException {
        if (c.url() == null || c.url().isBlank()) {
            return StepResult.failed("http.request needs a url:");
        }
        HttpResponse<String> response = Http.send(c.url(), c.method(), c.headers(), c.body(),
                ctx.command().timeout());
        Map<String, Object> facts = Http.facts(response);

        StepResult result = Http.acceptable(response.statusCode(), c.expectStatus())
                ? StepResult.ok()
                : StepResult.failed(c.method().toUpperCase(Locale.ROOT) + " " + c.url()
                                    + " answered " + response.statusCode() + ", expected "
                                    + Http.expectation(c.expectStatus())
                                    + excerpt(response));
        return result.outputs(facts);
    }

    private static String excerpt(HttpResponse<String> response) {
        String excerpt = Http.excerpt(response.body());
        return excerpt.isEmpty() ? "" : ": " + excerpt;
    }

    @Override
    public String describe(Config c, RunContext ctx) {
        if (c.url() == null || c.url().isBlank()) {
            return "would fail: http.request needs a url:";
        }
        List<String> lines = new ArrayList<>();
        lines.add("would send   " + c.method().toUpperCase(Locale.ROOT) + " " + c.url());
        lines.add("      expecting " + Http.expectation(c.expectStatus()));
        if (c.body() != null && !c.body().isEmpty()) {
            for (String line : c.body().stripTrailing().split("\n")) {
                lines.add("      | " + line);
            }
        }
        return String.join("\n", lines);
    }

    @Override
    public List<String> preflight(Config c, RunContext ctx) {
        if (c.url() == null || c.url().isBlank()) {
            return List.of();
        }
        try {
            URI uri = URI.create(c.url());
            return uri.getScheme() == null || uri.getHost() == null
                    ? List.of("url is not absolute: " + c.url())
                    : List.of();
        } catch (IllegalArgumentException e) {
            return List.of("url does not parse: " + c.url());
        }
    }
}
