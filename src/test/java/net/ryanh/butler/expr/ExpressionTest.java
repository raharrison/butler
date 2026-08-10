package net.ryanh.butler.expr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ExpressionTest {

    private static final Scope SCOPE = Scope.of(Map.of(
            "trigger", Map.of("version", "1.2.4", "path", "/srv/artifacts/api/api-1.2.4.jar"),
            "state", Map.of("deployed_version", "1.2.3"),
            "vars", Map.of("releases_root", "/srv/apps", "keep", 5L),
            "steps", Map.of(
                    "health", Map.of("status", 200L, "ok", true, "failed", false),
                    "symlink", Map.of("previous_target", "/srv/apps/api/releases/1.2.3")),
            "run", Map.of("attempt", 1L),
            "items", List.of("a", "b", "c"),
            "empty", Map.of()));

    private static Object eval(String source) {
        return new Evaluator(SCOPE).eval(Expressions.condition(source));
    }

    private static boolean cond(String source) {
        return new Evaluator(SCOPE).evalCondition(Expressions.condition(source));
    }

    @Nested
    @DisplayName("literals and arithmetic-free comparison")
    class Literals {

        @ParameterizedTest
        @CsvSource({
                "'1 == 1', true",
                "'1 != 2', true",
                "'2 > 1', true",
                "'1 >= 1', true",
                "'1 < 2', true",
                "'2 <= 2', true",
                "'\"a\" == \"a\"', true",
                "'\"a\" != \"b\"', true",
                "'true', true",
                "'false', false",
                "'not false', true",
                "'not true', false",
                "'null == null', true",
                "'1.5 > 1.4', true",
        })
        void comparisons(String expr, boolean expected) {
            assertEquals(expected, cond(expr), expr);
        }

        @Test
        void numberAndStringCompareByRenderedForm() {
            assertTrue(cond("steps.health.status == 200"));
            assertTrue(cond("steps.health.status == \"200\""));
        }

        @Test
        void integerLiteralsStayIntegers() {
            // Integer literals must stay Long: array indexing and int() depend on the
            // distinction, and comparison normalises through doubleValue() so it would not
            // notice either way.
            assertInstanceOf(Long.class, eval("42"));
            assertInstanceOf(Double.class, eval("42.5"));
        }

        @Test
        void durationLiteralsParseAndCompare() {
            assertTrue(cond("30s < 1m"));
            assertTrue(cond("2h > 90m"));
            assertTrue(cond("500ms < 1s"));
            assertEquals(Duration.ofSeconds(30), eval("30s"));
        }

        @Test
        void aDurationEqualsItsWrittenForm() {
            assertTrue(cond("30s == \"30s\""));
            assertTrue(cond("60s == 1m"));
        }

        @Test
        void oversizedDurationLiteralIsAnExpressionError() {
            ExprException e = assertThrows(ExprException.class, () -> eval("99999999999999999d"));
            assertTrue(e.getMessage().contains("too large"), e.getMessage());
        }
    }

    @Nested
    @DisplayName("precedence")
    class Precedence {

        @Test
        void andBindsTighterThanOr() {
            // false and false or true  ==  (false and false) or true
            assertTrue(cond("false and false or true"));
            // true or true and false  ==  true or (true and false)
            assertTrue(cond("true or true and false"));
        }

        @Test
        void notBindsTighterThanAnd() {
            assertTrue(cond("not false and true"));
        }

        @Test
        void comparisonBindsTighterThanAnd() {
            assertTrue(cond("1 == 1 and 2 == 2"));
        }

        @Test
        void parenthesesOverride() {
            assertFalse(cond("(false and false) and true"));
            assertTrue(cond("false and (false or true) or true"));
        }

        @Test
        void shortCircuitsBeforeEvaluatingRightSide() {
            // int() on a non-numeric string would throw if the right side were evaluated.
            assertFalse(cond("false and int(\"abc\") == 1"));
            assertTrue(cond("true or int(\"abc\") == 1"));
        }
    }

    @Nested
    @DisplayName("paths")
    class Paths {

        @Test
        void resolvesNestedMaps() {
            assertEquals("1.2.4", eval("trigger.version"));
            assertEquals(200L, eval("steps.health.status"));
        }

        @Test
        void resolvesArrayIndex() {
            assertEquals("b", eval("items[1]"));
        }

        @Test
        void unknownPathIsNullNotAnError() {
            assertNull(eval("state.nothing_here"));
            assertNull(eval("steps.missing.status"));
            assertNull(eval("trigger.version.deeper"));
            assertNull(eval("items[99]"));
        }

        @Test
        void unknownRootIsNull() {
            // Unknown *namespaces* are a validation-time error, not an evaluation-time one.
            assertNull(eval("nosuch.thing"));
        }

        @Test
        void booleanFieldsReadNaturally() {
            assertTrue(cond("steps.health.ok"));
            assertFalse(cond("steps.health.failed"));
            assertTrue(cond("not steps.health.failed"));
        }
    }

    @Nested
    @DisplayName("semver")
    class SemverOrdering {

        @Test
        void tenIsGreaterThanNine() {
            // The whole reason semver() exists: string comparison gets this wrong.
            assertTrue(cond("semver(\"1.10.0\") > semver(\"1.9.0\")"));
            assertFalse("1.10.0".compareTo("1.9.0") > 0, "string compare should disagree");
        }

        @Test
        void comparesAgainstState() {
            assertTrue(cond("semver(trigger.version) > semver(state.deployed_version)"));
        }

        @Test
        void firstRunFallback() {
            assertTrue(cond("semver(trigger.version) > semver(default(state.absent, \"0.0.0\"))"));
        }

        @ParameterizedTest
        @CsvSource({
                "1.0.0, 2.0.0",
                "1.0.0, 1.1.0",
                "1.0.0, 1.0.1",
                "1.9.9, 1.10.0",
                "1.0.0-alpha, 1.0.0",
                "1.0.0-alpha, 1.0.0-beta",
                "1.0.0-alpha.1, 1.0.0-alpha.2",
                "1.0.0-1, 1.0.0-alpha",
        })
        void ordering(String lower, String higher) {
            assertTrue(Semver.parse(lower).compareTo(Semver.parse(higher)) < 0,
                    lower + " should be < " + higher);
        }

        @Test
        void toleratesLeadingV() {
            assertEquals(0, Semver.parse("v1.2.3").compareTo(Semver.parse("1.2.3")));
        }

        @Test
        void partialVersions() {
            assertEquals(0, Semver.parse("1").compareTo(Semver.parse("1.0.0")));
            assertEquals(0, Semver.parse("1.2").compareTo(Semver.parse("1.2.0")));
        }

        @Test
        void buildMetadataIgnoredForOrdering() {
            assertEquals(0, Semver.parse("1.2.3+build1").compareTo(Semver.parse("1.2.3+build2")));
        }

        @Test
        void rejectsNonsense() {
            assertThrows(ExprException.class, () -> Semver.parse("not-a-version"));
            assertNull(Semver.tryParse("not-a-version"));
        }

        @Test
        void oversizedComponentsAreExpressionErrorsNotNumberFormat() {
            // Date-stamped versions are a realistic input and must not escape as an NFE, or
            // tryParse would throw instead of returning null.
            ExprException e = assertThrows(ExprException.class,
                    () -> Semver.parse("99999999999.0.0"));
            assertTrue(e.getMessage().contains("too large"), e.getMessage());
            assertNull(Semver.tryParse("99999999999.0.0"));
        }

        @Test
        void oversizedPrereleaseIdentifiersCompareAsText() {
            assertDoesNotThrow(() -> Semver.parse("1.0.0-9999999999999999999999")
                    .compareTo(Semver.parse("1.0.0-1")));
        }

        @Test
        void equalsAndHashCodeAgree() {
            Semver a = Semver.parse("1.0.0-1");
            Semver b = Semver.parse("1.0.0-01");
            assertEquals(a, b, "numeric prerelease identifiers compare equal");
            assertEquals(a.hashCode(), b.hashCode(), "so they must hash equal too");
        }
    }

    @Nested
    @DisplayName("functions")
    class Fns {

        @Test
        void existsAndDefault() {
            assertTrue(cond("exists(state.deployed_version)"));
            assertFalse(cond("exists(state.absent)"));
            assertEquals("fallback", eval("default(state.absent, \"fallback\")"));
            assertEquals("1.2.3", eval("default(state.deployed_version, \"fallback\")"));
        }

        @Test
        void stringHelpers() {
            assertEquals("abc", eval("lower(\"ABC\")"));
            assertEquals("ABC", eval("upper(\"abc\")"));
            assertEquals("x", eval("trim(\"  x  \")"));
            assertEquals(3L, eval("len(\"abc\")"));
            assertEquals(3L, eval("len(items)"));
        }

        @Test
        void pathHelpers() {
            assertEquals("api-1.2.4.jar", eval("basename(trigger.path)"));
            assertEquals("/srv/artifacts/api", eval("dirname(trigger.path)"));
            assertEquals("1.2.3", eval("basename(steps.symlink.previous_target)"));
            assertEquals("releases", eval("basename(\"/a/releases/\")"));
        }

        @Test
        void matchExtractsGroups() {
            assertEquals("1.2.4", eval("match(trigger.path, \"api-(\\\\d+\\\\.\\\\d+\\\\.\\\\d+)\\\\.jar\", 1)"));
            assertNull(eval("match(\"nope\", \"(\\\\d+)\", 1)"));
        }

        @Test
        void intConversion() {
            assertEquals(42L, eval("int(\"42\")"));
            assertEquals(200L, eval("int(steps.health.status)"));
            assertThrows(ExprException.class, () -> eval("int(\"abc\")"));
        }

        @Test
        void fileExistsAsksTheFilesystem(@TempDir Path dir) throws IOException {
            Path present = Files.writeString(dir.resolve("artifact.jar"), "x");
            assertTrue(cond("file_exists(\"" + present.toString().replace("\\", "/") + "\")"));
            assertFalse(cond("file_exists(\"" + dir.resolve("absent.jar").toString()
                    .replace("\\", "/") + "\")"));
            assertFalse(cond("file_exists(state.absent)"), "a missing path is not a file");
        }

        @Test
        void nowIsAnInstantThatMoves() {
            Object first = eval("now()");
            assertInstanceOf(Instant.class, first);
            Instant taken = (Instant) first;
            assertFalse(taken.isBefore(Instant.now().minusSeconds(60)));
            assertFalse(taken.isAfter(Instant.now().plusSeconds(60)));
        }

        @Test
        void unknownFunctionSuggests() {
            ExprException e = assertThrows(ExprException.class, () -> eval("semvar(\"1.0.0\")"));
            assertTrue(e.getMessage().contains("semver"), e.getMessage());
        }

        @Test
        void arityIsChecked() {
            ExprException e = assertThrows(ExprException.class, () -> eval("semver()"));
            assertTrue(e.getMessage().contains("takes 1"), e.getMessage());
            assertThrows(ExprException.class, () -> eval("default(1)"));
        }
    }

    @Nested
    @DisplayName("matches and contains")
    class Operators {

        @Test
        void matchesUsesRegex() {
            assertTrue(cond("trigger.version matches \"^1\\\\.\""));
            assertFalse(cond("trigger.version matches \"^9\\\\.\""));
        }

        @Test
        void containsWorksOnStringsAndLists() {
            assertTrue(cond("trigger.path contains \"artifacts\""));
            assertTrue(cond("items contains \"b\""));
            assertFalse(cond("items contains \"z\""));
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "trigger.version matches state.missing",
                "state.missing matches \"x\"",
                "state.missing matches state.missing",
                "trigger.version contains state.missing",
                "state.missing contains \"x\"",
                "items contains state.missing",
        })
        void nullOperandsYieldFalseRatherThanCrashing(String expr) {
            // An absent path is normal, so it must never surface as an unexplained NPE.
            assertFalse(cond(expr), expr);
        }

        @Test
        void matchToleratesNullArguments() {
            assertNull(eval("match(state.missing, \"(x)\", 1)"));
            assertNull(eval("match(\"abc\", state.missing, 1)"));
        }
    }

    @Nested
    @DisplayName("truthiness")
    class Truthiness {

        @ParameterizedTest
        @ValueSource(strings = {"\"x\"", "1", "true", "items"})
        void truthy(String expr) {
            assertTrue(cond(expr), expr);
        }

        @ParameterizedTest
        @ValueSource(strings = {"\"\"", "0", "false", "null", "state.absent", "empty"})
        void falsy(String expr) {
            assertFalse(cond(expr), expr);
        }
    }

    @Nested
    @DisplayName("interpolation")
    class Interpolation {

        private String render(String template) {
            return Expressions.template(template).render(new Evaluator(SCOPE));
        }

        @Test
        void plainLiteral() {
            assertEquals("no holes", render("no holes"));
        }

        @Test
        void singleHole() {
            assertEquals("1.2.4", render("${trigger.version}"));
        }

        @Test
        void mixedText() {
            assertEquals("/srv/apps/api/releases/1.2.4",
                    render("${vars.releases_root}/api/releases/${trigger.version}"));
        }

        @Test
        void nullHoleRendersEmpty() {
            assertEquals("a-b", render("a-${state.absent}b"));
        }

        @Test
        void escapeProducesLiteralDollarBrace() {
            assertEquals("${trigger.version}", render("$${trigger.version}"));
        }

        @Test
        void loneDollarIsLiteral() {
            assertEquals("cost $5", render("cost $5"));
        }

        @Test
        void holeContainingBracesInAString() {
            assertEquals("}", render("${default(state.absent, \"}\")}"));
        }

        @Test
        void functionCallInHole() {
            assertEquals("api-1.2.4.jar", render("${basename(trigger.path)}"));
        }

        @Test
        void singleHolePreservesType() {
            Object v = Expressions.template("${vars.keep}").renderValue(new Evaluator(SCOPE));
            assertEquals(5L, v, "a lone hole should stay a number, not become a string");
        }

        @Test
        void unclosedHoleIsAnError() {
            assertThrows(ExprException.class, () -> render("${trigger.version"));
        }
    }

    @Nested
    @DisplayName("conditions tolerate ${} for readability")
    class DollarBraceInConditions {

        @Test
        void holeIsEquivalentToBareReference() {
            assertTrue(cond("trigger.version == ${trigger.version}"));
            assertTrue(cond("${trigger.version} == \"1.2.4\""));
        }

        @Test
        void mixedFormMatchesTheDesignExample() {
            Scope s = SCOPE.with(Map.of("status", 200L, "json", Map.of("version", "1.2.4")));
            assertTrue(new Evaluator(s).evalCondition(
                    Expressions.condition("status == 200 and json.version == ${trigger.version}")));
        }
    }

    @Nested
    @DisplayName("parse errors are actionable")
    class Errors {

        static Stream<Arguments> badExpressions() {
            return Stream.of(
                    Arguments.of("a = b", "use '=='"),
                    Arguments.of("a && b", "use 'and'"),
                    Arguments.of("a || b", "use 'or'"),
                    Arguments.of("!a", "use 'not'"),
                    Arguments.of("'unterminated", "unterminated string"),
                    Arguments.of("(1 == 1", "expected \")\""),
                    Arguments.of("1 ==", "ended unexpectedly"),
                    Arguments.of("a.", "expected a name"),
                    Arguments.of("a[x]", "expected an integer index"),
                    Arguments.of("1 == 1 extra", "unexpected \"extra\""));
        }

        @ParameterizedTest
        @MethodSource("badExpressions")
        void message(String source, String fragment) {
            ExprException e = assertThrows(ExprException.class,
                    () -> Expressions.condition(source), source);
            assertTrue(e.getMessage().contains(fragment),
                    "expected \"" + fragment + "\" in: " + e.getMessage());
        }

        @Test
        void comparingNullWithOrderingIsAnError() {
            ExprException e = assertThrows(ExprException.class, () -> cond("state.absent > 1"));
            assertTrue(e.getMessage().contains("null"), e.getMessage());
        }

        @Test
        void comparingIncompatibleTypesIsAnError() {
            ExprException e = assertThrows(ExprException.class, () -> cond("items > 1"));
            assertTrue(e.getMessage().contains("cannot compare"), e.getMessage());
        }
    }

    @Nested
    @DisplayName("variable collection for validation")
    class VariableCollection {

        @Test
        void findsEveryReference() {
            var vars = Expressions.variables(Expressions.condition(
                    "semver(trigger.version) > semver(default(state.deployed_version, \"0.0.0\")) and vars.x"));
            assertEquals(List.of("trigger.version", "state.deployed_version", "vars.x"),
                    vars.stream().map(Node.Var::render).toList());
        }

        @Test
        void findsReferencesInsideTemplateHoles() {
            var t = Expressions.template("${vars.releases_root}/x/${trigger.version}");
            var roots = t.holes().stream()
                    .flatMap(n -> Expressions.variables(n).stream())
                    .map(Node.Var::root)
                    .toList();
            assertEquals(List.of("vars", "trigger"), roots);
        }
    }
}
