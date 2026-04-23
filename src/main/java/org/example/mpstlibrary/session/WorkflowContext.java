package org.example.mpstlibrary.session;

import java.sql.Timestamp;
import java.util.UUID;

public class WorkflowContext {

    private UUID workflowId;
    private String snapshotRef;

    // maybe could have parent workflows but will leave out for now
    // private UUID parentWorkflowId;

    private Timestamp startedAtTimestamp;


}
