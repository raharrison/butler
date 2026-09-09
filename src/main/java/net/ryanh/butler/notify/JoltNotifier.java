package net.ryanh.butler.notify;

import net.ryanh.butler.spi.Notifier;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Posts JSON to a Jolt channel's inbound endpoint, authenticated by the channel's token.
 */
public final class JoltNotifier implements Notifier<JoltNotifier.Config> {

    /**
     * @param token          the channel token, which is the whole of the credential
     * @param importance     1 to 5
     * @param idempotencyKey two sends carrying the same key collapse to one event
     */
    public record Config(String server, String token, String title, Integer importance,
                         List<String> tags, String click, String icon, String idempotencyKey,
                         Map<String, String> metadata) {
    }

    @Override
    public String name() {
        return "notify.jolt";
    }

    @Override
    public Class<Config> configType() {
        return Config.class;
    }

    @Override
    public void send(Config c, String message) throws IOException {
        if (c.server() == null || c.server().isBlank()) {
            throw new IOException("notify.jolt needs a server");
        }
        if (c.token() == null || c.token().isBlank()) {
            throw new IOException("notify.jolt needs a token");
        }
        if (c.title() == null || c.title().isBlank()) {
            throw new IOException("notify.jolt needs a title");
        }
        if (c.importance() != null && (c.importance() < 1 || c.importance() > 5)) {
            throw new IOException("notify.jolt importance is 1 to 5, not " + c.importance());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", c.title());
        body.put("message", message);
        body.put("importance", c.importance());
        body.put("tags", c.tags() == null || c.tags().isEmpty() ? null : c.tags());
        body.put("click", c.click());
        body.put("icon", c.icon());
        body.put("idempotencyKey", c.idempotencyKey());
        body.put("metadata", c.metadata() == null || c.metadata().isEmpty() ? null : c.metadata());

        String url = c.server().replaceAll("/+$", "") + "/api/v1/inbound";
        Posts.post(url, "application/json", Posts.json(body),
                Map.of("Authorization", "Bearer " + c.token()));
    }
}
