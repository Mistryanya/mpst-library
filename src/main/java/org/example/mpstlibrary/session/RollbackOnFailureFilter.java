package org.example.mpstlibrary.session;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.mpstlibrary.data.TransitionPlan;
import org.example.mpstlibrary.processor.ProtocolInterpreter;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
@Slf4j
public class RollbackOnFailureFilter {

    private final WorkflowSessionService sessionService;

    private final ProtocolInterpreter interpreter;

    public ExchangeFilterFunction filter() {
        return (request, next) -> next.exchange(request)
                .flatMap(response -> {
            int status = response.statusCode().value();
            boolean isFailure = status >= 400;

            if (!isFailure) {
                // State already committed during request phase; nothing to do.
                return Mono.just(response);
            }

            boolean shouldRollback = (boolean) request.attributes()
                    .getOrDefault("mpst.rollbackOnFailure", true);
            String sessionId = (String) request.attributes().get("mpst.sessionId");
            String workflowId = (String) request.attributes().get("mpst.workflowId");

            if (!shouldRollback) {
                log.info("Failure status {} tolerated by transition", status);
                return Mono.just(response);
            }

            if (sessionId != null && workflowId != null) {
                // Workflow was active — restore the snapshot.
                log.warn("Request failed ({}), rolling back workflow {}", status, workflowId);
                try {
                    sessionService.rollbackToSnapshot(sessionId, workflowId);
                } catch (Exception e) {
                    log.error("Rollback failed", e);
                }
            } else {
                // No workflow — non-workflow transition with rollback flag.
                // We committed already, so we need to undo. But we don't have a snapshot
                // because no session was created. This is the case where we'd need
                // to re-snapshot non-workflow transitions, or accept this limitation.
                log.warn("Request failed ({}), but no snapshot available to roll back", status);
            }

            return Mono.just(response);
        });
//        return (request, next) -> next.exchange(request)
//                .flatMap(response -> {
//                    int status = response.statusCode().value();
//                    boolean isFailure = status >= 400;
//
//                    TransitionPlan plan = (TransitionPlan) request.attributes().get("mpst.plan");
//                    if (plan == null) {
//                        // Untracked request — nothing to commit or roll back
//                        return Mono.just(response);
//                    }
//
//                    boolean shouldRollback = (boolean) request.attributes()
//                            .getOrDefault("mpst.rollbackOnFailure", true);
//                    String sessionId = (String) request.attributes().get("mpst.sessionId");
//                    String workflowId = (String) request.attributes().get("mpst.workflowId");
//
//                    if (!isFailure) {
//                        // SUCCESS — commit the state update now
//                        log.info("Request succeeded ({}), committing transition to {}",
//                                status, plan.getNextState().getName());
//                        interpreter.commitTransition(plan);
//                        return Mono.just(response);
//                    }
//
//                    if (!shouldRollback) {
//                        // Failure is tolerated by the transition — commit anyway
//                        log.info("Failure status {} tolerated by transition, committing anyway", status);
//                        interpreter.commitTransition(plan);
//                        return Mono.just(response);
//                    }
//
//                    // Failure + rollback required
//                    if (sessionId != null && workflowId != null) {
//                        // Workflow case: restore snapshot
//                        log.warn("Request failed ({}), rolling back session {} workflow {}",
//                                status, sessionId, workflowId);
//                        try {
//                            sessionService.rollbackToSnapshot(sessionId, workflowId);
//                        } catch (Exception e) {
//                            log.error("Rollback failed for session {} workflow {}",
//                                    sessionId, workflowId, e);
//                        }
//                    } else {
//                        // Non-workflow case: nothing was persisted, so nothing to undo
//                        log.warn("Request failed ({}), transition not committed — no state change", status);
//                    }
//
//                    return Mono.just(response);
//                });
    }
}