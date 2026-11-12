package com.mosaicql.type;

import com.mosaicql.ast.Ast;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import com.mosaicql.runtime.FunctionRegistry;
import com.mosaicql.runtime.Row;
import java.time.LocalDate;
import java.time.Instant;

public final class TypeInferencer {
    private TypeInferencer() {}

    public static MType runtime(Object value) {
        if (value == null) return MType.NULL;
        if (value instanceof Boolean) return MType.BOOLEAN;
        if (value instanceof Number) return MType.NUMBER;
        if (value instanceof String || value instanceof Character) return MType.STRING;
        if (value instanceof LocalDate) return MType.DATE;
        if (value instanceof Instant || value instanceof java.time.LocalDateTime) return MType.TIMESTAMP;
        if (value instanceof Row row) return runtime(row.values());
        if (value instanceof Map<?, ?> map) {
            var fields = new java.util.LinkedHashMap<String, MType>();
            for (var entry : map.entrySet()) {
                if (entry.getKey() instanceof String key) fields.put(key, runtime(entry.getValue()));
            }
            return new ObjectType(fields);
        }
        if (value instanceof List<?> list) {
            MType element = null;
            for (var item : list) element = merge(element, runtime(item));
            return new ArrayType(element == null ? UnknownType.INSTANCE : element);
        }
        return UnknownType.INSTANCE;
    }

    public static MType expression(Ast.Expr expression, Map<String, TableDefinition> sources) {
        if (expression instanceof Ast.Literal literal) return runtime(literal.value());
        if (expression instanceof Ast.Wildcard) return UnknownType.INSTANCE;
        if (expression instanceof Ast.Reference reference) return referenceType(reference, sources);
        if (expression instanceof Ast.NestedQuery nested) return queryType(nested.query(), sources, null);
        if (expression instanceof Ast.CaseWhen caseWhen) {
            MType result = null;
            for (var branch : caseWhen.branches()) result = merge(result, expression(branch.value(), sources));
            if (caseWhen.elseValue() != null) result = merge(result, expression(caseWhen.elseValue(), sources));
            return result == null ? UnknownType.INSTANCE : result;
        }
        if (expression instanceof Ast.Unary unary) return expression(unary.value(), sources);
        if (expression instanceof Ast.Binary binary) return binaryType(binary, sources);
        if (expression instanceof Ast.Function function) return functionType(function, sources);
        return UnknownType.INSTANCE;
    }

    public static MType expression(Ast.Expr expression, Map<String, TableDefinition> sources,
                                   FunctionRegistry functions) {
        if (expression instanceof Ast.NestedQuery nested) return queryType(nested.query(), sources, functions);
        if (expression instanceof Ast.CaseWhen conditional) {
            MType result = null;
            for (var branch : conditional.branches()) result = merge(result, expression(branch.value(), sources, functions));
            if (conditional.elseValue() != null) result = merge(result, expression(conditional.elseValue(), sources, functions));
            return result == null ? UnknownType.INSTANCE : result;
        }
        if (expression instanceof Ast.Function function) {
            var arguments = function.arguments().stream()
                .map(argument -> expression(argument, sources, functions)).toList();
            if (functions.isAggregate(function.name())) {
                return switch (function.name().toLowerCase(java.util.Locale.ROOT)) {
                    case "count" -> MType.NUMBER;
                    case "sum", "avg", "min", "max" -> arguments.isEmpty() ? UnknownType.INSTANCE : arguments.getFirst();
                    default -> UnknownType.INSTANCE;
                };
            }
            return functions.validateAndInfer(function.name(), arguments);
        }
        if (expression instanceof Ast.Binary binary) {
            return switch (binary.operator()) {
                case "+", "-", "*", "/" -> MType.NUMBER;
                case "=", "!=", "<", "<=", ">", ">=", "and", "or" -> MType.BOOLEAN;
                default -> UnknownType.INSTANCE;
            };
        }
        if (expression instanceof Ast.Unary unary) return expression(unary.value(), sources, functions);
        return expression(expression, sources);
    }

    private static MType queryType(Ast.Query query, Map<String, TableDefinition> sources, FunctionRegistry functions) {
        var fields = new java.util.LinkedHashMap<String, MType>();
        // Without a row schema, wildcard fields stay unknown; the object/array shape is still known.
        for (var field : query.projection().fields()) {
            if (!(field.value() instanceof Ast.Wildcard)) {
                fields.put(field.name(), functions == null ? expression(field.value(), sources)
                    : expression(field.value(), sources, functions));
            }
        }
        MType row = new ObjectType(fields);
        return query.shape() == Ast.ResultShape.ARRAY ? new ArrayType(row) : row;
    }

    public static TableDefinition inferTable(String name, List<? extends Map<String, ?>> rows) {
        var names = new java.util.LinkedHashSet<String>();
        for (var row : rows) names.addAll(row.keySet());
        var columns = names.stream().map(column -> {
            MType type = null; boolean nullable = false;
            for (var row : rows) {
                if (!row.containsKey(column)) { nullable = true; continue; }
                if (row.get(column) == null) { nullable = true; type = merge(type, MType.NULL); continue; }
                type = merge(type, runtime(row.get(column)));
            }
            return new ColumnDefinition(column, type == null ? UnknownType.INSTANCE : type, nullable);
        }).toList();
        return new TableDefinition(name, columns);
    }

    private static MType referenceType(Ast.Reference reference, Map<String, TableDefinition> sources) {
        if (reference.qualifier() == null || reference.path().isEmpty()) return UnknownType.INSTANCE;
        var definition = sources.get(reference.qualifier());
        if (definition == null) return UnknownType.INSTANCE;
        MType type = definition.columnType(reference.path().getFirst());
        for (int i = 1; i < reference.path().size(); i++) {
            if (!(type instanceof ObjectType object)) return UnknownType.INSTANCE;
            type = object.fields().getOrDefault(reference.path().get(i), UnknownType.INSTANCE);
        }
        return type;
    }

    private static MType binaryType(Ast.Binary binary, Map<String, TableDefinition> sources) {
        return switch (binary.operator()) {
            case "+", "-", "*", "/" -> MType.NUMBER;
            case "=", "!=", "<", "<=", ">", ">=", "and", "or" -> MType.BOOLEAN;
            default -> UnknownType.INSTANCE;
        };
    }

    private static MType functionType(Ast.Function function, Map<String, TableDefinition> sources) {
        return switch (function.name().toLowerCase(java.util.Locale.ROOT)) {
            case "sum", "avg", "min", "max", "count", "length", "abs", "round" -> MType.NUMBER;
            case "lower", "upper", "trim", "concat" -> MType.STRING;
            case "coalesce" -> function.arguments().stream().map(argument -> expression(argument, sources))
                .findFirst().orElse(UnknownType.INSTANCE);
            default -> UnknownType.INSTANCE;
        };
    }

    private static MType merge(MType left, MType right) {
        if (left == null || left instanceof UnknownType) return right;
        if (right instanceof UnknownType || left.equals(right)) return left;
        if (left instanceof PrimitiveType a && right instanceof PrimitiveType b
            && a.kind() == PrimitiveKind.NULL) return b;
        if (right instanceof PrimitiveType a && left instanceof PrimitiveType b
            && a.kind() == PrimitiveKind.NULL) return b;
        return UnknownType.INSTANCE;
    }
}
