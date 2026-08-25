package net.ryanh.butler.step.http;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.*;

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
     * How much of an error body to read for a failure message.
     */
    private static final int ERROR_EXCERPT = 8192;

    /**
     * Opens a response without reading its body, so the status can be judged before anything is
     * held in memory or written.
     */
    static HttpResponse<InputStream> open(String url, Map<String, String> headers, Duration timeout)
            throws IOException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout == null ? DEFAULT_TIMEOUT : timeout)
                .GET();
        headers.forEach(request::header);
        try {
            return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("the request was interrupted", e);
        }
    }

    /**
     * The first line of an error body.
     */
    static String errorExcerpt(HttpResponse<InputStream> response) {
        try (InputStream in = response.body()) {
            return excerpt(new String(in.readNBytes(ERROR_EXCERPT), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * What a streamed download came to.
     */
    record Downloaded(long bytes, String sha256) {
    }

    /**
     * Streams a response into a file, hashing it on the way past.
     */
    static Downloaded stream(HttpResponse<InputStream> response, Path target) throws IOException {
        MessageDigest digest = sha256();
        long bytes = 0;
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(target)) {
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
                bytes += read;
            }
        }
        return new Downloaded(bytes, HexFormat.of().formatHex(digest.digest()));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // Every JVM has it; the checked exception is the API's, not a real possibility.
            throw new IllegalStateException(e);
        }
    }

    /**
     * Moves a finished download over the destination. Only the move is visible to a reader, so a
     * fetch that failed part-way leaves what was there before.
     */
    static void replace(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            // Some filesystems cannot; a plain replace still beats a partial file.
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * The hex of a {@code checksum:}, which may name its algorithm or be a bare sha256.
     *
     * @throws IllegalArgumentException if it names an algorithm this step cannot check
     */
    static String expectedHex(String checksum) {
        String text = checksum.strip();
        int colon = text.indexOf(':');
        if (colon < 0) {
            return text.toLowerCase(Locale.ROOT);
        }
        String algorithm = text.substring(0, colon).strip().toLowerCase(Locale.ROOT);
        if (!algorithm.equals("sha256")) {
            throw new IllegalArgumentException("checksum names " + algorithm
                    + ", and sha256 is the one this step can check");
        }
        return text.substring(colon + 1).strip().toLowerCase(Locale.ROOT);
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
