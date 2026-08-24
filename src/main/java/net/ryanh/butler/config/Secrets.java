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
 * The {@code secret.*} namespace: secrets files, the process environment, or both.
 *
 * <p>A map because that is what the evaluator walks, but environment-backed secrets cannot be
 * enumerated: {@link #get} does the work and {@link #entrySet} reports only the files. Values are
 * never redacted (DESIGN.md §11).
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
     * Reads the configured files in order and merges them. A file named but absent is not an
     * error: configs are routinely validated somewhere other than the host they run on. A name
     * defined in two of them is.
     */
    public static Secrets load(ButlerConfig.SecretsConfig config, Diagnostics diags) {
        if (config == null) {
            return none();
        }
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, Path> definedIn = new HashMap<>();
        YAMLMapper mapper = YAMLMapper.builder().build();
        List<Path> files = config.files();
        for (int i = 0; i < files.size(); i++) {
            Path file = files.get(i);
            if (!Files.isReadable(file)) {
                continue;
            }
            // With one file there is no index in the source to point at.
            String at = files.size() == 1 ? "/secrets/files" : "/secrets/files/" + i;
            try {
                Map<String, Object> read = mapper.readValue(Files.readString(file),
                        new TypeReference<LinkedHashMap<String, Object>>() {
                        });
                if (read == null) {
                    continue;
                }
                read.forEach((key, value) -> {
                    Path previous = definedIn.get(key);
                    if (previous != null) {
                        diags.error(at, "secret \"" + key + "\" is already defined in "
                                + Literals.path(previous));
                        return;
                    }
                    definedIn.put(key, file);
                    values.put(key, value);
                });
            } catch (IOException | RuntimeException e) {
                diags.error(at, "could not read secrets from " + Literals.path(file)
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
