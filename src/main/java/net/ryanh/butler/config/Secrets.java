package net.ryanh.butler.config;

import net.ryanh.butler.config.model.ButlerConfig;
import net.ryanh.butler.util.Literals;
import tools.jackson.core.type.TypeReference;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * The {@code secret.*} namespace: a secrets file, the process environment, or both.
 *
 * <p>A map rather than a lookup method because that is what the expression evaluator walks, and
 * environment-backed secrets cannot be enumerated - only asked for by name - so {@link #get} does
 * the work and {@link #entrySet} reports what came from the file.
 *
 * <p>Values are not redacted anywhere (DESIGN.md §11); the documented guidance is to keep them out
 * of step output rather than to scrub them afterwards.
 */
public final class Secrets extends AbstractMap<String, Object> {

    private final Map<String, Object> fromFile;
    private final boolean fromEnv;

    private Secrets(Map<String, Object> fromFile, boolean fromEnv) {
        this.fromFile = fromFile;
        this.fromEnv = fromEnv;
    }

    public static Secrets none() {
        return new Secrets(Map.of(), false);
    }

    /**
     * Reads the configured secrets file, if there is one and it is there. A file named but absent
     * is not an error: configs are routinely validated somewhere other than the host they run on.
     */
    public static Secrets load(ButlerConfig.SecretsConfig config, Diagnostics diags) {
        if (config == null) {
            return none();
        }
        Map<String, Object> values = new LinkedHashMap<>();
        Path file = config.file();
        if (file != null && Files.isReadable(file)) {
            try {
                Map<String, Object> read = YAMLMapper.builder().build()
                        .readValue(Files.readString(file),
                                new TypeReference<LinkedHashMap<String, Object>>() {
                                });
                if (read != null) {
                    values.putAll(read);
                }
            } catch (IOException | RuntimeException e) {
                diags.error("/secrets/file", "could not read secrets from " + Literals.path(file)
                        + ": " + firstLine(e));
            }
        }
        return new Secrets(Collections.unmodifiableMap(values), config.fromEnv());
    }

    /**
     * A parser failure arrives as a paragraph of context and a Java reference chain, neither of
     * which says anything to someone reading a YAML file.
     */
    private static String firstLine(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank()
                ? e.getClass().getSimpleName() : message.split("\n")[0].strip();
    }

    @Override
    public Object get(Object key) {
        Object fromTheFile = fromFile.get(key);
        if (fromTheFile != null) {
            return fromTheFile;
        }
        return fromEnv && key != null ? System.getenv(String.valueOf(key)) : null;
    }

    @Override
    public boolean containsKey(Object key) {
        return get(key) != null;
    }

    @Override
    public Set<Entry<String, Object>> entrySet() {
        return fromFile.entrySet();
    }
}
