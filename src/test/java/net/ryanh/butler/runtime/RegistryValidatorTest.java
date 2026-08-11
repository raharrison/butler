package net.ryanh.butler.runtime;

import net.ryanh.butler.config.ConfigLoader;
import net.ryanh.butler.config.ConfigValidator;
import net.ryanh.butler.config.Diagnostic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The half of validation that needs the registries. Messages and locations, not just failure.
 */
class RegistryValidatorTest {

    private static List<Diagnostic> check(String yaml) {
        ConfigLoader.Result r = ConfigLoader.parse(yaml);
        ConfigValidator.validate(r.config(), r.diagnostics(),
                StepRegistry.discover().conditionParams());
        RegistryValidator.validate(r.config(), StepRegistry.discover(),
                TriggerRegistry.discover(), r.diagnostics());
        return r.diagnostics().all();
    }

    private static Diagnostic only(String yaml) {
        List<Diagnostic> all = check(yaml);
        assertEquals(1, all.size(), "expected exactly one diagnostic, got: " + all);
        return all.getFirst();
    }

    @Test
    @DisplayName("an unknown step type names the near match")
    void unknownStepTypeSuggests() {
        Diagnostic d = only("""
                jobs:
                  j:
                    on: [{uses: manual}]
                    steps:
                      - uses: control.lg
                        message: hi
                """);
        assertTrue(d.message().contains("unknown step type \"control.lg\""), d.message());
        assertTrue(d.message().contains("did you mean \"control.log\""), d.message());
        assertEquals(new Diagnostic.Loc(5, 9), d.loc());
    }

    @Test
    @DisplayName("an unknown step type with no near match lists what is registered")
    void unknownStepTypeListsTheRegistry() {
        Diagnostic d = only("""
                jobs:
                  j:
                    on: [{uses: manual}]
                    steps: [{uses: fs.copy}]
                """);
        assertTrue(d.message().contains("registered: control.log, control.set"), d.message());
    }

    @Test
    @DisplayName("an unknown trigger type is caught too")
    void unknownTriggerType() {
        Diagnostic d = only("""
                jobs:
                  j:
                    on: [{uses: file.appeared, dir: /srv}]
                    steps: [{uses: control.log}]
                """);
        assertTrue(d.message().contains("unknown trigger type \"file.appeared\""), d.message());
    }

    @Test
    @DisplayName("a misspelled parameter is caught with a suggestion, at its own line")
    void unknownParameterSuggests() {
        Diagnostic d = only("""
                jobs:
                  j:
                    on: [{uses: manual}]
                    steps:
                      - uses: control.log
                        mesage: hi
                """);
        assertTrue(d.message().contains("unknown parameter \"mesage\""), d.message());
        assertTrue(d.message().contains("did you mean \"message\""), d.message());
        assertEquals(new Diagnostic.Loc(6, 9), d.loc());
    }

    @Test
    @DisplayName("a value of the wrong type is caught when nothing is templated")
    void wrongTypeIsCaught() {
        Diagnostic d = only("""
                jobs:
                  j:
                    on: [{uses: manual}]
                    steps:
                      - uses: control.log
                        message: hi
                        level: shout
                """);
        assertTrue(d.message().contains("cannot use these parameters with control.log"),
                d.message());
        assertTrue(d.message().contains("shout"), d.message());
    }

    @Test
    @DisplayName("a templated value has no type until the run supplies one, so it is left alone")
    void templatedValuesAreNotTypeChecked() {
        assertEquals(List.of(), check("""
                vars: {level: warn}
                jobs:
                  j:
                    on: [{uses: manual}]
                    steps:
                      - uses: control.log
                        message: hi
                        level: ${vars.level}
                """));
    }

    @Test
    @DisplayName("the whole vocabulary of a valid config passes both passes")
    void aGoodConfigIsClean() {
        assertEquals(List.of(), check("""
                jobs:
                  j:
                    on: [{uses: manual}]
                    steps:
                      - uses: control.set
                        vars: {a: 1}
                      - uses: control.log
                        message: ${vars.a}
                        level: debug
                """));
    }
}
