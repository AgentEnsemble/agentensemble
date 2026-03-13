package net.agentensemble.tool;

import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Generates a LangChain4j {@link JsonObjectSchema} from a Java record class annotated
 * with {@link ToolInput} and {@link ToolParam}.
 *
 * <p>The generated schema maps Java types to JSON Schema types as follows:
 *
 * <table>
 *   <caption>Java to JSON Schema type mapping</caption>
 *   <tr><th>Java Type</th><th>JSON Schema Type</th></tr>
 *   <tr><td>{@code String}</td><td>string</td></tr>
 *   <tr><td>{@code int}, {@code Integer}, {@code long}, {@code Long}, {@code short}, {@code Short}</td><td>integer</td></tr>
 *   <tr><td>{@code double}, {@code Double}, {@code float}, {@code Float}, {@code BigDecimal}</td><td>number</td></tr>
 *   <tr><td>{@code boolean}, {@code Boolean}</td><td>boolean</td></tr>
 *   <tr><td>Enum subclasses</td><td>enum (with values from {@code Enum.values()})</td></tr>
 *   <tr><td>{@code List<T>}, {@code Collection<T>}, {@code T[]}</td><td>array (items typed if T is known)</td></tr>
 *   <tr><td>{@code Map<K,V>} and other object types</td><td>object (open schema)</td></tr>
 * </table>
 *
 * <p>Record components without a {@link ToolParam} annotation are treated as required.
 * Components with {@code @ToolParam(required = false)} are optional and excluded from
 * the schema's {@code required} array.
 *
 * @see TypedAgentTool
 * @see ToolInput
 * @see ToolParam
 */
public final class ToolSchemaGenerator {

    private ToolSchemaGenerator() {
        // Utility class -- not instantiable
    }

    /**
     * Generate a {@link JsonObjectSchema} from the given record class.
     *
     * @param inputType the record class to introspect; must be a Java record
     * @return the generated schema; never null
     * @throws IllegalArgumentException if {@code inputType} is not a record
     */
    public static JsonObjectSchema generateSchema(Class<?> inputType) {
        if (inputType == null) {
            throw new IllegalArgumentException("inputType must not be null");
        }
        if (!inputType.isRecord()) {
            throw new IllegalArgumentException("Input type '" + inputType.getName() + "' must be a Java record. "
                    + "Declare your tool input as: public record MyInput(...) {}");
        }

        RecordComponent[] components = inputType.getRecordComponents();
        JsonObjectSchema.Builder builder = JsonObjectSchema.builder();
        List<String> requiredNames = new ArrayList<>();

        for (RecordComponent comp : components) {
            String name = comp.getName();
            ToolParam param = comp.getAnnotation(ToolParam.class);
            String description = (param != null && !param.description().isEmpty()) ? param.description() : null;
            boolean required = (param == null) || param.required();

            addProperty(builder, name, comp.getType(), comp.getGenericType(), description);

            if (required) {
                requiredNames.add(name);
            }
        }

        if (!requiredNames.isEmpty()) {
            builder.required(requiredNames);
        }

        return builder.build();
    }

    // ========================
    // Private helpers
    // ========================

