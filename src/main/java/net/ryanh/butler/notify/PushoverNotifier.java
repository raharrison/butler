package net.ryanh.butler.notify;

import net.ryanh.butler.spi.Notifier;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Posts to the Pushover API, which takes the message and everything else as form fields rather
 * than JSON, unlike every other channel here.
 */
public final class PushoverNotifier implements Notifier<PushoverNotifier.Config> {

    private static final String ENDPOINT = "https://api.pushover.net/1/messages.json";

    private final String endpoint;

    public PushoverNotifier() {
        this(ENDPOINT);
    }

    /**
     * Pushover has one API endpoint, not a self-hosted one like ntfy's, so this has no config
     * key of its own - it exists so a test can point the notifier at a stub server.
     */
    PushoverNotifier(String endpoint) {
        this.endpoint = endpoint;
    }

    /**
     * @param token the application's API token
     * @param user  the user or group key to notify
     */
    public record Config(String token, String user, String title, String priority, String sound) {
    }

    @Override
    public String name() {
        return "notify.pushover";
    }

    @Override
    public Class<Config> configType() {
        return Config.class;
    }

    @Override
    public void send(Config c, String message) throws IOException {
        if (c.token() == null || c.token().isBlank()) {
            throw new IOException("notify.pushover needs a token");
        }
        if (c.user() == null || c.user().isBlank()) {
            throw new IOException("notify.pushover needs a user");
        }
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("token", c.token());
        fields.put("user", c.user());
        fields.put("message", message);
        fields.put("title", c.title());
        fields.put("priority", c.priority());
        fields.put("sound", c.sound());
        Posts.post(endpoint, "application/x-www-form-urlencoded; charset=utf-8", form(fields),
                Map.of());
    }

    /**
     * {@code application/x-www-form-urlencoded}, leaving out fields the config did not set.
     */
    private static String form(Map<String, String> fields) {
        StringBuilder sb = new StringBuilder();
        fields.forEach((key, value) -> {
            if (value == null) {
                return;
            }
            if (!sb.isEmpty()) {
                sb.append('&');
            }
            sb.append(key).append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8));
        });
        return sb.toString();
    }
}
