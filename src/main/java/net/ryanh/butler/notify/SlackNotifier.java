package net.ryanh.butler.notify;

import net.ryanh.butler.spi.Notifier;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Posts to a Slack incoming webhook, whose URL is a secret: write
 * {@code webhook: ${secret.SLACK_WEBHOOK}}.
 */
public final class SlackNotifier implements Notifier<SlackNotifier.Config> {

    public record Config(String webhook, String channel, String username, String iconEmoji) {
    }

    @Override
    public String name() {
        return "notify.slack";
    }

    @Override
    public Class<Config> configType() {
        return Config.class;
    }

    @Override
    public void send(Config c, String message) throws IOException {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("text", message);
        body.put("channel", c.channel());
        body.put("username", c.username());
        body.put("icon_emoji", c.iconEmoji());
        Posts.post(c.webhook(), "application/json", Posts.json(body), Map.of());
    }
}
