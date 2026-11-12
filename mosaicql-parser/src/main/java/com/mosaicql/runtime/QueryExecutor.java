package com.mosaicql.runtime;

import com.mosaicql.ast.Ast;
import com.mosaicql.error.MosaicQLException;
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectImmutableList;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import com.mosaicql.plan.CompiledQuery;
import com.mosaicql.plan.QueryCompiler;
import com.mosaicql.plan.DefaultExpressionInterpreter;
import com.mosaicql.plan.ExpressionContext;
import com.mosaicql.plan.ExpressionInterpreter;
import com.mosaicql.plan.TypedExpression;
import com.mosaicql.plan.AggregateExpressionContext;
import com.mosaicql.plan.ExecutablePlan;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.io.OutputStream;
import java.io.Writer;
import java.io.IOException;
import java.util.function.Consumer;

/** In-memory execution with joins, grouping, functions, and correlated nesting. */
public final class QueryExecutor {
    private record Evaluated(Row row, List<Object> sortKeys, long ordinal) {}
    private record HashJoinKey(List<ExecutablePlan.JoinEquality> equalities) {}
    @FunctionalInterface
    private interface ContextConsumer { boolean accept(Map<String, Row> context); }
    private interface OutputSink {
        void row(Row row);
        default void projected(RowLayout layout, Object[] values) { row(Row.projected(layout, values)); }
    }
    private final SourceProvider sources;
    private final FunctionRegistry functions;
    private final ExpressionInterpreter expressionInterpreter;
    private final ExecutionOptions options;
    private final ThreadLocal<ExecutionState> executionState = new ThreadLocal<>();
    private final ThreadLocal<Integer> executionDepth = new ThreadLocal<>();
    private final ThreadLocal<CompiledQuery> executionPlan = new ThreadLocal<>();
    private final ThreadLocal<ExecutablePlan> activePlan = new ThreadLocal<>();
    private final Map<CompiledQuery, ExecutablePlan> reboundPlans = new java.util.IdentityHashMap<>();

    public QueryExecutor(SourceProvider sources) {
        this(sources, new FunctionRegistry());
    }

    public QueryExecutor(SourceProvider sources, FunctionRegistry functions) {
        this(sources, functions, new DefaultExpressionInterpreter(), ExecutionOptions.DEFAULT);
    }

    public QueryExecutor(SourceProvider sources, ExecutionOptions options) {
        this(sources, new FunctionRegistry(), new DefaultExpressionInterpreter(), options);
    }

    public QueryExecutor(SourceProvider sources, FunctionRegistry functions,
                         ExpressionInterpreter expressionInterpreter) {
        this(sources, functions, expressionInterpreter, ExecutionOptions.DEFAULT);
    }

    public QueryExecutor(SourceProvider sources, FunctionRegistry functions,
                         ExecutionOptions options) {
        this(sources, functions, new DefaultExpressionInterpreter(), options);
    }

    public QueryExecutor(SourceProvider sources, FunctionRegistry functions,
                         ExpressionInterpreter expressionInterpreter, ExecutionOptions options) {
        this.sources = Objects.requireNonNull(sources);
        this.functions = Objects.requireNonNull(functions);
        this.expressionInterpreter = Objects.requireNonNull(expressionInterpreter);
        this.options = Objects.requireNonNull(options);
    }

    public Table execute(Ast.Query query) {
        return executeWithOptions(new QueryCompiler(functions).compile(query), options, false);
    }

    public Table execute(CompiledQuery query) {
        return executeWithOptions(query, options, false);
    }

    public QueryResult executeResult(Ast.Query query) {
        return executeResult(new QueryCompiler(functions).compile(query), options);
    }

    public QueryResult executeResult(Ast.Query query, ExecutionOptions options) {
        Objects.requireNonNull(options);
        var state = new ExecutionState(options);
        var previousState = executionState.get();
        executionState.set(state);
        try {
            state.check(0);
            var compiled = new QueryCompiler(functions).compile(query);
            return new QueryResult(query.shape(), execute(compiled, Map.of(), 0, true).rows());
        } finally {
            if (previousState == null) executionState.remove(); else executionState.set(previousState);
        }
    }

    public QueryResult executeResult(CompiledQuery query) {
        return executeResult(query, options);
    }

    public QueryResult executeResult(CompiledQuery query, ExecutionOptions options) {
        return new QueryResult(query.ast().shape(), executeWithOptions(query, options, true).rows());
    }

    /** Streams root output; ordering/grouping retain their required operator state. */
    public void executeTo(CompiledQuery query, OutputStream output) throws IOException { executeTo(query, output, options); }
    public void executeTo(CompiledQuery query, Writer output) throws IOException { executeTo(query, output, options); }
    public void executeTo(CompiledQuery query, OutputStream output, ExecutionOptions options) throws IOException {
        try (var generator = JsonAdapter.generator(Objects.requireNonNull(output))) { executeJson(query, generator, options); }
    }
    public void executeTo(CompiledQuery query, Writer output, ExecutionOptions options) throws IOException {
        try (var generator = JsonAdapter.generator(Objects.requireNonNull(output))) { executeJson(query, generator, options); }
    }
    private void executeJson(CompiledQuery query, tools.jackson.core.JsonGenerator generator, ExecutionOptions options) {
        var prepared=prepared(query);
        if(prepared==null || !prepared.streamSafe()) {
            JsonAdapter.writeNode(generator,executeResult(query,options).value());return;
        }
        boolean array = query.ast().shape() == Ast.ResultShape.ARRAY;
        var count = new long[1];
        if (array) generator.writeStartArray();
        executeOutput(query, options, new OutputSink() {
            public void row(Row row) { count[0]++; JsonAdapter.writeNode(generator,row); }
            public void projected(RowLayout layout, Object[] values) { count[0]++; JsonAdapter.writeProjected(generator,layout,values); }
        });
        if (array) generator.writeEndArray(); else if (count[0] == 0) generator.writeNull();
    }
    public long forEach(CompiledQuery query, Consumer<? super Row> consumer) { return forEach(query, consumer, options); }
    public long forEach(CompiledQuery query, Consumer<? super Row> consumer, ExecutionOptions options) {
        Objects.requireNonNull(consumer); var count = new long[1];
        executeOutput(query, options, row -> { consumer.accept(row); count[0]++; });
        return count[0];
    }
    private void executeOutput(CompiledQuery query, ExecutionOptions options, OutputSink output) {
        var previous=executionState.get(); executionState.set(new ExecutionState(Objects.requireNonNull(options)));
        try { state().check(0); execute(query,Map.of(),0,true,output); }
        finally { if(previous==null)executionState.remove();else executionState.set(previous); }
    }

    private Table executeWithOptions(CompiledQuery query, ExecutionOptions options, boolean applyShape) {
        Objects.requireNonNull(options);
        var state = new ExecutionState(options);
        var previousState = executionState.get();
        executionState.set(state);
        try {
            state.check(0);
            return execute(query, Map.of(), 0, applyShape);
        } finally {
            if (previousState == null) executionState.remove(); else executionState.set(previousState);
        }
    }

    /** Executes a typed expression with the same source/function capabilities as this executor. */
    public Object evaluate(TypedExpression expression, Map<String, Row> context) {
        return evaluate(expression, context, new ArrayList<>(context.keySet()));
    }

