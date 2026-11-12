package com.mosaicql.runtime;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.core.json.JsonWriteFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.module.SimpleModule;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Objects;

/** Jackson 3 codec. String/UTF-8 input is parsed directly into rows, without a JSON tree. */
public final class JsonAdapter {
    private record JsonOutput(Object value) {}
    private static final String[] SCALAR_COLUMNS = {"value"};
    private static final JsonMapper MAPPER = JsonMapper.builderWithJackson2Defaults()
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,
                    DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN, StreamWriteFeature.AUTO_CLOSE_CONTENT)
            .disable(StreamWriteFeature.AUTO_CLOSE_TARGET)
            .enable(JsonWriteFeature.COMBINE_UNICODE_SURROGATES_IN_UTF8)
            .addModule(new SimpleModule("MosaicQL-output").addSerializer(JsonOutput.class,
                new ValueSerializer<JsonOutput>() {
                    @Override public void serialize(JsonOutput value, JsonGenerator generator, SerializationContext context) {
                        writeNode(generator, value.value());
                    }
                }))
            .build();

    private JsonAdapter() {}

    public static Table read(String json) throws JacksonException {
        try (var parser = MAPPER.createParser(json)) { return readStream(parser); }
    }

    public static Table read(byte[] utf8) throws JacksonException {
        try (var parser = MAPPER.createParser(utf8)) { return readStream(parser); }
    }

    /** Opt-in fixed schema: missing fields are null; undeclared fields are rejected. */
    public static Table read(String json, RowLayout layout) {
        try (var parser = MAPPER.createParser(json)) { return readSchema(parser, Objects.requireNonNull(layout)); }
    }
    public static Table read(byte[] utf8, RowLayout layout) {
        try (var parser = MAPPER.createParser(utf8)) { return readSchema(parser, Objects.requireNonNull(layout)); }
    }
    private static Table readSchema(JsonParser parser, RowLayout layout) {
        var rows = new ArrayList<Row>(); var first = parser.nextToken();
        if (first == null) throw new IllegalArgumentException("JSON input must contain one value");
        if (first == JsonToken.START_ARRAY) {
            while (parser.nextToken() != JsonToken.END_ARRAY) rows.add(readSchemaRow(parser, layout));
        } else if (first != JsonToken.VALUE_NULL) rows.add(readSchemaRow(parser, layout));
        if (parser.nextToken() != null) throw new StreamReadException(parser, "JSON input must contain exactly one value");
        return new Table(rows);
    }
    private static Row readSchemaRow(JsonParser parser, RowLayout layout) {
        if (parser.currentToken() != JsonToken.START_OBJECT) throw new StreamReadException(parser, "Schema rows must be objects");
        var values = new Object[layout.size()]; var present = new boolean[layout.size()]; String name;
        while ((name=parser.nextName()) != null) {
            int slot=layout.indexOf(name);
            if (slot < 0) throw new StreamReadException(parser, "Undeclared schema field: " + name);
            if (present[slot]) throw new StreamReadException(parser, "Duplicate JSON field: " + name);
            present[slot]=true; parser.nextToken(); values[slot]=readValue(parser);
        }
        if (parser.currentToken()!=JsonToken.END_OBJECT) throw new StreamReadException(parser,"Expected JSON object field");
        return Row.projected(layout, values);
    }

    private static Table readStream(JsonParser parser) {
        var token = parser.nextToken();
        if (token == null) throw new IllegalArgumentException("JSON input must contain one value");
        var rows = new ArrayList<Row>();
        if (token == JsonToken.START_ARRAY) {
            while (parser.nextToken() != JsonToken.END_ARRAY) rows.add(readRow(parser));
        } else if (token != JsonToken.VALUE_NULL) {
            rows.add(readRow(parser));
        }
        if (parser.nextToken() != null) throw new StreamReadException(parser, "JSON input must contain exactly one value");
        return new Table(rows);
    }

    private static Row readRow(JsonParser parser) {
        if (parser.currentToken() == JsonToken.START_OBJECT) return readObject(parser);
        return Row.projected(SCALAR_COLUMNS, new Object[]{readValue(parser)});
    }

    private static Row readObject(JsonParser parser) {
        var names = new ArrayList<String>(8);
        var values = new ArrayList<Object>(8);
        java.util.Set<String> wideNames = null;
        String name;
        while ((name = parser.nextName()) != null) {
            if (names.size() == 16) wideNames = new it.unimi.dsi.fastutil.objects.ObjectOpenHashSet<>(names);
            if (wideNames == null ? names.contains(name) : !wideNames.add(name))
                throw new StreamReadException(parser, "Duplicate JSON field: " + name);
            names.add(name);
            parser.nextToken();
            values.add(readValue(parser));
        }
        if (parser.currentToken() != JsonToken.END_OBJECT) throw new StreamReadException(parser, "Expected JSON object field");
        return Row.projected(names.toArray(String[]::new), values.toArray());
    }

    private static Object readValue(JsonParser parser) {
        var token = parser.currentToken();
        if (token == null) throw new StreamReadException(parser, "Unexpected end of JSON input");
        return switch (token) {
            case START_OBJECT -> readObject(parser).values();
            case START_ARRAY -> {
                var values = new ArrayList<>();
                while (parser.nextToken() != JsonToken.END_ARRAY) values.add(readValue(parser));
                yield values;
            }
            case VALUE_NULL -> null;
            case VALUE_TRUE -> true;
            case VALUE_FALSE -> false;
            case VALUE_STRING -> parser.getString();
            case VALUE_NUMBER_INT -> parser.getDecimalValue();
            case VALUE_NUMBER_FLOAT -> parser.getDecimalValue().stripTrailingZeros();
            default -> throw new StreamReadException(parser, "Unexpected JSON token: " + token);
        };
    }

    public static Table read(JsonNode root) {
        if (root == null || root.isMissingNode()) {
            throw new IllegalArgumentException("JSON input must contain one value");
        }
        if (root.isNull()) return new Table(List.of());
        if (root.isArray()) {
            var rows = new ArrayList<Row>(root.size());
            for (var item : root) rows.add(toRow(item));
            return new Table(rows);
        }
        return new Table(List.of(toRow(root)));
    }

    public static String write(Table table) throws JacksonException {
        return writeValue(Objects.requireNonNull(table));
    }

    public static String write(QueryResult result) throws JacksonException {
        return writeValue(Objects.requireNonNull(result).value());
    }

    /** Writes without a second JSON tree or a complete output String; the caller owns the target. */
    public static void write(QueryResult result, OutputStream output) throws IOException {
        try (var generator = MAPPER.createGenerator(output)) {
            writeNode(generator, Objects.requireNonNull(result).value());
        }
    }

    public static void write(QueryResult result, Writer output) throws IOException {
        try (var generator = MAPPER.createGenerator(output)) {
            writeNode(generator, Objects.requireNonNull(result).value());
        }
    }

    private static String writeValue(Object value) throws JacksonException {
        // Jackson owns its segmented text buffer and recycler lifecycle, including exceptional exits.
        return MAPPER.writeValueAsString(new JsonOutput(value));
    }

    static JsonGenerator generator(OutputStream output) { return MAPPER.createGenerator(output); }
    static JsonGenerator generator(Writer output) { return MAPPER.createGenerator(output); }
    static void writeProjected(JsonGenerator generator, RowLayout layout, Object[] values) {
        generator.writeStartObject();
        for (int i=0;i<values.length;i++) { generator.writeName(layout.nameAt(i)); writeNode(generator,values[i]); }
        generator.writeEndObject();
    }
    static void writeNode(JsonGenerator generator, Object value) {
        if (value == null) { generator.writeNull(); return; }
        if (value instanceof Row row) { writeNode(generator, row.values()); return; }
        if (value instanceof Table table) { writeNode(generator, table.rows()); return; }
        if (value instanceof RowValues row) {
            generator.writeStartObject();
            for (int i = 0; i < row.size(); i++) {
                generator.writeName(row.nameAt(i));
                writeNode(generator, row.valueAt(i));
            }
            generator.writeEndObject();
            return;
        }
        if (value instanceof Map<?, ?> map) {
            generator.writeStartObject();
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String name)) throw new IllegalArgumentException("JSON object keys must be strings");
                generator.writeName(name);
                writeNode(generator, entry.getValue());
            }
            generator.writeEndObject();
            return;
        }
        if (value instanceof List<?> list) {
            generator.writeStartArray();
            for (var item : list) writeNode(generator, item);
            generator.writeEndArray();
            return;
        }
        if (value instanceof String text) { generator.writeString(text); return; }
        if (value instanceof Boolean bool) { generator.writeBoolean(bool); return; }
        if (value instanceof BigDecimal number) { generator.writeNumber(number.stripTrailingZeros()); return; }
        if (value instanceof BigInteger number) { generator.writeNumber(number); return; }
        if (value instanceof Long number) { generator.writeNumber(number); return; }
        if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
            generator.writeNumber(((Number)value).intValue()); return;
        }
        if (value instanceof Double || value instanceof Float) {
            generator.writeNumber(jsonDecimal((Number)value)); return;
        }
        // Retain Jackson's established handling of uncommon Number subtypes, without slowing primitives.
        if (value instanceof Number) { MAPPER.writeTree(generator, MAPPER.valueToTree(value)); return; }
        throw new IllegalArgumentException("Unsupported JSON value: " + value.getClass().getName());
    }

    private static BigDecimal jsonDecimal(Number number) {
        if (!Double.isFinite(number.doubleValue())) throw new IllegalArgumentException("JSON numbers must be finite");
        // The previous tree conversion used USE_BIG_DECIMAL_FOR_FLOATS, including Float -> double.
        return BigDecimal.valueOf(number.doubleValue()).stripTrailingZeros();
    }

    public static JsonNode toJson(QueryResult result) {
        return toNode(result.value());
    }

    public static String write(Row row) throws JacksonException {
        return writeValue(Objects.requireNonNull(row));
    }

    public static ObjectNode toJson(Row row) {
        var object = MAPPER.createObjectNode();
        row.values().forEach((key, value) -> object.set(key, toNode(value)));
        return object;
    }

    public static ArrayNode toJson(Table table) {
        var array = MAPPER.getNodeFactory().arrayNode(table.rows().size());
        table.rows().forEach(row -> array.add(toJson(row)));
        return array;
    }

    private static Row toRow(JsonNode node) {
        var values = new LinkedHashMap<String, Object>();
        if (node.isObject()) {
            node.properties().forEach(field -> values.put(field.getKey(), fromNode(field.getValue())));
        } else {
            values.put("value", fromNode(node));
        }
        return new Row(values);
    }

    private static Object fromNode(JsonNode node) {
        if (node.isNull()) return null;
        if (node.isObject()) return toRow(node).values();
        if (node.isArray()) {
            var values = new ArrayList<Object>(node.size());
            node.forEach(item -> values.add(fromNode(item)));
            return values;
        }
        if (node.isNumber()) return node.decimalValue();
        if (node.isBoolean()) return node.booleanValue();
        if (node.isTextual()) return node.textValue();
        throw new IllegalArgumentException("Unsupported JSON node: " + node.getNodeType());
    }

    private static JsonNode toNode(Object value) {
        if (value == null) return MAPPER.nullNode();
        if (value instanceof Row row) return toJson(row);
        if (value instanceof Table table) return toJson(table);
        if (value instanceof Map<?, ?> map) {
            var object = MAPPER.createObjectNode();
            map.forEach((key, item) -> {
                if (!(key instanceof String name)) throw new IllegalArgumentException("JSON object keys must be strings");
                object.set(name, toNode(item));
            });
            return object;
        }
        if (value instanceof List<?> list) {
            var array = MAPPER.getNodeFactory().arrayNode(list.size());
            list.forEach(item -> array.add(toNode(item)));
            return array;
        }
        if (value instanceof String text) return MAPPER.getNodeFactory().textNode(text);
        if (value instanceof Boolean bool) return MAPPER.getNodeFactory().booleanNode(bool);
        if (value instanceof BigDecimal number) return MAPPER.getNodeFactory().numberNode(number.stripTrailingZeros());
        if (value instanceof BigInteger number) return MAPPER.getNodeFactory().numberNode(number);
        if (value instanceof Long number) return MAPPER.getNodeFactory().numberNode(number);
        if (value instanceof Integer || value instanceof Short || value instanceof Byte)
            return MAPPER.getNodeFactory().numberNode(((Number)value).intValue());
        if (value instanceof Double || value instanceof Float) return MAPPER.getNodeFactory().numberNode(jsonDecimal((Number)value));
        if (value instanceof Number) return MAPPER.valueToTree(value);
        throw new IllegalArgumentException("Unsupported JSON value: " + value.getClass().getName());
    }
}
