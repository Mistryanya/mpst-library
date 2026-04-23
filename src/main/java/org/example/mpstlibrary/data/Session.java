package org.example.mpstlibrary.data;

import lombok.Data;

import java.io.Serializable;
import java.util.Map;

import org.example.mpstlibrary.session.Snapshot;
import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;
import org.springframework.data.redis.core.TimeToLive;

import java.util.HashMap;
import java.util.concurrent.TimeUnit;

@Data
@RedisHash("session")
public class Session implements Serializable {
    @Id
    private String sessionId;

    private CurrentState currentState;
    private CurrentWorkflow activeWorkflow;
    private Map<String, Snapshot> snapshots = new HashMap<>();

    @TimeToLive(unit = TimeUnit.SECONDS)
    private Long ttl = 3600L; // 1 hour default; override per-session if needed
}