    private Object evaluate(TypedExpression expression, Map<String, Row> context, List<String> aliases) {
        return evaluate(expression, new RowExpressionContext(context, aliases));
    }

    private Object evaluate(TypedExpression expression, ExpressionContext context) {
        var plan = activePlan.get();
        var program = plan == null ? null : plan.program(expression);
        return program == null ? expressionInterpreter.evaluate(expression, context) : program.evaluate(context);
    }

    private final class RowExpressionContext implements ExpressionContext {
        private Map<String, Row> context;
        private final List<String> aliases;
        private final Row[] localRows;

        private RowExpressionContext(Map<String, Row> context, List<String> aliases) {
            this.aliases = aliases;
            this.localRows = new Row[aliases.size()];
            bind(context);
        }

        private void bind(Map<String, Row> context) {
            this.context = context;
            for (int i=0;i<localRows.length;i++) localRows[i] = context.get(aliases.get(i));
        }

        public Object boundReference(Ast.Reference reference, int sourceSlot, int columnSlot) {
            if (sourceSlot < 0 || sourceSlot >= localRows.length) return reference(reference);
            if(reference.qualifier()!=null && !reference.qualifier().equals(aliases.get(sourceSlot)))return reference(reference);
            if(reference.qualifier()==null && aliases.size()!=1)return reference(reference);
            var row = localRows[sourceSlot];
            if (row == null) return null;
            var path = reference.path();
            Object value = row.get(path.getFirst(), columnSlot);
            for (int i=1;i<path.size();i++) {
                if (value instanceof Row nested) value = nested.values();
                if (!(value instanceof Map<?,?> map)) return null;
                value = map.get(path.get(i));
            }
            return value;
        }

        public Object reference(Ast.Reference reference) { return referenceValue(reference, context, aliases); }
        public Object nested(Ast.Query query) { return nestedValue(query, context); }
        public Object function(Ast.Function function, List<Object> arguments) {
            if (functions.isAggregate(function.name())) {
                throw new IllegalArgumentException("Aggregate requires a query group context");
            }
            return functions.apply(function.name(), arguments);
        }
        public Map<String, Row> rows() { return context; }
    }


    public Object evaluateAggregate(TypedExpression expression, Map<String, Row> context,
                                    List<Map<String, Row>> group, List<String> aliases) {
        return expressionInterpreter.evaluateAggregate(expression, new AggregateExpressionContext() {
            public Object reference(Ast.Reference reference) { return referenceValue(reference, context, aliases); }
            public Object nested(Ast.Query query) { return nestedValue(query, context); }
            public Object function(Ast.Function function, List<Object> arguments) {
                return functions.apply(function.name(), arguments);
            }
            public Map<String, Row> rows() { return context; }
            public List<Map<String, Row>> group() { return group; }
            public Object aggregate(Ast.Function function, List<Object> ignored) {
                return QueryExecutor.this.aggregate(findAggregate(expression, function), aliases, group);
            }
            public Object aggregate(TypedExpression function) {
                return QueryExecutor.this.aggregate(function, aliases, group);
            }
        });
    }

    private Object nestedValue(Ast.Query query, Map<String, Row> parent) {
        var plan = executionPlan.get();
        var nested = plan == null ? null : plan.nested(query);
        // Standalone expression evaluation has no enclosing compiled query.
        if (nested == null) nested = new QueryCompiler(functions).compileUnchecked(query);
        return new QueryResult(query.shape(), execute(nested, parent, depth() + 1, true).rows()).value();
    }

    private Table execute(CompiledQuery compiled, Map<String, Row> parent, int depth, boolean applyShape) {
        return execute(compiled,parent,depth,applyShape,null);
    }
    private Table execute(CompiledQuery compiled, Map<String, Row> parent, int depth, boolean applyShape, OutputSink output) {
        var executable=prepared(compiled);
        var previousDepth = executionDepth.get();
        var previousPlan = executionPlan.get();
        var previousExecutable = activePlan.get();
        executionDepth.set(depth);
        executionPlan.set(compiled);
        activePlan.set(executable);
        try {
            return executeInternal(compiled, parent, depth, applyShape, output);
        } finally {
            if (previousDepth == null) executionDepth.remove(); else executionDepth.set(previousDepth);
            if (previousPlan == null) executionPlan.remove(); else executionPlan.set(previousPlan);
            if (previousExecutable == null) activePlan.remove(); else activePlan.set(previousExecutable);
        }
    }

    private ExecutablePlan prepared(CompiledQuery compiled) {
        if (!(expressionInterpreter instanceof DefaultExpressionInterpreter)) return null;
        var plan = compiled.executable();
        if (plan == null) return null;
        if (plan.compatible(functions)) return plan;
        synchronized (reboundPlans) {
            var rebound = reboundPlans.get(compiled);
            if (rebound != null && rebound.compatible(functions)) return rebound;
            rebound = plan.rebind(functions);
            if (reboundPlans.size() >= 256) reboundPlans.clear();
            reboundPlans.put(compiled, rebound);
            return rebound;
        }
    }

    private Table executeInternal(CompiledQuery compiled, Map<String, Row> parent, int depth, boolean applyShape, OutputSink output) {
        var query = compiled.ast();
        state().check(depth);
        int limit = query.limit() == null ? Integer.MAX_VALUE : query.limit();
        if (applyShape && query.shape() == Ast.ResultShape.OBJECT) limit = Math.min(limit, 1);
        if (limit == 0) return new Table(List.of());
        final int resultLimit = limit;
        // These paths only accept side-effect-free, structurally proven query shapes.
        if (expressionInterpreter instanceof DefaultExpressionInterpreter && parent.isEmpty()) {
            var counted = countJoin(compiled, depth);
            if (counted != null) return deliver(counted,output);
            if(activePlan.get()!=null && activePlan.get().simpleAggregate())
                return deliver(streamAggregate(compiled,depth,resultLimit),output);
            if (activePlan.get()==null && streamableAggregation(compiled)) return deliver(streamAggregate(compiled, depth, resultLimit),output);
        }
        if(activePlan.get()!=null && activePlan.get().onlineAggregate())
            return onlineAggregate(compiled,parent,depth,resultLimit,output);
        boolean grouped = activePlan.get() == null ? new AggregationValidator(functions).isGrouped(query) : activePlan.get().grouped();
        if (activePlan.get()!=null && activePlan.get().lateTopK() && resultLimit<Integer.MAX_VALUE)
            return lateTopK(compiled,parent,depth,resultLimit,output);
        if (!grouped && query.joins().isEmpty() && query.orderBy().isEmpty() && activePlan.get()!=null)
            return fused(compiled,parent,depth,resultLimit,output);
        var aliases = new ArrayList<String>();
        aliases.add(query.source().effectiveAlias());
        var joins = new ArrayList<JoinInput>();
        for (int i = 0; i < query.joins().size(); i++) {
            var join = query.joins().get(i);
            var key = hashJoinKey(join, aliases, parent);
            aliases.add(join.source().effectiveAlias());
            joins.add(new JoinInput(join, compiled.joins().get(i), List.copyOf(aliases), key));
        }
        var rows = new ArrayList<Row>();
        var sorted = query.orderBy().isEmpty() ? null : new SortCollector(query.orderBy(), resultLimit, depth);
        var columns = projectionColumns(query);
        var contexts = grouped ? new ArrayList<Map<String, Row>>() : null;
        var projectionContext = !grouped && expressionInterpreter instanceof DefaultExpressionInterpreter
            ? new RowExpressionContext(parent, aliases) : null;
        scan(compiled, parent, depth, joins, aliases, grouped, context -> {
            if (grouped) {
                contexts.add(context);
                return true;
            }
            var row = project(compiled, context, aliases, null, depth, columns, projectionContext);
            if (query.orderBy().isEmpty()) {
                rows.add(row);
                return rows.size() < resultLimit;
            }
            sorted.add(row, sortKeys(compiled, row, context, aliases, null));
            return true;
        });
        if (grouped) {
            for (var group : group(contexts, query, compiled.groupBy(), aliases)) {
                var context = group.isEmpty() ? parent : group.getFirst();
                var row = project(compiled, context, aliases, group, depth, columns, null);
                if (query.orderBy().isEmpty()) {
                    rows.add(row);
                    if (rows.size() == resultLimit) break;
                } else {
                    sorted.add(row, sortKeys(compiled, row, context, aliases, group));
                }
            }
        }
        if (sorted != null) for (var result : sorted.finish()) rows.add(result.row());
        state().check(depth);
        return deliver(new Table(rows),output);
    }

