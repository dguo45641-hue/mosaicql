package com.mosaicql.runtime;

import com.mosaicql.ast.Ast;
import com.mosaicql.type.MType;
import com.mosaicql.type.UnknownType;
import com.mosaicql.type.MTypeFactory;
import java.time.LocalDate;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.ArrayList;
import it.unimi.dsi.fastutil.objects.ObjectImmutableList;
import it.unimi.dsi.fastutil.objects.ObjectLists;

/** Built-ins plus explicitly registered scalar UDFs. Register before executing queries. */
public final class FunctionRegistry {
    private static final Set<String> AGGREGATES = Set.of("sum", "count", "min", "max", "avg");
    private static final Set<String> KEYWORDS = Set.of(
        "as", "join", "on", "where", "and", "or", "group", "by", "true", "false", "null",
        "order", "asc", "desc", "limit");
    private final Map<String, List<FunctionDefinition>> scalars = new LinkedHashMap<>();
    private final Map<String, Integer> builtinCounts = new LinkedHashMap<>();
    private long version;
    private long builtinVersion;
    private final Set<FunctionDefinition> pureDefinitions=Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    public FunctionRegistry() {
        add("coalesce", new FunctionSignature(List.of(UnknownType.INSTANCE), 1, Integer.MAX_VALUE, UnknownType.INSTANCE, false),
            types -> types.stream().reduce(MTypeFactory::common).orElse(UnknownType.INSTANCE), args -> {
            for (var value : args) if (value != null) return value;
            return null;
        });
        add("concat", new FunctionSignature(List.of(MType.STRING), 1, Integer.MAX_VALUE, MType.STRING, false), args -> {
            var result = new StringBuilder();
            for (var value : args) if (value != null) result.append(value);
            return result.toString();
        });
        add("lower", signature(MType.STRING, MType.STRING), args -> args.getFirst() == null ? null : string(args.getFirst()).toLowerCase(Locale.ROOT));
        add("upper", signature(MType.STRING, MType.STRING), args -> args.getFirst() == null ? null : string(args.getFirst()).toUpperCase(Locale.ROOT));
        add("trim", signature(MType.STRING, MType.STRING), args -> args.getFirst() == null ? null : string(args.getFirst()).strip());
        add("length", signature(UnknownType.INSTANCE, MType.NUMBER), args -> {
            var value = args.getFirst();
            return switch (value) {
                case null -> null;
                case String text -> BigDecimal.valueOf(text.codePointCount(0, text.length()));
                case List<?> list -> BigDecimal.valueOf(list.size());
                case Map<?, ?> map -> BigDecimal.valueOf(map.size());
                case Row(Map<String, Object> values) -> BigDecimal.valueOf(values.size());
                default -> throw new IllegalArgumentException("length requires a string, list, or map");
            };
        });
        add("abs", signature(MType.NUMBER, MType.NUMBER), args -> args.getFirst() == null ? null : decimal(args.getFirst()).abs());
        add("round", new FunctionSignature(List.of(MType.NUMBER), 1, 2, MType.NUMBER, false), args -> {
            if (args.contains(null)) return null;
            int scale = args.size() == 1 ? 0 : decimal(args.get(1)).intValueExact();
            return decimal(args.getFirst()).setScale(scale, RoundingMode.HALF_UP);
        });
        add("date", signature(MType.STRING, MType.DATE), args -> args.getFirst() == null ? null : LocalDate.parse((String) args.getFirst()));
        add("timestamp", signature(MType.STRING, MType.TIMESTAMP), args -> args.getFirst() == null ? null : Instant.parse((String) args.getFirst()));
        add("year", signature(MType.DATE, MType.NUMBER), args -> args.getFirst() == null ? null : BigDecimal.valueOf(((LocalDate) args.getFirst()).getYear()));
        add("month", signature(MType.DATE, MType.NUMBER), args -> args.getFirst() == null ? null : BigDecimal.valueOf(((LocalDate) args.getFirst()).getMonthValue()));
        add("day", signature(MType.DATE, MType.NUMBER), args -> args.getFirst() == null ? null : BigDecimal.valueOf(((LocalDate) args.getFirst()).getDayOfMonth()));
        add("date_add", new FunctionSignature(List.of(MType.DATE, MType.NUMBER), 2, 2, MType.DATE, false), args -> {
            if (args.get(0) == null || args.get(1) == null) return null;
            return ((LocalDate) args.get(0)).plusDays(decimal(args.get(1)).longValueExact());
        });
        add("date_diff", new FunctionSignature(List.of(MType.DATE, MType.DATE), 2, 2, MType.NUMBER, false), args -> {
            if (args.get(0) == null || args.get(1) == null) return null;
            return BigDecimal.valueOf(ChronoUnit.DAYS.between((LocalDate) args.get(1), (LocalDate) args.get(0)));
        });
        add("format_date", new FunctionSignature(List.of(MType.DATE, MType.STRING), 2, 2, MType.STRING, false), args -> {
            if (args.get(0) == null || args.get(1) == null) return null;
            return ((LocalDate) args.get(0)).format(DateTimeFormatter.ofPattern((String) args.get(1)));
        });
        scalars.forEach((name, definitions) -> builtinCounts.put(name, definitions.size()));
        scalars.forEach((name, definitions) -> { if(!name.equals("format_date"))pureDefinitions.addAll(definitions); });
        builtinVersion = version;
    }

