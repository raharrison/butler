package net.ryanh.butler.notify;

import net.ryanh.butler.testing.StubServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NtfyNotifierTest {

    private final NtfyNotifier notifier = new NtfyNotifier();

    @Test
    @DisplayName("a missing or blank server: falls back to the public ntfy.sh")
    void defaultsToThePublicServer() {
        assertEquals("https://ntfy.sh",
                new NtfyNotifier.Config(null, "topic", null, null, null).server());
        assertEquals("https://ntfy.sh",
                new NtfyNotifier.Config("  ", "topic", null, null, null).server());
    }

    @Test
    @DisplayName("posts the message as plain text to server/topic")
    void postsPlainTextBody() throws Exception {
        try (StubServer server = StubServer.serving(200, "{}")) {
            notifier.send(new NtfyNotifier.Config(server.url(""), "deploys", null, null, null),
                    "api 1.2.4 is live");

            var received = server.received().getFirst();
            assertEquals("/deploys", received.path());
            assertEquals("api 1.2.4 is live", received.body());
        }
    }

    @Test
    @DisplayName("title, priority and token become Title, Priority and a bearer Authorization header")
    void optionalFieldsBecomeHeaders() throws Exception {
        try (StubServer server = StubServer.serving(200, "{}")) {
            notifier.send(new NtfyNotifier.Config(server.url(""), "deploys", "Butler", "5", "tok"),
                    "hi");

            var headers = server.received().getFirst().headers();
            assertEquals(List.of("Butler"), headers.get("Title"));
            assertEquals(List.of("5"), headers.get("Priority"));
            assertEquals(List.of("Bearer tok"), headers.get("Authorization"));
        }
    }

    @Test
    @DisplayName("a missing topic fails clearly rather than posting to the bare server")
    void missingTopicFailsClearly() {
        var config = new NtfyNotifier.Config("http://127.0.0.1:1", null, null, null, null);
        Exception e = assertThrows(Exception.class, () -> notifier.send(config, "hi"));
        assertTrue(e.getMessage().contains("needs a topic"), e.getMessage());
    }

    @Test
    @DisplayName("the far end refusing the message is reported")
    void aRefusalIsReported() {
        try (StubServer server = StubServer.serving(500, "no")) {
            var config = new NtfyNotifier.Config(server.url(""), "deploys", null, null, null);
            Exception e = assertThrows(Exception.class, () -> notifier.send(config, "hi"));
            assertTrue(e.getMessage().contains("500"), e.getMessage());
        }
    }
}
