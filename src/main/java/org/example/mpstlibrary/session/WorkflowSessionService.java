package org.example.mpstlibrary.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.mpstlibrary.data.*;
import org.example.mpstlibrary.processor.ProtocolInterpreter;
import org.example.mpstlibrary.repo.CurrentStateRepository;
import org.example.mpstlibrary.repo.CurrentWorkflowRepository;
import org.example.mpstlibrary.repo.SessionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import static org.example.mpstlibrary.processor.ProtocolInterpreter.CURRENT_WORKFLOW_STATE_ID;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowSessionService {

    private static final Duration DEFAULT_WORKFLOW_TIMEOUT = Duration.ofMinutes(15);

    @Autowired
    private final SessionRepository sessionRepository;

    @Autowired
    private final CurrentStateRepository currentStateRepository;

    @Autowired
    private final CurrentWorkflowRepository currentWorkflowRepository;

    @Getter
    private static String sessionId;
    @Getter
    private static String workflowId;

    @Getter
    private static boolean rollbackOnFailure;


    /**
     * Called when a workflow begins. Loads or creates the session,
     * snapshots the current state, and marks the workflow active.
     *
     * @param sessionId  the caller's session identifier
     * @param workflowId the identifier of the workflow being started
     * @param workflow   the workflow definition
     * @return the updated (and persisted) session
     */
    public Session onWorkflowStart(String sessionId, String workflowId, Workflow workflow, boolean rollbackOnFailure) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(workflowId, "workflowId");
        Objects.requireNonNull(workflow, "workflow");

        WorkflowSessionService.sessionId = sessionId;
        WorkflowSessionService.rollbackOnFailure = rollbackOnFailure;
        WorkflowSessionService.workflowId = workflowId;

        // 1. Load or create the session
        Session session = sessionRepository.findById(sessionId)
                .orElseGet(() -> createNewSession(sessionId, workflow));

        // 2. Guard: refuse to start a workflow if one is already active.
        //    Remove this check if you later support nested / concurrent workflows.
        if (session.getActiveWorkflow() != null) {
            throw new IllegalStateException(
                    "Session " + sessionId + " already has active workflow: "
                            + session.getActiveWorkflow().getId());
        }

        // Read current values from the three stores at snapshot time
        CurrentState protocolState = currentStateRepository
                .findById(ProtocolInterpreter.CURRENT_STATE_ID).orElse(null);
        CurrentState workflowState = currentStateRepository
                .findById(ProtocolInterpreter.CURRENT_WORKFLOW_STATE_ID).orElse(null);
        CurrentWorkflow currentWf = currentWorkflowRepository
                .findById(ProtocolInterpreter.CURRENT_WORKFLOW_ID).orElse(null);

        Snapshot snapshot = new Snapshot(
                sessionId,
                workflowId,
                Instant.now(),
                DEFAULT_WORKFLOW_TIMEOUT.toSeconds(),
                deepCopy(protocolState, CurrentState.class),
                deepCopy(workflowState, CurrentState.class),
                deepCopy(currentWf, CurrentWorkflow.class)
        );

        session.getSnapshots().put(workflowId, snapshot);
        session.setActiveWorkflow(new CurrentWorkflow(workflow, workflowId));
        return sessionRepository.save(session);
    }

    private Session createNewSession(String sessionId, Workflow workflow) {
        Session session = new Session();
        session.setSessionId(sessionId);
        session.setCurrentState(initialStateFor(workflow));
        return session;
    }

    private CurrentState initialStateFor(Workflow workflow) {
        State start = workflow.getStates().stream()
                .filter(s -> Boolean.TRUE.equals(s.getStart()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Workflow " + workflow.getName() + " has no start state"));

        // Adjust this constructor call if CurrentState's shape differs.
        return new CurrentState(start, CURRENT_WORKFLOW_STATE_ID);
    }

    /**
     * Deep copy via JSON round-trip. Safe against reference leaks between
     * the live session state and the snapshot copy.
     */
    private <T> T deepCopy(T obj, Class<T> type) {
        if (obj == null) return null;
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            return objectMapper.readValue(objectMapper.writeValueAsBytes(obj), type);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deep copy " + type.getSimpleName(), e);
        }
    }

    public void rollbackToSnapshot(String sessionId, String workflowId) {
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalStateException(
                        "No session found for id: " + sessionId));

        Snapshot snapshot = session.getSnapshots().get(workflowId);
        if (snapshot == null) {
            throw new IllegalStateException(
                    "No snapshot found for workflow " + workflowId + " in session " + sessionId);
        }

        // Restore the three Redis stores to their snapshotted values.
        // Null values mean "that key was empty at snapshot time" — delete it.

        if (snapshot.getProtocolState() != null) {
            currentStateRepository.save(snapshot.getProtocolState());
        } else {
            currentStateRepository.deleteById(ProtocolInterpreter.CURRENT_STATE_ID);
        }

        if (snapshot.getWorkflowState() != null) {
            currentStateRepository.save(snapshot.getWorkflowState());
        } else {
            currentStateRepository.deleteById(ProtocolInterpreter.CURRENT_WORKFLOW_STATE_ID);
        }

        if (snapshot.getCurrentWorkflow() != null) {
            currentWorkflowRepository.save(snapshot.getCurrentWorkflow());
        } else {
            currentWorkflowRepository.deleteById(ProtocolInterpreter.CURRENT_WORKFLOW_ID);
        }

        // Clear the used snapshot and the active workflow marker
        session.getSnapshots().remove(workflowId);
        session.setActiveWorkflow(null);
        sessionRepository.save(session);

        log.info("Rolled back session {} to snapshot for workflow {}", sessionId, workflowId);
    }
}