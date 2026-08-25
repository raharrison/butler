package net.ryanh.butler.config;

import net.ryanh.butler.config.model.*;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.core.type.TypeReference;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/**
 * Reads one or more config files into one model, collecting every problem rather than stopping
 * at the first.
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
    public record Result(ButlerConfig config, Diagnostics diagnostics) {
        public boolean ok() {
            return !diagnostics.hasErrors();
        }
    }

    /**
     * One config file: its text, and the name its problems are reported against.
     */
    public record Source(String file, String yaml) {
    }

    public static Result load(Path file) throws IOException {
        return load(List.of(file));
    }

    public static Result load(List<Path> files) throws IOException {
        List<Source> sources = new ArrayList<>(files.size());
        for (Path file : files) {
            sources.add(new Source(file.toString(), Files.readString(file)));
        }
        return parse(sources);
    }

    public static Result parse(String yaml) {
        return parse(List.of(new Source(null, yaml)));
    }

    public static Result parse(List<Source> sources) {
        Diagnostics diags = new Diagnostics();
        Merge merge = new Merge(diags);
        for (Source source : sources) {
            SourceMap sourceMap = SourceMap.of(source.yaml());
            diags.source(source.file(), sourceMap);
            read(source, sourceMap, diags, merge);
        }
        // Validation runs on the merged document, not on any one file.
        diags.merged();
        return new Result(merge.build(), diags);
    }

    /**
     * Reads one file into the merge. An unreadable one contributes nothing and does not stop
     * the rest.
     */
    private static void read(Source source, SourceMap sourceMap, Diagnostics diags, Merge merge) {
        String yaml = source.yaml();
        rejectAliases(sourceMap, diags);

        if (isEffectivelyEmpty(yaml)) {
            diags.error("", "the config file is empty");
            merge.unreadable();
            return;
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
            reportParseFailure(e, diags);
            merge.unreadable();
            return;
        }
        if (root == null) {
            diags.error("", "the config file is empty");
            merge.unreadable();
            return;
        }

        merge.add(document(new Cursor(root, "", diags)), source.file());
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

    /**
     * A document that will not parse has no paths for the {@link SourceMap} to look up, so the
     * location comes off the exception instead; otherwise every syntax error would be reported at
     * the top of the file.
     */
    private static void reportParseFailure(RuntimeException e, Diagnostics diags) {
        String message = "could not parse YAML: " + explain(e);
        if (e instanceof JacksonException jackson && jackson.getLocation() != null) {
            TokenStreamLocation at = jackson.getLocation();
            diags.errorAt(new Diagnostic.Loc(at.getLineNr(), at.getColumnNr()), message);
            return;
        }
        diags.error("", message);
    }

    /**
     * The parser says what went wrong, then quotes the offending line and points at it, then often
     * says what it expected instead. The quoted line is already on screen and the caret is aligned
     * to nothing once the message is indented, so the sentences are kept and the rest dropped.
     */
    private static String explain(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        if (message == null || message.isBlank()) {
            return cause.getClass().getSimpleName();
        }
        List<String> sentences = new ArrayList<>();
        for (String line : message.split("\n")) {
            String stripped = line.strip();
            if (stripped.isEmpty() || stripped.startsWith("^") || stripped.startsWith("in reader,")
                    || stripped.startsWith("at [Source:")) {
                continue;
            }
            // Anything left that is not a sentence is the quoted source line, which follows one.
            if (sentences.isEmpty() || stripped.startsWith("expected ")) {
                sentences.add(stripped);
            }
        }
        return sentences.isEmpty() ? message.split("\n")[0] : String.join("; ", sentences);
    }

    // ------------------------------------------------------------------ document

    /**
     * One file's contribution. {@code settings} is null unless the file declared it, and
     * {@code secrets} holds only the fields it set.
     */
    private record Part(int version,
                        ButlerConfig.Settings settings,
                        ButlerConfig.SecretsConfig secrets,
                        Map<String, Object> vars,
                        Map<String, NotifierDef> notifiers,
                        Map<String, JobDef> jobs) {
    }

    private static Part document(Cursor c) {
        int version = c.integer("version", 1);
        if (version != 1) {
            c.diagnostics().error("/version",
                    "unsupported config version " + version + " (this build understands version 1)");
        }

        // Asked for even when absent: asked-for keys are the "did you mean" candidates.
        boolean hasSettings = c.has("settings");
        ButlerConfig.Settings settings = settings(c.object("settings"));
        ButlerConfig.SecretsConfig secrets = secrets(c.object("secrets"));
        Map<String, Object> vars = c.anyMap("vars");

        Map<String, NotifierDef> notifiers = new LinkedHashMap<>();
        c.namedObjects("notifiers").forEach((name, nc) -> notifiers.put(name, notifier(name, nc)));

        Map<String, JobDef> jobs = new LinkedHashMap<>();
        c.namedObjects("jobs").forEach((name, jc) -> jobs.put(name, job(name, jc)));

        c.rejectUnknownKeys();
        return new Part(version, hasSettings ? settings : null, secrets, vars, notifiers, jobs);
    }

    /**
     * Accumulates the files into one config. A file is still the current source while it is
     * added, so a duplicate is reported against the file that repeated it.
     */
    private static final class Merge {

        private final Diagnostics diags;
        private final Map<String, Object> vars = new LinkedHashMap<>();
        private final Map<String, NotifierDef> notifiers = new LinkedHashMap<>();
        private final Map<String, JobDef> jobs = new LinkedHashMap<>();
        private final Map<String, String> definedIn = new HashMap<>();
        private final List<Path> secretFiles = new ArrayList<>();
        private int version = 1;
        private ButlerConfig.Settings settings;
        private String settingsFile;
        private Boolean fromEnv;
        private String secretsFile;
        private boolean any;
        private boolean unreadable;

        Merge(Diagnostics diags) {
            this.diags = diags;
        }

        void unreadable() {
            unreadable = true;
        }

        void add(Part part, String file) {
            if (!any) {
                version = part.version();
            }
            any = true;
            if (part.settings() != null) {
                if (settings == null) {
                    settings = part.settings();
                    settingsFile = file;
                } else {
                    alreadySet("/settings", "settings", settingsFile);
                }
            }
            // secrets: files: accumulate like jobs do, so a file can carry its app's own secrets.
            // from_env is a policy rather than a list, so it belongs to one file.
            secretFiles.addAll(part.secrets().files());
            if (part.secrets().fromEnv() != null) {
                if (fromEnv == null) {
                    fromEnv = part.secrets().fromEnv();
                    secretsFile = file;
                } else {
                    alreadySet("/secrets/from_env", "secrets: from_env", secretsFile);
                }
            }
            part.vars().forEach((key, value) -> put(vars, "var", "/vars/" + key, key, value, file));
            part.notifiers().forEach((key, value) ->
                    put(notifiers, "notifier", "/notifiers/" + key, key, value, file));
            part.jobs().forEach((key, value) ->
                    put(jobs, "job", "/jobs/" + key, key, value, file));
        }

        private void alreadySet(String path, String label, String first) {
            diags.error(path, label + ": is already set in " + name(first)
                    + "; it configures the whole daemon, so it belongs in one file");
        }

        private <T> void put(Map<String, T> into, String kind, String path,
                             String key, T value, String file) {
            String previous = definedIn.get(path);
            if (previous != null) {
                diags.error(path, kind + " \"" + key + "\" is already defined in " + name(previous));
                return;
            }
            definedIn.put(path, file);
            into.put(key, value);
        }

        private static String name(String file) {
            return file == null ? "this config" : file;
        }

        ButlerConfig build() {
            if (!any) {
                return null;
            }
            // An unreadable file may have held them.
            if (jobs.isEmpty() && !unreadable && !diags.hasErrorAt("/jobs")) {
                diags.error("", "no jobs defined: a config needs at least one job");
            }
            return new ButlerConfig(version,
                    settings == null ? ButlerConfig.Settings.defaults() : settings,
                    new ButlerConfig.SecretsConfig(fromEnv, secretFiles)
                            .or(ButlerConfig.SecretsConfig.defaults()),
                    Collections.unmodifiableMap(vars),
                    Collections.unmodifiableMap(notifiers),
                    Collections.unmodifiableMap(jobs));
        }
    }

    private static ButlerConfig.Settings settings(Cursor c) {
        ButlerConfig.Settings d = ButlerConfig.Settings.defaults();
        Path stateDir = c.path("state_dir", d.stateDir());
        Enums.LogFormat logFormat = c.enumValue("log_format", Enums.LogFormat.class, d.logFormat());
        int maxRuns = c.integer("max_concurrent_runs", d.maxConcurrentRuns());
        Duration poll = c.duration("poll_interval", d.pollInterval());
        Duration grace = c.duration("shutdown_grace", d.shutdownGrace());
        Duration jobTimeout = c.duration("default_job_timeout", d.defaultJobTimeout());

        ButlerConfig.RunRetention retention =
                runRetention(c.object("run_retention")).or(d.runRetention());

        Path plugins = c.path("plugins_dir", null);
        c.rejectUnknownKeys();

        if (maxRuns < 1) {
            c.diagnostics().error("/settings/max_concurrent_runs",
                    "must be at least 1, found " + maxRuns);
        }
        if (poll != null && poll.isZero()) {
            c.diagnostics().error("/settings/poll_interval",
                    "must be more than zero: a polling trigger would spin instead of sleeping");
        }
        if (jobTimeout != null && (jobTimeout.isZero() || jobTimeout.isNegative())) {
            c.diagnostics().error("/settings/default_job_timeout",
                    "must be more than zero: every run would time out before its first step");
        }
        return new ButlerConfig.Settings(
                stateDir, logFormat, maxRuns, poll, grace, jobTimeout, retention, plugins);
    }

    /**
     * A {@code run_retention:} block. Absent fields stay null for the caller to fall back on.
     */
    private static ButlerConfig.RunRetention runRetention(Cursor c) {
        ButlerConfig.RunRetention retention = new ButlerConfig.RunRetention(
                c.integer("count", null), c.duration("age", null));
        c.rejectUnknownKeys();
        if (retention.count() != null && retention.count() < 0) {
            c.diagnostics().error(c.path() + "/count",
                    "must not be negative, found " + retention.count());
        }
        return retention;
    }

    /**
     * A {@code secrets:} block. Absent fields stay null for the merge to fall back on.
     */
    private static ButlerConfig.SecretsConfig secrets(Cursor c) {
        Boolean fromEnv = c.bool("from_env", null);
        List<Path> files = c.paths("files");
        c.rejectUnknownKeys();
        return new ButlerConfig.SecretsConfig(fromEnv, files);
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
        ButlerConfig.RunRetention retention = runRetention(c.object("run_retention"));
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
                timeout, steps, onFailure, onSuccess, always, persist, retention, notify,
                c.path());
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
        List<String> to = c.requiredStrings("to");
        List<Enums.Outcome> on = c.enumValues("on", Enums.Outcome.class);
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
        return new NotifyDef(to, on, Collections.unmodifiableMap(messages));
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
