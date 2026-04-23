package org.example.mpstlibrary.data;

import java.io.Serializable;

import lombok.*;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Transition implements Serializable {

    // "from" maps to the service making the API call
    private String from;

    // "on" maps to the event that triggers the transition
    private String on;

    // "to" maps to the target state
    private String to;

    public String workflow;

    /**
     * If true, a failed request on this transition should restore
     * the pre-transition snapshot. Defaults to true because most
     * transitions represent state-changing operations where failure
     * means the state change didn't happen.
     *
     * Set false for transitions where a non-2xx response is semantically
     * valid (e.g. GET that tolerates 404, idempotent deletes).
     */
    private boolean rollbackOnFailure = true;

}
