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
    private static final String NOTIFY = "net.ryanh.butler.notify..";
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
                .resideInAnyPackage(CLI, CONFIG, EXPR, NOTIFY, RUNTIME, STEP, TRIGGER, UTIL));
    }

    /**
     * The three extension points see the SPI and {@code util}, and nothing else. {@code util} is
     * allowed because it holds the one duration syntax and the one semver: forbidding it would mean
     * every step that formats a timeout or ranks a release inventing its own.
     */
    @Test
    @DisplayName("steps, triggers and notifiers depend only on spi and util")
    void extensionsSeeOnlyTheSpi() {
        check(noClasses().that().resideInAnyPackage(STEP, TRIGGER, NOTIFY)
                .should().dependOnClassesThat().resideInAnyPackage(CLI, CONFIG, EXPR, RUNTIME));
    }

    /**
     * Nor on each other: a step that sends a notification goes through {@code spi/Notifications},
     * the way it reaches a process through {@code spi/ProcessRunner}.
     */
    @Test
    @DisplayName("steps, triggers and notifiers do not depend on each other")
    void extensionsDoNotSeeEachOther() {
        check(noClasses().that().resideInAPackage(STEP)
                .should().dependOnClassesThat().resideInAnyPackage(TRIGGER, NOTIFY));
        check(noClasses().that().resideInAPackage(TRIGGER)
                .should().dependOnClassesThat().resideInAnyPackage(STEP, NOTIFY));
        check(noClasses().that().resideInAPackage(NOTIFY)
                .should().dependOnClassesThat().resideInAnyPackage(STEP, TRIGGER));
    }

    @Test
    @DisplayName("the runtime never imports a concrete step, trigger or notifier")
    void runtimeKnowsNoStep() {
        check(noClasses().that().resideInAPackage(RUNTIME)
                .should().dependOnClassesThat().resideInAnyPackage(STEP, TRIGGER, NOTIFY));
    }

    @Test
    @DisplayName("config knows nothing of the runtime, the SPI or any step")
    void configStaysBelowTheRuntime() {
        check(noClasses().that().resideInAPackage(CONFIG)
                .should().dependOnClassesThat().resideInAnyPackage(CLI, NOTIFY, RUNTIME, SPI, STEP, TRIGGER));
    }

    /**
     * {@code Main} is the one thing above the CLI, and only to hand picocli the top-level command.
     */
    @Test
    @DisplayName("nothing below the cli depends on it")
    void nothingDependsOnTheCli() {
        check(noClasses().that()
                .resideInAnyPackage(CONFIG, EXPR, NOTIFY, RUNTIME, SPI, STEP, TRIGGER, UTIL)
                .should().dependOnClassesThat().resideInAPackage(CLI));
    }

    @Test
    @DisplayName("expr and util stay below config and the runtime")
    void languageLayersStayLow() {
        check(noClasses().that().resideInAPackage(UTIL)
                .should().dependOnClassesThat()
                .resideInAnyPackage(CLI, CONFIG, EXPR, NOTIFY, RUNTIME, SPI, STEP, TRIGGER));
        check(noClasses().that().resideInAPackage(EXPR)
                .should().dependOnClassesThat()
                .resideInAnyPackage(CLI, CONFIG, NOTIFY, RUNTIME, SPI, STEP, TRIGGER));
    }

    @Test
    @DisplayName("no package cycles")
    void noCycles() {
        check(slices().matching("net.ryanh.butler.(*)..").should().beFreeOfCycles());
    }
}
