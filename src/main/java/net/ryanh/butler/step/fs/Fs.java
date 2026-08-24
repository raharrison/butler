package net.ryanh.butler.step.fs;

import net.ryanh.butler.util.Literals;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * What the {@code fs.*} steps share: octal modes, parent directories, and the checks worth making
 * before touching anything.
 */
final class Fs {

    private Fs() {
    }

    /**
     * Parses an octal mode as the config writes it, {@code "0640"} or {@code "640"}.
     *
     * @throws IllegalArgumentException if it is not three or four octal digits
     */
    static Set<PosixFilePermission> mode(String mode) {
        String digits = mode.length() == 4 && mode.charAt(0) == '0' ? mode.substring(1) : mode;
        if (!digits.matches("[0-7]{3}")) {
            throw new IllegalArgumentException("not an octal mode: \"" + mode
                    + "\" (expected three or four octal digits, e.g. 0640)");
        }
        return PosixFilePermissions.fromString(rwx(digits));
    }

    private static String rwx(String digits) {
        StringBuilder sb = new StringBuilder(9);
        for (int i = 0; i < 3; i++) {
            int bits = digits.charAt(i) - '0';
            sb.append((bits & 4) != 0 ? 'r' : '-');
            sb.append((bits & 2) != 0 ? 'w' : '-');
            sb.append((bits & 1) != 0 ? 'x' : '-');
        }
        return sb.toString();
    }

    /**
     * Applies a mode, silently where the filesystem has no permissions to set. Butler deploys to
     * Linux hosts, but a config is authored and tested wherever Java runs.
     */
    static void applyMode(Path path, String mode) throws IOException {
        if (mode == null || mode.isBlank()) {
            return;
        }
        Set<PosixFilePermission> permissions = mode(mode);
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Not a POSIX filesystem.
        }
    }

    /**
     * Applies an owner and a group, silently where the filesystem has no notion of either, as
     * {@link #applyMode} does.
     *
     * <p>A name the host does not know, or a change it will not permit, fails the step instead: the
     * file would exist but not be the file the config asked for, and a service that cannot read its
     * own release is worse than a deploy that stopped.
     */
    static void applyOwnership(Path path, String owner, String group) throws IOException {
        if (!named(owner) && !named(group)) {
            return;
        }
        PosixFileAttributeView view =
                Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (view == null) {
            return;
        }
        UserPrincipalLookupService lookup = path.getFileSystem().getUserPrincipalLookupService();
        try {
            if (named(owner)) {
                view.setOwner(lookup.lookupPrincipalByName(owner));
            }
            if (named(group)) {
                view.setGroup(lookup.lookupPrincipalByGroupName(group));
            }
        } catch (UserPrincipalNotFoundException e) {
            throw new IOException("no such user or group on this host: " + e.getName());
        }
    }

    /**
     * How {@code owner:} and {@code group:} read in a plan: {@code owner app:staff},
     * {@code owner app}, {@code group staff}, or null when neither was asked for.
     */
    static String ownership(String owner, String group) {
        if (named(owner) && named(group)) {
            return "owner " + owner + ":" + group;
        }
        if (named(owner)) {
            return "owner " + owner;
        }
        return named(group) ? "group " + group : null;
    }

    /**
     * Warns about an owner or group this host has never heard of. Skipped where the filesystem has
     * no POSIX ownership at all, since a config describing a Linux host is routinely validated
     * somewhere else.
     */
    static List<String> ownershipChecks(String owner, String group) {
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return List.of();
        }
        UserPrincipalLookupService lookup =
                FileSystems.getDefault().getUserPrincipalLookupService();
        List<String> warnings = new ArrayList<>();
        if (named(owner)) {
            try {
                lookup.lookupPrincipalByName(owner);
            } catch (IOException e) {
                warnings.add("no such user on this host: " + owner);
            }
        }
        if (named(group)) {
            try {
                lookup.lookupPrincipalByGroupName(group);
            } catch (IOException e) {
                warnings.add("no such group on this host: " + group);
            }
        }
        return List.copyOf(warnings);
    }

    static boolean named(String name) {
        return name != null && !name.isBlank();
    }

    /**
     * Deletes a path, and everything under it if it is a directory. A symlink is removed rather
     * than followed, so what it pointed at is left alone.
     */
    static void delete(Path path) throws IOException {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(path);
            return;
        }
        // Depth first: a directory cannot be removed until it is empty. walk() does not follow
        // symlinks, so a link into another tree is deleted as the link it is.
        try (Stream<Path> tree = Files.walk(path)) {
            for (Path p : tree.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    /**
     * Whether a directory has anything in it at all.
     */
    static boolean isEmptyDirectory(Path dir) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.findAny().isEmpty();
        }
    }

    /**
     * How many paths a recursive delete would remove, the directory itself included.
     */
    static long entryCount(Path path) throws IOException {
        try (Stream<Path> tree = Files.walk(path)) {
            return tree.count();
        }
    }

    /**
     * How many of a path's parent directories do not exist yet.
     */
    static int missingParents(Path path) {
        int missing = 0;
        for (Path p = path.getParent(); p != null && !Files.isDirectory(p); p = p.getParent()) {
            missing++;
        }
        return missing;
    }

    static String parents(int count) {
        return count == 1 ? "1 parent directory" : count + " parent directories";
    }

    /**
     * Warnings for a source that cannot be read or a destination that cannot be written.
     */
    static List<String> transferChecks(Path from, Path to, boolean mkdirs) {
        List<String> warnings = new ArrayList<>();
        if (!Files.exists(from, LinkOption.NOFOLLOW_LINKS)) {
            warnings.add("source does not exist: " + Literals.path(from));
        } else if (!Files.isReadable(from)) {
            warnings.add("source is not readable: " + Literals.path(from));
        }
        Path parent = to.getParent();
        if (parent == null) {
            return List.copyOf(warnings);
        }
        if (Files.isDirectory(parent)) {
            if (!Files.isWritable(parent)) {
                warnings.add("destination directory is not writable: " + Literals.path(parent));
            }
        } else if (!mkdirs) {
            warnings.add("destination directory does not exist and mkdirs is false: "
                    + Literals.path(parent));
        }
        return List.copyOf(warnings);
    }

    /**
     * The target a symlink points at, or null if the path is not one.
     */
    static Path linkTarget(Path link) {
        try {
            return Files.isSymbolicLink(link) ? Files.readSymbolicLink(link) : null;
        } catch (IOException e) {
            return null;
        }
    }
}