    private Table deliver(Table table, OutputSink output) {
        if (output == null) return table;
        for (var row:table.rows()) output.row(row);
        return new Table(List.of());
    }

    private record LateCandidate(Row source, Object[] keys, long ordinal) {}
    private Table lateTopK(CompiledQuery compiled, Map<String,Row> parent, int depth, int limit, OutputSink output) {
        var plan=activePlan.get(); var aliases=plan.aliases(); var columns=projectionColumns(compiled.ast());
        var context=new Object2ObjectArrayMap<String,Row>(parent); var rowContext=new RowExpressionContext(context,aliases);
        var buffer=new Object[compiled.orderBy().size()];
        var keyPrograms=new ExecutablePlan.Program[buffer.length];
        for(int i=0;i<buffer.length;i++)keyPrograms[i]=plan.sortProgram(i);
        var heap=new PriorityQueue<LateCandidate>(Math.min(limit,128),(a,b)->compareLate(compiled,b.keys(),b.ordinal(),a.keys(),a.ordinal(),depth));
        IllegalArgumentException failure=null; long ordinal=0; var execution=state();
        for(var source:sourceRows(compiled.ast().source(),parent)) {
            execution.check(depth); context.put(aliases.getFirst(),source); rowContext.bind(context);
            if(plan.where()!=null && !condition(plan.where().evaluate(rowContext)))continue;
            // Reference-only projection cannot fail; all candidates still consume the original row budget.
            execution.row();
            for(int i=0;i<buffer.length;i++)buffer[i]=sortValue(keyPrograms[i].evaluate(rowContext));
            if(failure==null) {
                try {
                    if(heap.size()<limit)heap.add(new LateCandidate(source,buffer.clone(),ordinal));
                    else if(compareLate(compiled,buffer,ordinal,heap.peek().keys(),heap.peek().ordinal(),depth)<0) {
                        heap.poll();heap.add(new LateCandidate(source,buffer.clone(),ordinal));
                    }
                } catch(MosaicQLException error) { throw error; }
                catch(IllegalArgumentException error) { failure=error;heap.clear(); }
            }
            ordinal++;
        }
        if(failure!=null)throw failure;
        var best=new ArrayList<>(heap);best.sort((a,b)->compareLate(compiled,a.keys(),a.ordinal(),b.keys(),b.ordinal(),depth));
        var rows=output==null?new ArrayList<Row>(best.size()):null;
        for(var candidate:best) {
            context.put(aliases.getFirst(),candidate.source());rowContext.bind(context);
            var data=(Object[])projectData(compiled,context,aliases,null,depth,columns,rowContext,false);
            if(output==null)rows.add(Row.projected(plan.output(),data));else output.projected(plan.output(),data);
        }
        execution.check(depth);return new Table(rows==null?List.of():rows);
    }
    private int compareLate(CompiledQuery compiled,Object[] left,long leftOrdinal,Object[] right,long rightOrdinal,int depth) {
        state().check(depth);
        for(int i=0;i<left.length;i++) {
            int compared=compareValues(left[i],right[i]);
            if(compared!=0)return compiled.ast().orderBy().get(i).descending()?-compared:compared;
        }
        return Long.compare(leftOrdinal,rightOrdinal);
    }
    private Table fused(CompiledQuery compiled, Map<String,Row> parent, int depth, int limit, OutputSink output) {
        var plan=activePlan.get();var aliases=plan.aliases(); var columns=projectionColumns(compiled.ast());
        var context=new Object2ObjectArrayMap<String,Row>(parent);
        var expressionContext=new RowExpressionContext(context,aliases);
        var rows=output==null?new ArrayList<Row>():null; int count=0;
        var execution=state();
        for(var sourceRow:sourceRows(compiled.ast().source(),parent)) {
            execution.check(depth); context.put(aliases.getFirst(),sourceRow); expressionContext.bind(context);
            if(plan.where()!=null && !condition(plan.where().evaluate(expressionContext)))continue;
            var data=projectData(compiled,context,aliases,null,depth,columns,expressionContext,true);
            if(columns==null) {
                @SuppressWarnings("unchecked") var map=(Map<String,Object>)data; var row=new Row(map);
                if(output==null)rows.add(row);else output.row(row);
            } else if(output==null)rows.add(Row.projected(activePlan.get().output(),(Object[])data));
            else output.projected(activePlan.get().output(),(Object[])data);
            if(++count==limit)break;
        }
        execution.check(depth); return new Table(rows==null?List.of():rows);
    }

    /** Frequency join: COUNT(*) needs multiplicities, not one context per matching pair. */
    private Table countJoin(CompiledQuery compiled, int depth) {
        var query = compiled.ast();
        if (query.source().contextual() || query.joins().size() != 1 || query.where() != null
                || !query.groupBy().isEmpty() || !query.orderBy().isEmpty() || query.projection().fields().isEmpty()) return null;
        var columns = projectionColumns(query);
        if (columns == null) return null;
        for (var field : query.projection().fields()) {
            if (!(field.value() instanceof Ast.Function f) || !f.name().equalsIgnoreCase("count")
                    || f.arguments().size() != 1 || !(f.arguments().getFirst() instanceof Ast.Wildcard)) return null;
        }
        var join = query.joins().getFirst();
        var leftAlias = query.source().effectiveAlias();
        var key = hashJoinKey(join, List.of(leftAlias), Map.of());
        if(key==null || key.equalities().stream().anyMatch(k->!k.left().qualifier().equals(leftAlias))
                || equalityTerms(join.on())!=key.equalities().size())return null;
        var left = sources.get(query.source().name()).rows();
        var counts = new JoinIndexes.Counts();
        long count = 0;
        if (!left.isEmpty()) {
            var right = sources.get(join.source().name()).rows();
            boolean indexed = true;
            for (var row : right) {
                state().check(depth);
                var value=rightJoinKey(row,key,true);
                if(value==JoinIndexes.UNSUPPORTED) { indexed=false;break; }
                counts.add(value);
            }
            for (var row : left) {
                state().check(depth);
                var root=Collections.singletonMap(leftAlias,row);
                var value=leftJoinKey(root,List.of(leftAlias),key,true);
                long matches = 0;
                if (indexed && value!=JoinIndexes.UNSUPPORTED) matches = counts.get(value);
                else {
                    var context = with(Map.of(), query.source(), row);
                    var aliases = List.of(leftAlias, join.source().effectiveAlias());
                    for (var candidate : right) {
                        state().check(depth);
                        if (condition(evaluate(compiled.joins().getFirst(), with(context, join.source(), candidate), aliases))) matches++;
                    }
                }
                count += join.type() == Ast.JoinType.LEFT ? Math.max(matches, 1) : matches;
            }
        }
        var values = new Object[columns.length];
        java.util.Arrays.fill(values, count);
        state().check(depth);
        state().row();
        return new Table(List.of(Row.projected(columns, values)));
    }

