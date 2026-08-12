package net.ryanh.butler.trigger.file;

import net.ryanh.butler.spi.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Fires when a new file settles in a directory.
 *
 * <p>Polling rather than {@code WatchService}, which misses files written before startup, behaves
 * inconsistently on network and overlay filesystems, and coalesces events under load.
 */
public final class AppearedTrigger implements TriggerType<AppearedTrigger.Config> {

    private static final Logger log = LoggerFactory.getLogger(AppearedTrigger.class);

    /**
     * @param match     names to fire for; every named group becomes a {@code trigger.*} fact
     * @param settle    how long a file's size and modification time must hold still first
     * @param orderBy   ranks candidates, so only the greatest fires and dropping an old artifact
     *                  into the directory cannot trigger a downgrade
     * @param onStartup what to do about what is already there when the daemon starts
     */
    public record Config(Path dir, Pattern match, Duration settle, String orderBy,
                         OnStartup onStartup) {
        public Config {
            match = match != null && match.pattern().isBlank() ? null : match;
            settle = settle == null ? Duration.ofSeconds(10) : settle;
            onStartup = onStartup == null ? OnStartup.LATEST : onStartup;
        }

        boolean ranks() {
            return orderBy != null && !orderBy.isBlank();
        }
    }

    @Override
    public String name() {
        return "file.appeared";
    }

    @Override
    public Class<Config> configType() {
        return Config.class;
    }

    /**
     * {@code order_by:} ranks by whatever the regex captured, so it is an expression over this
     * trigger's own facts (DESIGN.md §7.2).
     */
    @Override
    public List<String> conditions() {
        return List.of("order_by");
    }

    @Override
    public Watcher start(Config config, EventSink sink, TriggerContext ctx) {
        // On the caller's thread, so an order_by that will not parse is reported rather than
        // killing the watch thread in silence.
        Comparator<Map<String, Object>> byFacts =
                config.ranks() ? ctx.ordering(config.orderBy()) : null;
        AtomicBoolean running = new AtomicBoolean(true);
        Thread thread = Thread.ofVirtual()
                .name("trigger-file.appeared-" + ctx.job())
                .start(() -> poll(config, byFacts, sink, ctx, running));
        return () -> {
            running.set(false);
            thread.interrupt();
        };
    }

    /**
     * The watch loop, blocking style on a virtual thread.
     */
    private void poll(Config config, Comparator<Map<String, Object>> byFacts, EventSink sink,
                      TriggerContext ctx, AtomicBoolean running) {
        Pattern pattern = config.match();
        Watched.Settling settling = new Watched.Settling();
        Set<String> emitted = new LinkedHashSet<>();

        // What on_startup: leaves alone is recorded as already emitted rather than filtered out
        // later, so a rewrite of the same file, which carries a different key, still fires.
        List<Candidate> ignored =
                ignoredAtStartup(config, ordered(scan(config, pattern), byFacts));
        ignored.forEach(c -> emitted.add(c.event().dedupeKey()));
        Map<String, Object> highest = ignored.isEmpty()
                ? null : ignored.getLast().event().facts();

        while (running.get()) {
            List<Candidate> present = scan(config, pattern);
            // Bounded to what is still there, or this grows for the life of the daemon.
            emitted.retainAll(present.stream().map(c -> c.event().dedupeKey()).toList());

            for (Candidate candidate : ordered(settled(config, settling, present), byFacts)) {
                if (!emitted.add(candidate.event().dedupeKey())) {
                    continue;
                }
                // Under order_by only the greatest yet seen fires, so an old artifact dropped in
                // later cannot deploy a downgrade. Without it, every new file is work.
                if (byFacts != null && highest != null
                        && byFacts.compare(candidate.event().facts(), highest) <= 0) {
                    log.info("ignoring {}: it does not rank above what has already been seen",
                            candidate.file());
                    continue;
                }
                highest = candidate.event().facts();
                sink.emit(candidate.event());
            }
            try {
                Thread.sleep(ctx.pollInterval());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * A file in the watch directory, with the event it would produce.
     */
    private record Candidate(Path file, Watched.Snapshot snapshot, Event event) {
    }

    /**
     * Every matching file in the directory as it stands, settled or not.
     */
    private List<Candidate> scan(Config config, Pattern pattern) {
        if (!Files.isDirectory(config.dir())) {
            return List.of();
        }
        List<Candidate> out = new ArrayList<>();
        try (Stream<Path> listed = Files.list(config.dir())) {
            for (Path file : listed.filter(Files::isRegularFile).toList()) {
                if (pattern != null && !pattern.matcher(file.getFileName().toString()).matches()) {
                    continue;
                }
                try {
                    Watched.Snapshot snapshot = Watched.Snapshot.of(file);
                    out.add(new Candidate(file, snapshot,
                            new Event(name(), Watched.facts(file, snapshot, pattern),
                                    Watched.dedupeKey(file, snapshot))));
                } catch (IOException e) {
                    // Deleted between listing and reading; the next poll will find it or not.
                    log.debug("{} vanished while being read: {}", file, e.toString());
                }
            }
        } catch (IOException e) {
            log.warn("could not read {}: {}", config.dir(), e.toString());
        }
        return out;
    }

    private static List<Candidate> settled(Config config, Watched.Settling settling,
                                           List<Candidate> present) {
        settling.forget(new HashSet<>(present.stream().map(Candidate::file).toList()));
        return present.stream()
                .filter(c -> settling.settled(c.file(), c.snapshot(), config.settle()))
                .toList();
    }

    /**
     * What was already there that {@code on_startup:} says not to fire for. Candidates arrive
     * least first, so the greatest is the last.
     */
    private static List<Candidate> ignoredAtStartup(Config config, List<Candidate> present) {
        return switch (config.onStartup()) {
            case ALL -> List.of();
            case NONE -> present;
            case LATEST -> present.isEmpty() ? List.of()
                    : present.subList(0, present.size() - 1);
        };
    }

    /**
     * Least first: by {@code order_by:} where there is one, by modification time otherwise.
     */
    private static List<Candidate> ordered(List<Candidate> candidates,
                                           Comparator<Map<String, Object>> byFacts) {
        List<Candidate> out = new ArrayList<>(candidates);
        out.sort(byFacts == null
                ? Comparator.comparingLong(c -> c.snapshot().modified())
                : (a, b) -> byFacts.compare(a.event().facts(), b.event().facts()));
        return out;
    }

    /**
     * What this trigger can see right now, least first.
     *
     * <p>Settle is judged from the modification time's age, since one look has no earlier
     * observation to compare against.
     */
    @Override
    public List<Event> current(Config config, TriggerContext ctx) {
        Instant settledBy = Instant.now().minus(config.settle());
        List<Candidate> settled = scan(config, config.match()).stream()
                .filter(c -> Instant.ofEpochMilli(c.snapshot().modified()).isBefore(settledBy))
                .toList();
        return ordered(settled, config.ranks() ? ctx.ordering(config.orderBy()) : null)
                .stream().map(Candidate::event).toList();
    }
}