    public FunctionRegistry register(String name, int arity, Udf implementation) {
        if (arity < 0) throw new IllegalArgumentException("Function arity must be nonnegative");
        if (!name.matches("[a-zA-Z_][a-zA-Z_0-9]*")) {
            throw new IllegalArgumentException("Invalid function name: " + name);
        }
        String key = normalize(name);
        if (KEYWORDS.contains(key)) throw new IllegalArgumentException("Reserved keyword: " + name);
        if (AGGREGATES.contains(key) || scalars.containsKey(key)) {
            throw new IllegalArgumentException("Function already registered: " + name);
        }
        add(key, new FunctionSignature(java.util.Collections.nCopies(arity, UnknownType.INSTANCE), arity, arity, UnknownType.INSTANCE, false), Objects.requireNonNull(implementation));
        return this;
    }

    public FunctionRegistry register(String name, FunctionSignature signature, Udf implementation) {
        return register(name, signature, ignored -> signature.returnType(), implementation);
    }

    /** Caller guarantees deterministic results and no observable side effects; throwing is allowed. */
    public FunctionRegistry registerPure(String name,int arity,Udf implementation) {
        register(name,arity,implementation);markPure(name);return this;
    }
    public FunctionRegistry registerPure(String name,FunctionSignature signature,Udf implementation) {
        register(name,signature,implementation);markPure(name);return this;
    }
    private void markPure(String name) {
        pureDefinitions.add(scalars.get(normalize(name)).getLast());version++;
    }
    public boolean pureFunction(String name) {
        var definitions=scalars.get(normalize(name));
        if(definitions==null || definitions.isEmpty())return false;
        for(var definition:definitions)if(!pureDefinitions.contains(definition))return false;
        return true;
    }

    public FunctionRegistry register(String name, FunctionSignature signature,
                                     ReturnTypeInference returnTypeInference, Udf implementation) {
        String key = normalize(name);
        if (!name.matches("[a-zA-Z_][a-zA-Z_0-9]*") || KEYWORDS.contains(key) || AGGREGATES.contains(key))
            throw new IllegalArgumentException("Invalid function: " + name);
        if (scalars.getOrDefault(key, List.of()).stream().anyMatch(item -> item.signature().equals(signature)))
            throw new IllegalArgumentException("Function signature already registered: " + name);
        add(key, signature, returnTypeInference, Objects.requireNonNull(implementation));
        return this;
    }

    private void add(String name, FunctionSignature signature, Udf function) {
        add(name, signature, ignored -> signature.returnType(), function);
    }

    private void add(String name, FunctionSignature signature, ReturnTypeInference inference, Udf function) {
        scalars.computeIfAbsent(name, ignored -> new ArrayList<>())
            .add(new FunctionDefinition(signature, inference, function));
        version++;
    }

    public long version() { return version; }
    public boolean builtinsOnly() { return version == builtinVersion; }
    public boolean pureBuiltin(String name) {
        var key = normalize(name);
        if(key.equals("format_date"))return false; // depends on the process default locale
        return builtinCounts.containsKey(key) && builtinCounts.get(key) == scalars.get(key).size();
    }

    /** A snapshot of candidate implementations; no name lookup or normalization per invocation. */
    public BoundFunction bind(String name) {
        String key = normalize(name);
        return new BoundFunction(key, List.copyOf(scalars.getOrDefault(key, List.of())));
    }

