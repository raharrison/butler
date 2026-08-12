package net.ryanh.butler.notify;

import net.ryanh.butler.spi.Notifier;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Posts to a Discord webhook, which takes the message in {@code content}.
 */
public final class DiscordNotifier implements Notifier<DiscordNotifier.Config> {

    public record Config(String webhook, String username) {
    }

    @Override
    public String name() {
        return "notify.discord";
    }

    @Override
    public Class<Config> configType() {
        return Config.class;
    }

    @Override
    public void send(Config c, String message) throws IOException {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("content", message);
        body.put("username", c.username());
        Posts.post(c.webhook(), "application/json", Posts.json(body), Map.of());
    }
}
