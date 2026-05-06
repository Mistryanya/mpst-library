package org.example.mpstlibrary.session;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.mpstlibrary.data.CurrentState;
import org.example.mpstlibrary.data.TransitionPlan;
import org.example.mpstlibrary.processor.ProtocolInterpreter;
import org.example.mpstlibrary.repo.CurrentStateRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import static org.example.mpstlibrary.processor.ProtocolInterpreter.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class RollbackOnFailureFilter {

    private final WorkflowSessionService sessionService;

    private final ProtocolInterpreter interpreter;

    @Autowired
    private CurrentStateRepository currentStateRepository;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    public ExchangeFilterFunction filter() {
        return (request, next) -> next.exchange(request)
                .publishOn(Schedulers.boundedElastic())
                .flatMap(response -> {
            int status = response.statusCode().value();
            boolean isFailure = status >= 400;

            if (!isFailure) {
                // State already committed during request phase; nothing to do.
                if (interpreter.getCurrentWorkflow() == null){
                    currentStateRepository.deleteById(PRE_COMMIT_STATE_ID);
                    stringRedisTemplate.delete("currentState:" + PRE_COMMIT_STATE_ID);

                    log.info("removed pre commit state");
                }
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
                log.info("Rolling back to pre-commit state");

                // set pre commit state

                currentStateRepository
                        .findById(PRE_COMMIT_STATE_ID).ifPresent(pre_commit_State ->
                                currentStateRepository.save(new CurrentState(pre_commit_State.getState(), CURRENT_STATE_ID)));


                currentStateRepository.deleteById(PRE_COMMIT_STATE_ID);
                stringRedisTemplate.delete("currentState:" + PRE_COMMIT_STATE_ID);

                log.info("removed pre commit state");

            }

            return Mono.just(response);
        });
    }
}