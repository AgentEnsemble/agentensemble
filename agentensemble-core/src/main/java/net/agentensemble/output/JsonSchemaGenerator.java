package net.agentensemble.output;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Generates a human-readable JSON schema description from a Java class.
 *
 * Supports records, POJOs with declared instance fields, and common JDK types:
 * String, numeric primitives and their wrappers, boolean, List/Collection,
 * Map, arrays, enums, and nested objects up to a depth of five levels.
 *
 * The generated output is a JSON-like structure intended for injection into
 * LLM prompts to guide structured output generation. It is NOT a formal
 * JSON Schema specification document (draft-07, 2019-09, etc.).
 *
 * Example for {@code record Report(String title, List<String> findings)} :
 * <pre>
 * {
 *   "title": "string",
 *   "findings": ["string"]
 * }
 * </pre>
 */
public final class JsonSchemaGenerator {

    private static final int MAX_NESTING_DEPTH = 5;

    private JsonSchemaGenerator() {
        // Utility class
    }

    /**
     * Generate a human-readable JSON schema description for the given class.
     *
     * @param type the class to describe
     * @return a JSON-like schema string
     * @throws IllegalArgumentException if type is {@code null}, a primitive,
     *                                  {@code Void}, or a top-level array
     */
    public static String generate(Class<?> type) {
        if (type == null) {
            throw new IllegalArgumentException("Type must not be null");
        }
        if (type.isPrimitive()) {
            throw new IllegalArgumentException(
                    "Top-level primitive types are not supported: " + type.getName());
        }
        if (type == Void.class) {
            throw new IllegalArgumentException("Void type is not supported");
        }
        if (type.isArray()) {
            throw new IllegalArgumentException(
                    "Top-level array types are not supported. Wrap the array in a record or class.");
        }
        return generateObject(type, 0);
    }

    private static String generateObject(Class<?> type, int depth) {
        if (depth > MAX_NESTING_DEPTH) {
            return "\"object\"";
        }
        Map<String, String> fields = extractFieldSchema(type, depth);
        if (fields.isEmpty()) {
            return "{}";
        }

        String indent = "  ".repeat(depth);
        String innerIndent = "  ".repeat(depth + 1);
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        int i = 0;
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            sb.append(innerIndent)
                    .append("\"").append(entry.getKey()).append("\": ")
                    .append(entry.getValue());
            if (i < fields.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
            i++;
        }
        sb.append(indent).append("}");
        return sb.toString();
    }

    private static Map<String, String> extractFieldSchema(Class<?> type, int depth) {
        Map<String, String> result = new LinkedHashMap<>();
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                result.put(component.getName(),
                        typeToSchema(component.getGenericType(), depth));
            }
        } else {
            for (Field field : type.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers)) {
                    continue;
                }
                result.put(field.getName(), typeToSchema(field.getGenericType(), depth));
            }
        }
        return result;
    }

    private static String typeToSchema(Type type, int depth) {
        if (type instanceof Class<?> clazz) {
            return classToSchema(clazz, depth);
        } else if (type instanceof ParameterizedType pt) {
            return parameterizedTypeToSchema(pt, depth);
        }
        return "\"any\"";
    }

    private static String classToSchema(Class<?> clazz, int depth) {
        if (clazz == String.class || clazz == CharSequence.class) {
            return "\"string\"";
        }
        if (clazz == int.class || clazz == long.class || clazz == short.class
                || clazz == byte.class || clazz == Integer.class || clazz == Long.class
                || clazz == Short.class || clazz == Byte.class) {
            return "\"integer\"";
        }
        if (clazz == double.class || clazz == float.class
                || clazz == Double.class || clazz == Float.class) {
            return "\"number\"";
        }
        if (clazz == boolean.class || clazz == Boolean.class) {
            return "\"boolean\"";
        }
        if (clazz.isEnum()) {
            String values = Stream.of(clazz.getEnumConstants())
                    .map(Object::toString)
                    .collect(Collectors.joining(", "));
            return "\"enum: " + values + "\"";
        }
        if (clazz.isArray()) {
            return "[" + classToSchema(clazz.getComponentType(), depth) + "]";
        }
        if (Collection.class.isAssignableFrom(clazz)) {
            return "[\"any\"]";
        }
        if (Map.class.isAssignableFrom(clazz)) {
            return "{\"key\": \"value\"}";
        }
        if (depth < MAX_NESTING_DEPTH) {
            return generateObject(clazz, depth + 1);
        }
        return "\"object\"";
    }

    private static String parameterizedTypeToSchema(ParameterizedType pt, int depth) {
        Type rawType = pt.getRawType();
        if (rawType instanceof Class<?> rawClass) {
            if (Collection.class.isAssignableFrom(rawClass)) {
                Type[] typeArgs = pt.getActualTypeArguments();
                if (typeArgs.length > 0) {
                    return "[" + typeToSchema(typeArgs[0], depth) + "]";
                }
                return "[\"any\"]";
            }
            if (Map.class.isAssignableFrom(rawClass)) {
                Type[] typeArgs = pt.getActualTypeArguments();
                if (typeArgs.length >= 2) {
                    return "{"
                            + typeToSchema(typeArgs[0], depth)
                            + ": "
                            + typeToSchema(typeArgs[1], depth)
                            + "}";
                }
                return "{\"key\": \"value\"}";
            }
        }
        return typeToSchema(rawType, depth);
    }
}
