package org.example.mpstlibrary.processor;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.example.mpstlibrary.config.RedisDataConfig;
import org.example.mpstlibrary.data.CurrentState;
import org.example.mpstlibrary.data.State;
import org.example.mpstlibrary.monitor.WebClientMonitor;
import org.example.mpstlibrary.repo.CurrentStateRepository;
import org.example.mpstlibrary.repo.ProtocolRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.event.annotation.AfterTestClass;
import reactor.core.publisher.Mono;

import java.util.Random;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.example.mpstlibrary.processor.ProtocolInitializer.PROTOCOL_DEF_ID;
import static org.example.mpstlibrary.processor.ProtocolInterpreter.CURRENT_STATE_ID;

/*
TODO This class is for testing the rollback for specified requests
Created a separate class for this to not add too many tests to the other class
RULES
1. Rollback is only executed if the boolean value 'rollbackOnFailure' is true
2. Rollback happens across workflow and state repos so need to test this
3. THEN the same request should be able to be made again
4. IF NOT ROLLBACK then protocol can resume as normal

 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
@SpringBootTest
@Execution(ExecutionMode.SAME_THREAD)
@TestPropertySource(properties = {
        "protocolPath=protocol-examples/example4.json"
})
public class RollbackOnFailureTest {

    // mock API calls please because they cannot actually successfully be made otherwise
    // wiremock?

    @Autowired
    WebClientMonitor webClientMonitor;


    @Autowired
    CurrentStateRepository currentStateRepository;

    @Autowired
    ProtocolRepository protocolRepository;
    private static final int PORT = 1225;
    WireMockServer server = new WireMockServer(PORT);

    private static final String HOST = "http://localhost:"+ PORT;

    @AfterEach
    public void resetContext(){
        server.resetRequests();
        server.resetAll();
        server.stop();

        if (currentStateRepository.findById(CURRENT_STATE_ID).isPresent()) {
            CurrentState currentState = currentStateRepository.findById(CURRENT_STATE_ID).get();
            if (!currentState.getState().getName().equals("S0")){
                if (protocolRepository.findById(PROTOCOL_DEF_ID).isPresent()) {
                    State intialState = protocolRepository.findById(PROTOCOL_DEF_ID).get().getStates().getFirst();
                    CurrentState resetInitialState = new CurrentState(intialState, CURRENT_STATE_ID);
                    currentStateRepository.save(resetInitialState);
                }
            }
        }
    }

    @BeforeEach
    public void startWireMock() {
        server.start();

    }

    /*
    --------- TESTING ROLLBACK BEHAVIOUR WORKS --------------
     */

    @Test
    public void testService3CanBeCalledExactlyThreeTimesA() {
        // Call 1: outer succeeds at HTTP level, but inner workflow fails
        // → rollback restores state to S0 as if the whole thing never happened
        ResponseEntity<String> call1 = service3CallService1();
        Assertions.assertEquals(HttpStatus.OK, call1.getStatusCode());
        workflowFail();   // call_method_a → 400 → rollback to S0

        // Call 2: outer succeeds, inner workflow succeeds, state moves S0 → S1
        ResponseEntity<String> call2 = service3CallService1();
        Assertions.assertEquals(HttpStatus.OK, call2.getStatusCode());
        workflowPass();

        // Call 3: outer succeeds, inner workflow succeeds, state moves S1 → S2 (end+restart)
        ResponseEntity<String> call3 = service3CallService1();
        Assertions.assertEquals(HttpStatus.OK, call3.getStatusCode());
        workflowPass();

        // Call 4: outer succeeds, S2 → S3, no workflow on this transition
        ResponseEntity<String> call4 = service3CallService1();
        Assertions.assertEquals(HttpStatus.OK, call4.getStatusCode());

        server.verify(4, postRequestedFor(urlEqualTo("/api/service3/fetchStatusService1")));
        server.verify(1, getRequestedFor(urlEqualTo("/api/service3/call_method_a")));
        server.verify(2, getRequestedFor(urlEqualTo("/api/service3/call_method_x")));
        server.verify(2, getRequestedFor(urlEqualTo("/api/service3/call_method_y")));
    }

    @Test
    public void testService3CanBeCalledExactlyThreeTimes(){

        ResponseEntity<String> call1 = service3CallService1_400();
        workflowFail();

//        Assertions.assertEquals("Service1 responded OK", call1.getBody());
        Assertions.assertEquals(HttpStatus.BAD_REQUEST, call1.getStatusCode());

        ResponseEntity<String> call2 = service3CallService1();
        workflowPass();

        Assertions.assertEquals("Service1 responded OK", call2.getBody());
        Assertions.assertEquals(HttpStatus.OK, call2.getStatusCode());

        ResponseEntity<String> call3 = service3CallService1();
        Assertions.assertEquals("Service1 responded OK", call3.getBody());
        Assertions.assertEquals(HttpStatus.OK, call3.getStatusCode());

        ResponseEntity<String> call4 = service3CallService1();
        Assertions.assertEquals("Service1 responded OK", call4.getBody());
        Assertions.assertEquals(HttpStatus.OK, call4.getStatusCode());



        server.verify(3, postRequestedFor(urlEqualTo("/api/service3/fetchStatusService1")));

        server.verify(2, getRequestedFor(urlEqualTo("/api/service3/call_method_a")));
        server.verify(3, getRequestedFor(urlEqualTo("/api/service3/call_method_y")));

    }

    @Test
    public void testWorkflowRollbackOnInternalFailure() {
        // Step 1: start the workflow successfully
        ResponseEntity<String> call1 = service3CallService1();
        Assertions.assertEquals(HttpStatus.OK, call1.getStatusCode());
        // Protocol state is now at W0 (inside workflow)

        // Step 2: fire a workflow-internal request that fails
        // This is call_method_a, which is valid at W0 and returns 400 per your stub
        ResponseEntity<String> failedCall = call_method_a();
        Assertions.assertEquals(HttpStatus.BAD_REQUEST, failedCall.getStatusCode());
        // Rollback fires here: workflow is torn down, protocol snaps back to S0

        // Step 3: prove the rollback worked by successfully starting the workflow again
        ResponseEntity<String> retry = service3CallService1();
        Assertions.assertEquals(HttpStatus.OK, retry.getStatusCode());
        workflowPass();
        // If rollback hadn't worked, call1 would have left us at W0 and
        // service3CallService1 would fail at plan time (S0's transition
        // doesn't exist from W0).
    }


    /*
    ----------------------- Webclient method calls ----------------------------------
     */
    public ResponseEntity<String> service3CallService1(){
        Mono<ResponseEntity<String>> retrieveResponse =
                webClientMonitor.monitoredWebClient()
                        .post()
                        .uri(HOST + "/api/service3/fetchStatusService1")
                        .retrieve()
                        .toBodilessEntity()
                        .map(response -> ResponseEntity.ok("Service1 responded OK"))
                        .onErrorResume(e -> Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                .body("Error calling Service1: " + e.getMessage())));
        return retrieveResponse.block();
    }


    public void workflowFail(){
        ResponseEntity<String> call_method_a = call_method_a();
        Assertions.assertEquals("call_method_a returned 400", call_method_a.getBody());
        Assertions.assertEquals(HttpStatus.BAD_REQUEST, call_method_a.getStatusCode());

    }

    public void workflowPass(){
        ResponseEntity<String> call_method_x = call_method_x();
        Assertions.assertEquals("call_method_x responded OK", call_method_x.getBody());
        Assertions.assertEquals(HttpStatus.OK, call_method_x.getStatusCode());

        ResponseEntity<String> call_method_y = call_method_y();
        Assertions.assertEquals("call_method_y responded OK", call_method_y.getBody());
        Assertions.assertEquals(HttpStatus.OK, call_method_y.getStatusCode());
    }
    public ResponseEntity<String> call_method_x(){
        Mono<ResponseEntity<String>> retrieveResponse =
                webClientMonitor.monitoredWebClient()
                        .get()
                        .uri(HOST + "/api/service3/call_method_x")
                        .retrieve()
                        .toBodilessEntity()
                        .map(response -> ResponseEntity.ok("call_method_x responded OK"))
                        .onErrorResume(e -> Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                .body("Error calling call_method_x: " + e.getMessage())));
        return retrieveResponse.block();
    }
    public ResponseEntity<String> call_method_y(){
        Mono<ResponseEntity<String>> retrieveResponse =
                webClientMonitor.monitoredWebClient()
                        .get()
                        .uri(HOST + "/api/service3/call_method_y")
                        .retrieve()
                        .toBodilessEntity()
                        .map(response -> ResponseEntity.ok("call_method_y responded OK"))
                        .onErrorResume(e -> Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                .body("Error calling call_method_y: " + e.getMessage())));
        return retrieveResponse.block();
    }
    public ResponseEntity<String> call_method_a() {
        Mono<ResponseEntity<String>> retrieveResponse =
                webClientMonitor.monitoredWebClient()
                        .get()
                        .uri(HOST + "/api/service3/call_method_a")
                        .retrieve()
                        .toBodilessEntity()
                        .map(response -> ResponseEntity.status(HttpStatus.BAD_REQUEST)
                                .body("call_method_a returned 400"))
                        .onErrorResume(e -> {
                            if (e.getMessage().contains("Bad Request")) {
                                return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST)
                                        .body("call_method_a returned 400"));
                            }
                            return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                    .body("Error calling call_method_a: " + e.getMessage()));
                        });

        return retrieveResponse.block();
    }

    public ResponseEntity<String> service3CallService1_400(){
        Mono<ResponseEntity<String>> retrieveResponse =
                webClientMonitor.monitoredWebClient()
                        .post()
                        .uri(HOST + "/api/service3/fetchStatusService1")
                        .header("X-Test-Case", "fail")
                        .retrieve()
                        .toBodilessEntity()
                        .map(response -> ResponseEntity.status(HttpStatus.BAD_REQUEST)
                                .body("fetchStatusService1 returned 400"))
                        .onErrorResume(e -> Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST)
                                .body("fetchStatusService1 returned 400")));

        return retrieveResponse.block();
    }

}
