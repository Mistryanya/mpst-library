package org.example.mpstlibrary.processor;

import lombok.extern.slf4j.Slf4j;
import org.example.mpstlibrary.data.CurrentState;
import org.example.mpstlibrary.data.Protocol;
import org.example.mpstlibrary.data.State;
import org.example.mpstlibrary.exception.InvalidProtocolException;
import org.example.mpstlibrary.repo.CurrentStateRepository;
import org.example.mpstlibrary.repo.ProtocolRepository;
import org.springframework.beans.factory.annotation.Autowired;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;

@Service
@Slf4j
public class ProtocolLoader {

    @Autowired
    ProtocolRepository protocolRepository;

    @Autowired
    ProtocolInterpreter protocolInterpreter;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    CurrentStateRepository currentStateRepository;

    // method to load and save the protocol
    public void loadAndSaveProtocol(String jsonFilePath, String protocolId) throws IOException {
        // Ensure path starts with / for looking up in classpath root
        String resourcePath = jsonFilePath.startsWith("/") ? jsonFilePath : "/" + jsonFilePath;
        try (InputStream inputStream = getClass().getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IOException("Protocol resource not found in classpath: " + resourcePath);
            }

            // Deserialize JSON into Protocol
            Protocol protocol = objectMapper.readValue(inputStream, Protocol.class);
            protocol.setId(protocolId);

            log.info("Protocol loaded RAW: {}", protocol.toString());

            // validate protocol and THEN save it
            if (protocolInterpreter.validateProtocol(protocol)){
                log.info("Validated protocol");

                // Save protocol via repository
                // set current state
                for (State state: protocol.getStates()) {
                    if (state.getStart()) {
                        CurrentState startState = new CurrentState(state, "current");
                        currentStateRepository.save(startState);
                        log.info("CURRENT STATE SAVED: {}", startState);
                    }
                }
                protocolRepository.save(protocol);

            } else{
                throw new InvalidProtocolException("Invalid Protocol - check logs for more information");
            }
        }
    }

    public Protocol getProtocol(String protocolId) {
        return protocolRepository.findById(protocolId).orElse(null);
    }
}