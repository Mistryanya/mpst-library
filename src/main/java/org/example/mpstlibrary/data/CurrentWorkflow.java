package org.example.mpstlibrary.data;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.redis.core.RedisHash;

import java.util.UUID;

@Data
@RedisHash("currentWorkflow")
@NoArgsConstructor
@AllArgsConstructor
public class CurrentWorkflow {
    private String id;
    private String sessionId;
    private Workflow workflow;
//    private String sessionId;

    public CurrentWorkflow(Workflow workflow, String id){
        this.workflow = workflow;
        this.id = id;

//        if (sessionId == null){
//            this.sessionId = UUID.randomUUID().toString();
//        } else {
//            this.sessionId = sessionId;
//        }
    }
}
