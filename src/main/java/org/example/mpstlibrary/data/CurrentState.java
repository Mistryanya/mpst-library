package org.example.mpstlibrary.data;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.redis.core.RedisHash;

@Data
@RedisHash("currentState")
@NoArgsConstructor
@AllArgsConstructor
public class CurrentState {

    private String id;
    private State state;
//    private boolean pending = false;
//    private State previousState;

    public CurrentState(State state, String id /*State previousState */){
        this.state = state;
        this.id = id;
//        this.previousState = previousState;
    }
}
