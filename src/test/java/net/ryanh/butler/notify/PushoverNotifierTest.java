package net.ryanh.butler.notify;

import net.ryanh.butler.testing.StubServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pushover has one real endpoint, not a user-supplied URL like every other notifier here, so
 * these construct the notifier directly against a stub rather than going through a job.
 */
class PushoverNotifierTest {

    @Test
    @DisplayName("posts token, user and message as form fields, omitting what was not set")
    void postsFormEncoded() throws Exception {
        try (StubServer server = StubServer.serving(200, "{\"status\":1}")) {
            new PushoverNotifier(server.url("/1/messages.json"))
                    .send(new PushoverNotifier.Config("tok", "usr", null, null, null),
                            "api 1.2.4 is live");

            assertEquals("token=tok&user=usr&message=api+1.2.4+is+live",
                    server.received().getFirst().body());
        }
    }

    @Test
    @DisplayName("title, priority and sound are included when set")
    void includesOptionalFields() throws Exception {
        try (StubServer server = StubServer.serving(200, "{\"status\":1}")) {
            new PushoverNotifier(server.url("/1/messages.json"))
                    .send(new PushoverNotifier.Config("tok", "usr", "Butler", "1", "cosmic"),
                            "deployed");

            assertEquals("token=tok&user=usr&message=deployed&title=Butler&priority=1"
                    + "&sound=cosmic", server.received().getFirst().body());
        }
    }

    @Test
    @DisplayName("a missing token fails clearly rather than posting an invalid request")
    void missingTokenFailsClearly() {
        PushoverNotifier notifier = new PushoverNotifier("http://127.0.0.1:1");
        var config = new PushoverNotifier.Config(null, "usr", null, null, null);
        Exception e = assertThrows(Exception.class, () -> notifier.send(config, "hi"));
        assertTrue(e.getMessage().contains("needs a token"), e.getMessage());
    }

    @Test
    @DisplayName("a missing user fails clearly rather than posting an invalid request")
    void missingUserFailsClearly() {
        PushoverNotifier notifier = new PushoverNotifier("http://127.0.0.1:1");
        var config = new PushoverNotifier.Config("tok", null, null, null, null);
        Exception e = assertThrows(Exception.class, () -> notifier.send(config, "hi"));
        assertTrue(e.getMessage().contains("needs a user"), e.getMessage());
    }

    @Test
    @DisplayName("the far end refusing the message is reported, matching every other channel")
    void aRefusalIsReported() {
        try (StubServer server = StubServer.serving(400, "{\"status\":0}")) {
            PushoverNotifier notifier = new PushoverNotifier(server.url("/1/messages.json"));
            var config = new PushoverNotifier.Config("tok", "usr", null, null, null);
            Exception e = assertThrows(Exception.class, () -> notifier.send(config, "hi"));
            assertTrue(e.getMessage().contains("400"), e.getMessage());
        }
    }
}
