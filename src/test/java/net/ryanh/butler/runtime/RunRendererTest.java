package net.ryanh.butler.runtime;

import net.ryanh.butler.spi.StepResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The live and replayed report of DESIGN.md §5.4/§6.4: same renderer either way, so a step's
 * captured output reads the same in {@code butler trigger} and {@code butler show}.
 */
class RunRendererTest {

    private static Run oneStepRun(Map<String, Object> outputs) {
        return new Run("20260809T031407-a1b2", "api", "manual", Map.of(),
                Run.Status.SUCCESS, Instant.parse("2026-08-09T03:14:07Z"), Duration.ofSeconds(1),
                List.of(), null,
                List.of(new Run.Step("step", "Run it", "shell.exec", StepResult.Status.OK,
                        Duration.ofMillis(80), 1, null, outputs)),
                Map.of(), null, null, null);
    }

    @Test
    @DisplayName("a step's stdout is shown, indented under its own line")
    void printsCapturedOutput() {
        String text = String.join("\n", "first", "second", "third") + "\n";
        String rendered = RunRenderer.render(oneStepRun(Map.of("stdout", text)));

        assertTrue(rendered.contains("stdout:"), rendered);
        assertTrue(rendered.contains("first"), rendered);
        assertTrue(rendered.contains("second"), rendered);
        assertTrue(rendered.contains("third"), rendered);
    }

    @Test
    @DisplayName("a step with nothing captured adds no output lines")
    void emptyOutputsAddNothing() {
        String rendered = RunRenderer.render(oneStepRun(Map.of()));

        assertFalse(rendered.contains("stdout:"), rendered);
        assertFalse(rendered.contains("stderr:"), rendered);
    }

    @Test
    @DisplayName("long output is truncated on screen, with a pointer to the full record")
    void longOutputIsTruncated() {
        List<String> lines = java.util.stream.IntStream.rangeClosed(1, 25)
                .mapToObj(i -> "line " + i).toList();
        String rendered = RunRenderer.render(oneStepRun(Map.of("stdout", String.join("\n", lines))));

        assertTrue(rendered.contains("line 20"), "the 20th line is within the shown tail:\n" + rendered);
        assertFalse(rendered.contains("line 21"),
                "the terminal report caps the tail; the JSON record keeps the rest:\n" + rendered);
        assertTrue(rendered.contains("5 more line(s)"), rendered);
        assertTrue(rendered.contains("run record"), rendered);
    }
}
