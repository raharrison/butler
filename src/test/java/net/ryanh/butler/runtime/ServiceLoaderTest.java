package net.ryanh.butler.runtime;

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
        assertEquals(implementationsUnder("step", StepType.class),
                declared(StepType.class.getName()));
    }

    @Test
    @DisplayName("every trigger under trigger/ is registered")
    void everyTriggerIsListed() {
        assertEquals(implementationsUnder("trigger", TriggerType.class),
                declared(TriggerType.class.getName()));
    }

    @Test
    @DisplayName("the registries load what the files declare")
    void theRegistriesAgree() {
        assertTrue(StepRegistry.discover().names().containsAll(List.of("control.log", "control.set")));
        assertTrue(TriggerRegistry.discover().names().contains("manual"));
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
     * Class names under a source directory that implement the interface. Read from the source
     * tree rather than by scanning the classpath, so a type that was written but never registered
     * is still found.
     */
    private static Set<String> implementationsUnder(String directory, Class<?> service) {
        try (var files = Files.walk(SOURCES.resolve(directory))) {
            return files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> implementsService(p, service))
                    .map(ServiceLoaderTest::className)
                    .collect(Collectors.toCollection(TreeSet::new));
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + directory, e);
        }
    }

    private static boolean implementsService(Path file, Class<?> service) {
        try {
            return Files.readString(file).contains("implements " + service.getSimpleName() + "<");
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
