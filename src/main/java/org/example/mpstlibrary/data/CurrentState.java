package org.example.mpstlibrary.data;

import lombok.Data;
import org.springframework.data.redis.core.RedisHash;

@Data
@RedisHash("currentState")
public class CurrentState {

    private String id;
    private State state;

    public CurrentState(State state, String id){
        this.state = state;
        this.id = id;
    }
}
