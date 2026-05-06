package org.example.mpstlibrary.monitor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.mpstlibrary.data.CurrentState;
import org.example.mpstlibrary.data.Session;
import org.example.mpstlibrary.data.TransitionPlan;
import org.example.mpstlibrary.exception.EndOfProtocolException;
import org.example.mpstlibrary.exception.InvalidTransitionException;
import org.example.mpstlibrary.exception.StateMismatchException;
import org.example.mpstlibrary.processor.ProtocolInterpreter;
import org.example.mpstlibrary.processor.ProtocolManagerService;
import org.example.mpstlibrary.repo.CurrentStateRepository;
import org.example.mpstlibrary.repo.CurrentWorkflowRepository;
import org.example.mpstlibrary.session.RollbackOnFailureFilter;
import org.example.mpstlibrary.session.WorkflowSessionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.redis.util.RedisLockRegistry;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;


@Configuration
@RequiredArgsConstructor
@Slf4j
public class WebClientMonitor {

//    @Autowired
//    private final ProtocolManagerService managerService;

    // The name of the current service (e.g., "Service2" or "Service3")
    @Value("${spring.application.name:#{null}}")
    private String serviceName;

    private final RollbackOnFailureFilter rollbackOnFailureFilter;

    private final ProtocolInterpreter interpreter;
    private final WorkflowSessionService sessionService;
    private final CurrentWorkflowRepository currentWorkflowRepository;

    @Autowired
    private final ProtocolManagerService managerService;

    private final RedisLockRegistry lockRegistry;
    private final CurrentStateRepository currentStateRepository;


    @Bean
    public WebClient monitoredWebClient() {
        return WebClient.builder()
                .filter(this::enforceProtocolTransition)
                .filter(rollbackOnFailureFilter.filter())
                .defaultHeader("X-Calling-Service", serviceName)
                .build();
    }

    /**
     * WebClient filter to enforce protocol transition before sending the request.
     * This acts as the external verification step.
     */
    private Mono<ClientResponse> enforceProtocolTransition(
            ClientRequest request, ExchangeFunction next) {

        String url = request.url().toString();

        String fromService = (serviceName == null)
                ? extractFromServiceFromUrl(url)
                : serviceName;

        String path = request.url().getPath();
        log.info("path : {}", path);
        log.info("url : {}", url);

        String[] parts = path.split("/");
        String lastPart = parts[parts.length - 1];
        String eventAction = lastPart.matches("[0-9]+")
                ? parts[parts.length - 2]
                : lastPart;

        if (fromService == null) {
            log.debug("URL does not contain a monitored service. Bypassing protocol check.");
            return next.exchange(request);
        }

        log.info("Planning protocol transition for {} ---> [{}]", fromService, eventAction);

        Lock lock = lockRegistry.obtain(WorkflowSessionService.STATE_LOCK_KEY);
        TransitionPlan plan = null;
        CurrentState preCommitState = null;

        boolean acquired = false;
        try {
            acquired = lock.tryLock(WorkflowSessionService.LOCK_WAIT_SECONDS,
                    TimeUnit.SECONDS);
            if (!acquired) {
                return Mono.error(new IllegalStateException("Could not " +
                        "acquire protocol lock"));
            }

            // Try to plan; if no valid transition, fall through to send the request
            // without protocol enforcement.
            try {
                plan = managerService.planEvent(fromService, eventAction);
            } catch (RuntimeException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;

                if (cause instanceof EndOfProtocolException ||
                        cause instanceof StateMismatchException ||
                        cause instanceof InvalidTransitionException) {
                    log.warn("Protocol violation blocked: {}", cause.getMessage());
                    return Mono.error(cause);
                }

                log.debug("No protocol transition for {} + {} at current state — passing through",
                        fromService, eventAction);
            }

            if (plan != null) {
                // Snapshot pre-commit state for non-workflow transitions only.
                // Workflow starts use the session snapshot mechanism.
                if (!plan.isStartsWorkflow()) {
                    preCommitState = currentStateRepository
                            .findById(ProtocolInterpreter.CURRENT_STATE_ID)
                            .orElse(null);
                }

                if (plan.isStartsWorkflow()) {
                    Session session = sessionService.onWorkflowStart(
                            UUID.randomUUID().toString(),
                            plan.getTransition().getWorkflow(),
                            plan.getWorkflowToStart(),
                            plan.getTransition().isRollbackOnFailure());
                    plan.setSessionId(session.getSessionId());
                    plan.setWorkflowId(plan.getTransition().getWorkflow());
                }

                interpreter.commitTransition(plan);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Mono.error(e);
        } finally {
            if (acquired) lock.unlock();
        }

        // Build outgoing request, tagging it with whatever rollback info is relevant.
        ClientRequest.Builder builder = ClientRequest.from(request);
        if (plan != null) {
            builder.attribute("mpst.plan", plan)
                    .attribute("mpst.rollbackOnFailure", plan.getTransition().isRollbackOnFailure());
            if (plan.getSessionId() != null) {
                builder.attribute("mpst.sessionId", plan.getSessionId());
                builder.attribute("mpst.workflowId", plan.getWorkflowId());
            }
            if (preCommitState != null) {
                builder.attribute("mpst.preCommitState", preCommitState);
            }
        }

        return next.exchange(builder.build());
    }

    /**
     * Extract service name simply by URL pattern matching
     * NOTE: This is just for testing services
     * Banking services all have application.name property while running
     */
    // TODO After workflows successfully implemented -- please add service list and then check against that
    private String extractFromServiceFromUrl(String url) {
        // NOTE: The extracted name MUST match the State name in your Protocol JSON
        // (e.g., "Service2")
        if (url.contains("/service1"))
            return "Service1";
        if (url.contains("/service2"))
            return "Service2";
        if (url.contains("/service3"))
            return "Service3";
        return null;
    }
}