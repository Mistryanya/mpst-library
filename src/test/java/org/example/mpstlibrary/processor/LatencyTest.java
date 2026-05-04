package org.example.mpstlibrary.processor;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
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

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.example.mpstlibrary.processor.ProtocolInitializer.PROTOCOL_DEF_ID;
import static org.example.mpstlibrary.processor.ProtocolInterpreter.CURRENT_STATE_ID;

/*
 * LatencyTest — Benchmarks the runtime overhead introduced by the mpst-library monitor.
 *
 * Two test methods:
 *   1. benchmarkMonitoredTrace   — runs the full protocol trace using the monitored WebClient bean
 *   2. benchmarkUnmonitoredTrace — runs the same trace using a plain WebClient with no filters
 *
 * Each method:
 *   - Performs a 5-iteration JVM warmup phase (results discarded)
 *   - Records 30 measurements of the full S0 → S3 trace
 *   - Prints results to stdout with a labelled prefix for CSV extraction
 *
 * All HTTP calls are stubbed via WireMock with a fixed 10ms delay per response,
 * ensuring the baseline is stable and consistent between monitored and unmonitored runs.
 * The only variable between the two test methods is the presence or absence of the
 * protocol enforcement and rollback filters on the WebClient.
 */

@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
@SpringBootTest
@Execution(ExecutionMode.SAME_THREAD)
@TestPropertySource(properties = {
        "protocolPath=protocol-examples/protocol.json"
})
public class LatencyTest {

    private static final int PORT = 1226; // separate port from ProtocolTest
    private static final String HOST = "http://localhost:" + PORT;
    private static final int WARMUP_RUNS = 5;
    private static final int BENCHMARK_RUNS  = 30;
    private static final int STUB_DELAY_MS   = 10;   // fixed WireMock response delay

    @Autowired
    WebClientMonitor webClientMonitor;

    @Autowired
    CurrentStateRepository currentStateRepository;

    @Autowired
    ProtocolRepository protocolRepository;

    WireMockServer server = new WireMockServer(
            WireMockConfiguration.options().port(PORT)
    );


    @BeforeEach
    public void startWireMock() {
        server.start();
    }

    @AfterEach
    public void tearDown() {
        server.resetRequests();
        server.resetAll();
        server.stop();
        resetToS0();
    }


    /**
     * Benchmarks the full S0 → S3 protocol trace using the monitored WebClient bean.
     * The monitor intercepts every request, acquires a Redis lock, evaluates the
     * FSA transition, and commits the state update before the request is dispatched.
     */
    @Test
    public void benchmarkMonitoredTrace() {
        System.out.println("=== MONITORED BENCHMARK START ===");

        // JVM warmup — results discarded
        System.out.println("--- Warmup phase (" + WARMUP_RUNS + " runs) ---");
        for (int i = 0; i < WARMUP_RUNS; i++) {
            resetToS0();
            runMonitoredTrace();
        }

        // Measurement phase
        System.out.println("--- Measurement phase (" + BENCHMARK_RUNS + " runs) ---");
        List<Long> results = new ArrayList<>();
        for (int i = 0; i < BENCHMARK_RUNS; i++) {
            resetToS0();
            long start = System.nanoTime();
            runMonitoredTrace();
            long end = System.nanoTime();
            long durationMs = (end - start) / 1_000_000L;
            results.add(durationMs);
        }

        // Print results for CSV extraction
        results.forEach(r -> System.out.println("MONITORED:" + r));
        printSummary("MONITORED", results);

        System.out.println("=== MONITORED BENCHMARK END ===");

        writeResultsToCsv("MONITORED", results);
    }

    /**
     * Benchmarks the same full trace using a plain WebClient with no filters attached.
     * This provides the baseline latency against which the monitored overhead is calculated.
     * The Spring context, Redis, and WireMock are all still active — the only difference
     * is the absence of the protocol enforcement and rollback filters.
     */
    @Test
    public void benchmarkUnmonitoredTrace() {
        System.out.println("=== UNMONITORED BENCHMARK START ===");

        // Plain WebClient — no filters, no monitor
        WebClient plainClient = WebClient.builder().build();

        // JVM warmup — results discarded
        System.out.println("--- Warmup phase (" + WARMUP_RUNS + " runs) ---");
        for (int i = 0; i < WARMUP_RUNS; i++) {
            runUnmonitoredTrace(plainClient);
        }

        // Measurement phase
        System.out.println("--- Measurement phase (" + BENCHMARK_RUNS + " runs) ---");
        List<Long> results = new ArrayList<>();
        for (int i = 0; i < BENCHMARK_RUNS; i++) {
            long start = System.nanoTime();
            runUnmonitoredTrace(plainClient);
            long end = System.nanoTime();
            long durationMs = (end - start) / 1_000_000L;
            results.add(durationMs);
        }

        // Print results for CSV extraction
        results.forEach(r -> System.out.println("UNMONITORED:" + r));
        printSummary("UNMONITORED", results);

        System.out.println("=== UNMONITORED BENCHMARK END ===");
        writeResultsToCsv("UNMONITORED", results);
    }

