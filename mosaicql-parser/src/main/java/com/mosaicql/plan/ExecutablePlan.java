package com.mosaicql.plan;

import com.mosaicql.ast.Ast;
import com.mosaicql.runtime.FunctionRegistry;
import com.mosaicql.runtime.RowLayout;
import com.mosaicql.type.TableDefinition;
import it.unimi.dsi.fastutil.objects.ObjectImmutableList;
import java.util.*;
import java.util.function.BinaryOperator;

/** Immutable bound programs and structural decisions. Never contains request rows or mutable operators. */
public final class ExecutablePlan {
    @FunctionalInterface public interface Program { Object evaluate(ExpressionContext context); }
    public record Aggregate(String name, Ast.Function function, TypedExpression operand, int operandSlot) {}
    public record JoinEquality(Ast.Reference left, List<String> rightPath) {}
    private final FunctionRegistry registry;
    private final long registryVersion;
    private final boolean defaultRegistry;
    private final IdentityHashMap<TypedExpression, Program> programs = new IdentityHashMap<>();
    private final List<String> aliases;
    private final Map<String, TableDefinition> schemas;
    private final RowLayout output;
    private final boolean grouped;
    private final boolean onlineAggregate;
    private final List<Aggregate> aggregates;
    private final List<TypedExpression> operands;
    private final Map<Ast.Function, Integer> aggregateSlots;
    private final CompiledQuery query;
    private final boolean lateTopK;
    private final int[] sortProjectionSlots;
    private final List<List<JoinEquality>> joinKeys;
    private final Program[] projectionPrograms;
    private final Program whereProgram;
    private final boolean streamSafe;
    private final boolean simpleAggregate;