    private boolean scalarJoinKey(Object value) {
        return value == null || value instanceof String || value instanceof Boolean
            || value instanceof Number && hashable(value);
    }

    private int equalityTerms(Ast.Expr expression) {
        if (expression instanceof Ast.Literal literal) return Boolean.TRUE.equals(literal.value()) ? 0 : -1;
        if (!(expression instanceof Ast.Binary binary)) return -1;
        if (binary.operator().equals("and")) {
            int left = equalityTerms(binary.left()), right = equalityTerms(binary.right());
            return left < 0 || right < 0 ? -1 : left + right;
        }
        return binary.operator().equals("=") && binary.left() instanceof Ast.Reference && binary.right() instanceof Ast.Reference ? 1 : -1;
    }

    private boolean streamableAggregation(CompiledQuery compiled) {
        var query = compiled.ast();
        if (query.source().contextual() || !query.joins().isEmpty() || query.where() != null) return false;
        boolean aggregate = false;
        for (var field : query.projection().fields()) {
            if (field.value() instanceof Ast.Reference) continue;
            if (!(field.value() instanceof Ast.Function f) || !functions.isAggregate(f.name())
                    || f.arguments().size() != 1 || !(f.arguments().getFirst() instanceof Ast.Reference
                        || f.name().equalsIgnoreCase("count") && f.arguments().getFirst() instanceof Ast.Wildcard)) return false;
            aggregate = true;
        }
        if (!aggregate) return false;
        var columns = projectionColumns(query);
        if (columns == null) return false;
        for (var key : query.groupBy()) if (!(key instanceof Ast.Reference)) return false;
        for (var order : query.orderBy()) {
            if (!(order.expression() instanceof Ast.Reference ref) || ref.qualifier() != null || ref.path().size() != 1
                    || !java.util.Arrays.asList(columns).contains(ref.name())) return false;
        }
        return true;
    }

    private Table onlineAggregate(CompiledQuery compiled,Map<String,Row> parent,int depth,int limit,OutputSink output) {
        var plan=activePlan.get();var aliases=plan.aliases();var query=compiled.ast();var columns=projectionColumns(query);
        var joins=new ArrayList<JoinInput>();var visible=new ArrayList<String>();visible.add(aliases.getFirst());
        for(int i=0;i<query.joins().size();i++) {
            var join=query.joins().get(i);var key=hashJoinKey(join,visible,parent);visible.add(join.source().effectiveAlias());
            joins.add(new JoinInput(join,compiled.joins().get(i),List.copyOf(visible),key));
        }
        var groups=new Object2ObjectLinkedOpenHashMap<Object,OnlineGroup>();
        if(query.groupBy().isEmpty())groups.put(null,new OnlineGroup(parent,plan));
        var operandContext=new RowExpressionContext(parent,aliases);var keyFailure=new RuntimeException[1];
        boolean fusedInput=joins.isEmpty();var execution=state();
        ContextConsumer accumulate=context->{
            if(keyFailure[0]!=null)return true;
            if(!fusedInput)operandContext.bind(context);Object key=null;
            try {
                if(compiled.groupBy().size()==1)key=groupKey(evaluate(compiled.groupBy().getFirst(),operandContext));
                else if(!compiled.groupBy().isEmpty()) {
                    var parts=new Object[compiled.groupBy().size()];
                    for(int i=0;i<parts.length;i++)parts[i]=groupKey(evaluate(compiled.groupBy().get(i),operandContext));
                    key=ObjectImmutableList.of(parts);
                }
            } catch(RuntimeException error) {
                if(error instanceof MosaicQLException)throw error;
                keyFailure[0]=error;return true;
            }
            var group=groups.get(key);
            if(group==null) { group=new OnlineGroup(context,plan);groups.put(key,group); }
            else if(!group.hasInput)group.first=new Object2ObjectArrayMap<>(context);
            group.hasInput=true;
            for(int i=0;i<group.operands.length;i++) {
                if((i&15)==15)execution.check(depth);
                var expression=plan.operands().get(i);
                try { group.operands[i].add(expression.ast() instanceof Ast.Wildcard?Boolean.TRUE:evaluate(expression,operandContext)); }
                catch(RuntimeException error) {
                    if(error instanceof MosaicQLException)throw error;
                    group.operands[i].operandFailure(error);
                }
            }
            return true;
        };
        if(fusedInput) {
            var context=new Object2ObjectArrayMap<String,Row>(parent);
            for(var row:sourceRows(query.source(),parent)) {
                execution.check(depth);context.put(aliases.getFirst(),row);operandContext.bind(context);
                if(plan.where()!=null && !condition(plan.where().evaluate(operandContext)))continue;
                accumulate.accept(context);
            }
        } else scan(compiled,parent,depth,joins,aliases,false,accumulate);
        if(keyFailure[0]!=null)throw keyFailure[0];
        var rows=output==null?new ArrayList<Row>():null;
        var sorted=query.orderBy().isEmpty()?null:new SortCollector(query.orderBy(),limit,depth);int emitted=0;
        for(var group:groups.values()) {
            state().check(depth);var context=group.first;var rowContext=new RowExpressionContext(context,aliases);
            AggregateExpressionContext aggregateContext=new AggregateExpressionContext() {
                public Object reference(Ast.Reference ref) { return rowContext.reference(ref); }
                public Object boundReference(Ast.Reference ref,int source,int column) { return rowContext.boundReference(ref,source,column); }
                public Object nested(Ast.Query nested) { return nestedValue(nested,context); }
                public Object function(Ast.Function function,List<Object> arguments) { return functions.apply(function.name(),arguments); }
                public Map<String,Row> rows() { return context; }
                public List<Map<String,Row>> group() { return List.of(); }
                public Object aggregate(Ast.Function function,List<Object> arguments) { return result(function); }
                public Object aggregate(TypedExpression expression) { return result((Ast.Function)expression.ast()); }
                private Object result(Ast.Function function) {
                    int slot=plan.aggregateSlot(function);
                    if(slot<0)throw new IllegalArgumentException("Aggregate is not part of the compiled expression");
                    var spec=plan.aggregates().get(slot);return group.operands[spec.operandSlot()].finish(spec.name());
                }
            };
            var data=projectData(compiled,context,aliases,null,depth,columns,null,true,aggregateContext);
            Row row=null;
            if(sorted!=null || output==null || columns==null) {
                if(columns==null) { @SuppressWarnings("unchecked") var map=(Map<String,Object>)data;row=new Row(map); }
                else row=Row.projected(plan.output(),(Object[])data);
            }
            if(sorted!=null) {
                var keys=new ArrayList<Object>(compiled.orderBy().size());
                for(int i=0;i<compiled.orderBy().size();i++) {
                    var order=query.orderBy().get(i);Object value;
                    if(order.expression() instanceof Ast.Reference ref && ref.qualifier()==null && ref.path().size()==1
                            && row.values().containsKey(ref.name()))value=row.values().get(ref.name());
                    else value=evaluate(compiled.orderBy().get(i),aggregateContext);
                    keys.add(sortValue(value));
                }
                sorted.add(row,keys);
            } else {
                if(output==null)rows.add(row);else if(columns==null)output.row(row);else output.projected(plan.output(),(Object[])data);
                if(++emitted==limit)break;
            }
        }
        if(sorted!=null)for(var value:sorted.finish()) { if(output==null)rows.add(value.row());else output.row(value.row()); }
        state().check(depth);return new Table(rows==null?List.of():rows);
    }

