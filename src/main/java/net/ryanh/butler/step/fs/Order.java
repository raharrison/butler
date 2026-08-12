package net.ryanh.butler.step.fs;

import net.ryanh.butler.util.Semver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * How {@code fs.list} and {@code fs.prune} rank directory entries, least first.
 */
public enum Order {

    /**
     * Lexicographic, which is right for anything zero-padded or dated.
     */
    NAME,

    /**
     * By version, so {@code 1.10.0} never ranks below {@code 1.9.0}. A name that is not a version
     * sorts below every name that is, so it can never be taken for the newest release.
     */
    SEMVER,

    /**
     * By last modification time.
     */
    MODIFIED;

    Comparator<Path> comparator() {
        return switch (this) {
            case NAME -> Comparator.comparing(Order::name);
            case SEMVER -> Comparator.comparing(p -> Semver.tryParse(name(p)),
                    Comparator.nullsFirst(Comparator.naturalOrder()));
            case MODIFIED -> Comparator.comparingLong(Order::modified);
        };
    }

    private static String name(Path p) {
        return p.getFileName().toString();
    }

    /**
     * An entry that vanished between listing and ranking sorts oldest, so it is never taken for
     * the newest release.
     */
    private static long modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return Long.MIN_VALUE;
        }
    }
}
