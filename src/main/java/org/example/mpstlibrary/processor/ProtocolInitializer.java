package org.example.mpstlibrary.processor;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@RequiredArgsConstructor
@Slf4j
public class ProtocolInitializer {

    // initialise (load) protocol and set current state

    @Autowired
    private final ProtocolLoader protocolLoader;

    @Value("protocol-examples/example1.json")
    private String protocolPath;

    public final static String PROTOCOL_DEF_ID = "default";

    @PostConstruct
    public void init() {
        try {
            log.info("Loading protocol definition from: {}", protocolPath);

            // load from mpst-library path
            String path = "/protocol-examples/example1.json";
            protocolLoader.loadAndSaveProtocol(path, PROTOCOL_DEF_ID);
            log.info("Protocol 'default' loaded successfully.");

        } catch (IOException e) {
            log.error("Failed to load protocol definition.", e);
        }
    }
}