    private final class OnlineGroup {
        private Map<String,Row> first;
        private boolean hasInput;
        private final SharedOperand[] operands;
        OnlineGroup(Map<String,Row> first,ExecutablePlan plan) {
            this.first=new Object2ObjectArrayMap<>(first);operands=new SharedOperand[plan.operands().size()];
            for(int i=0;i<operands.length;i++)operands[i]=new SharedOperand();
            for(var spec:plan.aggregates()) {
                var operand=operands[spec.operandSlot()];
                if(spec.name().equals("sum") || spec.name().equals("avg"))operand.needsSum=true;
                if(spec.name().equals("min"))operand.needsMin=true;
                if(spec.name().equals("max"))operand.needsMax=true;
            }
        }
    }
    private final class SharedOperand {
        private boolean needsSum,needsMin,needsMax;
        private long count;
        private BigDecimal sum=BigDecimal.ZERO;
        private Object min,max;
        private RuntimeException countError,sumError,minError,maxError;
        void operandFailure(RuntimeException error) {
            if(countError==null)countError=error;if(sumError==null)sumError=error;
            if(minError==null)minError=error;if(maxError==null)maxError=error;
        }
        void add(Object value) {
            if(value==null)return;count++;
            if(needsSum && sumError==null) {
                try { sum=sum.add(decimal(value)); } catch(RuntimeException error) { sumError=error; }
            }
            if((needsMin && minError==null) || (needsMax && maxError==null)) {
                Object comparable=value;
                try {
                    if(value instanceof Number)comparable=decimal(value);
                    else if(!(value instanceof String)) {
                        if(minError==null)minError=new IllegalArgumentException("min requires numbers or strings");
                        if(maxError==null)maxError=new IllegalArgumentException("max requires numbers or strings");return;
                    }
                } catch(RuntimeException error) { if(minError==null)minError=error;if(maxError==null)maxError=error;return; }
                if(needsMin && minError==null)try { if(min==null || compareValues(min,comparable)>0)min=comparable; }catch(RuntimeException error){minError=error;}
                if(needsMax && maxError==null)try { if(max==null || compareValues(max,comparable)<0)max=comparable; }catch(RuntimeException error){maxError=error;}
            }
        }
        Object finish(String name) {
            var error=switch(name) { case "count"->countError;case "sum","avg"->sumError;case "min"->minError;default->maxError;};
            if(error!=null)throw error;
            if(name.equals("count"))return count;if(count==0)return null;
            return switch(name) {case "sum"->sum;case "avg"->sum.divide(BigDecimal.valueOf(count),MathContext.DECIMAL128);case "min"->min;default->max;};
        }
    }

    /** Per-group state is O(groups * aggregates), without retaining O(input rows) contexts. */
    private Table streamAggregate(CompiledQuery compiled, int depth, int limit) {
        var query = compiled.ast();
        var fields = query.projection().fields();
        var columns = projectionColumns(query);
        var aliases = List.of(query.source().effectiveAlias());
        var context = new Object2ObjectArrayMap<String, Row>(1);
        var groups = new Object2ObjectLinkedOpenHashMap<Object, StreamingGroup>();
        if (query.groupBy().isEmpty()) groups.put(null, new StreamingGroup(null, fields));
        for (var row : sources.get(query.source().name()).rows()) {
            state().check(depth);
            context.put(aliases.getFirst(), row);
            Object key = null;
            if (query.groupBy().size() == 1) {
                key = groupKey(referenceValue((Ast.Reference)query.groupBy().getFirst(), context, aliases));
            } else if (!query.groupBy().isEmpty()) {
                var parts = new Object[query.groupBy().size()];
                for (int i = 0; i < parts.length; i++) parts[i] = groupKey(referenceValue((Ast.Reference)query.groupBy().get(i), context, aliases));
                key = ObjectImmutableList.of(parts);
            }
            var group = groups.get(key);
            if (group == null) { group = new StreamingGroup(row, fields); groups.put(key, group); }
            if (group.first == null) group.first = row;
            for (var accumulator : group.accumulators) {
                if (accumulator == null || accumulator.error != null) continue;
                try {
                    accumulator.add(accumulator.operand instanceof Ast.Wildcard ? Boolean.TRUE
                        : referenceValue((Ast.Reference)accumulator.operand, context, aliases));
                } catch (RuntimeException error) {
                    if (error instanceof MosaicQLException) throw error;
                    // Grouping used to finish before any projection. A later key error must still win;
                    // aggregate errors are rethrown in group/field order, including LIMIT short-circuit.
                    accumulator.error = error;
                }
            }
        }
        var rows = new ArrayList<Row>();
        var sorted = query.orderBy().isEmpty() ? null : new SortCollector(query.orderBy(), limit, depth);
        for (var group : groups.values()) {
            state().check(depth);
            context.put(aliases.getFirst(), group.first);
            var values = new Object[columns.length];
            for (int i = 0; i < values.length; i++) {
                values[i] = group.accumulators[i] == null
                    ? referenceValue((Ast.Reference)fields.get(i).value(), context, aliases) : group.accumulators[i].finish();
                state().check(depth);
            }
            state().row();
            var result = Row.projected(columns, values);
            if (sorted == null) { rows.add(result); if (rows.size() == limit) break; }
            else sorted.add(result, sortKeys(compiled, result, context, aliases, null));
        }
        if (sorted != null) for (var result : sorted.finish()) rows.add(result.row());
        state().check(depth);
        return new Table(rows);
    }

    private final class StreamingGroup {
        private Row first;
        private final Accumulator[] accumulators;
        StreamingGroup(Row first, List<Ast.Field> fields) {
            this.first = first;
            accumulators = new Accumulator[fields.size()];
            for (int i = 0; i < fields.size(); i++)
                if (fields.get(i).value() instanceof Ast.Function function) accumulators[i] = new Accumulator(function);
        }
    }

