package org.example.mpstlibrary.processor;

import com.github.tomakehurst.wiremock.WireMockServer;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;

import org.springframework.test.context.TestPropertySource;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Random;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.example.mpstlibrary.processor.ProtocolInitializer.PROTOCOL_DEF_ID;
import static org.example.mpstlibrary.processor.ProtocolInterpreter.CURRENT_STATE_ID;

/*
TODO This class is for testing the protocol. -- Add more test scenarios later
Services will be called according to the protocol adn then validating responses
I will test that the following occurs:
    1. Service 3 calls Service 1 exactly 3 times
    2. Service 2 can call Service 1 as many times (5 times adn 2 times then timeout)
    3. Service 3 can call service 2 once and then service 2 can call service 1 once

In between each test the spring context will be reset so that we can manage the protocol.
reset current state repo between each test back to state s0.
 */

// TODO - ADD nested requests (how to define these?) scenarios
// Are scenarios also monitored?

@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
@SpringBootTest
@Execution(ExecutionMode.SAME_THREAD)
@TestPropertySource(properties = {
        "protocolPath=protocol-examples/protocol.json"
})
public class ProtocolTest {

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
    --------- TESTING CORRECT BEHAVIOUR WORKS --------------
     */

    @Test
    public void testService3CanBeCalledExactlyThreeTimes(){

        ResponseEntity<String> call1 = service3CallService1();
        verifyFetchData_workflow();

        Assertions.assertEquals("Service1 responded OK", call1.getBody());
        Assertions.assertEquals(HttpStatus.OK, call1.getStatusCode());

        ResponseEntity<String> call2 = service3CallService1();
        verifyFetchData_workflow();

        Assertions.assertEquals("Service1 responded OK", call2.getBody());
        Assertions.assertEquals(HttpStatus.OK, call2.getStatusCode());

        ResponseEntity<String> call3 = service3CallService1();
        Assertions.assertEquals("Service1 responded OK", call3.getBody());
        Assertions.assertEquals(HttpStatus.OK, call3.getStatusCode());


        server.verify(3, postRequestedFor(urlEqualTo("/api/service3/fetchStatusService1")));

        server.verify(2, getRequestedFor(urlEqualTo("/api/service3/call_method_x")));
        server.verify(2, getRequestedFor(urlEqualTo("/api/service3/call_method_y")));

    }

    @Test
    public void testService2CanBeCalledInfinitely(){
        // get a random integer and loop the requests for service 2 call service 1
        Random rand = new Random();
        int rand_num = rand.nextInt(20);
        System.out.println("RANDOM NUMBER HERE: " + rand_num);
        for (int i=0; i< rand_num; i++){
            ResponseEntity<String> temp_call = service2CallService1();
            Assertions.assertEquals("Service1 responded OK", temp_call.getBody());
            Assertions.assertEquals(HttpStatus.OK, temp_call.getStatusCode());
        }

        // verify requests actually received by mock server
        server.verify(rand_num, postRequestedFor(urlEqualTo("/api/service2/fetchStatus")));

    }

    @Test
    public void testService3CanCallService2OnceAndThenService2CanCallService1Once(){
        ResponseEntity<String> call1 = service3CallService2();
        Assertions.assertEquals("Service1 responded OK", call1.getBody());
        Assertions.assertEquals(HttpStatus.OK, call1.getStatusCode());

        ResponseEntity<String> call2 = service2CallService1();
        Assertions.assertEquals("Service1 responded OK", call2.getBody());
        Assertions.assertEquals(HttpStatus.OK, call2.getStatusCode());

        // verify requests actually received by mock server
        server.verify(1, postRequestedFor(urlEqualTo("/api/service3/fetchStatusService2")));
        server.verify(1, postRequestedFor(urlEqualTo("/api/service2/fetchStatus")));

    }

        /*
    --------- TESTING INCORRECT BEHAVIOUR  --------------

    Rules for testing
    1. Test EACH scenario incorrect behaviour
    2. Once the exception has been thrown the incorrect behaviour must recover once correct service call is made
     */


