package org.example.mpstlibrary.monitor;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
//import org.example.mpstlibrary.processor.ProtocolManagerService;
import org.example.mpstlibrary.data.CurrentState;
import org.example.mpstlibrary.data.TransitionPlan;
import org.example.mpstlibrary.processor.ProtocolManagerService;
import org.example.mpstlibrary.repo.CurrentStateRepository;
import org.example.mpstlibrary.session.WorkflowSessionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import jakarta.servlet.Filter;

import static jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static org.example.mpstlibrary.processor.ProtocolInterpreter.CURRENT_STATE_ID;
import static org.example.mpstlibrary.processor.ProtocolInterpreter.PRE_COMMIT_STATE_ID;

@Component
@RequiredArgsConstructor
@Slf4j
public class RequestLoggingFilter implements Filter {

    @Autowired
    private final ProtocolManagerService managerService;

    @Autowired
    private CurrentStateRepository currentStateRepository;

    @Autowired
    private WorkflowSessionService sessionService;


    /*
    TODO | REFACTOR CODE
    1. MAKE requests set a pre commit state INSIDE the currentStateRepository NOT INCLUDING workflow states
     */

    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws ServletException, IOException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String uri = httpRequest.getRequestURI();
        log.info("Incoming request: {}", uri);

        // Inter-service call: this receiver should advance the protocol on its own behalf
        String thisService = extractFromServiceFromUrl(uri);  // "authorisation-service"
        String[] parts = uri.split("/");
        String lastPart = parts[parts.length - 1];
        String eventAction = lastPart.matches("[0-9]+")
                ? parts[parts.length - 2]
                : lastPart;

        TransitionPlan plan = null;

        CurrentState preCommitState = currentStateRepository
                .findById(PRE_COMMIT_STATE_ID)
                .orElse(null);

        if (preCommitState == null){
            log.info("preCommit state was null setting to current state");
            if (currentStateRepository.findById(CURRENT_STATE_ID).isPresent()) {
                currentStateRepository.save(new CurrentState(currentStateRepository.findById(CURRENT_STATE_ID).get().getState(), PRE_COMMIT_STATE_ID));
            }
            preCommitState = currentStateRepository
                    .findById(PRE_COMMIT_STATE_ID)
                    .orElse(null);
        }

        try {
            plan = managerService.planEvent(thisService, eventAction);
            log.info("Planned inbound transition: {} + {}", thisService, eventAction);
        } catch (RuntimeException e) {
            log.info("No protocol transition for {} + {} — passing through",
                    thisService, eventAction);
        }

        if (plan != null) {
            managerService.commitEvent(plan);
            log.info("Committed inbound transition for {} + {}", thisService, eventAction);
        }

        log.info("PreCommit state: {}", preCommitState.toString());

        chain.doFilter(request, response);

        // Rollback handling on failure
        if (plan != null && httpResponse.getStatus() >= 400
                && plan.getTransition().isRollbackOnFailure()) {

            if (plan.isStartsWorkflow() && plan.getSessionId() != null) {
                log.warn("Request failed ({}), rolling back workflow {}",
                        httpResponse.getStatus(), plan.getWorkflowId());
                try {
                    sessionService.rollbackToSnapshot(plan.getSessionId(), plan.getWorkflowId());
                } catch (Exception e) {
                    log.error("Workflow rollback failed", e);
                }
            } else if (preCommitState != null) {
                log.warn("Request failed ({}), restoring previous state {}",
                        httpResponse.getStatus(), preCommitState.getState().getName());
                currentStateRepository.save(preCommitState);
            }
        }
    }

    /**
     * Extract service name simply by URL pattern matching
     * NOTE: This should ideally use a proper service discovery mechanism.
     * I have considered something like a list of services inside the protocol.json that can be iterated through
     */
    // TODO After workflows successfully implemented -- please add service list and then check against that
    private String extractFromServiceFromUrl (String url){
        // NOTE: The extracted name MUST match the State name in your Protocol JSON
        // (e.g., "Service2")
        if (url.contains("/user"))
            return "user-service";
        if (url.contains("/auth"))
            return "authorisation-service";
        if (url.contains("/bank"))
            return "bank-service";
        if (url.contains("/payment-gateway"))
            return "payment-gateway-service";
        if (url.contains("/service1"))
            return "Service1";
        if (url.contains("/service2"))
            return "Service2";
        if (url.contains("/service3"))
            return "Service3";
        return null;
    }

}
