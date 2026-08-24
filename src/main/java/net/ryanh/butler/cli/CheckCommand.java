package net.ryanh.butler.cli;

import net.ryanh.butler.config.model.ButlerConfig;
import net.ryanh.butler.config.model.JobDef;
import net.ryanh.butler.config.model.StepDef;
import net.ryanh.butler.config.model.TriggerDef;
import net.ryanh.butler.util.Durations;
import net.ryanh.butler.util.Literals;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

/**
 * Validates, then prints the config as Butler actually understands it, with defaults filled in.
 *
 * <p>Useful for answering "is that key doing what I think" without reading the loader.
 */
@Command(
        name = "check",
        header = "Validate a config, then print the resolved effective form.",
        mixinStandardHelpOptions = true)
public final class CheckCommand implements Callable<Integer> {

    @Mixin
    ConfigMixin configOptions;

    @Override
    public Integer call() {
        var result = configOptions.loadAndValidate();
        var diags = result.diagnostics();

        if (!diags.isEmpty()) {
            System.err.print(diags.render(configOptions.describe()));
        }
        if (diags.hasErrors()) {
            return ButlerCommand.EXIT_FAILURE;
        }
        System.out.print(render(result.config()));
        return ButlerCommand.EXIT_OK;
    }

    private static String render(ButlerConfig c) {
        StringBuilder sb = new StringBuilder();
        sb.append("version: ").append(c.version()).append('\n');

        var s = c.settings();
        sb.append("settings:\n");
        kv(sb, 1, "state_dir", s.stateDir());
        kv(sb, 1, "log_format", s.logFormat().name().toLowerCase(Locale.ROOT));
        kv(sb, 1, "max_concurrent_runs", s.maxConcurrentRuns());
        kv(sb, 1, "poll_interval", Durations.format(s.pollInterval()));
        kv(sb, 1, "shutdown_grace", Durations.format(s.shutdownGrace()));
        kv(sb, 1, "run_retention", "count=" + s.runRetention().count()
                + " age=" + Durations.format(s.runRetention().age()));
        if (s.pluginsDir() != null) {
            kv(sb, 1, "plugins_dir", s.pluginsDir());
        }

        sb.append("secrets:\n");
        kv(sb, 1, "from_env", c.secrets().fromEnv());
        if (!c.secrets().files().isEmpty()) {
            kv(sb, 1, "files", c.secrets().files());
        }

        if (!c.vars().isEmpty()) {
            sb.append("vars:\n");
            c.vars().forEach((k, v) -> kv(sb, 1, k, v));
        }

        if (!c.notifiers().isEmpty()) {
            sb.append("notifiers:\n");
            c.notifiers().forEach((name, n) -> {
                indent(sb, 1).append(name).append(":\n");
                kv(sb, 2, "uses", n.uses());
                n.params().forEach((k, v) -> kv(sb, 2, k, v));
            });
        }

        sb.append("jobs:\n");
        c.jobs().forEach((name, job) -> renderJob(sb, job, c.retentionFor(job)));
        return sb.toString();
    }

    private static void renderJob(StringBuilder sb, JobDef job, ButlerConfig.RunRetention keep) {
        indent(sb, 1).append(job.name()).append(":\n");
        if (job.description() != null) {
            kv(sb, 2, "description", job.description());
        }
        if (!job.vars().isEmpty()) {
            indent(sb, 2).append("vars:\n");
            job.vars().forEach((k, v) -> kv(sb, 3, k, v));
        }
        indent(sb, 2).append("on:\n");
        for (TriggerDef t : job.on()) {
            indent(sb, 3).append("- uses: ").append(t.uses()).append('\n');
            t.params().forEach((k, v) -> kv(sb, 4, k, v));
        }
        if (job.when() != null) {
            kv(sb, 2, "when", job.when());
        }
        var conc = job.concurrency();
        kv(sb, 2, "concurrency", "group=" + conc.group()
                + " mode=" + conc.mode().name().toLowerCase(Locale.ROOT)
                + " queue_newest_only=" + conc.queueNewestOnly());
        if (job.timeout() != null) {
            kv(sb, 2, "timeout", Durations.format(job.timeout()));
        }
        kv(sb, 2, "run_retention", "count=" + keep.count() + " age=" + Durations.format(keep.age()));
        section(sb, "discover", job.discover());
        section(sb, "steps", job.steps());
        section(sb, "on_failure", job.onFailure());
        section(sb, "on_success", job.onSuccess());
        section(sb, "always", job.always());
        if (!job.persist().isEmpty()) {
            indent(sb, 2).append("persist:\n");
            job.persist().forEach((k, v) -> kv(sb, 3, k, v));
        }
        if (job.notifyPolicy() != null) {
            var n = job.notifyPolicy();
            indent(sb, 2).append("notify:\n");
            kv(sb, 3, "to", n.to());
            kv(sb, 3, "on", n.on().stream()
                    .map(o -> o.name().toLowerCase(Locale.ROOT)).toList());
            n.messages().forEach((k, v) -> kv(sb, 3, k, v));
        }
    }

