package net.ryanh.butler.cli;

import net.ryanh.butler.runtime.Params;
import net.ryanh.butler.runtime.StepRegistry;
import net.ryanh.butler.spi.StepType;
import net.ryanh.butler.util.Suggestions;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Prints the step vocabulary, generated from the registry and each step's own parameter record, so
 * a newly registered step is documented the moment it exists.
 */
@Command(
        name = "steps",
        header = "List the registered step types and their parameters.",
        mixinStandardHelpOptions = true)
public final class StepsCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", paramLabel = "<name>",
            description = "Show only this step type.")
    String name;

    @Override
    public Integer call() {
        StepRegistry registry = StepRegistry.discover();

        if (name != null) {
            StepType<?> type = registry.find(name);
            if (type == null) {
                System.err.println("no step type named \"" + name + "\""
                        + Suggestions.from(name, registry.names()));
                return ButlerCommand.EXIT_FAILURE;
            }
            System.out.print(render(type));
            return ButlerCommand.EXIT_OK;
        }

        boolean first = true;
        for (StepType<?> type : registry.all()) {
            if (!first) {
                System.out.println();
            }
            System.out.print(render(type));
            first = false;
        }
        return ButlerCommand.EXIT_OK;
    }

    private static String render(StepType<?> type) {
        StringBuilder sb = new StringBuilder(type.name());
        if (!type.summary().isEmpty()) {
            sb.append("   ").append(type.summary());
        }
        sb.append('\n');

        RecordComponent[] components = type.configType().getRecordComponents();
        List<String> names = Params.names(type.configType());
        List<String> required = type.required();
        int width = names.stream().mapToInt(String::length).max().orElse(0);
        int types = names.isEmpty() ? 0 : components.length;
        int typeWidth = 0;
        for (int i = 0; i < types; i++) {
            typeWidth = Math.max(typeWidth, Params.describeType(components[i].getType()).length());
        }
        for (int i = 0; i < components.length; i++) {
            String described = Params.describeType(components[i].getType());
            sb.append("    ").append(names.get(i))
                    .append(" ".repeat(width - names.get(i).length() + 3))
                    .append(described);
            if (required.contains(names.get(i))) {
                sb.append(" ".repeat(typeWidth - described.length() + 3)).append("required");
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
