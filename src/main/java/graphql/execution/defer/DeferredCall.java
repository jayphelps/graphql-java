package graphql.execution.defer;

import com.google.common.collect.ImmutableList;
import graphql.ExecutionResult;
import graphql.GraphQLError;
import graphql.Internal;
import graphql.execution.Async;
import graphql.execution.NonNullableFieldWasNullError;
import graphql.execution.NonNullableFieldWasNullException;
import graphql.execution.ResultPath;
import graphql.incremental.DeferPayload;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/**
 * Represents a deferred call (aka @defer) to get an execution result sometime after the initial query has returned.
 * <p>
 * A deferred call can encompass multiple fields. The deferred call will resolve once all sub-fields resolve.
 * <p>
 * For example, this query:
 * <pre>
 * {
 *     post {
 *         ... @defer(label: "defer-post") {
 *             text
 *             summary
 *         }
 *     }
 * }
 * </pre>
 * Will result on 1 instance of `DeferredCall`, containing calls for the 2 fields: "text" and "summary".
 */
@Internal
public class DeferredCall implements IncrementalCall<DeferPayload> {
    private final String label;
    private final ResultPath path;
    private final List<Supplier<CompletableFuture<FieldWithExecutionResult>>> calls;
    private final DeferredCallContext errorSupport;

    public DeferredCall(
            String label,
            ResultPath path,
            List<Supplier<CompletableFuture<FieldWithExecutionResult>>> calls,
            DeferredCallContext deferredErrorSupport
    ) {
        this.label = label;
        this.path = path;
        this.calls = calls;
        this.errorSupport = deferredErrorSupport;
    }

    @Override
    public CompletableFuture<DeferPayload> invoke() {
        Async.CombinedBuilder<FieldWithExecutionResult> futures = Async.ofExpectedSize(calls.size());

        calls.forEach(call -> futures.add(call.get()));

        return futures.await()
                .thenApply(this::transformToDeferredPayload)
                .handle(this::handleNonNullableFieldError);
    }

    /**
     * Non-nullable errors need special treatment.
     * When they happen, all the sibling fields will be ignored in the result. So as soon as one of the field calls
     * throw this error, we can ignore the {@link ExecutionResult} from all the fields associated with this {@link DeferredCall}
     * and build a special {@link DeferPayload} that captures the details of the error.
     */
    private DeferPayload handleNonNullableFieldError(DeferPayload result, Throwable throwable) {
        if (throwable != null) {
            Throwable cause = throwable.getCause();
            if (cause instanceof NonNullableFieldWasNullException) {
                GraphQLError error = new NonNullableFieldWasNullError((NonNullableFieldWasNullException) cause);
                return DeferPayload.newDeferredItem()
                        .errors(Collections.singletonList(error))
                        .label(label)
                        .path(path)
                        .build();
            }
            if (cause instanceof CompletionException) {
                throw (CompletionException) cause;
            }
            throw new CompletionException(cause);
        }
        return result;
    }

    private DeferPayload transformToDeferredPayload(List<FieldWithExecutionResult> fieldWithExecutionResults) {
        List<GraphQLError> errorsEncountered = errorSupport.getErrors();

        Map<String, Object> dataMap = new HashMap<>();

        ImmutableList.Builder<GraphQLError> errorsBuilder = ImmutableList.builder();

        fieldWithExecutionResults.forEach(entry -> {
            dataMap.put(entry.fieldName, entry.executionResult.getData());
            errorsBuilder.addAll(entry.executionResult.getErrors());
        });

        return DeferPayload.newDeferredItem()
                .errors(errorsEncountered)
                .path(path)
                .label(label)
                .data(dataMap)
                .build();
    }

    public static class FieldWithExecutionResult {
        private final String fieldName;
        private final ExecutionResult executionResult;

        public FieldWithExecutionResult(String fieldName, ExecutionResult executionResult) {
            this.fieldName = fieldName;
            this.executionResult = executionResult;
        }
    }
}
