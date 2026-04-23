package org.example.mpstlibrary.session;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.mpstlibrary.data.CurrentState;
import org.example.mpstlibrary.data.CurrentWorkflow;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Snapshot implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;


    private String sessionId;
    private String workflowId;
    private Instant capturedAt;
    private long ttlSeconds;

    // Full state triple captured at snapshot time
    private CurrentState protocolState;       // what was at CURRENT_STATE_ID
    private CurrentState workflowState;       // what was at CURRENT_WORKFLOW_STATE_ID (nullable)
    private CurrentWorkflow currentWorkflow;  // what was at CURRENT_WORKFLOW_ID (nullable)
}
