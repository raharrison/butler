package net.ryanh.butler.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static net.ryanh.butler.config.ConfigLoaderTest.loadAndValidate;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Every diagnostic asserts on its message <em>and</em> its location, not just that it failed.
 */
class DiagnosticsTest {

    private static Diagnostic only(String yaml) {
        var diags = loadAndValidate(yaml).diagnostics();
        List<Diagnostic> all = diags.all();
        assertEquals(1, all.size(),
                "expected exactly one diagnostic but got:\n" + diags.render("test.yaml"));
        return all.getFirst();
    }

    private static void assertAt(Diagnostic d, int line, String fragment) {
        assertNotNull(d.loc(), "diagnostic has no location: " + d.message());
        assertEquals(line, d.loc().line(), "wrong line for: " + d.message());
        assertTrue(d.message().contains(fragment),
                "expected \"" + fragment + "\" in: " + d.message());
    }

    private static void assertAt(Diagnostic d, int line, int col, String fragment) {
        assertAt(d, line, fragment);
        assertEquals(col, d.loc().col(), "wrong column for: " + d.message());
    }

    @Nested
    @DisplayName("unknown keys")
    class UnknownKeys {

        @Test
        void suggestsTheIntendedKey() {
            //       1  2      3        4              5        6
            var d = only("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps: [{uses: control.log}]
                        tiemout: 30s
                    """);
            assertAt(d, 5, "unknown key \"tiemout\"");
            assertTrue(d.message().contains("did you mean \"timeout\""), d.message());
        }

        @Test
        void topLevelTypo() {
            var d = only("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps: [{uses: control.log}]
                    setings:
                      log_format: json
                    """);
            assertAt(d, 5, "unknown key \"setings\"");
            assertTrue(d.message().contains("settings"), d.message());
        }

        @Test
        void settingsTypo() {
            var d = only("""
                    settings:
                      state_dirr: /tmp
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps: [{uses: control.log}]
                    """);
            assertAt(d, 2, "unknown key \"state_dirr\"");
            assertTrue(d.message().contains("state_dir"), d.message());
        }

        @Test
        void stepParametersAreNotRejected() {
            // Step params belong to the step type, so this pass leaves them alone entirely;
            // RegistryValidator is what checks them against the step's own record.
            var r = loadAndValidate("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps:
                          - uses: fs.copy
                            anything_at_all: yes
                    """);
            assertFalse(r.diagnostics().hasErrors(), r.diagnostics().render("x"));
        }
    }

    @Nested
    @DisplayName("required keys")
    class Required {

        @Test
        void jobWithoutTriggers() {
            var d = only("""
                    jobs:
                      j:
                        steps: [{uses: control.log}]
                    """);
            assertAt(d, 2, "missing required key \"on\"");
        }

        @Test
        void jobWithoutSteps() {
            var d = only("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                    """);
            assertAt(d, 2, "missing required key \"steps\"");
        }

        @Test
        void stepWithoutUses() {
            var d = only("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps:
                          - name: nameless
                    """);
            assertAt(d, 5, "missing required key \"uses\"");
        }

        @Test
        void emptyConfig() {
            var diags = loadAndValidate("").diagnostics();
            assertTrue(diags.hasErrors());
            assertTrue(diags.render("x").contains("empty"), diags.render("x"));
        }
    }

    @Nested
    @DisplayName("types and enums")
    class Types {

        @Test
        void badEnumListsWhatIsAllowed() {
            var d = only("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        concurrency:
                          mode: parallel
                        steps: [{uses: control.log}]
                    """);
            assertAt(d, 5, "expected one of queue, skip, cancel_previous");
        }

        @Test
        void badLogFormat() {
            var d = only("""
                    settings:
                      log_format: xml
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps: [{uses: control.log}]
                    """);
            assertAt(d, 2, "expected one of json, text");
        }

