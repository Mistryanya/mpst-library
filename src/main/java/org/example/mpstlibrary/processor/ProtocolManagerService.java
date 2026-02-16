package org.example.mpstlibrary.processor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.mpstlibrary.data.Protocol;
import org.example.mpstlibrary.exception.InvalidTransitionException;
import org.example.mpstlibrary.repo.CurrentStateRepository;
import org.example.mpstlibrary.repo.ProtocolRepository;
import org.springframework.stereotype.Service;


import static org.example.mpstlibrary.processor.ProtocolInitializer.PROTOCOL_DEF_ID;


@Service
@RequiredArgsConstructor
@Slf4j
public class ProtocolManagerService {

    // Dependencies injected via @RequiredArgsConstructor
    private final ProtocolRepository protocolRepository;
    private final ProtocolInterpreter interpreter;
    private final CurrentStateRepository currentStateRepository;

//    private final ProtocolStateManagementService stateManagementService;

    public String processEvent(String instanceId, String executingServiceName, String eventAction, String transitionService) {

//        if (!stateManagementService.acquireLock(instanceId)) {
//            log.warn("Could not acquire lock for instance {}. Aborting transition.", instanceId);
//            return null;
//        }

        try {
            //Fetch Protocol Definition (the static rule set)
            Protocol protocolDef = protocolRepository.findById(PROTOCOL_DEF_ID)
                    .orElseThrow(() -> new IllegalStateException("Protocol definition not found."));

            //Get Current State (from Redis)
            String currentStateName = interpreter.getCurrentStateString();
            log.info("CURRENT STATE NAME FOUND IN REQ : {}", currentStateName);
            return interpreter.updateCurrentState(executingServiceName, transitionService);

            } catch (InvalidTransitionException e) {
                throw new RuntimeException(e);
        }
    }


}