    private final class Accumulator {
        private final String name;
        private final Ast.Expr operand;
        private long count;
        private BigDecimal sum = BigDecimal.ZERO;
        private Object extremum;
        private RuntimeException error;
        Accumulator(Ast.Function function) { name = FunctionRegistry.normalize(function.name()); operand = function.arguments().getFirst(); }
        void add(Object value) {
            if (value == null) return;
            count++;
            if (name.equals("sum") || name.equals("avg")) sum = sum.add(decimal(value));
            else if (name.equals("min") || name.equals("max")) {
                if (value instanceof Number) value = decimal(value);
                else if (!(value instanceof String)) throw new IllegalArgumentException(name + " requires numbers or strings");
                if (extremum == null) extremum = value;
                else {
                    int comparison = compareValues(extremum, value);
                    if (name.equals("min") ? comparison > 0 : comparison < 0) extremum = value;
                }
            }
        }
        Object finish() {
            if (error != null) throw error;
            if (name.equals("count")) return count;
            if (count == 0) return null;
            return switch (name) {
                case "sum" -> sum;
                case "avg" -> sum.divide(BigDecimal.valueOf(count), MathContext.DECIMAL128);
                default -> extremum;
            };
        }
    }

    private void scan(CompiledQuery compiled, Map<String, Row> parent, int depth,
                      List<JoinInput> joins, List<String> aliases, boolean retainContexts, ContextConsumer consumer) {
        var reusable = !retainContexts && expressionInterpreter instanceof DefaultExpressionInterpreter
            ? new Object2ObjectArrayMap<String, Row>(parent) : null;
        var predicateContext=activePlan.get()!=null && compiled.where()!=null?new RowExpressionContext(parent,aliases):null;
        for (var row : sourceRows(compiled.ast().source(), parent)) {
            state().check(depth);
            Map<String, Row> context = reusable;
            if (context == null) context = with(parent, compiled.ast().source(), row);
            else context.put(compiled.ast().source().effectiveAlias(), row);
            if (!scanJoins(compiled, context, depth, joins, 0, aliases, consumer,predicateContext)) return;
        }
        // Complete RIGHT joins in order, so their unmatched rows can match later joins.
        for (int i = 0; i < joins.size(); i++) {
            var join = joins.get(i);
            if (join.join.type() != Ast.JoinType.RIGHT) continue;
            join.loadRows(depth);
            for (int position = 0; position < join.rows.size(); position++) {
                state().check(depth);
                if (join.matched[position]) continue;
                var context = new LinkedHashMap<>(parent);
                for (var alias : join.aliases) context.put(alias, null);
                context.put(join.join.source().effectiveAlias(), join.rows.get(position));
                if (!scanJoins(compiled, context, depth, joins, i + 1, aliases, consumer,predicateContext)) return;
            }
        }
    }

    private boolean scanJoins(CompiledQuery compiled, Map<String, Row> context, int depth,
                              List<JoinInput> joins, int index, List<String> aliases, ContextConsumer consumer,RowExpressionContext predicateContext) {
        state().check(depth);
        if (index == joins.size()) {
            if(compiled.where()!=null) {
                Object value;
                if(predicateContext==null)value=evaluate(compiled.where(),context,aliases);
                else { predicateContext.bind(context);value=activePlan.get().where().evaluate(predicateContext); }
                if(!condition(value))return true;
            }
            return consumer.accept(context);
        }
        var join = joins.get(index);
        boolean matched = false;
        int position = 0;
        for (var row : join.candidates(context, depth)) {
            state().check(depth);
            var candidate = with(context, join.join.source(), row);
            if (condition(evaluate(join.condition, candidate, join.aliases))) {
                matched = true;
                if (join.matched != null) join.matched[position] = true;
                if (!scanJoins(compiled, candidate, depth, joins, index + 1, aliases, consumer,predicateContext)) return false;
            }
            position++;
        }
        if (!matched && join.join.type() == Ast.JoinType.LEFT) {
            return scanJoins(compiled, with(context, join.join.source(), null), depth, joins, index + 1, aliases, consumer,predicateContext);
        }
        return true;
    }

    private String[] projectionColumns(Ast.Query query) {
        var prepared = activePlan.get();
        if (prepared != null) return prepared.output() == null ? null : prepared.output().names();
        var fields = query.projection().fields();
        var columns = new String[fields.size()];
        var seen = new ObjectOpenHashSet<String>(fields.size());
        for (int i = 0; i < columns.length; i++) {
            var field = fields.get(i);
            if (field.value() instanceof Ast.Wildcard || !seen.add(field.name())) return null;
            columns[i] = field.name();
        }
        return columns;
    }

    private Row project(CompiledQuery compiled, Map<String, Row> context, List<String> aliases,
                        List<Map<String, Row>> group, int depth, String[] columns, RowExpressionContext rowContext) {
        var data=projectData(compiled,context,aliases,group,depth,columns,rowContext,true);
        if(columns!=null) {
            var plan=activePlan.get();
            return plan==null?Row.projected(columns,(Object[])data):Row.projected(plan.output(),(Object[])data);
        }
        @SuppressWarnings("unchecked") var map=(Map<String,Object>)data; return new Row(map);
    }
    private Object projectData(CompiledQuery compiled, Map<String, Row> context, List<String> aliases,
                        List<Map<String, Row>> group, int depth, String[] columns, RowExpressionContext rowContext, boolean budget) {
        return projectData(compiled,context,aliases,group,depth,columns,rowContext,budget,null);
    }
    private Object projectData(CompiledQuery compiled, Map<String, Row> context, List<String> aliases,
                        List<Map<String, Row>> group, int depth, String[] columns, RowExpressionContext rowContext,
                        boolean budget, ExpressionContext aggregateContext) {
        var execution=state(); execution.check(depth);var plan=activePlan.get();
        var output = columns == null ? new LinkedHashMap<String, Object>() : null;
        var values = columns == null ? null : new Object[columns.length];
        if (rowContext != null) rowContext.bind(context);
        var fields = compiled.ast().projection().fields();
        for (int i = 0; i < fields.size(); i++) {
            var field = fields.get(i);
            if (field.value() instanceof Ast.Wildcard) {
                for (var alias : aliases) {
                    var sourceRow = context.get(alias);
                    if (sourceRow == null) continue;
                    for (var entry : sourceRow.values().entrySet()) {
                        if (output.containsKey(entry.getKey())) {
                            throw new IllegalArgumentException("Duplicate output field from wildcard: " + entry.getKey());
                        }
                        output.put(entry.getKey(), entry.getValue());
                    }
                }
            } else {
                var expression = compiled.projection().get(i);
                var program=plan==null?null:plan.projection(i);
                var value = aggregateContext!=null?(program==null?evaluate(expression,aggregateContext):program.evaluate(aggregateContext))
                    :rowContext==null?projectionValue(expression,context,aliases,group)
                    :program==null?expressionInterpreter.evaluate(expression,rowContext):program.evaluate(rowContext);
                if (values == null) output.put(field.name(), value); else values[i] = value;
            }
            execution.check(depth);
        }
        if(budget)execution.row();
        return values == null ? output : values;
    }

    private List<Object> sortKeys(CompiledQuery compiled, Row row, Map<String, Row> context,
                                  List<String> aliases, List<Map<String, Row>> group) {
        var keys = new ArrayList<Object>();
        for (int i = 0; i < compiled.ast().orderBy().size(); i++) {
            var order = compiled.ast().orderBy().get(i);
            Object value;
            if (order.expression() instanceof Ast.Reference reference && reference.qualifier() == null
                    && reference.path().size() == 1 && row.values().containsKey(reference.name())) {
                value = row.values().get(reference.name());
            } else {
                var expression = compiled.orderBy().get(i);
                value = projectionValue(expression, context, aliases, group);
            }
            keys.add(sortValue(value));
        }
        return keys;
    }

