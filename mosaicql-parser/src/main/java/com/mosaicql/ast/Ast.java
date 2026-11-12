package com.mosaicql.ast;

import java.util.List;
import java.util.Objects;

public final class Ast {
    private Ast() {}

    public record Query(Source source, List<Join> joins, Projection projection, Expr where, List<Expr> groupBy,
                        List<Order> orderBy, Integer limit, ResultShape shape) {
        public Query {
            Objects.requireNonNull(source);
            joins = List.copyOf(joins);
            Objects.requireNonNull(projection);
            groupBy = List.copyOf(groupBy);
            orderBy = List.copyOf(orderBy);
            Objects.requireNonNull(shape);
            if (limit != null && limit < 0) throw new IllegalArgumentException("limit cannot be negative");
        }
        public Query(Source source, List<Join> joins, Projection projection, Expr where, List<Expr> groupBy,
                     List<Order> orderBy, Integer limit) {
            this(source, joins, projection, where, groupBy, orderBy, limit, ResultShape.OBJECT);
        }
    }
    public enum ResultShape { OBJECT, ARRAY }
    public record Source(String name, List<String> path, String alias) {
        public Source {
            Objects.requireNonNull(name);
            path = List.copyOf(path);
        }

        public Source(String name, String alias) { this(name, List.of(), alias); }

        /** The source name is the implicit qualifier when no alias is written. */
        public String effectiveAlias() {
            return alias == null ? (path.isEmpty() ? name : path.getLast()) : alias;
        }

        public boolean contextual() { return !path.isEmpty(); }
    }
    public record Join(JoinType type, Source source, Expr on) {
        public Join(Source source, Expr on) { this(JoinType.INNER, source, on); }
        public Join {
            Objects.requireNonNull(type);
            Objects.requireNonNull(source);
            Objects.requireNonNull(on);
        }
    }
    public enum JoinType { INNER, LEFT, RIGHT }
    public record Projection(List<Field> fields) {
        public Projection {
            fields = List.copyOf(fields);
        }
    }
    public record Field(String name, Expr value) {
        public Field {
            Objects.requireNonNull(name);
            Objects.requireNonNull(value);
        }
    }
    public record Order(Expr expression, boolean descending) {
        public Order {
            Objects.requireNonNull(expression);
        }
    }
    public sealed interface Expr permits Literal, Reference, Binary, Unary, Function, NestedQuery, Wildcard, CaseWhen {}
    /** Only valid as the sole argument of count(*). */
    public record Wildcard() implements Expr {}
    public record Literal(Object value) implements Expr {}
    public record Reference(String qualifier, List<String> path) implements Expr {
        public Reference {
            path = List.copyOf(path);
            if (path.isEmpty()) {
                throw new IllegalArgumentException("Reference path cannot be empty");
            }
        }

        public String name() {
            return path.getLast();
        }
    }
    public record Binary(String operator, Expr left, Expr right) implements Expr {}
    public record Unary(String operator, Expr value) implements Expr {}
    public record Function(String name, List<Expr> arguments) implements Expr {
        public Function {
            Objects.requireNonNull(name);
            arguments = List.copyOf(arguments);
        }
    }
    public record NestedQuery(Query query) implements Expr {}
    public record CaseWhen(List<When> branches, Expr elseValue) implements Expr {
        public CaseWhen { branches = List.copyOf(branches); }
    }
    public record When(Expr condition, Expr value) {}
}
