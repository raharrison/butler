package net.ryanh.butler;

import net.ryanh.butler.cli.ButlerCommand;
import picocli.CommandLine;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    /**
     * Separated from {@link #main} so tests can assert on exit codes without exiting the JVM.
     */
    public static int run(String... args) {
        return new CommandLine(new ButlerCommand())
                .setCaseInsensitiveEnumValuesAllowed(true)
                .setExecutionExceptionHandler((ex, cmd, parseResult) -> {
                    cmd.getErr().println(cmd.getColorScheme().errorText("error: " + message(ex)));
                    return ButlerCommand.EXIT_FAILURE;
                })
                .execute(args);
    }

    private static String message(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }
}