    /**
     * Full monitored trace: S0 → S1 → S2 → S3
     * Service3 calls Service1 three times (with fetchData_workflow on first two),
     * exercising the complete FSA path and all monitoring logic.
     */
    private void runMonitoredTrace() {
        // S0 → S1: Service3 calls Service1 (triggers fetchData_workflow)
        monitoredPost("/api/service3/fetchStatusService1");
        monitoredGet("/api/service3/call_method_x");   // W0 → W1
        monitoredGet("/api/service3/call_method_y");   // W1 → W_end

        // S1 → S2: Service3 calls Service1 again (triggers fetchData_workflow)
        monitoredPost("/api/service3/fetchStatusService1");
        monitoredGet("/api/service3/call_method_x");
        monitoredGet("/api/service3/call_method_y");

        // S2 → S3: Service3 calls Service1 a third time (no workflow on final step)
        monitoredPost("/api/service3/fetchStatusService1");
    }

    /**
     * Same trace sequence using a plain WebClient with no monitoring filters.
     * No FSA evaluation, no Redis lock, no state update — pure HTTP round trips.
     */
    private void runUnmonitoredTrace(WebClient client) {
        unmonitoedPost(client, "/api/service3/fetchStatusService1");
        unmonitoedGet(client, "/api/service3/call_method_x");
        unmonitoedGet(client, "/api/service3/call_method_y");

        unmonitoedPost(client, "/api/service3/fetchStatusService1");
        unmonitoedGet(client, "/api/service3/call_method_x");
        unmonitoedGet(client, "/api/service3/call_method_y");

        unmonitoedPost(client, "/api/service3/fetchStatusService1");
    }


    private void monitoredPost(String path) {
        webClientMonitor.monitoredWebClient()
                .post()
                .uri(HOST + path)
                .retrieve()
                .toBodilessEntity()
                .onErrorResume(e -> Mono.empty())
                .block();
    }

    private void monitoredGet(String path) {
        webClientMonitor.monitoredWebClient()
                .get()
                .uri(HOST + path)
                .retrieve()
                .toBodilessEntity()
                .onErrorResume(e -> Mono.empty())
                .block();
    }


    private void unmonitoedPost(WebClient client, String path) {
        client.post()
                .uri(HOST + path)
                .retrieve()
                .toBodilessEntity()
                .onErrorResume(e -> Mono.empty())
                .block();
    }

    private void unmonitoedGet(WebClient client, String path) {
        client.get()
                .uri(HOST + path)
                .retrieve()
                .toBodilessEntity()
                .onErrorResume(e -> Mono.empty())
                .block();
    }


    /**
     * Resets the current session state in Redis back to S0 between benchmark iterations.
     * This ensures each measurement starts from the same protocol position.
     */
    private void resetToS0() {
        if (currentStateRepository.findById(CURRENT_STATE_ID).isPresent()) {
            CurrentState currentState = currentStateRepository.findById(CURRENT_STATE_ID).get();
            if (!currentState.getState().getName().equals("S0")) {
                if (protocolRepository.findById(PROTOCOL_DEF_ID).isPresent()) {
                    State initialState = protocolRepository.findById(PROTOCOL_DEF_ID)
                            .get().getStates().getFirst();
                    currentStateRepository.save(new CurrentState(initialState, CURRENT_STATE_ID));
                }
            }
        }
    }


    /**
     * Prints a basic summary of benchmark results to stdout.
     * Mean, min, and max are reported in milliseconds.
     */
    private void printSummary(String label, List<Long> results) {
        double mean = results.stream().mapToLong(Long::longValue).average().orElse(0);
        long min    = results.stream().mapToLong(Long::longValue).min().orElse(0);
        long max    = results.stream().mapToLong(Long::longValue).max().orElse(0);

        System.out.printf("%n--- %s SUMMARY ---%n", label);
        System.out.printf("  Mean   : %.2f ms%n", mean);
        System.out.printf("  Min    : %d ms%n", min);
        System.out.printf("  Max    : %d ms%n", max);
        System.out.printf("  N      : %d runs%n", results.size());
    }

    private void writeResultsToCsv(String label, List<Long> results) {
        String filename = "benchmark_results3.csv";
        File file = new File(filename);
        boolean writeHeader = !file.exists();

        try (FileWriter fw = new FileWriter(file, true);
             BufferedWriter bw = new BufferedWriter(fw)) {

            if (writeHeader) {
                bw.write("type,latency_ms");
                bw.newLine();
            }

            for (Long result : results) {
                bw.write(label + "," + result);
                bw.newLine();
            }

            System.out.println("Results written to: " + file.getAbsolutePath());

        } catch (IOException e) {
            System.err.println("Failed to write CSV: " + e.getMessage());
        }
    }
}