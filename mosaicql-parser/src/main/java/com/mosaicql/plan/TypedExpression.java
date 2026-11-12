package com.mosaicql.plan;

import com.mosaicql.ast.Ast;
import com.mosaicql.type.MType;
import java.util.List;
import java.util.Objects;

/** Immutable typed expression IR. The AST is retained for source-level execution compatibility. */
public record TypedExpression(Ast.Expr ast, MType type, List<TypedExpression> children,
                              ExpressionBinding binding) {
    public TypedExpression {
        Objects.requireNonNull(ast);
        Objects.requireNonNull(type);
        children = List.copyOf(children);
    }
    public TypedExpression(Ast.Expr ast, MType type, List<TypedExpression> children) {
        this(ast, type, children, null);
    }
}
