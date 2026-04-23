package org.example.mpstlibrary.processor;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.example.mpstlibrary.data.*;
import org.example.mpstlibrary.exception.CurrentStateNotFoundException;
import org.example.mpstlibrary.exception.EndOfProtocolException;
import org.example.mpstlibrary.exception.InvalidTransitionException;
import org.example.mpstlibrary.exception.StateMismatchException;
import org.example.mpstlibrary.repo.CurrentStateRepository;
import org.example.mpstlibrary.repo.CurrentWorkflowRepository;
import org.example.mpstlibrary.repo.ProtocolRepository;
import org.example.mpstlibrary.session.WorkflowSessionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.example.mpstlibrary.processor.ProtocolInitializer.PROTOCOL_DEF_ID;

@Service
@Slf4j
public class ProtocolInterpreter {

    @Autowired
    ProtocolRepository protocolRepository;

    @Autowired
    CurrentStateRepository currentStateRepository;

    @Autowired
    CurrentWorkflowRepository currentWorkflowRepository;

    @Autowired
    WorkflowSessionService workflowSessionService;

    public final static String CURRENT_STATE_ID = "current_state";
    public final static String CURRENT_WORKFLOW_ID = "workflow";
    public final static String CURRENT_WORKFLOW_STATE_ID = "workflow_state";

    /*
    TODO create sessions
    1. create new session for workflow
    2. when request is made assign to session context ONLY IF WORKFLOW IS PRESENT
    (OR is request fails?) -- how to do this inside a monitor that intercepts requests?
    3. If request INSIDE of workflow fails then resent state back to initial state store inside session context
    to before workflow was called so that it can be called again
    3. will it accomodate multiple sessions?? and can my redis store actually do this (is this a limitation?)

     */


    // get current state
    public State getCurrentState(){
        if (getCurrentWorkflow() != null){
            if (currentStateRepository.findById(CURRENT_WORKFLOW_STATE_ID).isPresent()){
                State current = currentStateRepository.findById(CURRENT_WORKFLOW_STATE_ID).get().getState();
                log.info("CURRENT WORKFLOW STATE: {}", current);
                return current;
            } else{
                log.info("Setting workflow current state");
                return null;
            }
        } else {
            if (currentStateRepository.findById(CURRENT_STATE_ID).isPresent()) {
                State current = currentStateRepository.findById(CURRENT_STATE_ID).get().getState();
                log.info("CURRENT STATE: {}", current);
                return current;
            } else {
                throw new CurrentStateNotFoundException("Cannot find current state: Please debug and check logs");
            }
        }
    }

    // get current workflow
    public Workflow getCurrentWorkflow(){
        if (currentWorkflowRepository.findById(CURRENT_WORKFLOW_ID).isPresent()){
            Workflow currentWorkflow = currentWorkflowRepository.findById(CURRENT_WORKFLOW_ID).get().getWorkflow();
            log.info("CURRENT WORKFLOW: {}", currentWorkflow.getName());
            return currentWorkflow;
        } else{
            log.info("No Workflow saved for this transition");
            return null;
        }
    }

    private CurrentState createNewCurrentState(State state, String id){
        return new CurrentState(state, id);
    }

    private CurrentWorkflow createNewCurrentWorkflow(Workflow workflow, String id){
        return new CurrentWorkflow(workflow, id);
    }

    public LinkedList<String> getCurrentStateServices(){
        LinkedList<Transition> transitions = getCurrentState().getTransitions();
        if (transitions != null) {
            LinkedList<String> services = transitions.stream()
                    .map(Transition::getFrom)
                    .collect(Collectors.toCollection(LinkedList::new));
            return services;
        } else {
            return null;
        }

    }

    public String getCurrentStateString(){
        return getCurrentState().name;

    }


    public boolean checkCurrentStateHasService(String service) throws InvalidTransitionException {
        log.info("CHECKING THAT: [{}] is part of [{}]", service, getCurrentStateString());
        LinkedList<String> currentStateServices = getCurrentStateServices();

        if (currentStateServices != null) {
            if (currentStateServices.contains(service)) {
                log.info("Service calling is valid");
                return true;
            } else {
                throw new StateMismatchException("INVALID SERVICE CALL | Service calling [" + service + "] is NOT in current state [" + getCurrentStateString() + "] in protocol");
            }
        } else {
            throw new InvalidTransitionException("ERROR | No Transitions Found");
        }
    }

    public String updateCurrentState(String currentService, String eventAction) throws InvalidTransitionException {

        // validate transition THEN UPDATE state

        // workflow is active and we are at the start of it
        if (getCurrentWorkflow() != null && getCurrentState() == null) {
            return handleWorkflowStart(currentService, eventAction);
        }
        // workflow is active and we have reached the end of it
        else if (getCurrentWorkflow() != null && getCurrentState().getEnd()) {
            return handleWorkflowEnd(currentService, eventAction);
        }
        // throw exception if the protocol has ended to let the application know
        // TODO ask supervisor what to do after this step?
        else if (getCurrentState().getEnd()) {
            throw new EndOfProtocolException("PROTOCOL HAS ENDED - NO MORE SERVICE CALLS ACCEPTED");
        }
        // workflow is active and we are in the middle of it
        else if (getCurrentWorkflow() != null) {
            return handleWorkflowTransition(currentService, eventAction);
        }
        // no workflow, normal protocol transition
        else if (checkCurrentStateHasService(currentService)) {
            return handleProtocolTransition(currentService, eventAction);
        } else {
            throw new InvalidTransitionException("Transition : " + eventAction + " not found for state: " + currentService);
        }
    }