    public static final class BoundFunction {
        private final String name;
        private final List<FunctionDefinition> definitions;
        private BoundFunction(String name, List<FunctionDefinition> definitions) {
            this.name = name; this.definitions = definitions;
        }
        public Object apply(List<Object> arguments) { return applyDefinitions(name, definitions, arguments); }
        public Object applyOne(Object argument) { return apply(ObjectLists.singleton(argument)); }
    }

    private static FunctionSignature signature(MType parameter, MType result) {
        return new FunctionSignature(List.of(parameter), 1, 1, result, false);
    }

    static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public boolean isAggregate(String name) {
        return AGGREGATES.contains(normalize(name));
    }

    void validate(Ast.Function function) {
        String name = normalize(function.name());
        int count = function.arguments().size();
        if (isAggregate(name)) {
            if (count != 1) throw new IllegalArgumentException(name + " requires one argument; use count(*) for row count");
        } else {
            var definitions = scalars.get(name);
            if (definitions == null) throw new UnsupportedOperationException("Unknown function: " + function.name());
            if (definitions.stream().noneMatch(item -> item.signature().accepts(count))) {
                throw new IllegalArgumentException("Invalid argument count for " + function.name() + ": " + count);
            }
        }
        if (!name.equals("count") && function.arguments().stream().anyMatch(Ast.Wildcard.class::isInstance)) {
            throw new IllegalArgumentException("* is only allowed in count(*)");
        }
    }

    public MType validateAndInfer(String name, List<MType> argumentTypes) {
        return returnType(name, argumentTypes);
    }

    public Object apply(String name, List<Object> arguments) {
        return applyDefinitions(name, scalars.getOrDefault(normalize(name), List.of()), arguments);
    }

    private static Object applyDefinitions(String name, List<FunctionDefinition> definitions, List<Object> arguments) {
        try {
            FunctionDefinition match = null;
            for (int d = 0; d < definitions.size(); d++) {
                var definition = definitions.get(d);
                if (!definition.signature().accepts(arguments.size())) continue;
                boolean compatible = true;
                for (int i = 0; i < arguments.size(); i++) {
                    var target = definition.signature().parameterType(i);
                    if (!target.isUnknown() && !MTypeFactory.canAssign(target, MTypeFactory.runtime(arguments.get(i)))) {
                        compatible = false; break;
                    }
                }
                if (!compatible) continue;
                if (match != null) throw new IllegalArgumentException("Ambiguous function: " + name + runtimeTypes(arguments));
                match = definition;
            }
            if (match == null) throw new IllegalArgumentException("No matching function: " + name + runtimeTypes(arguments));
            var readonly = arguments instanceof ObjectImmutableList<?> || arguments instanceof ObjectLists.Singleton<?>
                ? arguments : Collections.unmodifiableList(arguments);
            return match.implementation().apply(readonly);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Function " + name + " failed: " + error.getMessage(), error);
        }
    }

    public FunctionDefinition resolve(String name, List<MType> argumentTypes) {
        FunctionDefinition match = null;
        for (var item : scalars.getOrDefault(normalize(name), List.of())) {
            if (!item.signature().accepts(argumentTypes.size())) continue;
            boolean compatible = true;
            for (int i = 0; i < argumentTypes.size(); i++) {
                if (!MTypeFactory.canAssign(item.signature().parameterType(i), argumentTypes.get(i))) {
                    compatible = false; break;
                }
            }
            if (!compatible) continue;
            if (match != null) throw new IllegalArgumentException("Ambiguous function: " + name + argumentTypes);
            match = item;
        }
        if (match == null) throw new IllegalArgumentException("No matching function: " + name + argumentTypes);
        return match;
    }

    private static List<MType> runtimeTypes(List<Object> arguments) {
        var types = new ArrayList<MType>(arguments.size());
        for (var argument : arguments) types.add(MTypeFactory.runtime(argument));
        return types;
    }

    public MType returnType(String name, List<MType> argumentTypes) {
        var definition = resolve(name, argumentTypes);
        return definition.returnTypeInference().infer(List.copyOf(argumentTypes));
    }

    static BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) return decimal;
        if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte)
            return BigDecimal.valueOf(((Number)value).longValue());
        if (!(value instanceof Number)) throw new IllegalArgumentException("Numeric operand required: " + value);
        return new BigDecimal(value.toString());
    }

    private static String string(Object value) {
        if (value instanceof String text) return text;
        throw new IllegalArgumentException("String argument required: " + value);
    }
}