        @Test
        void listWhereMappingExpected() {
            var d = only("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps: [{uses: control.log}]
                        concurrency: [a, b]
                    """);
            assertAt(d, 5, "expected a mapping, found a list");
        }

        @Test
        void maxConcurrentRunsMustBePositive() {
            var d = only("""
                    settings:
                      max_concurrent_runs: 0
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps: [{uses: control.log}]
                    """);
            assertAt(d, 2, "at least 1");
        }
    }

    @Nested
    @DisplayName("expressions")
    class ExpressionChecks {

        @Test
        void unknownNamespaceIsCaught() {
            var d = only("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        when: triger.version == "1"
                        steps: [{uses: control.log}]
                    """);
            assertAt(d, 4, "unknown namespace \"triger\"");
            assertTrue(d.message().contains("trigger"), d.message());
        }

        @Test
        void unknownNamespaceInsideATemplateHole() {
            var d = only("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps:
                          - uses: control.log
                            message: hello ${varz.name}
                    """);
            assertAt(d, 6, "unknown namespace \"varz\"");
        }

        @Test
        void unknownPathWithinAKnownNamespaceIsFine() {
            // Must keep working: state.* is legitimately absent on a first run.
            var r = loadAndValidate("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        discover: [{uses: fs.readlink, path: /x, extract: {deployed_version: value}}]
                        when: semver(default(state.anything_at_all, "0.0.0")) > semver("0.0.0")
                        steps: [{uses: control.log}]
                    """);
            assertFalse(r.diagnostics().hasErrors(), r.diagnostics().render("x"));
        }

        @Test
        void syntaxErrorInCondition() {
            var d = only("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        when: a == = b
                        steps: [{uses: control.log}]
                    """);
            assertAt(d, 4, "invalid condition");
        }

        @Test
        void conditionParametersAreValidatedEvenWithoutADollarHole() {
            // control.assert's that: is a bare condition, not a template. Treating it as a
            // template would mean text containing no ${} was never parsed at all.
            var d = only("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps:
                          - uses: control.assert
                            that: nosuchns.value == 200
                    """);
            assertAt(d, 6, "unknown namespace \"nosuchns\"");
        }

        @Test
        void syntaxErrorInAConditionParameter() {
            var d = only("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps:
                          - uses: control.assert
                            that: status == = 200
                    """);
            assertAt(d, 6, "invalid condition");
        }

        @Test
        @DisplayName("a step's condition parameter sees the locals that step injects")
        void stepLocalsAreAllowedInConditionParameters() {
            var r = loadAndValidate("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps:
                          - uses: http.wait
                            url: http://localhost:8080/health
                            until: status == 200 and json.version == "1.0.0"
                    """);
            assertFalse(r.diagnostics().hasErrors(), r.diagnostics().render("x"));
        }

        @Test
        @DisplayName("but only that step's: control.assert injects nothing, so a probe's locals "
                + "would be null at runtime")
        void anotherStepsLocalsAreNot() {
            var d = only("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps:
                          - uses: control.assert
                            that: json.version == "1.0.0"
                    """);
            assertAt(d, 6, "unknown namespace \"json\"");
        }

        @Test
        @DisplayName("a template parameter never sees locals, whichever step it belongs to")
        void localsAreNotInScopeForTemplates() {
            var d = only("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps:
                          - uses: control.log
                            message: ${json.version}
                    """);
            assertAt(d, 6, "unknown namespace \"json\"");
        }

        @Test
        @DisplayName("an unrecognised uses: is reported once, without a second message about the "
                + "expressions nobody can judge")
        void anUnknownStepTypeSaysNothingAboutLocals() {
            var r = loadAndValidate("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps:
                          - uses: nosuch.step
                            message: ${json.version}
                    """);
            assertFalse(r.diagnostics().hasErrors(), r.diagnostics().render("x"));
        }

        @Test
        void stepReferencesInConditionParametersAreChecked() {
            var d = only("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps:
                          - uses: control.assert
                            that: steps.nope.ok
                    """);
            assertTrue(d.message().contains("steps.nope"), d.message());
        }