    private Object projectionValue(TypedExpression expression, Map<String, Row> context,
                                   List<String> aliases, List<Map<String, Row>> group) {
        if (group == null && expressionInterpreter instanceof DefaultExpressionInterpreter) {
            return evaluate(expression, context, aliases);
        }
        return evaluateAggregate(expression, context, group == null ? List.of(context) : group, aliases);
    }

    private HashJoinKey hashJoinKey(Ast.Join join, List<String> aliases, Map<String, Row> parent) {
        if(join.type()==Ast.JoinType.RIGHT || !(expressionInterpreter instanceof DefaultExpressionInterpreter) || join.source().contextual())return null;
        var plan=activePlan.get();var compiled=executionPlan.get();
        int index=compiled==null?-1:compiled.ast().joins().indexOf(join);
        var prefix=plan!=null && index>=0?plan.joinKeys(index):ExecutablePlan.leadingEqualities(join);
        // Unknown functions may mutate later nested join keys. Preserve the legacy single-key scan.
        if(plan!=null && !plan.streamSafe() && prefix.size()>1)prefix=prefix.subList(0,1);
        var keys=new ArrayList<ExecutablePlan.JoinEquality>();
        for(var key:prefix) {
            if(!aliases.contains(key.left().qualifier()) && !parent.containsKey(key.left().qualifier()))break;
            keys.add(key);
        }
        return keys.isEmpty()?null:new HashJoinKey(List.copyOf(keys));
    }
    private Object rightJoinKey(Row row,HashJoinKey key,boolean scalarOnly) {
        if(key.equalities().size()==1) {
            var value=row.get(key.equalities().getFirst().rightPath());
            return scalarOnly && !scalarJoinKey(value)?JoinIndexes.UNSUPPORTED:hashKey(value);
        }
        var components=new Object[key.equalities().size()];
        for(int i=0;i<components.length;i++) {
            var value=row.get(key.equalities().get(i).rightPath());
            if(!scalarJoinKey(value))return JoinIndexes.UNSUPPORTED;
            components[i]=hashKey(value);if(components[i]==JoinIndexes.UNSUPPORTED)return JoinIndexes.UNSUPPORTED;
        }
        return ObjectImmutableList.of(components);
    }
    private Object leftJoinKey(Map<String,Row> context,List<String> aliases,HashJoinKey key,boolean scalarOnly) {
        if(key.equalities().size()==1) {
            var value=referenceValue(key.equalities().getFirst().left(),context,aliases);
            return scalarOnly && !scalarJoinKey(value)?JoinIndexes.UNSUPPORTED:hashKey(value);
        }
        var components=new Object[key.equalities().size()];
        for(int i=0;i<components.length;i++) {
            var value=referenceValue(key.equalities().get(i).left(),context,aliases);
            if(!scalarJoinKey(value))return JoinIndexes.UNSUPPORTED;
            components[i]=hashKey(value);if(components[i]==JoinIndexes.UNSUPPORTED)return JoinIndexes.UNSUPPORTED;
        }
        return ObjectImmutableList.of(components);
    }

    private final class JoinInput {
        private final Ast.Join join;
        private final TypedExpression condition;
        private final List<String> aliases;
        private final HashJoinKey key;
        private List<Row> rows;
        private JoinIndexes.Rows index;
        private boolean[] matched;

        private JoinInput(Ast.Join join, TypedExpression condition, List<String> aliases, HashJoinKey key) {
            this.join = join;
            this.condition = condition;
            this.aliases = aliases;
            this.key = key;
        }

        private Iterable<Row> candidates(Map<String, Row> context, int depth) {
            if (join.source().contextual()) return sourceRows(join.source(), context);
            loadRows(depth);
            if (index == null) return rows;
            var value=leftJoinKey(context,aliases,key,false);
            if(value==JoinIndexes.UNSUPPORTED)return rows;
            var found=index.get(value);return found==null?List.of():found;
        }

        private void loadRows(int depth) {
            if (rows == null) {
                rows = sources.get(join.source().name()).rows();
                state().check(depth);
                if (join.type() == Ast.JoinType.RIGHT) matched = new boolean[rows.size()];
                if (key != null) {
                    index = new JoinIndexes.Rows();
                    for (var row : rows) {
                        state().check(depth);
                        var normalized=rightJoinKey(row,key,false);
                        if(normalized==JoinIndexes.UNSUPPORTED) { index=null;break; }
                        var bucket = index.get(normalized);
                        if (bucket == null) {
                            bucket = new ArrayList<>(2);
                            index.put(normalized, bucket);
                        }
                        bucket.add(row);
                    }
                }
            }
        }
    }

    private boolean hashable(Object value) { return JoinIndexes.hashable(value); }
    private Object hashKey(Object value) { return JoinIndexes.key(value); }

    private ExecutionState state() {
        var current = executionState.get();
        return current == null ? new ExecutionState(ExecutionOptions.DEFAULT) : current;
    }

    private int depth() { return executionDepth.get() == null ? 0 : executionDepth.get(); }

    private Iterable<Row> sourceRows(Ast.Source source, Map<String, Row> context) {
        if (source.path().isEmpty()) return sources.get(source.name()).rows();
        var root = context.get(source.name());
        if (root == null) {
            if (!context.containsKey(source.name())) throw new IllegalArgumentException("Unknown source alias: " + source.name());
            return List.of();
        }
        Object value = root.get(source.path());
        if (value == null) return List.of();
        if (value instanceof Row || value instanceof Map<?, ?>) return List.of(sourceRow(value, source));
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException("Contextual source must be an object or array: " + source.name() + "." + String.join(".", source.path()));
        return () -> new Iterator<>() {
            private final Iterator<?> items = list.iterator();
            public boolean hasNext() { return items.hasNext(); }
            public Row next() {
                state().check(depth());
                return sourceRow(items.next(), source);
            }
        };
    }