    private static void section(StringBuilder sb, String label, List<StepDef> steps) {
        if (steps.isEmpty()) {
            return;
        }
        indent(sb, 2).append(label).append(":\n");
        for (StepDef step : steps) {
            indent(sb, 3).append("- uses: ").append(step.uses()).append('\n');
            if (step.name() != null) {
                kv(sb, 4, "name", step.name());
            }
            if (step.when() != null) {
                kv(sb, 4, "when", step.when());
            }
            if (step.register() != null) {
                kv(sb, 4, "register", step.register());
            }
            if (step.timeout() != null) {
                kv(sb, 4, "timeout", Durations.format(step.timeout()));
            }
            if (step.retry() != null) {
                var r = step.retry();
                kv(sb, 4, "retry", "attempts=" + r.attempts()
                        + " delay=" + Durations.format(r.delay())
                        + " backoff=" + r.backoff().name().toLowerCase(Locale.ROOT)
                        + " on=" + r.on().name().toLowerCase(Locale.ROOT));
            }
            if (step.continueOnError()) {
                kv(sb, 4, "continue_on_error", true);
            }
            if (step.workingDir() != null) {
                kv(sb, 4, "working_dir", step.workingDir());
            }
            if (step.runAs() != null) {
                kv(sb, 4, "run_as", step.runAs());
            }
            step.env().forEach((k, v) -> kv(sb, 4, "env." + k, v));
            step.extract().forEach((k, v) -> kv(sb, 4, "extract." + k, v));
            step.params().forEach((k, v) -> kv(sb, 4, k, v));
        }
    }

    private static void kv(StringBuilder sb, int depth, String key, Object value) {
        indent(sb, depth).append(key).append(": ").append(value(value)).append('\n');
    }

    /**
     * A value as YAML. Collections take the flow form, so {@code expect_status: [200, 204]} reads
     * back as the list it is rather than as a string that happens to have brackets in it.
     */
    private static String value(Object v) {
        if (v instanceof List<?> items) {
            List<String> rendered = new ArrayList<>(items.size());
            items.forEach(item -> rendered.add(value(item)));
            return "[" + String.join(", ", rendered) + "]";
        }
        if (v instanceof Map<?, ?> entries) {
            List<String> rendered = new ArrayList<>(entries.size());
            entries.forEach((k, item) ->
                    rendered.add(scalar(String.valueOf(k)) + ": " + value(item)));
            return "{" + String.join(", ", rendered) + "}";
        }
        return scalar(v);
    }

    private static String scalar(Object v) {
        if (v instanceof Duration d) {
            return Durations.format(d);
        }
        if (v instanceof Path p) {
            return Literals.path(p);
        }
        if (v instanceof Boolean || v instanceof Number) {
            return String.valueOf(v);
        }
        String s = String.valueOf(v);
        if (s.contains("\n")) {
            // A shell.run script is the first multi-line value in the vocabulary. Printed raw it
            // would be a quoted string with real newlines inside it, which is not valid YAML.
            return "\"" + escape(s).replace("\n", "\\n") + "\"";
        }
        return needsQuoting(s) ? "\"" + escape(s) + "\"" : s;
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Quote anything that would read back as something other than the string it is: YAML
     * punctuation, surrounding space, or text that would re-parse as a number, boolean or null.
     * {@code mode: "0640"} unquoted becomes octal 416.
     */
    private static boolean needsQuoting(String s) {
        if (s.isEmpty() || !s.equals(s.strip())) {
            return true;
        }
        if (INDICATORS.indexOf(s.charAt(0)) >= 0 || s.contains(": ") || s.contains(" #")) {
            return true;
        }
        return NUMERIC.matcher(s).matches() || BOOLEAN_OR_NULL.matcher(s).matches();
    }

    /**
     * YAML characters that change a scalar's meaning when they lead it.
     */
    private static final String INDICATORS = "#&*!%@`>|{}[],'\"?:-";

    private static final Pattern NUMERIC =
            Pattern.compile("[-+]?(0[xXoObB][0-9a-fA-F_]+|[0-9][0-9_]*(\\.[0-9_]*)?([eE][-+]?[0-9]+)?|\\.[0-9]+)");

    private static final Pattern BOOLEAN_OR_NULL = Pattern.compile(
            "true|false|yes|no|on|off|y|n|null|~", Pattern.CASE_INSENSITIVE);

    private static StringBuilder indent(StringBuilder sb, int depth) {
        return sb.append("  ".repeat(depth));
    }
}
