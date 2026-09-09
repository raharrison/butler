package net.ryanh.butler.notify;

import net.ryanh.butler.testing.StubServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JoltNotifierTest {

    private final JoltNotifier notifier = new JoltNotifier();

    private static JoltNotifier.Config minimal(String server) {
        return new JoltNotifier.Config(server, "tok-abc", "api deploy", null, null, null, null,
                null, null);
    }

    @Test
    @DisplayName("the token is a bearer header, and the message is the body")
    void postsToTheInboundEndpoint() throws Exception {
        try (StubServer server = StubServer.serving(202, "{\"status\": \"processed\"}")) {
            notifier.send(minimal(server.url("")), "api 1.2.4 deployed");

            StubServer.Received received = server.received().getFirst();
            assertEquals("/api/v1/inbound", received.path());
            assertEquals(List.of("Bearer tok-abc"), received.headers().get("Authorization"));
            assertEquals("{\"title\": \"api deploy\", \"message\": \"api 1.2.4 deployed\"}",
                    received.body(), "an unset field is left out rather than sent as null");
        }
    }

    @Test
    @DisplayName("a trailing slash on the server does not double up in the path")
    void trailingSlashIsTrimmed() throws Exception {
        try (StubServer server = StubServer.serving(202, "{}")) {
            notifier.send(minimal(server.url("/")), "hi");

            assertEquals("/api/v1/inbound", server.received().getFirst().path());
        }
    }

    @Test
    @DisplayName("importance is a number, tags an array and metadata an object")
    void typedFieldsKeepTheirJsonShape() throws Exception {
        try (StubServer server = StubServer.serving(202, "{}")) {
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("host", "prod-api-01");
            metadata.put("version", "1.2.4");
            notifier.send(new JoltNotifier.Config(server.url(""), "tok-abc", "api deploy", 4,
                            List.of("infrastructure", "deploy"), "https://ci.example.com/build/9",
                            "https://ci.example.com/icon.png", "run-20260908T031407", metadata),
                    "api 1.2.4 deployed");

            assertEquals("{\"title\": \"api deploy\", \"message\": \"api 1.2.4 deployed\", "
                            + "\"importance\": 4, \"tags\": [\"infrastructure\", \"deploy\"], "
                            + "\"click\": \"https://ci.example.com/build/9\", "
                            + "\"icon\": \"https://ci.example.com/icon.png\", "
                            + "\"idempotencyKey\": \"run-20260908T031407\", "
                            + "\"metadata\": {\"host\": \"prod-api-01\", \"version\": \"1.2.4\"}}",
                    server.received().getFirst().body());
        }
    }

    @Test
    @DisplayName("a message holding a quote or a newline still parses as JSON")
    void theMessageIsEscaped() throws Exception {
        try (StubServer server = StubServer.serving(202, "{}")) {
            notifier.send(minimal(server.url("")), "it said \"no\"\nat step 3");

            assertEquals("{\"title\": \"api deploy\", "
                            + "\"message\": \"it said \\\"no\\\"\\nat step 3\"}",
                    server.received().getFirst().body());
        }
    }

    @Test
    @DisplayName("a missing token or title fails before anything is posted")
    void whatIsRequiredFailsClearly() {
        try (StubServer server = StubServer.serving(202, "{}")) {
            var noToken = new JoltNotifier.Config(server.url(""), null, "api deploy", null, null,
                    null, null, null, null);
            assertTrue(assertThrows(Exception.class, () -> notifier.send(noToken, "hi"))
                    .getMessage().contains("notify.jolt needs a token"));

            var noTitle = new JoltNotifier.Config(server.url(""), "tok-abc", "  ", null, null,
                    null, null, null, null);
            assertTrue(assertThrows(Exception.class, () -> notifier.send(noTitle, "hi"))
                    .getMessage().contains("notify.jolt needs a title"));

            assertEquals(List.of(), server.received());
        }
    }

    @Test
    @DisplayName("an importance outside 1 to 5 is refused rather than sent to be rejected")
    void importanceIsRangeChecked() {
        var config = new JoltNotifier.Config("https://jolt.example.com", "tok", "api deploy", 7,
                null, null, null, null, null);
        Exception e = assertThrows(Exception.class, () -> notifier.send(config, "hi"));
        assertTrue(e.getMessage().contains("importance is 1 to 5, not 7"), e.getMessage());
    }

    @Test
    @DisplayName("a revoked token is reported with what the server said")
    void aRefusalIsReported() {
        try (StubServer server = StubServer.serving(401, "{\"error\": \"unknown token\"}")) {
            var config = minimal(server.url(""));
            Exception e = assertThrows(Exception.class, () -> notifier.send(config, "hi"));

            assertTrue(e.getMessage().contains("401"), e.getMessage());
            assertTrue(e.getMessage().contains("unknown token"), e.getMessage());
        }
    }
}