    private Row sourceRow(Object value, Ast.Source source) {
        if (value instanceof Row row) return row;
        if (value instanceof RowValues values) return new Row(values);
        if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String)) {
                    throw new IllegalArgumentException("Contextual source object keys must be strings");
                }
            }
            @SuppressWarnings("unchecked") var fields = (Map<String, Object>)map;
            return new Row(fields);
        }
        throw new IllegalArgumentException("Contextual source array must contain objects: "
            + source.name() + "." + String.join(".", source.path()));
    }

    private int compareOrder(List<Ast.Order> orders, Evaluated left, Evaluated right) {
        for (int i = 0; i < orders.size(); i++) {
            int comparison = compareValues(left.sortKeys().get(i), right.sortKeys().get(i));
            if (comparison != 0) return orders.get(i).descending() ? -comparison : comparison;
        }
        return 0;
    }

    /** Keep only LIMIT best candidates, but still evaluate every projection and enforce its row budget. */
    private final class SortCollector {
        private final List<Ast.Order> orders;
        private final int limit;
        private final int depth;
        private List<Evaluated> buffered = new ArrayList<>();
        private PriorityQueue<Evaluated> heap;
        private IllegalArgumentException failure;
        private long ordinal;

        private SortCollector(List<Ast.Order> orders, int limit, int depth) {
            this.orders = orders; this.limit = limit; this.depth = depth;
        }

        private int compare(Evaluated left, Evaluated right) {
            state().check(depth);
            int order = compareOrder(orders, left, right);
            return order != 0 ? order : Long.compare(left.ordinal(), right.ordinal());
        }

        private void add(Row row, List<Object> keys) {
            if (failure != null) return;
            var candidate = new Evaluated(row, keys, ordinal++);
            if (heap == null && buffered.size() < limit) {
                buffered.add(candidate);
                return;
            }
            try {
                if (heap == null) {
                    heap = new PriorityQueue<>(Math.min(limit, 128), (left, right) -> compare(right, left));
                    heap.addAll(buffered);
                    buffered = null;
                }
                if (compare(candidate, heap.peek()) < 0) {
                    heap.poll();
                    heap.add(candidate);
                }
            } catch (MosaicQLException error) {
                throw error;
            } catch (IllegalArgumentException error) {
                // The original full sort compared keys after all projections. Preserve that error order.
                failure = error;
                heap = null;
                buffered = null;
            }
        }

        private List<Evaluated> finish() {
            if (failure != null) throw failure;
            var results = heap == null ? buffered : new ArrayList<>(heap);
            results.sort(this::compare);
            return results;
        }
    }

    private Object sortValue(Object value) {
        if(integral(value))return value;
        if (value instanceof Number) return decimal(value);
        if (value == null || value instanceof String || value instanceof Boolean) return value;
        throw new IllegalArgumentException("Sort keys must be numbers, strings, booleans, or null");
    }

    private int compareValues(Object left, Object right) {
        if (left == right) return 0;
        if (left == null) return -1;
        if (right == null) return 1;
        if(integral(left) && integral(right))return Long.compare(((Number)left).longValue(),((Number)right).longValue());
        if (left instanceof Number && right instanceof Number) return decimal(left).compareTo(decimal(right));
        if (left instanceof String a && right instanceof String b) return a.compareTo(b);
        if (left instanceof Boolean a && right instanceof Boolean b) return Boolean.compare(a, b);
        throw new IllegalArgumentException("Sort keys have incompatible types");
    }

    private boolean integral(Object value) {
        return value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte;
    }

    private List<List<Map<String, Row>>> group(List<Map<String, Row>> contexts, Ast.Query query,
                                               List<TypedExpression> keys, List<String> localAliases) {
        if (query.groupBy().isEmpty()) return List.of(contexts);
        var groups = new Object2ObjectLinkedOpenHashMap<Object, List<Map<String, Row>>>();
        for (var context : contexts) {
            state().check(depth());
            Object key;
            if (keys.size() == 1) {
                key = groupKey(evaluate(keys.getFirst(), context, localAliases));
            } else {
                var components = new Object[keys.size()];
                for (int i = 0; i < components.length; i++)
                    components[i] = groupKey(evaluate(keys.get(i), context, localAliases));
                key = ObjectImmutableList.of(components);
            }
            var group = groups.get(key);
            if (group == null) {
                group = new ArrayList<>();
                groups.put(key, group);
            }
            group.add(context);
        }
        return new ArrayList<>(groups.values());
    }

    private Object groupKey(Object value) {
        if (value instanceof Number) return decimal(value).stripTrailingZeros();
        if (value == null || value instanceof String || value instanceof Boolean) return value;
        throw new IllegalArgumentException("Group keys must be scalar values: " + value);
    }

    private Map<String, Row> with(Map<String, Row> parent, Ast.Source source, Row row) {
        if (expressionInterpreter instanceof DefaultExpressionInterpreter) {
            if (parent.isEmpty()) return Collections.singletonMap(source.effectiveAlias(), row);
            var context = new Object2ObjectArrayMap<String, Row>(parent.size() + 1);
            context.putAll(parent);
            context.put(source.effectiveAlias(), row);
            return context;
        }
        var context = new LinkedHashMap<>(parent);
        context.put(source.effectiveAlias(), row);
        return context;
    }

    private Object referenceValue(Ast.Reference reference, Map<String, Row> context, List<String> localAliases) {
        if (reference.qualifier() != null) {
            var row = context.get(reference.qualifier());
            if (row == null) {
                if (!context.containsKey(reference.qualifier())) throw new IllegalArgumentException("Unknown source alias: " + reference.qualifier());
                return null;
            }
            return row.get(reference.path());
        }
        if (localAliases.size() == 1) {
            // With one local source, ambiguity is impossible; Row.get already handles absent fields.
            var row = context.get(localAliases.getFirst());
            return row == null ? null : row.get(reference.path());
        }
        Object found = null;
        boolean present = false;
        for (var alias : localAliases) {
            var row = context.get(alias);
            if (row == null) continue;
            if (row.values().containsKey(reference.path().getFirst())) {
                if (present) throw new IllegalArgumentException("Ambiguous field: " + reference.name());
                found = row.get(reference.path());
                present = true;
            }
        }
        return found;
    }

    private BigDecimal decimal(Object value) {
        return FunctionRegistry.decimal(value);
    }

    private boolean condition(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean bool) return bool;
        throw new IllegalArgumentException("Boolean condition required: " + value);
    }

    private TypedExpression findAggregate(TypedExpression expression, Ast.Function function) {
        if (expression.ast() instanceof Ast.Function candidate
                && candidate.name().equalsIgnoreCase(function.name()) && candidate.arguments().equals(function.arguments())) {
            return expression;
        }
        for (var child : expression.children()) {
            var found = findAggregate(child, function);
            if (found != null) return found;
        }
        return null;
    }

    private Object aggregate(TypedExpression expression, List<String> aliases, List<Map<String, Row>> group) {
        if (expression == null || !(expression.ast() instanceof Ast.Function function)) {
            throw new IllegalArgumentException("Aggregate is not part of the compiled expression");
        }
        String name = FunctionRegistry.normalize(function.name());
        if (name.equals("count") && function.arguments().getFirst() instanceof Ast.Wildcard) return (long) group.size();
        long count = 0;
        BigDecimal sum = BigDecimal.ZERO;
        Object extremum = null;
        var operandContext = expressionInterpreter instanceof DefaultExpressionInterpreter
            ? new RowExpressionContext(Map.of(), aliases) : null;
        for (var rowContext : group) {
            state().check(depth());
            Object value;
            if (operandContext == null) value = evaluate(expression.children().getFirst(), rowContext, aliases);
            else {
                operandContext.bind(rowContext);
                value = evaluate(expression.children().getFirst(), operandContext);
            }
            if (value == null) continue;
            count++;
            if (name.equals("sum") || name.equals("avg")) {
                sum = sum.add(decimal(value));
            } else if (name.equals("min") || name.equals("max")) {
                if (value instanceof Number) value = decimal(value);
                else if (!(value instanceof String)) throw new IllegalArgumentException(name + " requires numbers or strings");
                if (extremum == null) extremum = value;
                else {
                    int comparison = compareValues(extremum, value);
                    if (name.equals("min") ? comparison > 0 : comparison < 0) extremum = value;
                }
            }
        }
        if (name.equals("count")) return count;
        if (count == 0) return null;
        return switch (name) {
            case "sum" -> sum;
            case "avg" -> sum.divide(BigDecimal.valueOf(count), MathContext.DECIMAL128);
            case "min", "max" -> extremum;
            default -> throw new IllegalArgumentException("Unsupported aggregate: " + name);
        };
    }
}
