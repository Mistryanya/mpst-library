package org.example.mpstlibrary.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.mpstlibrary.data.*;
import org.example.mpstlibrary.processor.ProtocolInterpreter;
import org.example.mpstlibrary.repo.CurrentStateRepository;
import org.example.mpstlibrary.repo.CurrentWorkflowRepository;
import org.example.mpstlibrary.repo.SessionRepository;
import org.springframework.integration.redis.util.RedisLockRegistry;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;

import static org.example.mpstlibrary.processor.ProtocolInterpreter.CURRENT_WORKFLOW_STATE_ID;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowSessionService {

    public static final String STATE_LOCK_KEY = "protocol:state:lock";
    public static final long LOCK_WAIT_SECONDS = 5;

    private static final Duration DEFAULT_WORKFLOW_TIMEOUT = Duration.ofMinutes(15);

    private final SessionRepository sessionRepository;
    private final CurrentStateRepository currentStateRepository;
    private final CurrentWorkflowRepository currentWorkflowRepository;
    private final RedisLockRegistry lockRegistry;

    /**
     * Called when a workflow begins. Loads or creates the session,
     * snapshots the current state, and marks the workflow active.
     *
     * Note: this method is normally called from inside a caller that
     * already holds the protocol state lock (the WebClientMonitor's
     * plan+commit window). It does not acquire the lock itself.
     */
    public Session onWorkflowStart(String sessionId, String workflowId, Workflow workflow,
                                   boolean rollbackOnFailure) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(workflowId, "workflowId");
        Objects.requireNonNull(workflow, "workflow");

        Session session = sessionRepository.findById(sessionId)
                .orElseGet(() -> createNewSession(sessionId, workflow));

        if (session.getActiveWorkflow() != null) {
            throw new IllegalStateException(
                    "Session " + sessionId + " already has active workflow: "
                            + session.getActiveWorkflow().getId());
        }

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

    /**
     * Restores the three Redis stores to the snapshot taken at workflow start.
     * Self-locking — safe to call from anywhere.
     */
    public void rollbackToSnapshot(String sessionId, String workflowId) {
        withLock(() -> doRollback(sessionId, workflowId));
    }

    private void doRollback(String sessionId, String workflowId) {
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalStateException(
                        "No session found for id: " + sessionId));

        Snapshot snapshot = session.getSnapshots().get(workflowId);
        if (snapshot == null) {
            throw new IllegalStateException(
                    "No snapshot found for workflow " + workflowId +
                            " in session " + sessionId);
        }

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

        session.getSnapshots().remove(workflowId);
        session.setActiveWorkflow(null);
        sessionRepository.save(session);

        log.info("Rolled back session {} to snapshot for workflow {}", sessionId, workflowId);
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
        return new CurrentState(start, CURRENT_WORKFLOW_STATE_ID);
    }

    private <T> T deepCopy(T obj, Class<T> type) {
        if (obj == null) return null;
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            return objectMapper.readValue(objectMapper.writeValueAsBytes(obj), type);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deep copy " + type.getSimpleName(), e);
        }
    }

    /**
     * Acquires the protocol state lock, runs the action, and releases the lock —
     * even on exception. Throws if the lock can't be acquired in LOCK_WAIT_SECONDS.
     */
    private void withLock(Runnable action) {
        Lock lock = lockRegistry.obtain(STATE_LOCK_KEY);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
            if (!acquired) {
                throw new IllegalStateException(
                        "Could not acquire protocol state lock within " + LOCK_WAIT_SECONDS + "s");
            }
            action.run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for lock", e);
        } finally {
            if (acquired) {
                lock.unlock();
            }
        }
    }
}