    private String handleWorkflowStart(String currentService, String eventAction) throws InvalidTransitionException {
        for (State state : getCurrentWorkflow().getStates()) {
            if (state.getStart()) {
                // UPDATE current state to next current state
                // UPDATE workflow state to next workflow state
                Transition transition = getValidTransition(eventAction, currentService);
                log.info("DEBUG | TRANSITION: {}", transition.toString());

                State newState = getStateByName(transition.getTo());
                currentStateRepository.save(createNewCurrentState(newState, CURRENT_STATE_ID));
                log.info("SUCCESSFULLY UPDATED state: {} -> {}", currentService, newState);

                currentStateRepository.save(createNewCurrentState(state, CURRENT_WORKFLOW_STATE_ID));
                log.info("ATTEMPTING TO UPDATE TO STATE: {}", transition.getTo());

                return state.getName();
            }
        }
        throw new InvalidTransitionException("No start state found in workflow: " + getCurrentWorkflow().getName());
    }

    private String handleWorkflowEnd(String currentService, String eventAction) throws InvalidTransitionException {
        currentWorkflowRepository.delete(createNewCurrentWorkflow(getCurrentWorkflow(), CURRENT_WORKFLOW_ID));
        currentStateRepository.delete(createNewCurrentState(getCurrentState(), CURRENT_WORKFLOW_STATE_ID));

        log.info("COMPLETED WORKFLOW | Resuming protocol at state: {}", getCurrentState().getName());

        // Now re-process the triggering event against the restored protocol state
        return handleProtocolTransition(currentService, eventAction);
    }

    private String handleWorkflowTransition(String currentService, String eventAction) throws InvalidTransitionException {
        Transition transition = getValidTransition(eventAction, currentService);
        log.info("DEBUG | TRANSITION: {}", transition.toString());
        log.info("ATTEMPTING TO UPDATE TO STATE: {}", transition.getTo());

        State newState = getStateByName(transition.getTo());
        currentStateRepository.save(createNewCurrentState(newState, CURRENT_WORKFLOW_STATE_ID));
        log.info("SUCCESSFULLY UPDATED state: {} -> {}", currentService, newState);
        return newState.getName();
    }

    private String handleProtocolTransition(String currentService, String eventAction) throws InvalidTransitionException {
        Transition transition = getValidTransition(eventAction, currentService);
        log.info("DEBUG | TRANSITION: {}", transition.toString());

        if (transition.getWorkflow() != null && getCurrentState() != null) {
            String workflowStartState = tryStartWorkflow(transition.getWorkflow(), transition, currentService);

            if (workflowStartState != null) {
                log.info("WORKFLOW STARTED | Workflow: {} | Start state: {}", transition.getWorkflow(), workflowStartState);
                return workflowStartState;
            }
        }

        log.info("ATTEMPTING TO UPDATE TO STATE: {}", transition.getTo());
        State newState = getStateByName(transition.getTo());
        currentStateRepository.save(createNewCurrentState(newState, CURRENT_STATE_ID));
        log.info("SUCCESSFULLY UPDATED state: {} -> {}", currentService, newState);
        return newState.getName();
    }

    // start workflow AND start workflow session
    private String tryStartWorkflow(String workflowName, Transition transition, String currentService) {
        if (protocolRepository.findById(PROTOCOL_DEF_ID).isEmpty()) return null;

        for (Workflow workflow : protocolRepository.findById(PROTOCOL_DEF_ID).get().getWorkflows()) {
            if (workflow.getName().equals(workflowName)) {
                // create session BEFORE setting workflow
                // save snapshot for new session
                Session newSession  = workflowSessionService.onWorkflowStart(UUID.randomUUID().toString(), workflowName ,workflow, transition.isRollbackOnFailure());
                log.info("New session started : {}", newSession.getSessionId());

                // Save the NEXT protocol state BEFORE starting the workflow
                State nextProtocolState = getStateByName(transition.getTo());
                currentStateRepository.save(createNewCurrentState(nextProtocolState, CURRENT_STATE_ID));
                log.info("PRE-WORKFLOW | Updating protocol state: {} -> {}", currentService, nextProtocolState.getName());

                currentWorkflowRepository.save(createNewCurrentWorkflow(workflow, CURRENT_WORKFLOW_ID));
                log.info("STARTING WORKFLOW: {}", workflowName);

                for (State state : workflow.getStates()) {
                    if (state.getStart()) {
                        currentStateRepository.save(createNewCurrentState(state, CURRENT_WORKFLOW_STATE_ID));
                        log.info("WORKFLOW START STATE: {}", state.getName());
                        return state.getName();
                    }
                }
            }
        }
        return null;
    }

    public State getStateByName(String state){
        if (currentWorkflowRepository.findById(CURRENT_WORKFLOW_ID).isPresent()){
            for (State compareState: currentWorkflowRepository.findById(CURRENT_WORKFLOW_ID).get().getWorkflow().getStates()){
                if (compareState.getName().equals(state)){
                    return compareState;
                }
            }
        }

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

    //TODO fix this method
    // get transition for currentState that HAS call_[newService]
    public Transition getValidTransition(String eventAction, String fromService) throws InvalidTransitionException {
        LinkedList<Transition> currentStateTransitions = getAvailableTransitions();
        for (Transition checkTransition : currentStateTransitions){
            if (checkTransition.getOn().equals(eventAction) && checkTransition.getFrom().equals(fromService)){
                return checkTransition;
            }
        }
        throw new InvalidTransitionException("Cannot find valid transition for ["+getCurrentStateString()+"] " +
              fromService +  "-> "+ eventAction);
    }

}