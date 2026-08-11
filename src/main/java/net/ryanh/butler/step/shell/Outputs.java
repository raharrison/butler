package net.ryanh.butler.step.shell;

import net.ryanh.butler.spi.ProcessRunner;
import net.ryanh.butler.spi.StepResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The parts {@code shell.run} and {@code shell.exec} do the same way: turning a finished process
 * into a result, and the read-only checks worth making before starting one.
 */
final class Outputs {

    private Outputs() {
    }

    /**
     * The result of a finished process. {@code stdout}, {@code stderr} and {@code exit_code} are
     * attached whether it succeeded or not, since a failure that hides its output is no use.
     */
    static StepResult of(ProcessRunner.Completed done, String what) {
        StepResult result = done.ok()
                ? StepResult.ok()
                : StepResult.failed(why(done, what));
        return result
                .output("stdout", done.stdout())
                .output("stderr", done.stderr())
                .output("exit_code", (long) done.exitCode())
                .duration(done.duration());
    }

    private static String why(ProcessRunner.Completed done, String what) {
        if (done.timedOut()) {
            return what + " ran out of time and was killed";
        }
        String tail = done.stderr().isBlank() ? done.stdout() : done.stderr();
        String reason = what + " exited " + done.exitCode();
        return tail.isBlank() ? reason : reason + ": " + lastLine(tail);
    }

    private static String lastLine(String text) {
        String[] lines = text.strip().split("\n");
        return lines[lines.length - 1].strip();
    }

    /**
     * What can be checked without running anything: that the directory the command starts in
     * exists, and that the program is there to be run.
     */
    static List<String> preflight(Path workingDir, String program) {
        List<String> warnings = new ArrayList<>();
        if (workingDir != null && !Files.isDirectory(workingDir)) {
            warnings.add("working_dir does not exist: " + workingDir);
        }
        Path path = Path.of(program);
        if (path.isAbsolute() && !Files.isExecutable(path)) {
            warnings.add("not an executable file: " + program);
        }
        return List.copyOf(warnings);
    }
}
