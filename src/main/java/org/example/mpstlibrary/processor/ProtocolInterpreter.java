package org.example.mpstlibrary.processor;

import lombok.extern.slf4j.Slf4j;
import org.example.mpstlibrary.data.CurrentState;
import org.example.mpstlibrary.data.State;
import org.example.mpstlibrary.data.Transition;
import org.example.mpstlibrary.data.Protocol;
import org.example.mpstlibrary.exception.CurrentStateNotFoundException;
import org.example.mpstlibrary.exception.InvalidTransitionException;
import org.example.mpstlibrary.exception.StateMismatchException;
import org.example.mpstlibrary.repo.CurrentStateRepository;
import org.example.mpstlibrary.repo.ProtocolRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedList;

import static org.example.mpstlibrary.processor.ProtocolInitializer.PROTOCOL_DEF_ID;

@Service
@Slf4j
public class ProtocolInterpreter {

    @Autowired
    ProtocolRepository protocolRepository;

    @Autowired
    CurrentStateRepository currentStateRepository;

    public final static String CURRENT_STATE_ID = "current";


    // Load the protocol - ProtocolInitialiser
    /*
    if there is an end-- there can also be a transition for repeat states

     */

    // Validate the protocol
    public boolean validateProtocol(Protocol protocol){
        // check against the mock protocol validation
        log.info("PROTOCOL check {}", protocol.toString());

        // check states
        for (State state: protocol.getStates()){
            if (state.getName().equals("Service1") || state.getName().equals("Service2") || state.getName().equals("Service3")){
                log.info("State validated");
            } else{
                log.error("STATE INVALID: {}", state.getName());
                return false;
            }

            if (state.getName().equals("Service3")){
                if (state.getStart()){
                    log.info("Correct start");
                } else{
                    log.error("START INVALID- start should be for: {}", state.getName());
                    return false;
                }
            }
            if (state.getName().equals("Service1")){
                if (state.getEnd()){
                    log.info("Correct END");
                } else{
                    log.error("END INVALID- start should be for: {}", state.getName());
                    return false;
                }
            }
        }
        return true;
    }

    // get current state
    public State getCurrentState(){
        if (currentStateRepository.findById(CURRENT_STATE_ID).isPresent()){
            State current = currentStateRepository.findById(CURRENT_STATE_ID).get().getState();
            log.info("CURRENT STATE: {}", current);
            return current;
        } else{
            throw new CurrentStateNotFoundException("Cannot find current state: Please debug and check logs");
        }
    }

    public String getCurrentStateString(){
        return getCurrentState().name;
    }

    public boolean checkCurrentState(String stateToCheck){
        log.info("CHECKING THAT: {} = {}", stateToCheck, getCurrentStateString());
        if (stateToCheck.equals(getCurrentStateString())){
            log.info("CURRENT STATE CORRECT: {}", stateToCheck);
            return true;
        } else {
            throw new StateMismatchException("INVALID SERVICE CALL | Service calling is NOT current state in protocol");
        }
    }

    public String updateCurrentState(String currentState, String serviceCall) throws InvalidTransitionException {

        if (checkCurrentState(currentState)) {
            Transition transition = getValidTransition(serviceCall);
            log.info("DEBUG | TRANSITION: {}", transition.toString());
            log.info("ATTEMPTING TO UPDATE TO STATE: {}", transition.getTo());

            State newState = getStateByName(transition.getTo());
            CurrentState newCurrentState = new CurrentState(newState, CURRENT_STATE_ID);
            currentStateRepository.save(newCurrentState);
            log.info("SUCCESSFULLY UPDATED state: {} -> {}", currentState, newCurrentState);
            return newState.getName();
        }
        throw new InvalidTransitionException("Transition : call_" + serviceCall + " not found for state: " + currentState);
    }

    public State getStateByName(String state){
        if (protocolRepository.findById(PROTOCOL_DEF_ID).isPresent()){
            for (State compareState: protocolRepository.findById(PROTOCOL_DEF_ID).get().getStates()){
                if (compareState.getName().equals(state)){
                    return compareState;
                }
            }
        }

        throw new StateMismatchException("transition State could not be found");

    }

    // get available transitions
    public LinkedList<Transition> getAvailableTransitions(){
        State current =  getCurrentState();
        return current.getTransitions();
    }

    // get transitions for a specific state
    public LinkedList<Transition> getAvailableTransitions(State state){
        return state.getTransitions();
    }

    // get transition for currentState that HAS call_[newState]
    public Transition getValidTransition(String serviceCalled) throws InvalidTransitionException {
        LinkedList<Transition> currentStateTransitions = getAvailableTransitions();
        for (Transition checkTransition : currentStateTransitions){
            if (checkTransition.getOn().equals("call_" + serviceCalled)){
                return checkTransition;
            }
        }
        throw new InvalidTransitionException("Cannot find valid transition for ["+getCurrentStateString()+"] " +
                "-> "+ serviceCalled);
    }

    // is valid transition
    public boolean validTransition(Transition transition){
        for (Transition checkTransition : getAvailableTransitions()){
            if (checkTransition.toString().equals(transition.toString())){
                return true;
            }
        }
        return false;
    }
}