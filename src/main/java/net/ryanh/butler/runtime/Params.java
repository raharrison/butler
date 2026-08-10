package net.ryanh.butler.runtime;

import com.fasterxml.jackson.annotation.JsonProperty;
import net.ryanh.butler.util.Durations;
import net.ryanh.butler.util.Literals;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.*;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Binds a step's or trigger's raw parameter map to its own config record.
 *
 * <p>This is the one place databind does any work. Config loading deliberately avoids it, because
 * it throws on the first mismatch and the loader must report every problem at once; here failing
 * fast on one step is exactly right, and the caller turns the failure into a diagnostic with a
 * file, line and column.
 *
 * <p>Config keys are snake_case, so the mapper carries the matching naming strategy and
 * {@link #names} asks that same strategy how a component is spelled - one source of truth for the
 * name a config author has to type.
 */
public final class Params {

    private static final PropertyNamingStrategies.NamingBase NAMING =
            (PropertyNamingStrategies.NamingBase) PropertyNamingStrategies.SNAKE_CASE;

    /**
     * FAIL_ON_UNKNOWN_PROPERTIES is off by default in Jackson 3, the opposite of Jackson 2, and it
     * applies only to record and bean binding. An unknown parameter is a typo the author needs
     * told about, so it is enabled explicitly.
     */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .propertyNamingStrategy(NAMING)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            // Every other enum in the config is written lowercase: log_format, backoff, mode.
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .addModule(butlerScalars())
            .build();

    private Params() {
    }

    /**
     * There is one duration syntax in Butler, and it is not ISO-8601. A step parameter typed as a
     * {@link Duration} therefore takes {@code 30s} like every other duration in the config, parsed
     * by the same converter the loader uses.
     */
    private static SimpleModule butlerScalars() {
        SimpleModule module = new SimpleModule("butler-scalars");
        module.addDeserializer(Duration.class, new ValueDeserializer<Duration>() {
            @Override
            public Duration deserialize(JsonParser p, DeserializationContext ctxt) {
                try {
                    return Durations.parse(p.getString());
                } catch (IllegalArgumentException e) {
                    throw new BindingException(e.getMessage());
                }
            }
        });
        return module;
    }

    /**
     * Thrown when a parameter map does not fit the record. Message is fit for a diagnostic.
     */
    public static final class BindingException extends RuntimeException {
        BindingException(String message) {
            super(message);
        }
    }

    /**
     * The parameter names a config author writes, in declaration order.
     */
    public static List<String> names(Class<?> configType) {
        List<String> out = new ArrayList<>();
        for (RecordComponent rc : configType.getRecordComponents()) {
            String explicit = explicitName(rc);
            // Records bind through their canonical constructor, so this is the same question
            // Jackson asks itself. NamingBase answers it from the name alone and ignores the
            // config and member arguments.
            out.add(explicit != null
                    ? explicit
                    : NAMING.nameForConstructorParameter(null, null, rc.getName()));
        }
        return List.copyOf(out);
    }

    /**
     * {@code @JsonProperty} does not target record components, so the compiler puts it on the
     * accessor and the backing field instead. Jackson reads it from there, and so must this.
     */
    private static String explicitName(RecordComponent rc) {
        JsonProperty annotation = rc.getAnnotation(JsonProperty.class);
        if (annotation == null) {
            annotation = rc.getAccessor().getAnnotation(JsonProperty.class);
        }
        if (annotation == null) {
            try {
                annotation = rc.getDeclaringRecord().getDeclaredField(rc.getName())
                        .getAnnotation(JsonProperty.class);
            } catch (NoSuchFieldException e) {
                return null;
            }
        }
        return annotation == null || annotation.value().isEmpty() ? null : annotation.value();
    }

    /**
     * Whether a parameter value still holds a {@code ${...}} anywhere inside it.
     *
     * <p>Asked before binding, where a template means the value has no type yet. Asked again after
     * resolution, where it means the author wrote {@code $${} for a literal, or the event carried
     * a value with braces in it, and a {@code ${} in the step's description is not the step's
     * fault.
     */
    public static boolean containsTemplate(Object value) {
        return switch (value) {
            case null -> false;
            case String s -> s.contains("${");
            case Map<?, ?> m -> m.values().stream().anyMatch(Params::containsTemplate);
            case List<?> l -> l.stream().anyMatch(Params::containsTemplate);
            default -> false;
        };
    }

    /**
     * Renders a component's type the way {@code butler steps} shows it.
     */
    public static String describeType(Class<?> type) {
        if (type.isEnum()) {
            List<String> constants = new ArrayList<>();
            for (Object c : type.getEnumConstants()) {
                constants.add(String.valueOf(c).toLowerCase(Locale.ROOT));
            }
            return String.join(" | ", constants);
        }
        if (type == String.class) return "text";
        if (type == boolean.class || type == Boolean.class) return "true/false";
        if (Number.class.isAssignableFrom(type) || type.isPrimitive()) return "number";
        if (Map.class.isAssignableFrom(type)) return "mapping";
        if (List.class.isAssignableFrom(type)) return "list";
        if (Duration.class == type) return "duration";
        if (Path.class.isAssignableFrom(type)) return "path";
        return type.getSimpleName().toLowerCase(Locale.ROOT);
    }

    /**
     * Binds resolved parameters to the record.
     *
     * @throws BindingException if a key is unknown or a value has the wrong type
     */
    public static Object bind(Class<?> configType, Map<String, Object> params) {
        try {
            return MAPPER.convertValue(params, configType);
        } catch (InvalidFormatException e) {
            throw new BindingException(Literals.of(e.getValue()) + " is not usable here, expected "
                    + describeType(e.getTargetType()));
        } catch (DatabindException e) {
            throw new BindingException(clean(e));
        } catch (IllegalArgumentException e) {
            throw new BindingException(e.getMessage());
        }
    }

    /**
     * A databind message ends with a reference chain naming Java types, which says nothing to
     * someone reading a YAML file.
     */
    private static String clean(DatabindException e) {
        String message = e.getOriginalMessage();
        if (message == null || message.isBlank()) {
            message = e.getMessage();
        }
        int at = message.indexOf(" (through reference chain");
        return at < 0 ? message : message.substring(0, at);
    }
}
