package net.ryanh.butler.trigger.file;

import net.ryanh.butler.spi.TriggerContext;
import net.ryanh.butler.util.Literals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * What both file triggers share: a file's facts, its dedupe key, and settle detection.
 */
final class Watched {

    /**
     * Java's regex API will not list a pattern's named groups, so they are read out of the pattern
     * text.
     */
    private static final Pattern GROUP_NAME = Pattern.compile("\\(\\?<([a-zA-Z][a-zA-Z0-9]*)>");

    private Watched() {
    }

    /**
     * One observation of a file or a directory: enough to tell whether it is still being written.
     */
    record Snapshot(long size, long modified, long entries) {

        static Snapshot of(Path file) throws IOException {
            return new Snapshot(Files.size(file), Files.getLastModifiedTime(file).toMillis(), 1);
        }

        /**
         * A directory as the sum of everything beneath it: total bytes of the regular files, the
         * newest mtime anywhere in the tree, and how many entries there are. A directory's own
         * size is a constant and its own mtime moves only when an entry is added or removed
         * directly in it, so neither notices a large file three levels down still being written.
         *
         * <p>{@link Files#walk} does not follow symlinks, so there is no loop to guard against.
         */
        static Snapshot ofTree(Path dir) throws IOException {
            long size = 0;
            long modified = Files.getLastModifiedTime(dir).toMillis();
            long entries = 0;
            try (Stream<Path> tree = Files.walk(dir)) {
                for (Path p : tree.skip(1).toList()) {
                    entries++;
                    if (Files.isRegularFile(p)) {
                        size += Files.size(p);
                    }
                    modified = Math.max(modified, Files.getLastModifiedTime(p).toMillis());
                }
            } catch (UncheckedIOException e) {
                // Files.walk reports an unreadable subtree mid-stream; the tree is not observable
                // this poll, which the caller treats as it treats a file that vanished.
                throw e.getCause();
            }
            return new Snapshot(size, modified, entries);
        }
    }

    static Snapshot snapshot(Path path, Kind kind) throws IOException {
        return kind == Kind.DIR ? Snapshot.ofTree(path) : Snapshot.of(path);
    }

    /**
     * Remembers what each file looked like last time, so a change resets its settle clock.
     *
     * <p>Compares against the previous poll rather than testing the modification time's age, or a
     * copy that preserves timestamps would look settled the instant it started.
     */
    static final class Settling {

        private final Map<Path, Snapshot> seen = new HashMap<>();
        private final Map<Path, Instant> since = new HashMap<>();

        /**
         * @return true if this file has looked the same for at least {@code settle}
         */
        boolean settled(Path file, Snapshot now, Duration settle) {
            Snapshot before = seen.put(file, now);
            if (!now.equals(before)) {
                since.put(file, Instant.now());
                return false;
            }
            Instant first = since.get(file);
            return first != null && !Instant.now().isBefore(first.plus(settle));
        }

        /**
         * Drops everything not in {@code present}, so a directory churning through artifacts does
         * not grow these maps forever.
         */
        void forget(Set<Path> present) {
            seen.keySet().retainAll(present);
            since.keySet().retainAll(present);
        }
    }

    /**
     * Where the file is, how big, when it changed, and every named group {@code match} captured.
     */
    static Map<String, Object> facts(Path file, Snapshot snapshot, Pattern match) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("path", Literals.path(file.toAbsolutePath()));
        facts.put("name", file.getFileName().toString());
        facts.put("dir", Literals.path(file.toAbsolutePath().getParent()));
        facts.put("size", snapshot.size());
        facts.put("modified", Instant.ofEpochMilli(snapshot.modified()).toString());

        if (match != null) {
            Matcher m = match.matcher(file.getFileName().toString());
            if (m.matches()) {
                for (String group : groupNames(match)) {
                    facts.put(group, m.group(group));
                }
            }
        }
        return facts;
    }

    static List<String> groupNames(Pattern pattern) {
        List<String> names = new ArrayList<>();
        Matcher m = GROUP_NAME.matcher(pattern.pattern());
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    /**
     * Absolute path, size and modification time, so the same file written again is new work and
     * the same file seen twice is not. A directory adds its entry count, so a tree whose files
     * were shuffled without a net change in size is still new work.
     */
    static String dedupeKey(Path file, Snapshot snapshot, Kind kind) {
        String key = Literals.path(file.toAbsolutePath()) + ":" + snapshot.size() + ":"
                + snapshot.modified();
        return kind == Kind.DIR ? key + ":" + snapshot.entries() : key;
    }

    /**
     * The trigger's own {@code poll_interval:} if it is set and positive, else the daemon's
     * default ({@code settings.poll_interval}). A zero or negative override is treated as unset
     * rather than refused: the daemon default gets a validate-time check for this
     * (`ConfigLoader`), but a per-trigger override has no equivalent hook to refuse it from.
     */
    static Duration pollInterval(Duration override, TriggerContext ctx) {
        return override != null && !override.isZero() && !override.isNegative()
                ? override : ctx.pollInterval();
    }

    static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(Files.readAllBytes(file));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xf, 16));
                sb.append(Character.forDigit(b & 0xf, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
    }
}
