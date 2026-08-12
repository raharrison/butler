package net.ryanh.butler.runtime;

import net.ryanh.butler.config.Diagnostics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Third-party step, trigger and notifier types: every jar in {@code settings.plugins_dir}, loaded
 * into one child classloader before the registries are built (DESIGN.md §7.1).
 *
 * <p>One loader for all of them, since they are a single vocabulary and isolating them from each
 * other would buy nothing.
 */
public final class Plugins {

    private static final Logger log = LoggerFactory.getLogger(Plugins.class);

    private Plugins() {
    }

    /**
     * The loader the registries discover types from. A named-but-absent directory is not an error,
     * for the same reason a named-but-absent secrets file is not: a config is routinely validated
     * somewhere other than the host it runs on.
     *
     * @param dir {@code settings.plugins_dir}, or null when the config names none
     */
    public static ClassLoader loader(Path dir, Diagnostics diags) {
        ClassLoader parent = Plugins.class.getClassLoader();
        if (dir == null || !Files.exists(dir)) {
            return parent;
        }
        if (!Files.isDirectory(dir)) {
            diags.error("/settings/plugins_dir", "not a directory: " + dir);
            return parent;
        }

        List<URL> jars = new ArrayList<>();
        try (DirectoryStream<Path> found = Files.newDirectoryStream(dir, "*.jar")) {
            for (Path jar : found) {
                try {
                    jars.add(jar.toUri().toURL());
                } catch (MalformedURLException e) {
                    diags.error("/settings/plugins_dir", "could not load " + jar + ": " + e);
                }
            }
        } catch (IOException e) {
            diags.error("/settings/plugins_dir", "could not read " + dir + ": " + e);
            return parent;
        }

        if (jars.isEmpty()) {
            return parent;
        }
        log.info("loading {} plugin jar(s) from {}", jars.size(), dir);
        return new URLClassLoader("butler-plugins", jars.toArray(URL[]::new), parent);
    }
}
