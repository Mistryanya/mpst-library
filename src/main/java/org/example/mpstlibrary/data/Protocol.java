package org.example.mpstlibrary.data;

import java.io.Serializable;
import java.util.LinkedList;

import lombok.Data;
import org.springframework.data.redis.core.RedisHash;
import com.fasterxml.jackson.annotation.JsonProperty;

// @RedisHash is used to specify a key namespace for storing the object in Redis
@RedisHash("protocol")
@Data
public class Protocol implements Serializable {

    // This is the structure you want to deserialize from the JSON's root
    private String id;

    @JsonProperty("states")
    private LinkedList<State> states;

    @JsonProperty("workflows")
    private LinkedList<Workflow> workflows;

    // Default constructor is required by Redis/Jackson
    public Protocol() {
    }
}

