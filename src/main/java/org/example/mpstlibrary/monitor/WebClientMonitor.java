package org.example.mpstlibrary.monitor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.mpstlibrary.processor.ProtocolInterpreter;
import org.example.mpstlibrary.processor.ProtocolManagerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Optional;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class WebClientMonitor {

    @Autowired
    private final ProtocolManagerService managerService;

    // The name of the current service (e.g., "Service2" or "Service3")
    @Value("${spring.application.name}")
    private String serviceName;

    // Define a constant for the header key used to pass the instance ID
    private static final String PROTOCOL_INSTANCE_ID_HEADER = "X-Protocol-Instance-Id";

    @Bean
    public WebClient monitoredWebClient() {
        return WebClient.builder()
                .filter(this::enforceProtocolTransition)
                .build();
    }

    /**
     * WebClient filter to enforce protocol transition before sending the request.
     * This acts as the external verification step.
     */
    private Mono<ClientResponse> enforceProtocolTransition(
            ClientRequest request,
            ExchangeFunction next) {
        return Mono.deferContextual(ctx -> {
            // Retrieve Instance ID from Context (populated by ProtocolContextFilter)
            String instanceId = ctx.getOrDefault(ProtocolContextFilter.PROTOCOL_INSTANCE_ID_KEY,
                    request.headers().getFirst(PROTOCOL_INSTANCE_ID_HEADER));

            if (instanceId == null) {
                // Determine behavior if ID is missing (e.g. background task?)
                // For now, generate a new one to allow ad-hoc requests to start a flow,
                // but usually this means we are missing context.
                instanceId = java.util.UUID.randomUUID().toString();
                log.info("No Protocol Instance ID found in context. Generated new ID: {}", instanceId);
            }
            final String finalInstanceId = instanceId;

            String url = request.url().toString();

            // 1. Extract Target Service (The "to" state/event)
            String targetService = extractServiceFromUrl(url);

            if (targetService == null) {
                log.debug("URL does not contain a monitored service. Bypassing protocol check.");
                return next.exchange(request);
            }

            // 3. Define the Event
            String eventAction = "call_" + targetService;

            log.info("Attempting protocol transition for Instance {} : {} --[{}]--> {}",
                    finalInstanceId, serviceName, eventAction, targetService);

            // 4. ATOMIC TRANSITION CHECK AND UPDATE
            // Call the service that handles locking, checking validity, and updating state
            // in Redis.
            String nextState = managerService.processEvent(finalInstanceId, serviceName, eventAction, targetService);
            log.info("Protocol transition verified and state updated to: {}", nextState);

            // 5. Propagate the ID to the next service
            ClientRequest newRequest = ClientRequest.from(request)
                    .header(PROTOCOL_INSTANCE_ID_HEADER, finalInstanceId)
                    .build();

            return next.exchange(newRequest);
        });
    }

    /**
     * Extract service name simply by URL pattern matching
     * NOTE: This should ideally use a proper service discovery mechanism.
     */
    private String extractServiceFromUrl(String url) {
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