package net.ryanh.butler.trigger.file;

import net.ryanh.butler.spi.*;
import net.ryanh.butler.util.Literals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fires when the contents of one file change.
 *
 * <p>By content hash rather than modification time, so a config-management tool rewriting a file
 * every hour with the same contents does not redeploy anything.
 */
public final class ChangedTrigger implements TriggerType<ChangedTrigger.Config> {

    private static final Logger log = LoggerFactory.getLogger(ChangedTrigger.class);

    /**
     * @param onStartup    {@code all} means the same as {@code latest} here, since one path has
     *                     only ever one candidate
     * @param pollInterval how often to re-check the file; {@code settings.poll_interval} unless
     *                     set here
     */
    public record Config(Path path, Duration settle, OnStartup onStartup, Duration pollInterval) {
        public Config {
            settle = settle == null ? Duration.ofSeconds(10) : settle;
            onStartup = onStartup == null ? OnStartup.LATEST : onStartup;
        }
    }

    @Override
    public String name() {
        return "file.changed";
    }

    @Override
    public List<String> required() {
        return List.of("path");
    }

    @Override
    public Class<Config> configType() {
        return Config.class;
    }

    @Override
    public Watcher start(Config config, EventSink sink, TriggerContext ctx) {
        if (config.path() == null) {
            throw new IllegalArgumentException("file.changed needs a path:");
        }
        AtomicBoolean running = new AtomicBoolean(true);
        Thread thread = Thread.ofVirtual()
                .name("trigger-file.changed-" + ctx.job())
                .start(() -> poll(config, sink, ctx, running));
        return () -> {
            running.set(false);
            thread.interrupt();
        };
    }

    private void poll(Config config, EventSink sink, TriggerContext ctx, AtomicBoolean running) {
        Watched.Settling settling = new Watched.Settling();
        String lastHash = null;
        boolean first = true;

        while (running.get()) {
            try {
                Watched.Snapshot snapshot = Files.isRegularFile(config.path())
                        ? Watched.Snapshot.of(config.path()) : null;
                if (snapshot != null
                        && settling.settled(config.path(), snapshot, config.settle())) {
                    String hash = Watched.sha256(config.path());
                    // on_startup: governs the first reading only; after that a change is a change.
                    boolean announce = first
                            ? config.onStartup() != OnStartup.NONE
                            : !hash.equals(lastHash);
                    boolean changed = !hash.equals(lastHash);
                    lastHash = hash;
                    first = false;
                    if (changed && announce) {
                        sink.emit(event(config.path(), snapshot, hash));
                    }
                }
                settling.forget(snapshot == null ? Set.of() : Set.of(config.path()));
            } catch (IOException e) {
                log.warn("could not read {}: {}", config.path(), e.toString());
            }
            try {
                Thread.sleep(Watched.pollInterval(config.pollInterval(), ctx));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private Event event(Path file, Watched.Snapshot snapshot, String hash) {
        Map<String, Object> facts = new LinkedHashMap<>(Watched.facts(file, snapshot, null));
        facts.put("sha256", hash);
        // Keyed by the hash, not the timestamp, for the reason in the class javadoc.
        return new Event(name(), facts, Literals.path(file.toAbsolutePath()) + ":" + hash);
    }

    /**
     * The file as it stands, if it has held still long enough to be worth reading.
     */
    @Override
    public List<Event> current(Config config, TriggerContext ctx) {
        try {
            if (config.path() == null || !Files.isRegularFile(config.path())) {
                return List.of();
            }
            Watched.Snapshot snapshot = Watched.Snapshot.of(config.path());
            return Instant.ofEpochMilli(snapshot.modified())
                    .isBefore(Instant.now().minus(config.settle()))
                    ? List.of(event(config.path(), snapshot, Watched.sha256(config.path())))
                    : List.of();
        } catch (IOException e) {
            log.warn("could not read {}: {}", config.path(), e.toString());
            return List.of();
        }
    }
}
