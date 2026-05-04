package org.example.mpstlibrary.data;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TransitionPlan {
    private Transition transition;
    private State nextState;                // the state the protocol/workflow will move to on success

    // Set only when the transition kicks off a workflow
    private Workflow workflowToStart;
    private State workflowStartState;       // the workflow's own start state
    private State nextProtocolState;        // the protocol-level state saved pre-workflow

    // Flags for commit routing
    private boolean startsWorkflow;
    private boolean endsWorkflow;
    private boolean insideWorkflow;

    // Populated by the filter after session creation
    private String sessionId;
    private String workflowId;
}