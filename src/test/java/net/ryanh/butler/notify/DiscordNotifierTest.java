package net.ryanh.butler.notify;

import net.ryanh.butler.testing.StubServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DiscordNotifierTest {

    private final DiscordNotifier notifier = new DiscordNotifier();

    @Test
    @DisplayName("posts the message as content, with no username")
    void postsRequiredFieldsOnly() throws Exception {
        try (StubServer server = StubServer.serving(200, "ok")) {
            notifier.send(new DiscordNotifier.Config(server.url("/hook"), null),
                    "api 1.2.4 deployed");

            assertEquals("{\"content\": \"api 1.2.4 deployed\"}",
                    server.received().getFirst().body());
        }
    }

    @Test
    @DisplayName("username is included when set")
    void includesUsername() throws Exception {
        try (StubServer server = StubServer.serving(200, "ok")) {
            notifier.send(new DiscordNotifier.Config(server.url("/hook"), "butler"), "deployed");

            assertEquals("{\"content\": \"deployed\", \"username\": \"butler\"}",
                    server.received().getFirst().body());
        }
    }

    @Test
    @DisplayName("a missing webhook fails clearly rather than posting to nothing")
    void missingWebhookFailsClearly() {
        var config = new DiscordNotifier.Config(null, null);
        Exception e = assertThrows(Exception.class, () -> notifier.send(config, "hi"));
        assertTrue(e.getMessage().contains("no url configured"), e.getMessage());
    }

    @Test
    @DisplayName("the far end refusing the message is reported")
    void aRefusalIsReported() {
        try (StubServer server = StubServer.serving(500, "no")) {
            var config = new DiscordNotifier.Config(server.url("/hook"), null);
            Exception e = assertThrows(Exception.class, () -> notifier.send(config, "hi"));
            assertTrue(e.getMessage().contains("500"), e.getMessage());
        }
    }
}
