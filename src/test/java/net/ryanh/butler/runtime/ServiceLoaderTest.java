package net.ryanh.butler.runtime;

import net.ryanh.butler.spi.Notifier;
import net.ryanh.butler.spi.StepType;
import net.ryanh.butler.spi.TriggerType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Extending Butler is a record, a class, and a line in a services file. Only the first two are
 * checked by the compiler, so a step with the line missing would build, pass every other test and
 * simply not exist at runtime.
 */
class ServiceLoaderTest {

    private static final Path SOURCES = Path.of("src/main/java/net/ryanh/butler");
    private static final Path SERVICES = Path.of("src/main/resources/META-INF/services");

    @Test
    @DisplayName("every step under step/ is registered")
    void everyStepIsListed() {
        assertEquals(implementationsUnder("step"), declared(StepType.class.getName()));
    }

    @Test
    @DisplayName("every trigger under trigger/ is registered")
    void everyTriggerIsListed() {
        assertEquals(implementationsUnder("trigger"), declared(TriggerType.class.getName()));
    }

    @Test
    @DisplayName("every notifier under notify/ is registered")
    void everyNotifierIsListed() {
        assertEquals(implementationsUnder("notify"), declared(Notifier.class.getName()));
    }

    @Test
    @DisplayName("the registries load what the files declare")
    void theRegistriesAgree() {
        assertTrue(StepRegistry.discover().names().containsAll(List.of("control.log", "control.set")));
        assertTrue(TriggerRegistry.discover().names().contains("manual"));
        assertTrue(NotifierRegistry.discover().names().contains("notify.slack"));
    }

    /**
     * Class names declared in the services file, ignoring blank lines and comments.
     */
    private static Set<String> declared(String service) {
        try {
            return Files.readAllLines(SERVICES.resolve(service)).stream()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .collect(Collectors.toCollection(TreeSet::new));
        } catch (IOException e) {
            throw new UncheckedIOException("no services file for " + service, e);
        }
    }

    /**
     * Class names under a source directory that are a step or a trigger. Read from the source
     * tree rather than by scanning the classpath, so a type that was written but never registered
     * is still found.
     */
    private static Set<String> implementationsUnder(String directory) {
        try (var files = Files.walk(SOURCES.resolve(directory))) {
            return files.filter(p -> p.toString().endsWith(".java"))
                    .filter(ServiceLoaderTest::isRegisterable)
                    .map(ServiceLoaderTest::className)
                    .collect(Collectors.toCollection(TreeSet::new));
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + directory, e);
        }
    }

    /**
     * A type answering {@code name()} is one the registry keys by that name, whether it implements
     * the interface itself or inherits it from a shared base. An abstract base does not count:
     * {@code systemd.restart}, {@code start} and {@code reload} share one, and it has no name of
     * its own to register.
     */
    private static boolean isRegisterable(Path file) {
        try {
            String source = Files.readString(file);
            return source.contains("public String name() {") && !source.contains("abstract class");
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + file, e);
        }
    }

    private static String className(Path file) {
        Path relative = SOURCES.getParent().getParent().getParent().relativize(file);
        return relative.toString()
                .replace(".java", "")
                .replace('\\', '.')
                .replace('/', '.');
    }
}
