package org.example.mpstlibrary.monitor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.mpstlibrary.processor.ProtocolManagerService;
import org.example.mpstlibrary.session.RollbackOnFailureFilter;
import org.example.mpstlibrary.session.WorkflowSessionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;


@Configuration
@RequiredArgsConstructor
@Slf4j
public class WebClientMonitor {

    @Autowired
    private final ProtocolManagerService managerService;

    // The name of the current service (e.g., "Service2" or "Service3")
    @Value("${spring.application.name:#{null}}")
    private String serviceName;

    private final RollbackOnFailureFilter rollbackOnFailureFilter;

    @Bean
    public WebClient monitoredWebClient() {
        return WebClient.builder()
                .filter(this::enforceProtocolTransition)
                .filter(rollbackOnFailureFilter.filter())
                .build();
    }

    /**
     * WebClient filter to enforce protocol transition before sending the request.
     * This acts as the external verification step.
     */
    private Mono<ClientResponse> enforceProtocolTransition(
            ClientRequest request,
            ExchangeFunction next) {
        return Mono.deferContextual(ctx -> {;

            String url = request.url().toString();

            // todo add sessions
            // check if session present

            // Extract Target Service (The "to" state/event)
            String fromService;
            if (serviceName == null ) {
                fromService = extractFromServiceFromUrl(url);
            } else {
                fromService = serviceName;
            }

            String[] parts = url.split("/");
            String lastPart = parts[parts.length - 1];

            String eventAction = lastPart.matches("[0-9]+")
                    ? parts[parts.length - 2]
                    : lastPart;

            if (fromService == null) {
                log.debug("URL does not contain a monitored service. Bypassing protocol check.");
                return next.exchange(request);
            }

            // Define the Event
            log.info("Attempting protocol transition for {} ---> [{}]", fromService, eventAction);

            // Call the service that handles locking, checking validity, and updating state
            // in Redis.
            String nextState = managerService.processEvent(fromService, eventAction);
            log.info("Protocol transition verified and state updated to: {}", nextState);

            ClientRequest newRequest;
            if (WorkflowSessionService.getSessionId() != null) {
                newRequest = ClientRequest.from(request)
                        .attribute("mpst.sessionId", WorkflowSessionService.getSessionId())
                        .attribute("mpst.workflowId", WorkflowSessionService.getWorkflowId())
                        .attribute("mpst.rollbackOnFailure", WorkflowSessionService.isRollbackOnFailure())
                        .build();
            } else {
                newRequest = ClientRequest.from(request)
                        .build();
            }

            return next.exchange(newRequest);
        });
    }

    /**
     * Extract service name simply by URL pattern matching
     * NOTE: This should ideally use a proper service discovery mechanism.
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