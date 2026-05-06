package org.example.mpstlibrary.processor;

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
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedList;
import java.util.Set;
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
    public final static String PRE_COMMIT_STATE_ID = "pre_commit_state";

    // ---------- Reads ----------

    public State getCurrentState() {
        if (getCurrentWorkflow() != null) {
            if (currentStateRepository.findById(CURRENT_WORKFLOW_STATE_ID).isPresent()) {
                State current = currentStateRepository.findById(CURRENT_WORKFLOW_STATE_ID).get().getState();
                log.info("CURRENT WORKFLOW STATE: {}", current);
                return current;
            } else {
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

    public Workflow getCurrentWorkflow() {
        if (currentWorkflowRepository.findById(CURRENT_WORKFLOW_ID).isPresent()) {
            Workflow currentWorkflow = currentWorkflowRepository.findById(CURRENT_WORKFLOW_ID).get().getWorkflow();
            log.info("CURRENT WORKFLOW: {}", currentWorkflow.getName());
            return currentWorkflow;
        } else {
            log.info("No Workflow saved for this transition");
            return null;
        }
    }

    public LinkedList<String> getCurrentStateServices() {
        LinkedList<Transition> transitions = getCurrentState().getTransitions();
        if (transitions != null) {
            return transitions.stream()
                    .map(Transition::getFrom)
                    .collect(Collectors.toCollection(LinkedList::new));
        }
        return null;
    }

    public String getCurrentStateString() {
        return getCurrentState().name;
    }

    public boolean checkCurrentStateHasTransition(String service, String eventAction)
            throws InvalidTransitionException {
        LinkedList<Transition> transitions = getCurrentState().getTransitions();
        if (transitions == null) {
            throw new InvalidTransitionException("No transitions on state [" + getCurrentStateString() + "]");
        }
        boolean found = transitions.stream()
                .anyMatch(t -> t.getFrom().equals(service) && t.getOn().equals(eventAction));
        if (!found) {
            throw new StateMismatchException(
                    "INVALID CALL | " + service + " -> " + eventAction
                            + " is NOT a transition at [" + getCurrentStateString() + "]");
        }
        return true;
    }

    // ---------- PLAN (no Redis writes) ----------

    /**
     * Validates the transition and returns the intended next state WITHOUT persisting.
     * Call commitTransition() after the request succeeds.
     */
    public TransitionPlan planTransition(String currentService,
                                         String eventAction)
            throws InvalidTransitionException {

        Workflow workflow = getCurrentWorkflow();
        State state = getCurrentState();

        if (workflow != null && state == null) {
            return planWorkflowStart(currentService, eventAction, workflow);
        } else if (workflow != null && state.getEnd()) {
            return planWorkflowEnd(currentService, eventAction);
        } else if (state != null && state.getEnd()) {
            throw new EndOfProtocolException("PROTOCOL HAS ENDED - NO"+
                    " MORE SERVICE CALLS ACCEPTED");
        } else if (workflow != null) {
            return planWorkflowTransition(currentService, eventAction);
        } else if (checkCurrentStateHasTransition(currentService,
                eventAction)) {
            return planProtocolTransition(currentService, eventAction);
        } else {
            throw new InvalidTransitionException(
                    "Transition : " + eventAction +
                            " not found for state: " + currentService);
        }
    }

    private TransitionPlan planWorkflowStart(String currentService, String eventAction, Workflow workflow)
            throws InvalidTransitionException {
        for (State state : workflow.getStates()) {
            if (state.getStart()) {
                Transition transition = getValidTransition(eventAction, currentService);
                log.info("PLAN | WORKFLOW_START transition: {}", transition);

                State nextWorkflowState = getStateByName(transition.getTo());
                return TransitionPlan.builder()
                        .transition(transition)
                        .nextState(nextWorkflowState)
                        .workflowStartState(state)
                        .insideWorkflow(true)
                        .build();
            }
        }
        throw new InvalidTransitionException("No start state found in workflow: " + workflow.getName());
    }

    private TransitionPlan planWorkflowEnd(String currentService, String eventAction)
            throws InvalidTransitionException {

        State protocolState = currentStateRepository.findById(CURRENT_STATE_ID)
                .map(CurrentState::getState)
                .orElseThrow(() -> new CurrentStateNotFoundException(
                        "No protocol state found when ending workflow"));

        Transition transition = findTransition(protocolState, eventAction, currentService);
        log.info("PLAN | WORKFLOW_END resolving to PROTOCOL transition: {}", transition);

        // Does this transition also START a new workflow?
        if (transition.getWorkflow() != null) {
            Workflow workflowToStart = findWorkflowByName(transition.getWorkflow());
            if (workflowToStart != null) {
                State workflowStartState = workflowToStart.getStates().stream()
                        .filter(State::getStart)
                        .findFirst()
                        .orElseThrow(() -> new InvalidTransitionException(
                                "Workflow " + workflowToStart.getName() + " has no start state"));

                State nextProtocolState = getStateByName(transition.getTo());

                log.info("PLAN | WORKFLOW_END also STARTS workflow: {}", transition.getWorkflow());

                return TransitionPlan.builder()
                        .transition(transition)
                        .nextState(workflowStartState)        // caller sees the new workflow's start
                        .workflowToStart(workflowToStart)
                        .workflowStartState(workflowStartState)
                        .nextProtocolState(nextProtocolState)
                        .endsWorkflow(true)                   // tear down old workflow
                        .startsWorkflow(true)                 // start new workflow
                        .build();
            }
        }

        // Plain end — just return to protocol state
        State nextState = getStateByName(transition.getTo());
        return TransitionPlan.builder()
                .transition(transition)
                .nextState(nextState)
                .endsWorkflow(true)
                .build();
    }

    /**
     * Like getValidTransition but against a specific state, not getCurrentState().
     * Needed during workflow-end planning where the "current" reader would still
     * point at the workflow's terminal state.
     */
    private Transition findTransition(State state, String eventAction, String fromService)
            throws InvalidTransitionException {
        LinkedList<Transition> transitions = state.getTransitions();
        if (transitions == null) {
            throw new InvalidTransitionException(
                    "No transitions on state [" + state.getName() + "]");
        }
        for (Transition t : transitions) {
            if (t.getOn().equals(eventAction) && t.getFrom().equals(fromService)) {
                return t;
            }
        }
        throw new InvalidTransitionException("Cannot find valid transition for ["
                + state.getName() + "] " + fromService + " -> " + eventAction);
    }

    private TransitionPlan planWorkflowTransition(String currentService, String eventAction)
            throws InvalidTransitionException {
        Transition transition = getValidTransition(eventAction, currentService);
        log.info("PLAN | WORKFLOW transition: {}", transition);

        State nextState = getStateByName(transition.getTo());

        boolean reachesEnd = Boolean.TRUE.equals(nextState.getEnd());

        return TransitionPlan.builder()
                .transition(transition)
                .nextState(nextState)
                .insideWorkflow(true)
                .endsWorkflow(reachesEnd)   // ← teardown on terminal state
                .build();
    }

    private TransitionPlan planProtocolTransition(String currentService, String eventAction)
            throws InvalidTransitionException {
        Transition transition = getValidTransition(eventAction, currentService);
        log.info("PLAN | PROTOCOL transition: {}", transition);

        // Does this transition start a workflow?
        if (transition.getWorkflow() != null && getCurrentState() != null) {
            Workflow workflowToStart = findWorkflowByName(transition.getWorkflow());
            if (workflowToStart != null) {
                State workflowStartState = workflowToStart.getStates().stream()
                        .filter(State::getStart)
                        .findFirst()
                        .orElseThrow(() -> new InvalidTransitionException(
                                "Workflow " + workflowToStart.getName() + " has no start state"));

                State nextProtocolState = getStateByName(transition.getTo());

                return TransitionPlan.builder()
                        .transition(transition)
                        .nextState(workflowStartState)            // what the caller 'sees' as next
                        .workflowToStart(workflowToStart)
                        .workflowStartState(workflowStartState)
                        .nextProtocolState(nextProtocolState)
                        .startsWorkflow(true)
                        .build();
            }
        }

        State nextState = getStateByName(transition.getTo());
        return TransitionPlan.builder()
                .transition(transition)
                .nextState(nextState)
                .build();
    }

    private Workflow findWorkflowByName(String workflowName) {
        if (protocolRepository.findById(PROTOCOL_DEF_ID).isEmpty()) return null;
        for (Workflow workflow : protocolRepository.findById(PROTOCOL_DEF_ID).get().getWorkflows()) {
            if (workflow.getName().equals(workflowName)) {
                return workflow;
            }
        }
        return null;
    }

    // ---------- COMMIT (writes to Redis after response) ----------

    /**
     * Applies a previously-planned transition to Redis. Call after request success.
     */
    public void commitTransition(TransitionPlan plan) {
        log.info("COMMIT routing: startsWorkflow={}, endsWorkflow={}, insideWorkflow={}",
                plan.isStartsWorkflow(), plan.isEndsWorkflow(), plan.isInsideWorkflow());

        if (plan.isEndsWorkflow() && plan.isStartsWorkflow()) {
            commitWorkflowEndTeardown();
            commitWorkflowStart(plan);
            log.info("COMMITTED workflow-end-and-restart into: {}", plan.getNextState().getName());
            return;
        }

        if (plan.isStartsWorkflow()) {
            commitWorkflowStart(plan);
            return;
        }

        // NEW: workflow-internal transition that lands on terminal state
        if (plan.isInsideWorkflow() && plan.isEndsWorkflow()) {
            commitWorkflowEndTeardown();
            log.info("COMMIT | Workflow reached terminal state, torn down");
            return;
        }

        // Old-style end (planWorkflowEnd resolved against protocol state)
        if (plan.isEndsWorkflow()) {
            commitWorkflowEnd(plan);
            return;
        }

        if (plan.isInsideWorkflow()) {
            currentStateRepository.save(new CurrentState(plan.getNextState(), CURRENT_WORKFLOW_STATE_ID));
            log.info("COMMITTED workflow transition to: {}", plan.getNextState().getName());
        } else {
            currentStateRepository.save(new CurrentState(plan.getNextState(), CURRENT_STATE_ID));
            log.info("COMMITTED protocol transition to: {}", plan.getNextState().getName());
        }
    }

    // Extracted so it can be reused by the combined case

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private void commitWorkflowEndTeardown() {
        Workflow current = getCurrentWorkflow();
        if (current != null) {
            currentWorkflowRepository.deleteById(CURRENT_WORKFLOW_ID);
        }
        currentStateRepository.deleteById(CURRENT_WORKFLOW_STATE_ID);
        log.info("COMMIT | Workflow ended, cleared workflow-scoped state");

        // Read back immediately to verify
        boolean stillThere = currentWorkflowRepository.findById(CURRENT_WORKFLOW_ID).isPresent();

        boolean stillThere2 = currentWorkflowRepository.findById(CURRENT_WORKFLOW_STATE_ID).isPresent();
        log.info("COMMIT | Workflow ended, cleared workflow-scoped state. " +
                "Verify still present workflow and workflow state: {} {}", stillThere, stillThere2);

        // Try every plausible key name
        Long deleted1 = stringRedisTemplate.delete("currentWorkflow:" + CURRENT_WORKFLOW_ID) ? 1L : 0L;
        Long deleted2 = stringRedisTemplate.delete("currentState:" + CURRENT_WORKFLOW_STATE_ID) ? 1L : 0L;
        log.info("Direct delete attempts: workflow={}, state={}", deleted1, deleted2);

        Set<String> keysAfter = stringRedisTemplate.keys("*");
        log.info("KEYS AFTER delete: {}", keysAfter);
    }

    private void commitWorkflowEnd(TransitionPlan plan) {
        commitWorkflowEndTeardown();
        currentStateRepository.save(new CurrentState(plan.getNextState(), CURRENT_STATE_ID));
        log.info("COMMIT | Resumed protocol at state: {}", plan.getNextState().getName());
    }

    private void commitWorkflowStart(TransitionPlan plan) {
        // Save the NEXT protocol state BEFORE the workflow (so when the workflow ends,
        // we resume from the correct protocol state).
        currentStateRepository.save(new CurrentState(plan.getNextProtocolState(), CURRENT_STATE_ID));
        log.info("COMMIT | PRE-WORKFLOW protocol state: {}", plan.getNextProtocolState().getName());

        CurrentWorkflow cw = new CurrentWorkflow(
                CURRENT_WORKFLOW_ID,
                plan.getSessionId(),
                plan.getWorkflowToStart()
        );
        currentWorkflowRepository.save(cw);
        log.info("COMMIT | STARTING WORKFLOW: {} (session: {})",
                plan.getWorkflowToStart().getName(), plan.getSessionId());

        currentStateRepository.save(new CurrentState(plan.getWorkflowStartState(), CURRENT_WORKFLOW_STATE_ID));
        log.info("COMMIT | WORKFLOW START STATE: {}", plan.getWorkflowStartState().getName());
    }


    public State getStateByName(String state) {
        if (currentWorkflowRepository.findById(CURRENT_WORKFLOW_ID).isPresent()) {
            for (State compareState : currentWorkflowRepository.findById(CURRENT_WORKFLOW_ID).get().getWorkflow().getStates()) {
                if (compareState.getName().equals(state)) {
                    return compareState;
                }
            }
        }

        if (protocolRepository.findById(PROTOCOL_DEF_ID).isPresent()) {
            for (State compareState : protocolRepository.findById(PROTOCOL_DEF_ID).get().getStates()) {
                if (compareState.getName().equals(state)) {
                    return compareState;
                }
            }
        }

        throw new StateMismatchException("transition State could not be found");
    }

    public LinkedList<Transition> getAvailableTransitions() {
        return getCurrentState().getTransitions();
    }

    public LinkedList<Transition> getAvailableTransitions(State state) {
        return state.getTransitions();
    }

    public Transition getValidTransition(String eventAction, String fromService) throws InvalidTransitionException {
        LinkedList<Transition> currentStateTransitions = getAvailableTransitions();
        for (Transition checkTransition : currentStateTransitions) {
            if (checkTransition.getOn().equals(eventAction) && checkTransition.getFrom().equals(fromService)) {
                return checkTransition;
            }
        }
        throw new InvalidTransitionException("Cannot find valid transition for [" + getCurrentStateString() + "] "
                + fromService + "-> " + eventAction);
    }
}