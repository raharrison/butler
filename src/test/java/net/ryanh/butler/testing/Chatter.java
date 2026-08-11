package net.ryanh.butler.testing;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A program for the process tests to run. This JVM rather than {@code /bin/sh}, so the tests run
 * wherever Java does.
 */
public final class Chatter {

    private Chatter() {
    }

    /**
     * The command that runs a given mode, built from the JVM running the test.
     */
    public static List<String> argv(String... args) {
        List<String> argv = new ArrayList<>();
        argv.add(ProcessHandle.current().info().command().orElseThrow());
        argv.add("-cp");
        argv.add(System.getProperty("java.class.path"));
        argv.add(Chatter.class.getName());
        argv.addAll(List.of(args));
        return List.copyOf(argv);
    }

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "echo" -> System.out.println(args[1]);
            case "complain" -> System.err.println(args[1]);
            case "exit" -> System.exit(Integer.parseInt(args[1]));
            case "flood" -> flood(Integer.parseInt(args[1]));
            case "chatter" -> chatter(Long.parseLong(args[1]));
            case "sleep" -> Thread.sleep(Long.parseLong(args[1]));
            case "spawn" -> spawn(Path.of(args[1]));
            default -> throw new IllegalArgumentException("no such mode: " + args[0]);
        }
    }

    /**
     * Writes more than the capture buffer holds, proving the output is both drained and bounded.
     */
    private static void flood(int bytes) {
        PrintStream out = new PrintStream(System.out, false, StandardCharsets.UTF_8);
        String line = "x".repeat(99) + "\n";
        for (int written = 0; written < bytes; written += line.length()) {
            out.print(line);
        }
        out.flush();
    }

    /**
     * Prints steadily and outlives its welcome, so a test can kill it and check the output it had
     * already produced survived.
     */
    private static void chatter(long millis) throws InterruptedException {
        long until = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < until) {
            System.out.println("still here");
            System.out.flush();
            Thread.sleep(50);
        }
    }

    /**
     * Starts a child that outlives its parent unless the whole tree is killed, and records its pid
     * where the test can find it.
     */
    private static void spawn(Path pidFile) throws IOException, InterruptedException {
        Process child = new ProcessBuilder(argv("sleep", "60000")).start();
        Files.writeString(pidFile, String.valueOf(child.pid()));
        Thread.sleep(60_000);
    }
}
