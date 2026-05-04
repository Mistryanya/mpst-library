package org.example.mpstlibrary.processor;

import org.example.mpstlibrary.data.CurrentState;
import org.example.mpstlibrary.repo.CurrentStateRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import redis.embedded.RedisServer;
import java.io.IOException;

import static org.mockito.ArgumentMatchers.any;


@SpringBootTest
public class ProtocolLoaderTest {

    private static RedisServer redisServer;

    @Autowired
    private ProtocolLoader protocolLoader;

    @MockitoBean
    CurrentStateRepository repo;

    // start redis server for testing (NOT ON SAME PORT AS ACTUAL SERVER)
    @BeforeAll
    static void startRedis() throws IOException {
        redisServer = new RedisServer(6399);
        redisServer.start();
    }

    @AfterAll
    static void stopRedis() {
        redisServer.stop();
    }

    // test actual protocol load
    // check the actual JSON file is validated correctly through logging
    @Test
    void testLoadProtocol() throws IOException {
        String path = "/protocol-examples/protocol.json";
        protocolLoader.loadAndSaveProtocol(path, "test");
    }

    // test current state save
    @Test
    void testInitialCurrentStateLoadedCorrectly() throws IOException {
        String path = "/protocol-examples/protocol.json";
        protocolLoader.loadAndSaveProtocol(path, "test_currentState");
        Mockito.verify(repo).save(any(CurrentState.class));
    }

}