        @Test
        void unknownFunctionSuggests() {
            var d = only("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        when: semvar(trigger.version) > semver("1.0.0")
                        steps: [{uses: control.log}]
                    """);
            assertAt(d, 4, "unknown function \"semvar\"");
            assertTrue(d.message().contains("semver"), d.message());
        }
    }

    @Nested
    @DisplayName("trigger parameters")
    class TriggerParameters {

        @Test
        @DisplayName("an order_by that will not parse is caught before the daemon starts")
        void aBadOrderByIsAnError() {
            //       1  2  3   4        5     6
            var d = only("""
                    jobs:
                      j:
                        on:
                          - uses: file.appeared
                            dir: /srv/artifacts
                            order_by: semver(version
                        steps: [{uses: control.log}]
                    """);
            assertAt(d, 6, 9, "invalid condition");
        }

        @Test
        @DisplayName("what an order_by may reference is the trigger's own captures, so its roots "
                + "are not judged")
        void anOrderBySeesWhateverTheRegexCaptured() {
            var r = loadAndValidate("""
                    jobs:
                      j:
                        on:
                          - uses: file.appeared
                            dir: /srv/artifacts
                            match: 'api-(?<version>\\d+)\\.jar'
                            order_by: semver(version)
                        steps: [{uses: control.log}]
                    """);
            assertFalse(r.diagnostics().hasErrors(), r.diagnostics().render("x"));
        }

        @Test
        @DisplayName("a trigger parameter cannot be templated, because there is no run to resolve "
                + "it against")
        void aTemplatedTriggerParameterIsAnError() {
            var d = only("""
                    vars:
                      artifacts: /srv/artifacts
                    jobs:
                      j:
                        on:
                          - uses: file.appeared
                            dir: ${vars.artifacts}
                        steps: [{uses: control.log}]
                    """);
            assertAt(d, 7, 9, "would be taken literally");
        }
    }

    @Nested
    @DisplayName("register names")
    class RegisterNames {

        @Test
        void duplicateIsRejected() {
            var d = only("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps:
                          - uses: control.log
                            register: a
                          - uses: control.log
                            register: a
                    """);
            assertAt(d, 8, "duplicate register name \"a\"");
        }

        @Test
        void forwardReferenceSaysTheNameExistsButComesLater() {
            var d = only("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps:
                          - uses: control.log
                            when: steps.later.ok
                          - uses: control.log
                            register: later
                    """);
            assertTrue(d.message().contains("steps.later"), d.message());
            assertTrue(d.message().contains("registered later in this job"), d.message());
        }

        @Test
        void referenceToANameThatExistsNowhereSuggests() {
            var d = only("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps:
                          - uses: control.log
                            register: health
                          - uses: control.log
                            when: steps.helth.ok
                    """);
            assertTrue(d.message().contains("no step registers that name"), d.message());
            assertTrue(d.message().contains("health"), d.message());
        }

        @Test
        void discoverRegistrationsAreVisibleToThePipeline() {
            // Discovery runs before everything, so what it registers is legitimately in scope.
            var r = loadAndValidate("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        discover:
                          - uses: fs.readlink
                            path: /srv/apps/api/current
                            register: probe
                        steps:
                          - uses: control.log
                            message: ${steps.probe.value}
                    """);
            assertFalse(r.diagnostics().hasErrors(), r.diagnostics().render("x"));
        }

        @Test
        void duplicateAcrossSectionsIsRejected() {
            var d = only("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps:
                          - uses: control.log
                            register: a
                        on_failure:
                          - uses: control.log
                            register: a
                    """);
            assertTrue(d.message().contains("duplicate register name \"a\" in this job"),
                    d.message());
        }

        @Test
        void backwardReferenceIsFine() {
            var r = loadAndValidate("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps:
                          - uses: control.log
                            register: first
                          - uses: control.log
                            when: steps.first.ok
                    """);
            assertFalse(r.diagnostics().hasErrors(), r.diagnostics().render("x"));
        }

        @Test
        void hooksCanSeeStepsFromThePipeline() {
            // The on_failure rollback in the canonical config depends on this.
            var r = loadAndValidate("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps:
                          - uses: fs.symlink
                            register: symlink
                        on_failure:
                          - uses: fs.symlink
                            target: ${steps.symlink.previous_target}
                    """);
            assertFalse(r.diagnostics().hasErrors(), r.diagnostics().render("x"));
        }

        @Test
        void referenceInAParameterIsChecked() {
            var d = only("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps:
                          - uses: fs.copy
                            from: ${steps.nope.path}
                    """);
            assertTrue(d.message().contains("steps.nope"), d.message());
        }

        @Test
        void unusableRegisterName() {
            var d = only("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps:
                          - uses: control.log
                            register: my-step
                    """);
            assertAt(d, 6, "not a usable name");
        }
    }