    private static void addProperty(
            JsonObjectSchema.Builder builder, String name, Class<?> type, Type genericType, String description) {

        // String
        if (type == String.class) {
            if (description != null) {
                builder.addStringProperty(name, description);
            } else {
                builder.addStringProperty(name);
            }
            return;
        }

        // Integer types (JSON "integer")
        if (type == int.class
                || type == Integer.class
                || type == long.class
                || type == Long.class
                || type == short.class
                || type == Short.class
                || type == byte.class
                || type == Byte.class) {
            if (description != null) {
                builder.addIntegerProperty(name, description);
            } else {
                builder.addIntegerProperty(name);
            }
            return;
        }

        // Floating-point / decimal types (JSON "number")
        if (type == double.class
                || type == Double.class
                || type == float.class
                || type == Float.class
                || type == BigDecimal.class
                || type == Number.class) {
            if (description != null) {
                builder.addNumberProperty(name, description);
            } else {
                builder.addNumberProperty(name);
            }
            return;
        }

        // Boolean (JSON "boolean")
        if (type == boolean.class || type == Boolean.class) {
            if (description != null) {
                builder.addBooleanProperty(name, description);
            } else {
                builder.addBooleanProperty(name);
            }
            return;
        }

        // Enum (JSON "string" constrained to enum values)
        if (type.isEnum()) {
            List<String> values = Arrays.stream(type.getEnumConstants())
                    .map(e -> ((Enum<?>) e).name())
                    .collect(Collectors.toList());
            if (description != null) {
                builder.addEnumProperty(name, values, description);
            } else {
                builder.addEnumProperty(name, values);
            }
            return;
        }

        // List / Collection (JSON "array")
        if (List.class.isAssignableFrom(type) || Collection.class.isAssignableFrom(type)) {
            JsonSchemaElement itemSchema = resolveListItemSchema(genericType);
            JsonArraySchema.Builder arrayBuilder = JsonArraySchema.builder().items(itemSchema);
            if (description != null) {
                arrayBuilder.description(description);
            }
            builder.addProperty(name, arrayBuilder.build());
            return;
        }

        // Array types (JSON "array")
        if (type.isArray()) {
            JsonSchemaElement itemSchema = resolveClassSchema(type.getComponentType(), null);
            JsonArraySchema.Builder arrayBuilder = JsonArraySchema.builder().items(itemSchema);
            if (description != null) {
                arrayBuilder.description(description);
            }
            builder.addProperty(name, arrayBuilder.build());
            return;
        }

        // Map / other object types (JSON "object", open schema)
        JsonObjectSchema.Builder objBuilder = JsonObjectSchema.builder();
        if (description != null) {
            objBuilder.description(description);
        }
        builder.addProperty(name, objBuilder.build());
    }

    /**
     * Resolve the item schema for a {@code List<T>} or {@code Collection<T>} by
     * inspecting the generic type parameter.
     */
    private static JsonSchemaElement resolveListItemSchema(Type genericType) {
        if (genericType instanceof ParameterizedType pt) {
            Type[] typeArgs = pt.getActualTypeArguments();
            if (typeArgs.length > 0 && typeArgs[0] instanceof Class<?> itemClass) {
                return resolveClassSchema(itemClass, null);
            }
        }
        // Unknown item type -- default to open object schema
        return JsonObjectSchema.builder().build();
    }

    /**
     * Map a Java class to a leaf JSON schema element for use as an array item type.
     *
     * @param cls         the Java class to map
     * @param description an optional description; may be null
     * @return a JSON schema element; never null
     */
    static JsonSchemaElement resolveClassSchema(Class<?> cls, String description) {
        if (cls == String.class || cls == CharSequence.class) {
            return description != null
                    ? JsonStringSchema.builder().description(description).build()
                    : new JsonStringSchema();
        }
        if (cls == int.class
                || cls == Integer.class
                || cls == long.class
                || cls == Long.class
                || cls == short.class
                || cls == Short.class
                || cls == byte.class
                || cls == Byte.class) {
            return description != null
                    ? JsonIntegerSchema.builder().description(description).build()
                    : new JsonIntegerSchema();
        }
        if (cls == double.class
                || cls == Double.class
                || cls == float.class
                || cls == Float.class
                || cls == BigDecimal.class
                || cls == Number.class) {
            return description != null
                    ? JsonNumberSchema.builder().description(description).build()
                    : new JsonNumberSchema();
        }
        if (cls == boolean.class || cls == Boolean.class) {
            return description != null
                    ? JsonBooleanSchema.builder().description(description).build()
                    : new JsonBooleanSchema();
        }
        if (cls.isEnum()) {
            List<String> values = Arrays.stream(cls.getEnumConstants())
                    .map(e -> ((Enum<?>) e).name())
                    .collect(Collectors.toList());
            JsonEnumSchema.Builder enumBuilder = JsonEnumSchema.builder().enumValues(values);
            if (description != null) {
                enumBuilder.description(description);
            }
            return enumBuilder.build();
        }
        // Default: open object schema for unknown/complex types (e.g. Map, POJO)
        JsonObjectSchema.Builder objBuilder = JsonObjectSchema.builder();
        if (description != null) {
            objBuilder.description(description);
        }
        return objBuilder.build();
    }
}
