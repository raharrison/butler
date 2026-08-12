package net.ryanh.butler.step.shell;

import net.ryanh.butler.util.Literals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The checks {@code shell.run} and {@code shell.exec} both make before starting a process.
 */
final class Programs {

    private Programs() {
    }

    /**
     * That the directory the command starts in exists, and the program is there to be run.
     */
    static List<String> preflight(Path workingDir, String program) {
        List<String> warnings = new ArrayList<>();
        if (workingDir != null && !Files.isDirectory(workingDir)) {
            warnings.add("working_dir does not exist: " + Literals.path(workingDir));
        }
        Path path = Path.of(program);
        if (path.isAbsolute() && !Files.isExecutable(path)) {
            warnings.add("not an executable file: " + program);
        }
        return List.copyOf(warnings);
    }
}
