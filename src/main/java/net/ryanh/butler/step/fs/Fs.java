package net.ryanh.butler.step.fs;

import net.ryanh.butler.util.Literals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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
