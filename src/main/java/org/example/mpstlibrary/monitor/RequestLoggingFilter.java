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

    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws ServletException, IOException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String uri = httpRequest.getRequestURI();
        log.info("Incoming request: {}", uri);

//        String callingService = httpRequest.getHeader("X-Calling-Service");
//        if (callingService == null) {
//            log.info("External traffic, no protocol enforcement");
//            // External traffic, no protocol enforcement
//            chain.doFilter(request, response);
//            return;
//        }


        // Inter-service call: this receiver should advance the protocol on its own behalf
        String thisService = extractFromServiceFromUrl(uri);  // "authorisation-service"
        String[] parts = uri.split("/");
        String lastPart = parts[parts.length - 1];
        String eventAction = lastPart.matches("[0-9]+")
                ? parts[parts.length - 2]
                : lastPart;

        TransitionPlan plan = null;
        CurrentState preCommitState = null;   // ← snapshot for rollback

        try {
            plan = managerService.planEvent(thisService, eventAction);
            log.info("Planned inbound transition: {} + {}", thisService, eventAction);
        } catch (RuntimeException e) {
            log.debug("No protocol transition for {} + {} — passing through",
                    thisService, eventAction);
        }

        if (plan != null) {
            // Snapshot pre-commit state for non-workflow transitions only.
            // Workflow starts use the session snapshot mechanism.
            if (!plan.isStartsWorkflow()) {
                preCommitState = currentStateRepository
                        .findById(CURRENT_STATE_ID)
                        .orElse(null);
            }
            managerService.commitEvent(plan);
            log.info("Committed inbound transition for {} + {}", thisService, eventAction);
        }

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


        //////////////////// WORKS WITHOUT ROLLBACK
//
//        TransitionPlan plan = null;
//        try {
//            plan = managerService.planEvent(thisService, eventAction);
//            log.info("Planned inbound transition: {} + {}", thisService, eventAction);
//        } catch (RuntimeException e) {
//            log.debug("No protocol transition for {} + {} at current state — passing through",
//                    thisService, eventAction);
//        }
//
//        // COMMIT HERE — before the chain runs
//        if (plan != null) {
//            managerService.commitEvent(plan);
//            log.info("Committed inbound transition for {} + {}", thisService, eventAction);
//        }
//
//        chain.doFilter(request, response);

        //////////////// works with rollback but not normally
//
//        TransitionPlan plan;
//        try {
//            plan = managerService.planEvent(thisService, eventAction);
//            log.info("Planned inbound transition: {} + {}", thisService, eventAction);
//        } catch (RuntimeException e) {
//            log.debug("No protocol transition for {} + {} at current state — passing through", thisService, eventAction);
//                    httpResponse.sendError(SC_BAD_REQUEST, e.getMessage());
//            return;
//        }
//        if (plan != null) {
//            int status = httpResponse.getStatus();
//            if (status < 400) {
//                managerService.commitEvent(plan);
//                log.info("Committed inbound transition for {} + {}", thisService, eventAction);
//            } else if (!plan.getTransition().isRollbackOnFailure()) {
//                managerService.commitEvent(plan);
//            }
//
//            chain.doFilter(request, response);
//
//            if (httpResponse.getStatus() < 400) {
//                managerService.commitEvent(plan);
//            }
//        }
    }

//    @Override
//    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
//            throws IOException, ServletException {
//
//        HttpServletRequest httpRequest = (HttpServletRequest) request;
//        log.info("Incoming request: {}", httpRequest.getRequestURI());
//
//        // The protocol state machine is enforced by WebClientMonitor on outgoing calls.
//        // Inbound servlet traffic doesn't drive protocol transitions directly — it's
//        // either external entry, or inter-service traffic whose caller already committed.
//        chain.doFilter(request, response);
//    }

//    @Override
//    public void doFilter(
//            ServletRequest request,
//            ServletResponse response,
//            FilterChain chain
//    ) throws IOException, ServletException {
//
//        HttpServletRequest httpRequest = (HttpServletRequest) request;
//        HttpServletResponse httpResponse = (HttpServletResponse) response;
//
//        // Identify the "calling service" and the method name
//        // push through into protocol
//        // make request
//
//        log.info("Incoming External request:");
//        String uri = httpRequest.getRequestURI();
//
//        log.info("URI: " + uri);
//        String fromService = extractFromServiceFromUrl(uri);
//
//        String[] parts = uri.split("/");
//        String lastPart = parts[parts.length - 1];
//
//        String eventAction = lastPart.matches("[0-9]+")
//                ? parts[parts.length - 2]
//                : lastPart;
//
//        log.info("Attempting protocol transition for {} ---> [{}]", fromService,eventAction);
//
//        // 1. PLAN — validate the transition, don't persist yet
//        TransitionPlan plan;
//        try {
//            plan = managerService.planEvent(fromService, eventAction);
//        } catch (RuntimeException e) {
//            log.warn("Invalid protocol transition for {} -> {}: {}",
//                    fromService, eventAction, e.getMessage());
//            httpResponse.sendError(HttpServletResponse.SC_BAD_REQUEST,
//                    "Invalid protocol transition: " + e.getMessage());
//            return;
//        }
//
//        // 2. EXECUTE the downstream handler
//        chain.doFilter(request, response);
//
//        // 3. COMMIT or skip based on response status
//        int status = httpResponse.getStatus();
//        boolean isFailure = status >= 400;
//
//        if (!isFailure) {
//            log.info("Request succeeded ({}), committing transition to {}",
//                    status, plan.getNextState().getName());
//            managerService.commitEvent(plan);
//        } else {
//            boolean shouldRollback = plan.getTransition().isRollbackOnFailure();
//            if (shouldRollback) {
//                log.warn("Request failed ({}), transition NOT committed — no state change", status);
//                // Nothing to rollback: we never committed, so there's no prior state to restore.
//                // (Unlike the WebClient workflow case, inbound requests don't create sessions
//                // here, so snapshot-based rollback doesn't apply.)
//            } else {
//                log.info("Failure status {} tolerated by transition, committing anyway", status);
//                managerService.commitEvent(plan);
//            }
//        }
//
//        chain.doFilter(request, response); // continue request
//    }

        // FOR BANKING SERVICE SPECIFICALLY
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
        return null;
    }

}