    public ExecutablePlan(CompiledQuery query, FunctionRegistry registry, Map<String, TableDefinition> schemas) {
        this.query = query; this.registry = registry; registryVersion = registry.version();
        defaultRegistry = registry.builtinsOnly(); this.schemas = Map.copyOf(schemas);
        var names = new ArrayList<String>(); names.add(query.ast().source().effectiveAlias());
        for (var join : query.ast().joins()) names.add(join.source().effectiveAlias());
        aliases = List.copyOf(names);
        joinKeys=query.ast().joins().stream().map(ExecutablePlan::leadingEqualities).toList();
        var columns = new ArrayList<String>(); var seen = new HashSet<String>(); boolean fixed = true;
        for (var field : query.ast().projection().fields()) {
            if (field.value() instanceof Ast.Wildcard || !seen.add(field.name())) { fixed = false; break; }
            columns.add(field.name());
        }
        output = fixed ? new RowLayout(columns) : null;
        var specs = new ArrayList<Aggregate>(); var inputs = new ArrayList<TypedExpression>();
        var inputSlots = new LinkedHashMap<Ast.Expr, Integer>(); var resultSlots = new LinkedHashMap<Ast.Function, Integer>();
        for (var expression : query.expressions()) collectAggregates(expression, specs, inputs, inputSlots, resultSlots);
        aggregates = List.copyOf(specs); operands = List.copyOf(inputs); aggregateSlots = Map.copyOf(resultSlots);
        grouped = !query.ast().groupBy().isEmpty() || !aggregates.isEmpty();
        onlineAggregate = grouped && query.groupBy().stream().allMatch(e -> pure(e, false))
            && operands.stream().allMatch(e -> e.ast() instanceof Ast.Wildcard || pure(e, false))
            && (query.where() == null || pure(query.where(), false))
            && query.joins().stream().allMatch(e -> pure(e, false))
            && query.projection().stream().allMatch(e -> pure(e, true))
            && query.orderBy().stream().allMatch(e -> pure(e, true));
        streamSafe=query.expressions().stream().allMatch(e->pure(e,true))
            && operands.stream().allMatch(e->e.ast() instanceof Ast.Wildcard || pure(e,false));
        simpleAggregate=simpleAggregation(query);
        sortProjectionSlots=new int[query.orderBy().size()];
        for(int i=0;i<sortProjectionSlots.length;i++) {
            var ast=query.ast().orderBy().get(i).expression();
            sortProjectionSlots[i]=output!=null && ast instanceof Ast.Reference r && r.qualifier()==null && r.path().size()==1
                ? output.indexOf(r.name()) : -1;
        }
        lateTopK=!grouped && query.ast().joins().isEmpty() && output!=null && !query.orderBy().isEmpty()
            && query.ast().projection().fields().stream().allMatch(f->safeRead(f.value()))
            && query.ast().orderBy().stream().allMatch(o->safeRead(o.expression()));
        for (var expression : query.expressions()) compile(expression);
        projectionPrograms=query.projection().stream().map(programs::get).toArray(Program[]::new);
        whereProgram=query.where()==null?null:programs.get(query.where());
    }
    public boolean compatible(FunctionRegistry functions) {
        return registry == functions && registryVersion == functions.version()
            || defaultRegistry && functions.builtinsOnly();
    }
    public ExecutablePlan rebind(FunctionRegistry functions) { return new ExecutablePlan(query, functions, schemas); }
    public RowLayout output() { return output; }
    public List<String> aliases() { return aliases; }
    public boolean grouped() { return grouped; }
    public boolean onlineAggregate() { return onlineAggregate; }
    public boolean streamSafe() { return streamSafe; }
    public boolean simpleAggregate() { return simpleAggregate; }
    public boolean lateTopK() { return lateTopK; }
    public List<JoinEquality> joinKeys(int join) { return joinKeys.get(join); }
    public Program sortProgram(int index) {
        int projection=sortProjectionSlots[index];
        return programs.get(projection<0?query.orderBy().get(index):query.projection().get(projection));
    }
    public List<Aggregate> aggregates() { return aggregates; }
    public List<TypedExpression> operands() { return operands; }
    public int aggregateSlot(Ast.Function function) { return aggregateSlots.getOrDefault(normalized(function), -1); }
    public Program program(TypedExpression expression) { return programs.get(expression); }
    public Program projection(int field) { return projectionPrograms[field]; }
    public Program where() { return whereProgram; }
    /** Structural eligibility only; actual limits, schemas and primitive-key guards apply at execution. */
    public Map<String,Object> explain() {
        var result=new LinkedHashMap<String,Object>();
        result.put("evaluation","bound-programs");result.put("aliases",aliases);
        result.put("outputColumns",output==null?List.of():output.columns());
        result.put("lateTopKEligible",lateTopK);result.put("onlineAggregationEligible",onlineAggregate);
        result.put("incrementalJsonEligible",streamSafe);
        result.put("simpleAggregationEligible",simpleAggregate);
        result.put("aggregateCalls",aggregates.size());result.put("sharedAggregateOperands",operands.size());
        result.put("joinPrefixKeyCounts",joinKeys.stream().map(List::size).toList());
        result.put("schemaBindings",List.copyOf(schemas.keySet()));result.put("functionRegistryVersion",registryVersion);
        return Collections.unmodifiableMap(result);
    }
    public boolean pure(TypedExpression expression, boolean allowAggregate) {
        var ast = expression.ast();
        if (ast instanceof Ast.NestedQuery || ast instanceof Ast.Wildcard) return false;
        if (ast instanceof Ast.Function f) {
            if (aggregate(f.name())) return allowAggregate;
            if (!registry.pureFunction(f.name())) return false;
        }
        for (var child : expression.children()) if (!pure(child, allowAggregate)) return false;
        return true;
    }
    private void collectAggregates(TypedExpression expression, List<Aggregate> specs, List<TypedExpression> inputs,
            Map<Ast.Expr,Integer> inputSlots, Map<Ast.Function,Integer> resultSlots) {
        if (expression.ast() instanceof Ast.Function f && aggregate(f.name())) {
            var key = normalized(f);
            if (!resultSlots.containsKey(key)) {
                var operand = expression.children().getFirst();
                var slot = inputSlots.get(operand.ast());
                if (slot == null) { slot = inputs.size(); inputSlots.put(operand.ast(), slot); inputs.add(operand); }
                resultSlots.put(key, specs.size()); specs.add(new Aggregate(key.name(), key, operand, slot));
            }
            return;
        }
        for (var child : expression.children()) collectAggregates(child, specs, inputs, inputSlots, resultSlots);
    }
    private Program compile(TypedExpression expression) {
        var existing = programs.get(expression); if (existing != null) return existing;
        var ast = expression.ast(); Program result;
        if (ast instanceof Ast.Literal literal) result = context -> literal.value();
        else if (expression.binding() instanceof ExpressionBinding.Reference binding) {
            var reference = ast instanceof Ast.Reference ref && Objects.equals(ref.qualifier(), binding.qualifier())
                && ref.path().equals(binding.path()) ? ref : new Ast.Reference(binding.qualifier(), binding.path());
            int source = reference.qualifier() == null ? aliases.size() == 1 ? 0 : -1 : aliases.indexOf(reference.qualifier());
            int column = columnSlot(source, reference.path().getFirst());
            result = context -> context.boundReference(reference, source, column);
        } else if (ast instanceof Ast.NestedQuery nested) result = context -> context.nested(nested.query());
        else if (ast instanceof Ast.Function function && expression.binding() instanceof ExpressionBinding.Function binding) {
            if (aggregate(binding.normalizedName())) {
                result = context -> context instanceof AggregateExpressionContext group ? group.aggregate(expression)
                    : new DefaultExpressionInterpreter().evaluate(expression, context);
                for (var child : expression.children()) compile(child);
            } else if (binding.normalizedName().equals("coalesce")) {
                var children = expression.children().stream().map(this::compile).toArray(Program[]::new);
                result = context -> { for (var child : children) { var value = child.evaluate(context); if (value != null) return value; } return null; };
            } else {
                var target = registry.bind(binding.normalizedName());
                var children = expression.children().stream().map(this::compile).toArray(Program[]::new);
                if (children.length == 1) result = context -> target.applyOne(children[0].evaluate(context));
                else result = context -> { var arguments = new Object[children.length];
                    for (int i=0;i<arguments.length;i++) arguments[i] = children[i].evaluate(context);
                    return target.apply(ObjectImmutableList.of(arguments)); };
            }
        } else if (ast instanceof Ast.Binary binary) {
            var left = compile(expression.children().getFirst()); var right = compile(expression.children().get(1));
            if (binary.operator().equals("and")) result = context -> { var a = left.evaluate(context);
                if (Boolean.FALSE.equals(a)) return false; var b = right.evaluate(context);
                return DefaultExpressionInterpreter.condition(a) && DefaultExpressionInterpreter.condition(b); };
            else if (binary.operator().equals("or")) result = context -> { var a = left.evaluate(context);
                if (Boolean.TRUE.equals(a)) return true; var b = right.evaluate(context);
                return DefaultExpressionInterpreter.condition(a) || DefaultExpressionInterpreter.condition(b); };
            else {
                var op = expression.binding() instanceof ExpressionBinding.Operator b ? b.symbol() : binary.operator();
                BinaryOperator<Object> operation = switch (op) {
                    case "+" -> (a,b) -> DefaultExpressionInterpreter.binary("+",a,b);
                    case "-" -> (a,b) -> DefaultExpressionInterpreter.binary("-",a,b);
                    case "*" -> (a,b) -> DefaultExpressionInterpreter.binary("*",a,b);
                    case "/" -> (a,b) -> DefaultExpressionInterpreter.binary("/",a,b);
                    case "=" -> (a,b) -> DefaultExpressionInterpreter.binary("=",a,b);
                    case "!=" -> (a,b) -> DefaultExpressionInterpreter.binary("!=",a,b);
                    case "<" -> (a,b) -> DefaultExpressionInterpreter.binary("<",a,b);
                    case "<=" -> (a,b) -> DefaultExpressionInterpreter.binary("<=",a,b);
                    case ">" -> (a,b) -> DefaultExpressionInterpreter.binary(">",a,b);
                    case ">=" -> (a,b) -> DefaultExpressionInterpreter.binary(">=",a,b);
                    default -> (a,b) -> DefaultExpressionInterpreter.binary(op,a,b);
                };
                result = context -> operation.apply(left.evaluate(context), right.evaluate(context));
            }
        } else if (ast instanceof Ast.Unary unary && unary.operator().equals("-")) {
            var child = compile(expression.children().getFirst());
            result = context -> { var value=child.evaluate(context); return value==null?null:DefaultExpressionInterpreter.decimal(value).negate(); };
        } else if (ast instanceof Ast.CaseWhen conditional) {
            var children=expression.children().stream().map(this::compile).toArray(Program[]::new);
            int count=conditional.branches().size();
            result=context->{for(int i=0;i<count;i++) if(DefaultExpressionInterpreter.condition(children[2*i].evaluate(context)))
                return children[2*i+1].evaluate(context); return conditional.elseValue()==null?null:children[2*count].evaluate(context);};
        } else result = context -> new DefaultExpressionInterpreter().evaluate(expression, context);
        programs.put(expression,result); return result;
    }
    private int columnSlot(int source, String field) {
        if (source < 0) return -1;
        var alias = aliases.get(source); var schema=schemas.get(alias);
        if (schema==null) {
            String name=source==0?query.ast().source().name():query.ast().joins().get(source-1).source().name();
            schema=schemas.get(name);
        }
        if(schema==null)return -1;
        for(int i=0;i<schema.columns().size();i++)if(schema.columns().get(i).name().equals(field))return i;
        return -1;
    }
    private boolean safeRead(Ast.Expr expression) {
        return expression instanceof Ast.Literal || expression instanceof Ast.Reference r && r.path().size()==1
            && (r.qualifier()==null || r.qualifier().equals(aliases.getFirst()));
    }
    private boolean simpleAggregation(CompiledQuery query) {
        if(output==null || aggregates.isEmpty() || aggregates.size()!=operands.size()
            || query.ast().source().contextual() || !query.joins().isEmpty() || query.where()!=null)return false;
        int calls=0;
        for(var field:query.ast().projection().fields()) {
            if(field.value() instanceof Ast.Reference)continue;
            if(!(field.value() instanceof Ast.Function f) || !aggregate(f.name()) || f.arguments().size()!=1
                || !(f.arguments().getFirst() instanceof Ast.Reference || f.name().equalsIgnoreCase("count")
                    && f.arguments().getFirst() instanceof Ast.Wildcard))return false;
            calls++;
        }
        if(calls!=aggregates.size())return false; // duplicate calls/operands must use shared states
        for(var key:query.ast().groupBy())if(!(key instanceof Ast.Reference))return false;
        for(var order:query.ast().orderBy())if(!(order.expression() instanceof Ast.Reference ref) || ref.qualifier()!=null
            || ref.path().size()!=1 || output.indexOf(ref.name())<0)return false;
        return true;
    }
    public static List<JoinEquality> leadingEqualities(Ast.Join join) {
        var terms=new ArrayList<Ast.Expr>();flattenAnd(join.on(),terms);
        var result=new ArrayList<JoinEquality>();var alias=join.source().effectiveAlias();
        for(var term:terms) {
            if(term instanceof Ast.Literal literal && Boolean.TRUE.equals(literal.value()))continue;
            if(!(term instanceof Ast.Binary binary) || !binary.operator().equals("=")
                || !(binary.left() instanceof Ast.Reference a) || !(binary.right() instanceof Ast.Reference b)
                || a.qualifier()==null || b.qualifier()==null)break;
            if(a.qualifier().equals(alias)) { var swap=a;a=b;b=swap; }
            if(!b.qualifier().equals(alias) || a.qualifier().equals(alias))break;
            result.add(new JoinEquality(a,b.path()));
        }
        return List.copyOf(result);
    }
    private static void flattenAnd(Ast.Expr expr,List<Ast.Expr> terms) {
        if(expr instanceof Ast.Binary binary && binary.operator().equals("and")) {
            flattenAnd(binary.left(),terms);flattenAnd(binary.right(),terms);
        } else terms.add(expr);
    }
    private static Ast.Function normalized(Ast.Function f) { return new Ast.Function(f.name().toLowerCase(Locale.ROOT),f.arguments()); }
    private static boolean aggregate(String name) { return switch(name.toLowerCase(Locale.ROOT)) { case "sum","avg","min","max","count"->true;default->false;}; }
}
