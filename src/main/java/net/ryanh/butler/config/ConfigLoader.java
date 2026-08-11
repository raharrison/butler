package net.ryanh.butler.config;

import net.ryanh.butler.config.model.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/**
 * Reads a config file into the model, collecting every problem rather than stopping at the first.
 *
 * <p>The document is bound to a generic tree and walked with {@link Cursor} rather than bound
 * straight to records by databind. Databind throws on the first mismatch, which is the opposite
 * of what this needs; walking the tree keeps full control over unknown-key detection, paths and
 * error recovery.
 */
public final class ConfigLoader {

    private ConfigLoader() {
    }

    /**
     * The outcome of loading: whatever could be built, plus everything wrong with it.
     */
    public record Result(ButlerConfig config, Diagnostics diagnostics, String source) {
        public boolean ok() {
            return !diagnostics.hasErrors();
        }
    }

    public static Result load(Path file) throws IOException {
        return parse(Files.readString(file));
    }

    public static Result parse(String yaml) {
        Diagnostics diags = new Diagnostics();
        SourceMap sourceMap = SourceMap.of(yaml);
        diags.sourceMap(sourceMap);
        rejectAliases(sourceMap, diags);

        if (isEffectivelyEmpty(yaml)) {
            diags.error("", "the config file is empty");
            return new Result(null, diags, yaml);
        }

        Map<String, Object> root;
        try {
            // Strict duplicate detection is the one parser-level check that matters here: a
            // repeated key would otherwise be silently resolved last-one-wins. Unknown-key
            // handling is Cursor's job, since this reads into a plain map.
            YAMLMapper mapper = YAMLMapper.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .build();
            root = mapper.readValue(yaml, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (RuntimeException e) {
            diags.error("", "could not parse YAML: " + rootCause(e));
            return new Result(null, diags, yaml);
        }
        if (root == null) {
            diags.error("", "the config file is empty");
            return new Result(null, diags, yaml);
        }

        Cursor c = new Cursor(root, "", diags);
        ButlerConfig config = document(c);
        return new Result(config, diags, yaml);
    }

    /**
     * An alias binds as the anchor's <em>name</em> rather than its value, so {@code copy: *base}
     * would quietly become the string "base". Rather than resolve them, which would cost every
     * later diagnostic its true line and column, the document is refused and told what to do
     * instead.
     */
    private static void rejectAliases(SourceMap sourceMap, Diagnostics diags) {
        for (String path : sourceMap.aliases()) {
            diags.error(path, "YAML anchors and aliases are not supported; "
                    + "repeat the value, or put it in vars: and reference it with ${vars.name}");
        }
    }

    /**
     * True for a file with nothing but blank lines, comments and document markers. Checked
     * up front so an empty config is reported as such rather than as a parser end-of-input.
     */
    private static boolean isEffectivelyEmpty(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            return true;
        }
        return yaml.lines()
                .map(String::strip)
                .allMatch(l -> l.isEmpty() || l.startsWith("#") || l.equals("---") || l.equals("..."));
    }

    private static String rootCause(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String msg = cause.getMessage();
        return msg == null ? cause.getClass().getSimpleName() : msg.split("\n")[0];
    }

    // ------------------------------------------------------------------ document

    private static ButlerConfig document(Cursor c) {
        int version = c.integer("version", 1);
        if (version != 1) {
            c.diagnostics().error("/version",
                    "unsupported config version " + version + " (this build understands version 1)");
        }

        ButlerConfig.Settings settings = settings(c.object("settings"));
        ButlerConfig.SecretsConfig secrets = secrets(c.object("secrets"));
        Map<String, Object> vars = c.anyMap("vars");

        Map<String, NotifierDef> notifiers = new LinkedHashMap<>();
        c.namedObjects("notifiers").forEach((name, nc) -> notifiers.put(name, notifier(name, nc)));

        Map<String, JobDef> jobs = new LinkedHashMap<>();
        c.namedObjects("jobs").forEach((name, jc) -> jobs.put(name, job(name, jc)));

        if (jobs.isEmpty() && !c.diagnostics().hasErrorAt("/jobs")) {
            c.diagnostics().error("", "no jobs defined: a config needs at least one job");
        }

        c.rejectUnknownKeys();
        return new ButlerConfig(version, settings, secrets, vars,
                Collections.unmodifiableMap(notifiers), Collections.unmodifiableMap(jobs));
    }

    private static ButlerConfig.Settings settings(Cursor c) {
        ButlerConfig.Settings d = ButlerConfig.Settings.defaults();
        String stateDir = c.string("state_dir", d.stateDir().toString());
        Enums.LogFormat logFormat = c.enumValue("log_format", Enums.LogFormat.class, d.logFormat());
        int maxRuns = c.integer("max_concurrent_runs", d.maxConcurrentRuns());
        Duration poll = c.duration("poll_interval", d.pollInterval());

        Cursor rc = c.object("run_retention");
        ButlerConfig.RunRetention retention = new ButlerConfig.RunRetention(
                rc.integer("count", d.runRetention().count()),
                rc.duration("age", d.runRetention().age()));
        rc.rejectUnknownKeys();

        String plugins = c.string("plugins_dir", null);
        c.rejectUnknownKeys();

        if (maxRuns < 1) {
            c.diagnostics().error("/settings/max_concurrent_runs",
                    "must be at least 1, found " + maxRuns);
        }
        return new ButlerConfig.Settings(
                Path.of(stateDir), logFormat, maxRuns, poll, retention,
                plugins == null ? null : Path.of(plugins));
    }

    private static ButlerConfig.SecretsConfig secrets(Cursor c) {
        ButlerConfig.SecretsConfig d = ButlerConfig.SecretsConfig.defaults();
        boolean fromEnv = c.bool("from_env", d.fromEnv());
        String file = c.string("file", null);
        c.rejectUnknownKeys();
        return new ButlerConfig.SecretsConfig(fromEnv, file == null ? null : Path.of(file));
    }

    private static NotifierDef notifier(String name, Cursor c) {
        String uses = c.requiredString("uses");
        return new NotifierDef(name, uses, c.rest(), c.path());
    }

    // ----------------------------------------------------------------------- job

    private static JobDef job(String name, Cursor c) {
        String description = c.string("description", null);
        Map<String, Object> vars = c.anyMap("vars");
        Map<String, String> env = c.stringMap("env");

        List<TriggerDef> on = triggers(c);
        List<StepDef> discover = steps(c, "discover");
        String when = c.string("when", null);
        ConcurrencyDef concurrency = concurrency(c.object("concurrency"), name, c.has("concurrency"));
        Duration timeout = c.duration("timeout", null);
        List<StepDef> steps = steps(c, "steps");
        List<StepDef> onFailure = steps(c, "on_failure");
        List<StepDef> onSuccess = steps(c, "on_success");
        List<StepDef> always = steps(c, "always");
        Map<String, String> persist = c.stringMap("persist");
        NotifyDef notify = notify(c.object("notify"), c.has("notify"));

        // The "already reported" guards keep one mistake to one message: a wrongly-typed `on:`
        // has already produced "expected a list", and adding "no triggers defined" on top of it
        // just describes the same problem twice.
        if (!c.has("on")) {
            c.diagnostics().error(c.path(),
                    "missing required key \"on\": a job needs at least one trigger");
        } else if (on.isEmpty() && !c.diagnostics().hasErrorAt(c.path() + "/on")) {
            c.diagnostics().error(c.path() + "/on", "no triggers defined");
        }
        if (!c.has("steps")) {
            c.diagnostics().error(c.path(), "missing required key \"steps\"");
        } else if (steps.isEmpty() && !c.diagnostics().hasErrorAt(c.path() + "/steps")) {
            c.diagnostics().error(c.path() + "/steps", "no steps defined");
        }

        c.rejectUnknownKeys();
        return new JobDef(name, description, vars, env, on, discover, when, concurrency,
                timeout, steps, onFailure, onSuccess, always, persist, notify, c.path());
    }

    private static List<TriggerDef> triggers(Cursor c) {
        List<TriggerDef> out = new ArrayList<>();
        for (Cursor tc : c.objects("on")) {
            String uses = tc.requiredString("uses");
            out.add(new TriggerDef(uses, tc.rest(), tc.path()));
        }
        return List.copyOf(out);
    }

    private static ConcurrencyDef concurrency(Cursor c, String jobName, boolean present) {
        ConcurrencyDef d = ConcurrencyDef.defaultFor(jobName);
        if (!present) {
            return d;
        }
        String group = c.string("group", d.group());
        Enums.ConcurrencyMode mode = c.enumValue("mode", Enums.ConcurrencyMode.class, d.mode());
        boolean newestOnly = c.bool("queue_newest_only", d.queueNewestOnly());
        c.rejectUnknownKeys();
        return new ConcurrencyDef(group, mode, newestOnly);
    }

    private static NotifyDef notify(Cursor c, boolean present) {
        if (!present) {
            return null;
        }
        String to = c.requiredString("to");
        List<Enums.Outcome> on = new ArrayList<>();
        for (String s : c.strings("on")) {
            try {
                on.add(Enums.Outcome.valueOf(s.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                c.diagnostics().error(c.path() + "/on",
                        "expected success or failure, found \"" + s + "\"");
            }
        }
        Map<String, String> messages = new LinkedHashMap<>();
        for (Enums.Outcome o : Enums.Outcome.values()) {
            String key = o.name().toLowerCase(Locale.ROOT);
            String template = c.string(key, null);
            if (template != null) {
                messages.put(key, template);
            }
        }
        c.rejectUnknownKeys();
        if (on.isEmpty()) {
            on = List.of(Enums.Outcome.SUCCESS, Enums.Outcome.FAILURE);
        }
        return new NotifyDef(to, List.copyOf(on), Collections.unmodifiableMap(messages));
    }

    // ---------------------------------------------------------------------- step

    private static List<StepDef> steps(Cursor c, String key) {
        List<StepDef> out = new ArrayList<>();
        for (Cursor sc : c.objects(key)) {
            out.add(step(sc));
        }
        return List.copyOf(out);
    }

    private static StepDef step(Cursor c) {
        String name = c.string("name", null);
        String uses = c.requiredString("uses");
        String when = c.string("when", null);
        String register = c.string("register", null);
        Duration timeout = c.duration("timeout", null);
        RetryDef retry = retry(c.object("retry"), c.has("retry"));
        boolean continueOnError = c.bool("continue_on_error", false);
        Map<String, String> env = c.stringMap("env");
        String workingDir = c.string("working_dir", null);
        String runAs = c.string("run_as", null);
        Map<String, String> extract = c.stringMap("extract");

        // No rejectUnknownKeys here: whatever is left is the step type's own parameters, and
        // only the step registry knows what those are.
        return new StepDef(name, uses, when, register, timeout, retry, continueOnError,
                env, workingDir, runAs, extract, c.rest(), c.path());
    }

    private static RetryDef retry(Cursor c, boolean present) {
        if (!present) {
            return null;
        }
        int attempts = c.integer("attempts", 1);
        Duration delay = c.duration("delay", Duration.ZERO);
        Enums.Backoff backoff = c.enumValue("backoff", Enums.Backoff.class, Enums.Backoff.FIXED);
        Enums.RetryOn on = c.enumValue("on", Enums.RetryOn.class, Enums.RetryOn.FAILURE);
        c.rejectUnknownKeys();
        if (attempts < 1) {
            c.diagnostics().error(c.path() + "/attempts", "must be at least 1, found " + attempts);
        }
        return new RetryDef(attempts, delay, backoff, on);
    }
}
