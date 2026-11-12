package com.mosaicql.plan;

import com.mosaicql.ast.Ast;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Objects;
import it.unimi.dsi.fastutil.objects.ObjectImmutableList;
import it.unimi.dsi.fastutil.objects.ObjectLists;

/** Small expression interpreter for literals, references, functions and operators. */
public final class DefaultExpressionInterpreter implements ExpressionInterpreter {
    @Override public Object evaluate(TypedExpression expression, ExpressionContext context) {
        var ast = expression.ast();
        if (ast instanceof Ast.Literal(Object value1)) return value1;
        if (expression.binding() instanceof ExpressionBinding.Reference(String qualifier, java.util.List<String> path)) {
            if (ast instanceof Ast.Reference reference && Objects.equals(qualifier, reference.qualifier())
                    && path.equals(reference.path())) return context.reference(reference);
            return context.reference(new Ast.Reference(qualifier, path));
        }
        if (ast instanceof Ast.NestedQuery(Ast.Query query)) return context.nested(query);
        if (ast instanceof Ast.CaseWhen(java.util.List<Ast.When> branches, Ast.Expr elseValue)) {
            int index = 0;
            for (var ignored : branches) {
                if (condition(evaluate(expression.children().get(index++), context)))
                    return evaluate(expression.children().get(index), context);
                index++;
            }
            return elseValue == null ? null : evaluate(expression.children().get(index), context);
        }
        if (expression.binding() instanceof ExpressionBinding.Function(String normalizedName) && ast instanceof Ast.Function function) {
            if (context instanceof AggregateExpressionContext aggregate && isAggregate(normalizedName)) {
                return aggregate.aggregate(expression);
            }
            if ("coalesce".equals(normalizedName)) {
                for (var child : expression.children()) {
                    var value = evaluate(child, context);
                    if (value != null) return value;
                }
                return null;
            }
            var call = function.name().equals(normalizedName) ? function : new Ast.Function(normalizedName, function.arguments());
            if (expression.children().size() == 1)
                return context.function(call, ObjectLists.singleton(evaluate(expression.children().getFirst(), context)));
            var arguments = new Object[expression.children().size()];
            for (int i = 0; i < arguments.length; i++) arguments[i] = evaluate(expression.children().get(i), context);
            return context.function(call, ObjectImmutableList.of(arguments));
        }
        if (ast instanceof Ast.Unary unary) {
            var value = evaluate(expression.children().getFirst(), context);
            if (expression.binding() instanceof ExpressionBinding.Operator(String symbol)
                    && "-".equals(symbol)) return value == null ? null : decimal(value).negate();
        }
        if (ast instanceof Ast.Binary binary) {
            var left = evaluate(expression.children().getFirst(), context);
            if ("and".equals(binary.operator()) && left instanceof Boolean b && !b) return false;
            if ("or".equals(binary.operator()) && left instanceof Boolean b && b) return true;
            var right = evaluate(expression.children().get(1), context);
            var operator = expression.binding() instanceof ExpressionBinding.Operator(String symbol)
                ? symbol : binary.operator();
            return binary(operator, left, right);
        }
        throw new IllegalArgumentException("Unsupported expression: " + ast);
    }

    public static Object binary(String operator, Object left, Object right) {
        if ("and".equals(operator)) return condition(left) && condition(right);
        if ("or".equals(operator)) return condition(left) || condition(right);
        if ("=".equals(operator)) return equal(left, right);
        if ("!=".equals(operator)) return !equal(left, right);
        if (left == null || right == null) return null;
        if (!(left instanceof Number) && left instanceof Comparable<?> && left.getClass().isInstance(right)) {
            @SuppressWarnings("unchecked") int comparison = ((Comparable<Object>) left).compareTo(right);
            return switch (operator) {
                case "<" -> comparison < 0; case "<=" -> comparison <= 0;
                case ">" -> comparison > 0; case ">=" -> comparison >= 0;
                default -> throw new IllegalArgumentException("Operator requires numbers or comparable values: " + operator);
            };
        }
        var a = decimal(left); var b = decimal(right);
        return switch (operator) {
            case "+" -> a.add(b); case "-" -> a.subtract(b); case "*" -> a.multiply(b);
            case "/" -> a.divide(b, MathContext.DECIMAL128);
            case "<" -> a.compareTo(b) < 0; case "<=" -> a.compareTo(b) <= 0;
            case ">" -> a.compareTo(b) > 0; case ">=" -> a.compareTo(b) >= 0;
            default -> throw new IllegalArgumentException("Unsupported operator: " + operator);
        };
    }
    private static boolean equal(Object left, Object right) {
        if (left instanceof Number && right instanceof Number) return decimal(left).compareTo(decimal(right)) == 0;
        return Objects.equals(left, right);
    }
    private boolean isAggregate(String name) {
        return switch (name) { case "sum", "count", "min", "max", "avg" -> true; default -> false; };
    }
    public static BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) return decimal;
        if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte)
            return BigDecimal.valueOf(((Number)value).longValue());
        if (!(value instanceof Number)) throw new IllegalArgumentException("Numeric operand required: " + value);
        return new BigDecimal(value.toString());
    }
    public static boolean condition(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean b) return b;
        throw new IllegalArgumentException("Boolean condition required: " + value);
    }
}
