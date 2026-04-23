package org.example.mpstlibrary.processor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.mpstlibrary.exception.InvalidTransitionException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProtocolManagerService {

    private final ProtocolInterpreter interpreter;

    public String processEvent(String executingServiceName, String eventAction) {

        try {
            return interpreter.updateCurrentState(executingServiceName, eventAction);

            } catch (InvalidTransitionException e) {
                throw new RuntimeException(e);
        }
    }


}
