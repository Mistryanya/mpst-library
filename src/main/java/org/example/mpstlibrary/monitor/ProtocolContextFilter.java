package org.example.mpstlibrary.monitor;

import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.util.UUID;

@Component
public class ProtocolContextFilter implements WebFilter {

    public static final String PROTOCOL_INSTANCE_ID_KEY = "PROTOCOL_INSTANCE_ID";
    public static final String HEADER_NAME = "X-Protocol-Instance-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String instanceId = exchange.getRequest().getHeaders().getFirst(HEADER_NAME);

        // If no ID is present, we might be at the start of the chain.
        // We can generate one, or leave it empty and let the business logic decide.
        // For robustness, if it's missing, we generate one so that this service
        // can initiate a protocol if it's the start state.
        final String finalInstanceId = (instanceId != null && !instanceId.isEmpty())
                ? instanceId
                : UUID.randomUUID().toString();

        return chain.filter(exchange)
                .contextWrite(Context.of(PROTOCOL_INSTANCE_ID_KEY, finalInstanceId));
    }
}
