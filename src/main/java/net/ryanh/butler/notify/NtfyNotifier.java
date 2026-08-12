package net.ryanh.butler.notify;

import net.ryanh.butler.spi.Notifier;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Posts to an ntfy topic, which takes the message as the body and everything else as headers.
 */
public final class NtfyNotifier implements Notifier<NtfyNotifier.Config> {

    /**
     * @param server the ntfy instance; the public one unless the config names another
     * @param token  an access token for a protected topic
     */
    public record Config(String server, String topic, String title, String priority, String token) {
        public Config {
            server = server == null || server.isBlank() ? "https://ntfy.sh" : server;
        }
    }

    @Override
    public String name() {
        return "notify.ntfy";
    }

    @Override
    public Class<Config> configType() {
        return Config.class;
    }

    @Override
    public void send(Config c, String message) throws IOException {
        if (c.topic() == null || c.topic().isBlank()) {
            throw new IOException("notify.ntfy needs a topic");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        if (c.title() != null) {
            headers.put("Title", c.title());
        }
        if (c.priority() != null) {
            headers.put("Priority", c.priority());
        }
        if (c.token() != null) {
            headers.put("Authorization", "Bearer " + c.token());
        }
        String url = c.server().replaceAll("/+$", "") + "/" + c.topic();
        Posts.post(url, "text/plain; charset=utf-8", message, headers);
    }
}
