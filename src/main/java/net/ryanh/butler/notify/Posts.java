package net.ryanh.butler.notify;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * How every notifier delivers: POST, and throw if the far end says no.
 */
final class Posts {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * Short, because a notification is sent after the run has ended and waiting on it delays the
     * next run.
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private Posts() {
    }

    static void post(String url, String contentType, String body, Map<String, String> headers)
            throws IOException {
        if (url == null || url.isBlank()) {
            throw new IOException("no url configured");
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);

        HttpResponse<String> response;
        try {
            response = CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("the notification was interrupted", e);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException(url + " answered " + response.statusCode() + ": "
                    + firstLine(response.body()));
        }
    }

    /**
     * A JSON object, leaving out the fields the config did not set. Hand-written because the
     * bodies here are a handful of values.
     */
    static String json(Map<String, ?> fields) {
        StringBuilder sb = new StringBuilder("{");
        fields.forEach((key, value) -> {
            if (value == null) {
                return;
            }
            if (sb.length() > 1) {
                sb.append(", ");
            }
            sb.append(quote(key)).append(": ").append(value(value));
        });
        return sb.append('}').toString();
    }

    /**
     * One JSON value: the scalars, a list as an array and a map as a nested object.
     */
    private static String value(Object v) {
        return switch (v) {
            case Number n -> n.toString();
            case Boolean b -> b.toString();
            case Map<?, ?> m -> json(withStringKeys(m));
            case List<?> items -> items.stream().filter(Objects::nonNull)
                    .map(Posts::value).collect(Collectors.joining(", ", "[", "]"));
            default -> quote(String.valueOf(v));
        };
    }

    private static Map<String, Object> withStringKeys(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    /**
     * A JSON string literal, escaped, so a message holding a quote or a newline still parses.
     */
    static String quote(String text) {
        StringBuilder sb = new StringBuilder(text.length() + 2).append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    private static String firstLine(String body) {
        if (body == null || body.isBlank()) {
            return "(no body)";
        }
        String line = body.strip().split("\n")[0].strip();
        return line.length() <= 200 ? line : line.substring(0, 200) + "...";
    }
}
