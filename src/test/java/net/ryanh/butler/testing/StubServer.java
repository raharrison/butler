package net.ryanh.butler.testing;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * A one-endpoint HTTP server on a port the OS picks, for the steps and notifiers that talk to one.
 */
public final class StubServer implements AutoCloseable {

    /**
     * One request as the server saw it. {@code headers} is the exchange's own
     * {@code com.sun.net.httpserver.Headers}, so a lookup by name is case-insensitive.
     */
    public record Received(String method, String path, String body,
                           Map<String, List<String>> headers) {
    }

    /**
     * What to answer with.
     */
    public record Answer(int status, String body) {
    }

    private final HttpServer server;
    private final List<Received> received = new ArrayList<>();
    private volatile Function<Received, Answer> handler;

    private StubServer(HttpServer server, Function<Received, Answer> handler) {
        this.server = server;
        this.handler = handler;
    }

    public static StubServer serving(Function<Received, Answer> handler) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            StubServer stub = new StubServer(server, handler);
            server.createContext("/", stub::handle);
            server.start();
            return stub;
        } catch (IOException e) {
            throw new UncheckedIOException("could not start a stub server", e);
        }
    }

    public static StubServer serving(int status, String body) {
        return serving(request -> new Answer(status, body));
    }

    public void answering(Function<Received, Answer> next) {
        this.handler = next;
    }

    public String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    public synchronized List<Received> received() {
        return List.copyOf(received);
    }

    private void handle(HttpExchange exchange) throws IOException {
        Received request = new Received(exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                exchange.getRequestHeaders());
        synchronized (this) {
            received.add(request);
        }
        Answer answer = handler.apply(request);
        byte[] body = answer.body().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(answer.status(), body.length);
        try (var out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
