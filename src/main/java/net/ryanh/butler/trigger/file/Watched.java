package net.ryanh.butler.trigger.file;

import net.ryanh.butler.spi.TriggerContext;
import net.ryanh.butler.util.Literals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
     * One observation of a file: enough to tell whether it is still being written.
     */
    record Snapshot(long size, long modified) {

        static Snapshot of(Path file) throws IOException {
            return new Snapshot(Files.size(file), Files.getLastModifiedTime(file).toMillis());
        }
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
     * the same file seen twice is not.
     */
    static String dedupeKey(Path file, Snapshot snapshot) {
        return Literals.path(file.toAbsolutePath()) + ":" + snapshot.size() + ":"
                + snapshot.modified();
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