    @Nested
    @DisplayName("extract placement")
    class Extract {

        @Test
        void allowedInDiscover() {
            var r = loadAndValidate("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        discover:
                          - uses: fs.readlink
                            path: /srv/apps/api/current
                            extract:
                              deployed_version: basename(value)
                        steps: [{uses: control.log}]
                    """);
            assertFalse(r.diagnostics().hasErrors(), r.diagnostics().render("x"));
        }

        @Test
        void rejectedInThePipeline() {
            var d = only("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps:
                          - uses: fs.readlink
                            extract:
                              v: value
                    """);
            assertAt(d, 6, "only valid inside a discover block");
            assertTrue(d.message().contains("register"), d.message());
        }
    }

    @Nested
    @DisplayName("the first-run warning")
    class StateWithoutDiscover {

        @Test
        void warnsWhenDecidingOnStateWithNoDiscovery() {
            var d = only("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        when: state.deployed_version != "1.0.0"
                        steps: [{uses: control.log}]
                    """);
            assertEquals(Diagnostic.Severity.WARNING, d.severity());
            assertTrue(d.message().contains("no discover block"), d.message());
        }

        @Test
        void silentWhenDiscoveryIsPresent() {
            var r = loadAndValidate("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        discover:
                          - uses: fs.readlink
                            path: /x
                            extract: {deployed_version: value}
                        when: state.deployed_version != "1.0.0"
                        steps: [{uses: control.log}]
                    """);
            assertTrue(r.diagnostics().isEmpty(), r.diagnostics().render("x"));
        }

        @Test
        void warningsAloneDoNotFailValidation() {
            var r = loadAndValidate("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        when: state.x != "1"
                        steps: [{uses: control.log}]
                    """);
            assertFalse(r.diagnostics().hasErrors());
            assertEquals(1, r.diagnostics().warnings().size());
        }
    }

    @Nested
    @DisplayName("notifier references")
    class Notifiers {

        @Test
        void unknownNotifierSuggests() {
            var d = only("""
                    notifiers:
                      ops:
                        uses: notify.slack
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps: [{uses: control.log}]
                        notify:
                          to: opps
                    """);
            assertTrue(d.message().contains("no notifier named \"opps\""), d.message());
            assertTrue(d.message().contains("ops"), d.message());
        }

        @Test
        @DisplayName("a notify block with no to: is a missing required key, reported once")
        void notifyNeedsATo() {
            //       1  2  3   4                     5      6      7
            var d = only("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps: [{uses: control.log}]
                        notify:
                          on: [failure]
                          failure: it broke
                    """);
            assertAt(d, 5, 5, "missing required key \"to\"");
        }
    }

    @Nested
    @DisplayName("everything at once")
    class KitchenSink {

        @Test
        void reportsAllProblemsInOnePass() {
            var diags = loadAndValidate("""
                    setings:
                      log_format: xml
                    jobs:
                      broken:
                        on: [{uses: manual}]
                        tiemout: 30
                        when: triger.version == "1"
                        steps:
                          - uses: control.log
                            register: dup
                          - uses: control.log
                            register: dup
                          - name: no type here
                        notify:
                          to: nobody
                    """).diagnostics();

            String out = diags.render("test.yaml");
            assertTrue(diags.errors().size() >= 6,
                    "expected many errors in one pass, got:\n" + out);

            // A validator that stopped at the first error would report only one of these.
            assertTrue(out.contains("setings"), out);
            assertTrue(out.contains("tiemout"), out);
            assertTrue(out.contains("triger"), out);
            assertTrue(out.contains("duplicate register name"), out);
            assertTrue(out.contains("missing required key \"uses\""), out);
            assertTrue(out.contains("no notifier named"), out);
        }

        @Test
        void diagnosticsAreOrderedByPosition() {
            var diags = loadAndValidate("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps: [{uses: control.log}]
                        zzz_first: 1
                        aaa_second: 2
                    """).diagnostics();
            var lines = diags.all().stream()
                    .filter(d -> d.loc() != null)
                    .map(d -> d.loc().line())
                    .toList();
            assertEquals(lines.stream().sorted().toList(), lines,
                    "diagnostics should read top to bottom");
        }

        @Test
        void renderedFormIsMachineReadable() {
            var diags = loadAndValidate("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps: [{uses: control.log}]
                        tiemout: 30s
                    """).diagnostics();
            String out = diags.render("/etc/butler/butler.yaml");
            assertTrue(out.startsWith("/etc/butler/butler.yaml:5:"), out);
            assertTrue(out.contains(": error: "), out);
            assertTrue(out.contains("1 error, 0 warnings"), out);
        }
    }

