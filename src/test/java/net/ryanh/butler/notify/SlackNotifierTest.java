package net.ryanh.butler.notify;

import net.ryanh.butler.testing.StubServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SlackNotifierTest {

    private final SlackNotifier notifier = new SlackNotifier();

    @Test
    @DisplayName("posts just the message when channel, username and icon are unset")
    void postsRequiredFieldsOnly() throws Exception {
        try (StubServer server = StubServer.serving(200, "ok")) {
            notifier.send(new SlackNotifier.Config(server.url("/hook"), null, null, null),
                    "api 1.2.4 deployed");

            assertEquals("{\"text\": \"api 1.2.4 deployed\"}", server.received().getFirst().body());
        }
    }

    @Test
    @DisplayName("channel, username and icon_emoji are included when set")
    void includesOptionalFields() throws Exception {
        try (StubServer server = StubServer.serving(200, "ok")) {
            notifier.send(new SlackNotifier.Config(server.url("/hook"), "#deploys", "butler",
                    ":robot_face:"), "deployed");

            assertEquals("{\"text\": \"deployed\", \"channel\": \"#deploys\", "
                            + "\"username\": \"butler\", \"icon_emoji\": \":robot_face:\"}",
                    server.received().getFirst().body());
        }
    }

    @Test
    @DisplayName("a missing webhook fails clearly rather than posting to nothing")
    void missingWebhookFailsClearly() {
        var config = new SlackNotifier.Config(null, null, null, null);
        Exception e = assertThrows(Exception.class, () -> notifier.send(config, "hi"));
        assertTrue(e.getMessage().contains("no url configured"), e.getMessage());
    }

    @Test
    @DisplayName("the far end refusing the message is reported")
    void aRefusalIsReported() {
        try (StubServer server = StubServer.serving(500, "no")) {
            var config = new SlackNotifier.Config(server.url("/hook"), null, null, null);
            Exception e = assertThrows(Exception.class, () -> notifier.send(config, "hi"));
            assertTrue(e.getMessage().contains("500"), e.getMessage());
        }
    }
}
