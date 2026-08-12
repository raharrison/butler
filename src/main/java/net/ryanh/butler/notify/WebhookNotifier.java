package net.ryanh.butler.notify;

import net.ryanh.butler.spi.Notifier;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Posts the message as JSON to any URL, for a service with no notifier of its own.
 */
public final class WebhookNotifier implements Notifier<WebhookNotifier.Config> {

    /**
     * @param field the JSON field the message goes in, since every service names it differently
     */
    public record Config(String url, String field, Map<String, String> headers) {
        public Config {
            field = field == null || field.isBlank() ? "text" : field;
            headers = headers == null ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        }
    }

    @Override
    public String name() {
        return "notify.webhook";
    }

    @Override
    public Class<Config> configType() {
        return Config.class;
    }

    @Override
    public void send(Config c, String message) throws IOException {
        Map<String, String> body = new LinkedHashMap<>();
        body.put(c.field(), message);
        Posts.post(c.url(), "application/json", Posts.json(body), c.headers());
    }
}
