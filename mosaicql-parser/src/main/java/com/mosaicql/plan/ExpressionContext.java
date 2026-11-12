package com.mosaicql.plan;

import com.mosaicql.ast.Ast;
import com.mosaicql.runtime.Row;
import java.util.List;
import java.util.Map;

/** Runtime inputs required by an expression interpreter. */
public interface ExpressionContext {
    Object reference(Ast.Reference reference);
    default Object boundReference(Ast.Reference reference, int sourceSlot, int columnSlot) {
        return reference(reference);
    }
    Object nested(Ast.Query query);
    Object function(Ast.Function function, List<Object> arguments);
    Map<String, Row> rows();
}
