package net.ryanh.butler.runtime;

import com.fasterxml.jackson.annotation.JsonProperty;
import net.ryanh.butler.spi.RunContext;
import net.ryanh.butler.spi.StepResult;
import net.ryanh.butler.spi.StepType;
import net.ryanh.butler.step.control.LogStep;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RegistryTest {

    @Nested
    @DisplayName("discovery")
    class Discovery {

        @Test
        void findsEveryRegisteredStepAndTrigger() {
            assertNotNull(StepRegistry.discover().find("control.log"));
            assertNotNull(StepRegistry.discover().find("control.set"));
            assertNotNull(TriggerRegistry.discover().find("manual"));
            assertNotNull(StepRegistry.discover().find("fs.copy"));
            assertNull(StepRegistry.discover().find("docker.compose"));
        }

        @Test
        @DisplayName("two steps with one name is a startup error, not last-one-wins")
        void duplicateNamesAreFatal() {
            var e = assertThrows(IllegalStateException.class,
                    () -> StepRegistry.of(new Named("same"), new Named("same")));
            assertTrue(e.getMessage().contains("\"same\""), e.getMessage());
        }

        @Test
        @DisplayName("a step whose parameters are not a record is a startup error")
        void configTypeMustBeARecord() {
            var e = assertThrows(IllegalStateException.class,
                    () -> StepRegistry.of(new NotARecord()));
            assertTrue(e.getMessage().contains("must use a record"), e.getMessage());
        }

        @Test
        @DisplayName("a parameter named after a reserved step key is a startup error, since it "
                + "would be listed by butler steps and never receive a value")
        void reservedParameterNamesAreRefused() {
            var e = assertThrows(IllegalStateException.class,
                    () -> StepRegistry.of(new Reserving()));
            assertTrue(e.getMessage().contains("\"working_dir\""), e.getMessage());
            assertTrue(e.getMessage().contains("reserved step key"), e.getMessage());
        }
    }

    @Nested
    @DisplayName("parameter names")
    class Names {

        @Test
        @DisplayName("are the snake_case an author types, in declaration order")
        void areSnakeCased() {
            assertEquals(List.of("unit", "wait_active", "run-as"),
                    Params.names(Spelling.class));
        }

        @Test
        void renderTypesForTheStepsCommand() {
            assertEquals("text", Params.describeType(String.class));
            assertEquals("duration", Params.describeType(Duration.class));
            assertEquals("mapping", Params.describeType(Map.class));
            assertEquals("debug | info | warn | error", Params.describeType(LogStep.Level.class));
        }
    }

    @Nested
    @DisplayName("binding")
    class Binding {

        @Test
        void bindsSnakeCaseKeysAndLowercaseEnums() {
            Object bound = Params.bind(LogStep.Config.class,
                    Map.of("message", "hi", "level", "warn"));
            assertEquals(LogStep.Level.WARN, ((LogStep.Config) bound).level());
        }

        @Test
        @DisplayName("rejects an unknown key: FAIL_ON_UNKNOWN_PROPERTIES is off by default in "
                + "Jackson 3 and this is where it does its work")
        void rejectsUnknownKeys() {
            var e = assertThrows(Params.BindingException.class,
                    () -> Params.bind(Spelling.class, Map.of("unit", "api", "nonsense", "x")));
            assertTrue(e.getMessage().contains("nonsense"), e.getMessage());
        }

        @Test
        void rejectsAValueOfTheWrongType() {
            var e = assertThrows(Params.BindingException.class,
                    () -> Params.bind(Spelling.class, Map.of("wait_active", "not-a-duration")));
            assertTrue(e.getMessage().contains("not-a-duration"), e.getMessage());
        }
    }

    /**
     * Exercises both spellings: the naming strategy, and an explicit override.
     */
    private record Spelling(String unit, Duration waitActive,
                            @JsonProperty("run-as") String runAs) {
    }

    private record Empty() {
    }

    private static class Named implements StepType<Empty> {
        private final String name;

        Named(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return this.name;
        }

        @Override
        public Class<Empty> configType() {
            return Empty.class;
        }

        @Override
        public StepResult execute(Empty config, RunContext ctx) {
            return StepResult.ok();
        }

        @Override
        public String describe(Empty config, RunContext ctx) {
            return "would do nothing";
        }
    }

    /**
     * A step that names one of its parameters after a reserved key, which the loader lifts out
     * before the step ever sees it.
     */
    private static final class Reserving implements StepType<Reserving.Config> {

        record Config(String unit, String workingDir) {
        }

        @Override
        public String name() {
            return "test.reserving";
        }

        @Override
        public Class<Config> configType() {
            return Config.class;
        }

        @Override
        public StepResult execute(Config config, RunContext ctx) {
            return StepResult.ok();
        }

        @Override
        public String describe(Config config, RunContext ctx) {
            return "would do nothing";
        }
    }

    private static final class NotARecord implements StepType<String> {
        @Override
        public String name() {
            return "test.notarecord";
        }

        @Override
        public Class<String> configType() {
            return String.class;
        }

        @Override
        public StepResult execute(String config, RunContext ctx) {
            return StepResult.ok();
        }

        @Override
        public String describe(String config, RunContext ctx) {
            return "would do nothing";
        }
    }
}