    @Nested
    @DisplayName("malformed YAML")
    class Malformed {

        @Test
        void reportsRatherThanCrashing() {
            var diags = loadAndValidate("""
                    jobs:
                      j:
                       on: [
                    """).diagnostics();
            assertTrue(diags.hasErrors());
            assertTrue(diags.render("x").contains("could not parse YAML"), diags.render("x"));
        }

        @Test
        void duplicateKeysAreRejected() {
            // Without strict duplicate detection this resolves last-one-wins and the author's
            // first value silently disappears.
            var diags = loadAndValidate("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        timeout: 1m
                        timeout: 2m
                        steps: [{uses: control.log}]
                    """).diagnostics();
            assertTrue(diags.hasErrors());
            assertTrue(diags.render("x").toLowerCase().contains("duplicate"), diags.render("x"));
        }
    }

    @Nested
    @DisplayName("one mistake, one message")
    class NoDoubleReporting {

        @Test
        void wrongTypeForStepsDoesNotAlsoReportEmpty() {
            var d = only("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps:
                          not: a list
                    """);
            assertAt(d, 4, "expected a list, found a mapping");
        }

        @Test
        void wrongTypeForTriggersDoesNotAlsoReportEmpty() {
            var d = only("""
                    jobs:
                      j:
                        on: nonsense
                        steps: [{uses: control.log}]
                    """);
            assertAt(d, 3, "expected a list, found text");
        }

        @Test
        void emptyJobsMapIsStillReported() {
            var d = only("""
                    jobs: {}
                    """);
            assertTrue(d.message().contains("no jobs defined"), d.message());
        }
    }

    @Nested
    @DisplayName("YAML anchors")
    class Anchors {

        @Test
        @DisplayName("an alias is refused, because it would bind as the anchor's name")
        void aliasIsRejected() {
            var d = only("""
                    vars:
                      base: &b hello
                      copy: *b
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps: [{uses: control.log, message: hi}]
                    """);
            assertAt(d, 3, 3, "anchors and aliases are not supported");
            assertTrue(d.message().contains("${vars.name}"), d.message());
        }

        @Test
        @DisplayName("a merge key is an alias too, and would otherwise vanish without a word")
        void mergeKeyIsRejected() {
            var diags = loadAndValidate("""
                    vars:
                      defaults: &d
                        uses: control.log
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps:
                          - <<: *d
                            message: hi
                    """).diagnostics();
            var alias = diags.all().stream()
                    .filter(d -> d.message().contains("anchors and aliases"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(diags.render("x")));
            assertAt(alias, 8, 9, "anchors and aliases are not supported");
        }

        @Test
        @DisplayName("an anchor nobody refers to changes nothing, so it is left alone")
        void anchorWithoutAnAliasIsFine() {
            var r = loadAndValidate("""
                    vars:
                      base: &b hello
                    jobs:
                      j:
                        on: [{uses: manual}]
                        steps: [{uses: control.log, message: hi}]
                    """);
            assertTrue(r.diagnostics().isEmpty(), r.diagnostics().render("x"));
            assertEquals("hello", r.config().vars().get("base"));
        }
    }

    @Nested
    @DisplayName("oversized values are diagnostics, not crashes")
    class Overflow {

        @Test
        void durationTooLargeKeepsOtherDiagnostics() {
            // An exception escaping the loader would discard every diagnostic collected so far.
            var diags = loadAndValidate("""
                    jobs:
                      j:
                        on: [{uses: manual}]
                        timeout: 99999999999999999d
                        steps: [{uses: control.log}]
                        tiemout: 5s
                    """).diagnostics();
            String out = diags.render("x");
            assertTrue(out.contains("too large"), out);
            assertTrue(out.contains("tiemout"), "the later typo must survive:\n" + out);
        }
    }
}
