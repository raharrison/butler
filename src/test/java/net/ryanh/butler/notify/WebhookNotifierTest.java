package net.ryanh.butler.notify;

import net.ryanh.butler.testing.StubServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WebhookNotifierTest {

    private final WebhookNotifier notifier = new WebhookNotifier();

    @Test
    @DisplayName("the message lands in the \"text\" field by default")
    void postsDefaultField() throws Exception {
        try (StubServer server = StubServer.serving(200, "ok")) {
            notifier.send(new WebhookNotifier.Config(server.url("/hook"), null, null),
                    "api 1.2.4 deployed");

            assertEquals("{\"text\": \"api 1.2.4 deployed\"}", server.received().getFirst().body());
        }
    }

    @Test
    @DisplayName("field: renames which JSON key the message goes in")
    void customFieldName() throws Exception {
        try (StubServer server = StubServer.serving(200, "ok")) {
            notifier.send(new WebhookNotifier.Config(server.url("/hook"), "message", null),
                    "deployed");

            assertEquals("{\"message\": \"deployed\"}", server.received().getFirst().body());
        }
    }

    @Test
    @DisplayName("headers: are sent with the request")
    void headersArePassedThrough() throws Exception {
        try (StubServer server = StubServer.serving(200, "ok")) {
            notifier.send(new WebhookNotifier.Config(server.url("/hook"), null,
                    Map.of("X-Api-Key", "secret")), "hi");

            assertEquals(java.util.List.of("secret"),
                    server.received().getFirst().headers().get("X-Api-Key"));
        }
    }

    @Test
    @DisplayName("a missing url fails clearly rather than posting to nothing")
    void missingUrlFailsClearly() {
        var config = new WebhookNotifier.Config(null, null, null);
        Exception e = assertThrows(Exception.class, () -> notifier.send(config, "hi"));
        assertTrue(e.getMessage().contains("no url configured"), e.getMessage());
    }

    @Test
    @DisplayName("the far end refusing the message is reported")
    void aRefusalIsReported() {
        try (StubServer server = StubServer.serving(500, "no")) {
            var config = new WebhookNotifier.Config(server.url("/hook"), null, null);
            Exception e = assertThrows(Exception.class, () -> notifier.send(config, "hi"));
            assertTrue(e.getMessage().contains("500"), e.getMessage());
        }
    }
}
