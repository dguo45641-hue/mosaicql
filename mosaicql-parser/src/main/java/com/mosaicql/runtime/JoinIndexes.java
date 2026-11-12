package com.mosaicql.runtime;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.math.BigDecimal;

/** Exact numeric canonicalization and adaptive primitive indexes. Instances belong to one execution. */
final class JoinIndexes {
    static final Object UNSUPPORTED = new Object();
    static boolean hashable(Object value) {
        if(value instanceof Double n)return Double.isFinite(n);
        if(value instanceof Float n)return Float.isFinite(n);
        return !(value instanceof Number) || value instanceof BigDecimal || value instanceof java.math.BigInteger
            || value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte;
    }
    static Object key(Object value) {
        if(!hashable(value))return UNSUPPORTED;
        if(value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte)
            return ((Number)value).longValue();
        if(value instanceof Number) {
            var number=FunctionRegistry.decimal(value);
            if(number.scale()>0) {
                number=number.stripTrailingZeros();
                if(number.scale()>0)return number;
            }
            if((long)number.precision()-number.scale()>19)return number.stripTrailingZeros();
            try { return number.longValueExact(); }
            catch(ArithmeticException nonIntegralOrLarge) { return number.stripTrailingZeros(); }
        }
        return value;
    }
    static final class Rows {
        private Long2ObjectOpenHashMap<java.util.List<Row>> longs=new Long2ObjectOpenHashMap<>();
        private Object2ObjectOpenHashMap<Object,java.util.List<Row>> objects;
        private java.util.List<Row> nullRows;
        java.util.List<Row> get(Object key) {
            if(key==null)return nullRows;
            return objects!=null?objects.get(key):key instanceof Long n?longs.get(n.longValue()):null;
        }
        void put(Object key,java.util.List<Row> rows) {
            if(key==null) { nullRows=rows;return; }
            if(objects==null && key instanceof Long n) { longs.put(n.longValue(),rows);return; }
            if(objects==null) {
                objects=new Object2ObjectOpenHashMap<>(longs.size()+1);
                for(var entry:longs.long2ObjectEntrySet())objects.put(entry.getLongKey(),entry.getValue());
                longs=null;
            }
            objects.put(key,rows);
        }
    }
    static final class Counts {
        private Long2LongOpenHashMap longs=new Long2LongOpenHashMap();
        private Object2LongOpenHashMap<Object> objects;
        private long nulls;
        void add(Object key) {
            if(key==null) { nulls++;return; }
            if(objects==null && key instanceof Long n) { longs.addTo(n.longValue(),1);return; }
            if(objects==null) {
                objects=new Object2LongOpenHashMap<>(longs.size()+1);
                for(var entry:longs.long2LongEntrySet())objects.put(Long.valueOf(entry.getLongKey()),entry.getLongValue());
                longs=null;
            }
            objects.addTo(key,1);
        }
        long get(Object key) {
            if(key==null)return nulls;
            return objects!=null?objects.getLong(key):key instanceof Long n?longs.get(n.longValue()):0;
        }
    }
}
