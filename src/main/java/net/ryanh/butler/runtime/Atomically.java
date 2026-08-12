package net.ryanh.butler.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The one way Butler replaces a file in the state directory: write a temporary file beside it,
 * then move it over.
 *
 * <p>Everything under {@code state_dir} is read by an operator with {@code jq} while the daemon is
 * running, and re-read by the daemon itself on the next event, so a crash part-way through a write
 * must leave the previous document rather than half of the new one.
 */
final class Atomically {

    private Atomically() {
    }

    /**
     * Replaces {@code target} with {@code content}, creating the directory above it.
     */
    static void write(Path target, String content) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(),
                target.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                // Some filesystems cannot; a plain replace still beats a partial write.
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