    @Test
    public void testService3CalledFourTimesFails(){

        ResponseEntity<String> call1 = service3CallService1();
        verifyFetchData_workflow();

        Assertions.assertEquals("Service1 responded OK", call1.getBody());
        Assertions.assertEquals(HttpStatus.OK, call1.getStatusCode());


        ResponseEntity<String> call2 = service3CallService1();
        verifyFetchData_workflow();

        Assertions.assertEquals("Service1 responded OK", call2.getBody());
        Assertions.assertEquals(HttpStatus.OK, call2.getStatusCode());


        ResponseEntity<String> call3 = service3CallService1();
        Assertions.assertEquals("Service1 responded OK", call3.getBody());
        Assertions.assertEquals(HttpStatus.OK, call3.getStatusCode());

        ResponseEntity<String> call4 = service3CallService1();
        Assertions.assertEquals("Error calling Service1: PROTOCOL HAS ENDED - NO MORE SERVICE CALLS ACCEPTED", call4.getBody());
        Assertions.assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, call4.getStatusCode());

        server.verify(3, postRequestedFor(urlEqualTo("/api/service3/fetchStatusService1")));

        server.verify(2, getRequestedFor(urlEqualTo("/api/service3/call_method_x")));
        server.verify(2, getRequestedFor(urlEqualTo("/api/service3/call_method_y")));

    }

    @Test
    public void testService3CanCallService2OnceAndIncorrectCallThenService2CanCallService1Once(){
        ResponseEntity<String> call1 = service3CallService2();
        Assertions.assertEquals("Service1 responded OK", call1.getBody());
        Assertions.assertEquals(HttpStatus.OK, call1.getStatusCode());

        ResponseEntity<String> call2 = service3CallService2();
        Assertions.assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, call2.getStatusCode());

        ResponseEntity<String> call3 = service2CallService1();
        Assertions.assertEquals("Service1 responded OK", call3.getBody());
        Assertions.assertEquals(HttpStatus.OK, call3.getStatusCode());

        // verify requests actually received by mock server
        server.verify(1, postRequestedFor(urlEqualTo("/api/service3/fetchStatusService2")));
        server.verify(1, postRequestedFor(urlEqualTo("/api/service2/fetchStatus")));

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

    public ResponseEntity<String> service3CallService2(){
        Mono<ResponseEntity<String>> retrieveResponse =
                webClientMonitor.monitoredWebClient()
                .post()
                .uri(HOST + "/api/service3/fetchStatusService2")
                .retrieve()
                .toBodilessEntity()
                .map(response -> ResponseEntity.ok("Service1 responded OK"))
                .onErrorResume(e -> Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body("Error calling Service1: " + e.getMessage())));
        return retrieveResponse.block();
    }

    public ResponseEntity<String> service2CallService1(){
        Mono<ResponseEntity<String>> retrieveResponse =
                webClientMonitor.monitoredWebClient()
                .post()
                .uri(HOST + "/api/service2/fetchStatus")
                .retrieve()
                .toBodilessEntity()
                .map(response -> ResponseEntity.ok("Service1 responded OK"))
                .onErrorResume(e -> Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body("Error calling Service1: " + e.getMessage())));
        return retrieveResponse.block();
    }

    public void verifyFetchData_workflow(){
        ResponseEntity<String> call_method_x = call_method_x();
        Assertions.assertEquals("call_method_x responded OK", call_method_x.getBody());
        Assertions.assertEquals(HttpStatus.OK, call_method_x.getStatusCode());

//        ResponseEntity<String> call_method_a = call_method_a();
//        Assertions.assertEquals("call_method_a responded OK", call_method_a.getBody());
//        Assertions.assertEquals(HttpStatus.OK, call_method_a.getStatusCode());

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
    public ResponseEntity<String> call_method_a(){
        Mono<ResponseEntity<String>> retrieveResponse =
                webClientMonitor.monitoredWebClient()
                .get()
                .uri(HOST + "/api/service3/call_method_a")
                .retrieve()
                .toBodilessEntity()
                .map(response -> ResponseEntity.ok("call_method_a responded OK"))
                .onErrorResume(e -> Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body("Error calling call_method_a: " + e.getMessage())));
        return retrieveResponse.block();
    }

}
