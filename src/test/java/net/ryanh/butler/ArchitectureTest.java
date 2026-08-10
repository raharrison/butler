package net.ryanh.butler;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * The dependency direction of DESIGN.md §8, which is the whole extensibility argument: the runtime
 * knows about triggers, steps and results, and nothing about files, systemd or Docker.
 *
 * <p>Package patterns are written out in full rather than as {@code ..util..}, which would also
 * match {@code java.util} and fail on every map in the codebase.
 */
class ArchitectureTest {

    private static final String CLI = "net.ryanh.butler.cli..";
    private static final String CONFIG = "net.ryanh.butler.config..";
    private static final String EXPR = "net.ryanh.butler.expr..";
    private static final String RUNTIME = "net.ryanh.butler.runtime..";
    private static final String SPI = "net.ryanh.butler.spi..";
    private static final String STEP = "net.ryanh.butler.step..";
    private static final String TRIGGER = "net.ryanh.butler.trigger..";
    private static final String UTIL = "net.ryanh.butler.util..";

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("net.ryanh.butler");

    private static void check(ArchRule rule) {
        rule.check(CLASSES);
    }

    @Test
    @DisplayName("spi depends on nothing else in the codebase")
    void spiIsALeaf() {
        check(noClasses().that().resideInAPackage(SPI)
                .should().dependOnClassesThat()
                .resideInAnyPackage(CLI, CONFIG, EXPR, RUNTIME, STEP, TRIGGER, UTIL));
    }

    /**
     * Steps and triggers see the SPI and {@code util}, and nothing else. {@code util} is allowed
     * because it holds the one duration syntax: forbidding it would mean every step that formats
     * a timeout inventing its own.
     */
    @Test
    @DisplayName("steps and triggers depend only on spi and util")
    void extensionsSeeOnlyTheSpi() {
        check(noClasses().that().resideInAnyPackage(STEP, TRIGGER)
                .should().dependOnClassesThat().resideInAnyPackage(CLI, CONFIG, EXPR, RUNTIME));
    }

    @Test
    @DisplayName("the runtime never imports a concrete step or trigger")
    void runtimeKnowsNoStep() {
        check(noClasses().that().resideInAPackage(RUNTIME)
                .should().dependOnClassesThat().resideInAnyPackage(STEP, TRIGGER));
    }

    @Test
    @DisplayName("expr and util stay below config and the runtime")
    void languageLayersStayLow() {
        check(noClasses().that().resideInAPackage(UTIL)
                .should().dependOnClassesThat()
                .resideInAnyPackage(CLI, CONFIG, EXPR, RUNTIME, SPI, STEP, TRIGGER));
        check(noClasses().that().resideInAPackage(EXPR)
                .should().dependOnClassesThat()
                .resideInAnyPackage(CLI, CONFIG, RUNTIME, SPI, STEP, TRIGGER));
    }

    @Test
    @DisplayName("no package cycles")
    void noCycles() {
        check(slices().matching("net.ryanh.butler.(*)..").should().beFreeOfCycles());
    }
}
