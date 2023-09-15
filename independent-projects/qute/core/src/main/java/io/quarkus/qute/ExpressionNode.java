package io.quarkus.qute;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.jboss.logging.Logger;

/**
 * This node holds a single expression such as {@code foo.bar}.
 */
class ExpressionNode implements TemplateNode {

    private static final Logger LOG = Logger.getLogger("io.quarkus.qute.nodeResolve");

    final ExpressionImpl expression;
    private final Engine engine;
    private final boolean traceLevel;
    private final boolean hasEngineResultMappers;
    private final boolean unrestrictedCompletionStages;

    ExpressionNode(ExpressionImpl expression, Engine engine) {
        this.expression = expression;
        this.engine = engine;
        this.traceLevel = LOG.isTraceEnabled();
        this.hasEngineResultMappers = !engine.getResultMappers().isEmpty();
        this.unrestrictedCompletionStages = CompletionStageSupport.UNRESTRICTED;
    }

    @Override
    public CompletionStage<ResultNode> resolve(ResolutionContext context) {
        if (traceLevel) {
            LOG.tracef("Resolve {%s} started:%s", expression.toOriginalString(), expression.getOrigin());
        }
        return context.evaluate(expression).thenCompose(this::toResultNode);
    }

    CompletionStage<ResultNode> toResultNode(Object result) {
        if (traceLevel) {
            LOG.tracef("Resolve {%s} completed:%s", expression.toOriginalString(), expression.getOrigin());
        }
        if (result instanceof ResultNode) {
            return CompletedStage.of((ResultNode) result);
        } else if (result instanceof CompletableFuture) {
            return (CompletableFuture<ResultNode>) ((CompletionStage<?>) result).thenCompose(this::toResultNode);
        } else if (result instanceof CompletedStage) {
            return (CompletableFuture<ResultNode>) ((CompletionStage<?>) result).thenCompose(this::toResultNode);
        } else if (unrestrictedCompletionStages && result instanceof CompletionStage) {
            return ((CompletionStage<?>) result).thenCompose(this::toResultNode);
        } else {
            return CompletedStage.of(new SingleResultNode(result, this));
        }
    }

    public Origin getOrigin() {
        return expression.getOrigin();
    }

    @Override
    public boolean isConstant() {
        return expression.isLiteral();
    }

    Engine getEngine() {
        return engine;
    }

    public List<Expression> getExpressions() {
        return Collections.singletonList(expression);
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("ExpressionNode [expression=").append(expression).append("]");
        return builder.toString();
    }

    boolean hasEngineResultMappers() {
        return hasEngineResultMappers;
    }

    String mapResult(Object result) {
        return engine.mapResult(result, expression);
    }

}
