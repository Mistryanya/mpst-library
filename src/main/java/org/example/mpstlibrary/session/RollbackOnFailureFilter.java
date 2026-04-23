package org.example.mpstlibrary.session;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
@Slf4j
public class RollbackOnFailureFilter {

    private final WorkflowSessionService sessionService;

    public ExchangeFilterFunction filter() {
        return (request, next) -> next.exchange(request)
                .flatMap(response -> {
                    int status = response.statusCode().value();
                    boolean isFailure = status >= 400;

                    if (!isFailure) {
                        return Mono.just(response);
                    }

                    boolean shouldRollback = (boolean) request.attributes()
                            .getOrDefault("mpst.rollbackOnFailure", true);
                    String sessionId = (String) request.attributes().get("mpst.sessionId");
                    String workflowId = (String) request.attributes().get("mpst.workflowId");

                    // Not in a workflow, or transition opted out — return response as-is
                    if (!shouldRollback || sessionId == null || workflowId == null) {
                        log.debug("Failure status {} but no rollback: shouldRollback={}, sessionId={}, workflowId={}",
                                status, shouldRollback, sessionId, workflowId);
                        return Mono.just(response);
                    }

                    log.warn("Request failed with status {}, rolling back session {} workflow {}",
                            status, sessionId, workflowId);

                    try {
                        sessionService.rollbackToSnapshot(sessionId, workflowId);
                    } catch (Exception e) {
                        // Rollback itself failed — log but don't swallow the original error
                        log.error("Rollback failed for session {} workflow {}", sessionId, workflowId, e);
                    }

                    // Return the original response unchanged — the caller still sees the error body
                    return Mono.just(response);
                });
    }
}