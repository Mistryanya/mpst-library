package org.example.mpstlibrary.monitor;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.mpstlibrary.processor.ProtocolManagerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import jakarta.servlet.Filter;

@Component
@RequiredArgsConstructor
@Slf4j
public class RequestLoggingFilter implements Filter {

    @Autowired
    private final ProtocolManagerService managerService;

    @Override
    public void doFilter(
            ServletRequest request,
            ServletResponse response,
            FilterChain chain
    ) throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;

        // Identify the "calling service" and the method name
        // push through into protocol
        // make request

        log.info("Incoming External request:");
        String uri = httpRequest.getRequestURI();

        log.info("URI: " + uri);
        String fromService = extractFromServiceFromUrl(uri);

        String[] parts = uri.split("/");
        String lastPart = parts[parts.length - 1];

        String eventAction = lastPart.matches("[0-9]+")
                ? parts[parts.length - 2]
                : lastPart;

        log.info("Attempting protocol transition for {} ---> [{}]", fromService,eventAction);

        // Call the service that handles locking, checking validity, and updating state
        // in Redis.
        String nextState = managerService.processEvent(fromService, eventAction);
        log.info("Protocol transition verified and state updated to: {}", nextState);

        chain.doFilter(request, response); // continue request
    }

    // FOR BANKING SERVICE SPECIFICALLY
    private String extractFromServiceFromUrl(String url) {
        // NOTE: The extracted name MUST match the State name in your Protocol JSON
        // (e.g., "Service2")
        if (url.contains("/user"))
            return "user-service";
        if (url.contains("/auth"))
            return "authorisation-service";
        if (url.contains("/bank"))
            return "bank-service";
        if (url.contains("/payment-gateway"))
            return "bank-service";
        return null;
    }
}
