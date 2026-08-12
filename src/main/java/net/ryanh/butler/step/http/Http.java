package net.ryanh.butler.step.http;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What {@code http.request} and {@code http.wait} share: the client, the request builder, and the
 * shape a response takes in an expression.
 */
final class Http {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    /**
     * Used when the step has no timeout of its own, so a socket that never answers cannot hang a
     * run the runtime has no deadline to interrupt.
     */
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private Http() {
    }

    static HttpResponse<String> send(String url, String method, Map<String, String> headers,
                                     String body, Duration timeout) throws IOException {
        HttpRequest.BodyPublisher publisher = body == null || body.isEmpty()
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);

        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout == null ? DEFAULT_TIMEOUT : timeout)
                .method(method.toUpperCase(Locale.ROOT), publisher);
        headers.forEach(request::header);

        try {
            return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("the request was interrupted", e);
        }
    }

    /**
     * {@code status}, {@code headers}, {@code body}, and {@code json} where the body parses as
     * one.
     */
    static Map<String, Object> facts(HttpResponse<String> response) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", (long) response.statusCode());
        out.put("headers", headers(response));
        out.put("body", response.body());
        out.put("json", json(response.body()));
        return out;
    }

    /**
     * Names lowercased, so a config need not guess the case the server chose.
     */
    private static Map<String, String> headers(HttpResponse<String> response) {
        Map<String, String> out = new LinkedHashMap<>();
        response.headers().map().forEach((name, values) -> {
            if (!values.isEmpty()) {
                out.put(name.toLowerCase(Locale.ROOT), values.getFirst());
            }
        });
        return out;
    }

    /**
     * @return the body parsed as JSON, or null when it does not parse. Null rather than a throw:
     * a body that is not JSON is an answer, and callers treat it as one
     */
    private static Object json(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(body, new TypeReference<Object>() {
            });
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * A status the step was told to accept, or any 2xx when it was told nothing.
     */
    static boolean acceptable(int status, List<Integer> expected) {
        return expected == null || expected.isEmpty()
                ? status >= 200 && status < 300
                : expected.contains(status);
    }

    static String expectation(List<Integer> expected) {
        if (expected == null || expected.isEmpty()) {
            return "2xx";
        }
        return String.join(" or ", expected.stream().map(String::valueOf).toList());
    }

    /**
     * The one line of a body worth putting in a failure message.
     */
    static String excerpt(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String first = body.strip().split("\n")[0].strip();
        return first.length() <= 200 ? first : first.substring(0, 200) + "...";
    }
}
