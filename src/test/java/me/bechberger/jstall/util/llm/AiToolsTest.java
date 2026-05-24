package me.bechberger.jstall.util.llm;

import com.sun.net.httpserver.HttpServer;
import me.bechberger.jstall.analyzer.ResolvedData;
import me.bechberger.jstall.model.ThreadDumpSnapshot;
import me.bechberger.jthreaddump.model.LockInfo;
import me.bechberger.jthreaddump.model.StackFrame;
import me.bechberger.jthreaddump.model.ThreadDump;
import me.bechberger.jthreaddump.model.ThreadInfo;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AiToolsTest {

    private ResolvedData createTestData() {
        ThreadInfo thread1 = new ThreadInfo(
            "main", 1L, 100L, 5, false, Thread.State.RUNNABLE,
            0.5, 10.0,
            List.of(
                new StackFrame("com.example.App", "processRequest", "App.java", 42),
                new StackFrame("com.example.Server", "handleConnection", "Server.java", 100)
            ),
            List.of(), null, null
        );

        ThreadInfo thread2 = new ThreadInfo(
            "worker-1", 2L, 101L, 5, true, Thread.State.BLOCKED,
            0.1, 10.0,
            List.of(
                new StackFrame("java.util.concurrent.locks.ReentrantLock", "lock", null, -1),
                new StackFrame("com.example.Cache", "get", "Cache.java", 55)
            ),
            List.of(
                new LockInfo("0x00000001", "java.util.concurrent.locks.ReentrantLock",
                    LockInfo.LockOperation.WAITING_TO_LOCK)
            ),
            null, null
        );

        ThreadInfo thread3 = new ThreadInfo(
            "worker-2", 3L, 102L, 5, true, Thread.State.WAITING,
            0.0, 10.0,
            List.of(
                new StackFrame("sun.misc.Unsafe", "park", null, -2),
                new StackFrame("java.util.concurrent.locks.LockSupport", "park", "LockSupport.java", 175),
                new StackFrame("com.example.Pool", "awaitTask", "Pool.java", 88)
            ),
            List.of(
                new LockInfo("0x00000002", "java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject",
                    LockInfo.LockOperation.WAITING_ON)
            ),
            null, null
        );

        ThreadInfo thread4 = new ThreadInfo(
            "lock-holder", 4L, 103L, 5, true, Thread.State.RUNNABLE,
            1.2, 10.0,
            List.of(
                new StackFrame("com.example.Cache", "rebuild", "Cache.java", 200)
            ),
            List.of(
                new LockInfo("0x00000001", "java.util.concurrent.locks.ReentrantLock",
                    LockInfo.LockOperation.LOCKED)
            ),
            null, null
        );

        ThreadDump dump = new ThreadDump(
            Instant.now(),
            "OpenJDK 64-Bit Server VM (21.0.1+12)",
            List.of(thread1, thread2, thread3, thread4),
            null,
            null,
            null
        );

        String rawDump = """
            "main" #1 prio=5 os_prio=0 cpu=500ms elapsed=10.0s tid=0x0001 nid=100 runnable
               java.lang.Thread.State: RUNNABLE
                at com.example.App.processRequest(App.java:42)
                at com.example.Server.handleConnection(Server.java:100)

            "worker-1" #2 prio=5 os_prio=0 cpu=100ms elapsed=10.0s tid=0x0002 nid=101 waiting for monitor entry
               java.lang.Thread.State: BLOCKED (on object monitor)
                at com.example.Cache.get(Cache.java:55)
                - waiting to lock <0x00000001> (a java.util.concurrent.locks.ReentrantLock)

            "worker-2" #3 prio=5 os_prio=0 cpu=0ms elapsed=10.0s tid=0x0003 nid=102 waiting on condition
               java.lang.Thread.State: WAITING (parking)
                at sun.misc.Unsafe.park(Native Method)
                at java.util.concurrent.locks.LockSupport.park(LockSupport.java:175)
                at com.example.Pool.awaitTask(Pool.java:88)

            """;

        Map<String, String> sysProps = Map.of(
            "java.version", "21.0.1",
            "java.vm.name", "OpenJDK 64-Bit Server VM",
            "java.class.path", "/app/target/classes",
            "sun.java.command", "com.example.App"
        );

        ThreadDumpSnapshot snapshot = new ThreadDumpSnapshot(dump, rawDump, null, sysProps);
        return ResolvedData.fromDumps(List.of(snapshot));
    }

    @Test
    void testGetToolDefinitions() {
        AiTools tools = new AiTools(createTestData());
        List<ToolDefinition> defs = tools.getToolDefinitions();

        assertThat(defs).isNotEmpty();
        assertThat(defs.stream().map(ToolDefinition::name))
            .contains("get_thread_stack_trace", "search_stack_frames",
                      "get_lock_info", "get_system_properties",
                      "get_top_cpu_threads", "compare_thread_across_dumps");
    }

    @Test
    void testToolDefinitionToOpenAiSchema() {
        AiTools tools = new AiTools(createTestData());
        ToolDefinition def = tools.getToolDefinitions().get(0); // get_thread_stack_trace

        Map<String, Object> schema = def.toOpenAiSchema();
        assertThat(schema.get("type")).isEqualTo("function");
        @SuppressWarnings("unchecked")
        Map<String, Object> function = (Map<String, Object>) schema.get("function");
        assertThat(function.get("name")).isEqualTo("get_thread_stack_trace");
        assertThat(function.get("description")).asString().contains("stack trace");
    }

    @Test
    void testGetThreadStackTrace() {
        AiTools tools = new AiTools(createTestData());
        ToolExecutor executor = tools.createExecutor();

        String result = executor.execute(new ToolCall("1", "get_thread_stack_trace",
            Map.of("thread_name", "main")));

        assertThat(result).contains("main");
        assertThat(result).contains("com.example.App.processRequest");
        assertThat(result).contains("RUNNABLE");
    }

    @Test
    void testGetThreadStackTraceNotFound() {
        AiTools tools = new AiTools(createTestData());
        ToolExecutor executor = tools.createExecutor();

        String result = executor.execute(new ToolCall("1", "get_thread_stack_trace",
            Map.of("thread_name", "nonexistent-thread")));

        assertThat(result).contains("No thread found");
    }

    @Test
    void testSearchStackFrames() {
        AiTools tools = new AiTools(createTestData());
        ToolExecutor executor = tools.createExecutor();

        String result = executor.execute(new ToolCall("1", "search_stack_frames",
            Map.of("pattern", "com.example.Cache")));

        assertThat(result).contains("worker-1");
        assertThat(result).contains("lock-holder");
        assertThat(result).contains("Cache");
    }

    @Test
    void testGetLockInfo() {
        AiTools tools = new AiTools(createTestData());
        ToolExecutor executor = tools.createExecutor();

        String result = executor.execute(new ToolCall("1", "get_lock_info", Map.of()));

        assertThat(result).contains("Blocked Threads");
        assertThat(result).contains("worker-1");
        assertThat(result).contains("Threads Holding Locks");
        assertThat(result).contains("lock-holder");
    }

    @Test
    void testGetSystemProperties() {
        AiTools tools = new AiTools(createTestData());
        ToolExecutor executor = tools.createExecutor();

        String result = executor.execute(new ToolCall("1", "get_system_properties",
            Map.of("filter", "java.version")));

        assertThat(result).contains("java.version");
        assertThat(result).contains("21.0.1");
    }

    @Test
    void testGetSystemPropertiesNoFilter() {
        AiTools tools = new AiTools(createTestData());
        ToolExecutor executor = tools.createExecutor();

        String result = executor.execute(new ToolCall("1", "get_system_properties", Map.of()));

        assertThat(result).contains("java.version");
        assertThat(result).contains("java.vm.name");
    }

    @Test
    void testGetRawThreadDumpSection() {
        AiTools tools = new AiTools(createTestData());
        ToolExecutor executor = tools.createExecutor();

        String result = executor.execute(new ToolCall("1", "get_raw_thread_dump_section",
            Map.of("thread_name", "worker-1")));

        assertThat(result).contains("worker-1");
        assertThat(result).contains("BLOCKED");
    }

    @Test
    void testUnknownTool() {
        AiTools tools = new AiTools(createTestData());
        ToolExecutor executor = tools.createExecutor();

        String result = executor.execute(new ToolCall("1", "nonexistent_tool", Map.of()));
        assertThat(result).contains("Unknown tool");
    }

    @Test
    void testToolCallRecord() {
        ToolCall call = new ToolCall("call_123", "get_thread_stack_trace",
            Map.of("thread_name", "main", "count", 5));

        assertThat(call.getString("thread_name")).isEqualTo("main");
        assertThat(call.getString("missing")).isNull();
        assertThat(call.getString("missing", "default")).isEqualTo("default");
        assertThat(call.getInt("count", 0)).isEqualTo(5);
        assertThat(call.getInt("missing", 10)).isEqualTo(10);
    }

    @Test
    void testChatWithToolsResponse() throws Exception {
        // Set up a fake server that returns tool calls first, then a final response
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();

        AtomicInteger requestCount = new AtomicInteger(0);

        server.createContext("/v1/chat/completions", exchange -> {
            // Read request body to verify tool results are sent back
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int count = requestCount.incrementAndGet();

            String responseBody;
            if (count == 1) {
                // First request: model wants to call get_lock_info
                responseBody = """
                    {
                      "choices": [{
                        "message": {
                          "role": "assistant",
                          "content": null,
                          "tool_calls": [{
                            "id": "call_abc123",
                            "type": "function",
                            "function": {
                              "name": "get_lock_info",
                              "arguments": "{}"
                            }
                          }]
                        }
                      }]
                    }
                    """;
            } else {
                // Second request: should contain tool result; model gives final answer
                assertThat(requestBody).contains("call_abc123");
                assertThat(requestBody).contains("tool");
                responseBody = """
                    {
                      "choices": [{
                        "message": {
                          "role": "assistant",
                          "content": "There is lock contention: worker-1 is blocked waiting for a lock held by lock-holder."
                        }
                      }]
                    }
                    """;
            }

            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();

        try {
            OpenAiLlmProvider provider = new OpenAiLlmProvider("http://127.0.0.1:" + port);
            AiTools aiTools = new AiTools(createTestData());

            StringBuilder collected = new StringBuilder();
            String result = provider.chatWithToolLoop(
                "test-model",
                List.of(new LlmProvider.Message("user", "Analyze the threads")),
                aiTools.getToolDefinitions(),
                aiTools.createExecutor(),
                new LlmProvider.StreamHandlers(collected::append),
                5
            );

            assertThat(result).contains("lock contention");
            assertThat(collected.toString()).isEqualTo(result);
            assertThat(requestCount.get()).isEqualTo(2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testChatWithToolsNoToolCalls() throws Exception {
        // Server returns direct response without tool calls
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();

        server.createContext("/v1/chat/completions", exchange -> {
            String responseBody = """
                {
                  "choices": [{
                    "message": {
                      "role": "assistant",
                      "content": "The application looks healthy."
                    }
                  }]
                }
                """;

            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();

        try {
            OpenAiLlmProvider provider = new OpenAiLlmProvider("http://127.0.0.1:" + port);
            AiTools aiTools = new AiTools(createTestData());

            StringBuilder collected = new StringBuilder();
            String result = provider.chatWithToolLoop(
                "test-model",
                List.of(new LlmProvider.Message("user", "Analyze")),
                aiTools.getToolDefinitions(),
                aiTools.createExecutor(),
                new LlmProvider.StreamHandlers(collected::append),
                5
            );

            assertThat(result).isEqualTo("The application looks healthy.");
            assertThat(collected.toString()).isEqualTo(result);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testChatWithToolsMultipleRounds() throws Exception {
        // Server returns tool calls for 2 rounds, then a final answer
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();
        AtomicInteger requestCount = new AtomicInteger(0);

        server.createContext("/v1/chat/completions", exchange -> {
            int count = requestCount.incrementAndGet();
            String responseBody;
            if (count == 1) {
                // Round 1: ask for lock info
                responseBody = """
                    {"choices": [{"message": {"role": "assistant", "content": null,
                      "tool_calls": [{"id": "call_1", "type": "function",
                        "function": {"name": "get_lock_info", "arguments": "{}"}}]}}]}
                    """;
            } else if (count == 2) {
                // Round 2: ask for stack trace based on lock info
                responseBody = """
                    {"choices": [{"message": {"role": "assistant", "content": null,
                      "tool_calls": [{"id": "call_2", "type": "function",
                        "function": {"name": "get_thread_stack_trace", "arguments": "{\\"thread_name\\": \\"worker-1\\"}"}}]}}]}
                    """;
            } else {
                // Round 3: final answer
                responseBody = """
                    {"choices": [{"message": {"role": "assistant",
                      "content": "worker-1 is blocked on a ReentrantLock held by lock-holder rebuilding cache."}}]}
                    """;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();

        try {
            OpenAiLlmProvider provider = new OpenAiLlmProvider("http://127.0.0.1:" + port);
            AiTools aiTools = new AiTools(createTestData());

            String result = provider.chatWithToolLoop(
                "test-model",
                List.of(new LlmProvider.Message("user", "Why is worker-1 blocked?")),
                aiTools.getToolDefinitions(),
                aiTools.createExecutor(),
                new LlmProvider.StreamHandlers(s -> {}),
                5
            );

            assertThat(result).contains("worker-1");
            assertThat(result).contains("lock-holder");
            assertThat(requestCount.get()).isEqualTo(3);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testChatWithToolsMultipleToolCallsInOneRound() throws Exception {
        // Server returns multiple tool calls in a single response
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();
        AtomicInteger requestCount = new AtomicInteger(0);

        server.createContext("/v1/chat/completions", exchange -> {
            int count = requestCount.incrementAndGet();
            String responseBody;
            if (count == 1) {
                // Two tool calls in one round
                responseBody = """
                    {"choices": [{"message": {"role": "assistant", "content": null,
                      "tool_calls": [
                        {"id": "call_a", "type": "function",
                          "function": {"name": "get_lock_info", "arguments": "{}"}},
                        {"id": "call_b", "type": "function",
                          "function": {"name": "get_top_cpu_threads", "arguments": "{\\"count\\": 3}"}}
                      ]}}]}
                    """;
            } else {
                // Verify both tool results are in the request
                String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                assertThat(requestBody).contains("call_a");
                assertThat(requestBody).contains("call_b");
                responseBody = """
                    {"choices": [{"message": {"role": "assistant",
                      "content": "lock-holder uses 1.2s CPU and holds a lock blocking worker-1."}}]}
                    """;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();

        try {
            OpenAiLlmProvider provider = new OpenAiLlmProvider("http://127.0.0.1:" + port);
            AiTools aiTools = new AiTools(createTestData());

            String result = provider.chatWithToolLoop(
                "test-model",
                List.of(new LlmProvider.Message("user", "Analyze")),
                aiTools.getToolDefinitions(),
                aiTools.createExecutor(),
                new LlmProvider.StreamHandlers(s -> {}),
                5
            );

            assertThat(result).contains("lock-holder");
            assertThat(requestCount.get()).isEqualTo(2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testChatWithToolsMaxIterationsExceeded() throws Exception {
        // Server always returns tool calls, never a final answer
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();
        AtomicInteger requestCount = new AtomicInteger(0);

        server.createContext("/v1/chat/completions", exchange -> {
            int count = requestCount.incrementAndGet();
            String responseBody;
            if (count <= 2) {
                // Keep asking for tools
                responseBody = """
                    {"choices": [{"message": {"role": "assistant", "content": null,
                      "tool_calls": [{"id": "call_%d", "type": "function",
                        "function": {"name": "get_lock_info", "arguments": "{}"}}]}}]}
                    """.formatted(count);
            } else {
                // After max iterations, chatWithToolLoop falls back to streaming chat()
                // Return SSE response
                String sseBody = """
                    data: {"choices":[{"delta":{"content":"Fallback answer."}}]}

                    data: [DONE]

                    """;
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                byte[] body = sseBody.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.getResponseBody().close();
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();

        try {
            OpenAiLlmProvider provider = new OpenAiLlmProvider("http://127.0.0.1:" + port);
            AiTools aiTools = new AiTools(createTestData());

            StringBuilder collected = new StringBuilder();
            String result = provider.chatWithToolLoop(
                "test-model",
                List.of(new LlmProvider.Message("user", "Analyze")),
                aiTools.getToolDefinitions(),
                aiTools.createExecutor(),
                new LlmProvider.StreamHandlers(collected::append),
                2  // Only allow 2 iterations
            );

            // Should have done 2 tool rounds + 1 final streaming request = 3 requests
            assertThat(requestCount.get()).isEqualTo(3);
            assertThat(result).contains("Fallback answer");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testChatWithToolsWithArguments() throws Exception {
        // Verify that tool arguments are correctly parsed and passed
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();
        AtomicInteger requestCount = new AtomicInteger(0);

        server.createContext("/v1/chat/completions", exchange -> {
            int count = requestCount.incrementAndGet();
            String responseBody;
            if (count == 1) {
                responseBody = """
                    {"choices": [{"message": {"role": "assistant", "content": null,
                      "tool_calls": [{"id": "call_1", "type": "function",
                        "function": {"name": "search_stack_frames",
                          "arguments": "{\\"pattern\\": \\"com.example.Cache\\"}"}}]}}]}
                    """;
            } else {
                // The tool result should contain cache-related threads
                String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                assertThat(requestBody).contains("worker-1");
                assertThat(requestBody).contains("lock-holder");
                responseBody = """
                    {"choices": [{"message": {"role": "assistant",
                      "content": "Two threads access Cache: worker-1 (blocked) and lock-holder (rebuilding)."}}]}
                    """;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();

        try {
            OpenAiLlmProvider provider = new OpenAiLlmProvider("http://127.0.0.1:" + port);
            AiTools aiTools = new AiTools(createTestData());

            String result = provider.chatWithToolLoop(
                "test-model",
                List.of(new LlmProvider.Message("user", "What threads use Cache?")),
                aiTools.getToolDefinitions(),
                aiTools.createExecutor(),
                new LlmProvider.StreamHandlers(s -> {}),
                5
            );

            assertThat(result).contains("Cache");
            assertThat(requestCount.get()).isEqualTo(2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testGetTopCpuThreads() {
        AiTools aiTools = new AiTools(createTestData());
        ToolExecutor executor = aiTools.createExecutor();

        String result = executor.execute(new ToolCall("1", "get_top_cpu_threads", Map.of("count", 3)));

        assertThat(result).contains("Top");
        assertThat(result).contains("lock-holder"); // 1.2s CPU - highest
        assertThat(result).contains("main");        // 0.5s CPU - second
    }

    @Test
    void testGetTopCpuThreadsDefaultCount() {
        AiTools aiTools = new AiTools(createTestData());
        ToolExecutor executor = aiTools.createExecutor();

        String result = executor.execute(new ToolCall("1", "get_top_cpu_threads", Map.of()));

        assertThat(result).contains("Top");
        assertThat(result).contains("CPU");
    }

    @Test
    void testGetDependencyTree() {
        AiTools aiTools = new AiTools(createTestData());
        ToolExecutor executor = aiTools.createExecutor();

        String result = executor.execute(new ToolCall("1", "get_dependency_tree", Map.of()));

        // Should return either dependency info or "no dependencies found" message
        assertThat(result).isNotNull();
        assertThat(result).isNotEmpty();
    }

    @Test
    void testCompareThreadAcrossDumps() {
        AiTools aiTools = new AiTools(createTestData());
        ToolExecutor executor = aiTools.createExecutor();

        String result = executor.execute(new ToolCall("1", "compare_thread_across_dumps",
            Map.of("thread_name", "main")));

        // With only 1 dump, should report need for 2+
        assertThat(result).contains("Only 1 dump");
    }

    @Test
    void testCompareThreadAcrossDumpsMultiple() {
        // Create data with 2 dumps
        ThreadInfo thread1 = new ThreadInfo(
            "main", 1L, 100L, 5, false, Thread.State.RUNNABLE,
            0.5, 10.0,
            List.of(new StackFrame("com.example.App", "processRequest", "App.java", 42)),
            List.of(), null, null
        );
        ThreadInfo thread2 = new ThreadInfo(
            "main", 1L, 100L, 5, false, Thread.State.BLOCKED,
            1.0, 20.0,
            List.of(new StackFrame("com.example.App", "waitForLock", "App.java", 100)),
            List.of(), null, null
        );

        ThreadDump dump1 = new ThreadDump(Instant.now(), "JVM", List.of(thread1), null, null, null);
        ThreadDump dump2 = new ThreadDump(Instant.now(), "JVM", List.of(thread2), null, null, null);

        List<ThreadDumpSnapshot> snapshots = List.of(
            new ThreadDumpSnapshot(dump1, "dump1", null, null),
            new ThreadDumpSnapshot(dump2, "dump2", null, null)
        );
        ResolvedData data = ResolvedData.fromDumps(snapshots);

        AiTools aiTools = new AiTools(data);
        ToolExecutor executor = aiTools.createExecutor();

        String result = executor.execute(new ToolCall("1", "compare_thread_across_dumps",
            Map.of("thread_name", "main")));

        assertThat(result).contains("main");
        assertThat(result).contains("Dump 1");
        assertThat(result).contains("Dump 2");
        assertThat(result).contains("RUNNABLE");
        assertThat(result).contains("BLOCKED");
    }

    @Test
    void testToolDefinitionsIncludeNewTools() {
        AiTools aiTools = new AiTools(createTestData());
        List<ToolDefinition> defs = aiTools.getToolDefinitions();

        List<String> names = defs.stream().map(ToolDefinition::name).toList();
        assertThat(names).contains("get_top_cpu_threads");
        assertThat(names).contains("get_dependency_tree");
        assertThat(names).contains("compare_thread_across_dumps");
    }

    @Test
    void testGetThreadStackTraceWithDumpIndex() {
        // Create data with 2 dumps where the thread has different stacks
        ThreadInfo thread1 = new ThreadInfo(
            "main", 1L, 100L, 5, false, Thread.State.RUNNABLE,
            0.5, 10.0,
            List.of(new StackFrame("com.example.App", "init", "App.java", 10)),
            List.of(), null, null
        );
        ThreadInfo thread2 = new ThreadInfo(
            "main", 1L, 100L, 5, false, Thread.State.RUNNABLE,
            1.0, 20.0,
            List.of(new StackFrame("com.example.App", "processRequest", "App.java", 42)),
            List.of(), null, null
        );

        ThreadDump dump1 = new ThreadDump(Instant.now(), "JVM", List.of(thread1), null, null, null);
        ThreadDump dump2 = new ThreadDump(Instant.now(), "JVM", List.of(thread2), null, null, null);

        List<ThreadDumpSnapshot> snapshots = List.of(
            new ThreadDumpSnapshot(dump1, "dump1", null, null),
            new ThreadDumpSnapshot(dump2, "dump2", null, null)
        );
        ResolvedData data = ResolvedData.fromDumps(snapshots);

        AiTools aiTools = new AiTools(data);
        ToolExecutor executor = aiTools.createExecutor();

        // Query dump 1 — should show "init"
        String result1 = executor.execute(new ToolCall("1", "get_thread_stack_trace",
            Map.of("thread_name", "main", "dump_index", 1)));
        assertThat(result1).contains("dump 1/2");
        assertThat(result1).contains("init");

        // Query dump 2 — should show "processRequest"
        String result2 = executor.execute(new ToolCall("2", "get_thread_stack_trace",
            Map.of("thread_name", "main", "dump_index", 2)));
        assertThat(result2).contains("dump 2/2");
        assertThat(result2).contains("processRequest");

        // Default (no dump_index) — should use latest (dump 2)
        String resultDefault = executor.execute(new ToolCall("3", "get_thread_stack_trace",
            Map.of("thread_name", "main")));
        assertThat(resultDefault).contains("latest dump");
        assertThat(resultDefault).contains("processRequest");
    }

    @Test
    void testGetThreadStackTraceWithInvalidDumpIndex() {
        // Create data with 2 dumps
        ThreadInfo thread = new ThreadInfo(
            "main", 1L, 100L, 5, false, Thread.State.RUNNABLE,
            0.5, 10.0,
            List.of(new StackFrame("com.example.App", "run", "App.java", 5)),
            List.of(), null, null
        );

        ThreadDump dump1 = new ThreadDump(Instant.now(), "JVM", List.of(thread), null, null, null);
        ThreadDump dump2 = new ThreadDump(Instant.now(), "JVM", List.of(thread), null, null, null);

        List<ThreadDumpSnapshot> snapshots = List.of(
            new ThreadDumpSnapshot(dump1, "dump1", null, null),
            new ThreadDumpSnapshot(dump2, "dump2", null, null)
        );
        ResolvedData data = ResolvedData.fromDumps(snapshots);

        AiTools aiTools = new AiTools(data);
        ToolExecutor executor = aiTools.createExecutor();

        // Out-of-range index should fall back to latest
        String result = executor.execute(new ToolCall("1", "get_thread_stack_trace",
            Map.of("thread_name", "main", "dump_index", 99)));
        assertThat(result).contains("latest dump");
    }

    @Test
    void testGetThreadStackTraceNotFoundWithDumpIndex() {
        AiTools aiTools = new AiTools(createTestData());
        ToolExecutor executor = aiTools.createExecutor();

        String result = executor.execute(new ToolCall("1", "get_thread_stack_trace",
            Map.of("thread_name", "nonexistent-thread", "dump_index", 1)));
        assertThat(result).contains("No thread found");
        assertThat(result).contains("nonexistent-thread");
    }

    // --- Edge case tests for tools ---

    @Test
    void testSearchStackFramesNoMatch() {
        AiTools tools = new AiTools(createTestData());
        ToolExecutor executor = tools.createExecutor();

        String result = executor.execute(new ToolCall("1", "search_stack_frames",
            Map.of("pattern", "com.nonexistent.Missing")));

        assertThat(result).contains("No threads have");
    }

    @Test
    void testSearchStackFramesBlankPattern() {
        AiTools tools = new AiTools(createTestData());
        ToolExecutor executor = tools.createExecutor();

        String result = executor.execute(new ToolCall("1", "search_stack_frames",
            Map.of("pattern", "  ")));

        assertThat(result).contains("provide a search pattern");
    }

    @Test
    void testGetRawThreadDumpSectionNotFound() {
        AiTools tools = new AiTools(createTestData());
        ToolExecutor executor = tools.createExecutor();

        String result = executor.execute(new ToolCall("1", "get_raw_thread_dump_section",
            Map.of("thread_name", "nonexistent-thread")));

        assertThat(result).contains("not found in raw dump");
    }

    @Test
    void testGetSystemPropertiesNoProperties() {
        // Create data with no system properties
        ThreadInfo thread = new ThreadInfo(
            "main", 1L, 100L, 5, false, Thread.State.RUNNABLE,
            0.5, 10.0, List.of(), List.of(), null, null
        );
        ThreadDump dump = new ThreadDump(Instant.now(), "JVM", List.of(thread), null, null, null);
        ThreadDumpSnapshot snapshot = new ThreadDumpSnapshot(dump, "raw", null, null);
        ResolvedData data = ResolvedData.fromDumps(List.of(snapshot));

        AiTools tools = new AiTools(data);
        ToolExecutor executor = tools.createExecutor();

        String result = executor.execute(new ToolCall("1", "get_system_properties", Map.of()));
        assertThat(result).contains("No system properties");
    }

    @Test
    void testGetSystemPropertiesFilterNoMatch() {
        AiTools tools = new AiTools(createTestData());
        ToolExecutor executor = tools.createExecutor();

        String result = executor.execute(new ToolCall("1", "get_system_properties",
            Map.of("filter", "nonexistent.property.xyz")));

        assertThat(result).contains("No properties matching");
    }

    @Test
    void testGetThreadStackTraceCaseInsensitive() {
        AiTools tools = new AiTools(createTestData());
        ToolExecutor executor = tools.createExecutor();

        // Search with uppercase — should still find "main"
        String result = executor.execute(new ToolCall("1", "get_thread_stack_trace",
            Map.of("thread_name", "MAIN")));

        assertThat(result).contains("main");
        assertThat(result).contains("RUNNABLE");
    }

    @Test
    void testGetThreadStackTracePartialMatch() {
        AiTools tools = new AiTools(createTestData());
        ToolExecutor executor = tools.createExecutor();

        // "worker" should match both worker-1 and worker-2
        String result = executor.execute(new ToolCall("1", "get_thread_stack_trace",
            Map.of("thread_name", "worker")));

        assertThat(result).contains("worker-1");
        assertThat(result).contains("worker-2");
    }

    @Test
    void testCompareThreadAcrossDumpsNotFound() {
        ThreadInfo thread = new ThreadInfo(
            "main", 1L, 100L, 5, false, Thread.State.RUNNABLE,
            0.5, 10.0, List.of(), List.of(), null, null
        );
        ThreadDump dump1 = new ThreadDump(Instant.now(), "JVM", List.of(thread), null, null, null);
        ThreadDump dump2 = new ThreadDump(Instant.now(), "JVM", List.of(thread), null, null, null);
        List<ThreadDumpSnapshot> snapshots = List.of(
            new ThreadDumpSnapshot(dump1, "d1", null, null),
            new ThreadDumpSnapshot(dump2, "d2", null, null)
        );
        ResolvedData data = ResolvedData.fromDumps(snapshots);

        AiTools tools = new AiTools(data);
        ToolExecutor executor = tools.createExecutor();

        String result = executor.execute(new ToolCall("1", "compare_thread_across_dumps",
            Map.of("thread_name", "nonexistent")));

        assertThat(result).contains("not found in any dump");
    }

    @Test
    void testEmptyDumps() {
        ResolvedData data = ResolvedData.fromDumps(List.of());

        AiTools tools = new AiTools(data);
        ToolExecutor executor = tools.createExecutor();

        assertThat(executor.execute(new ToolCall("1", "get_thread_stack_trace",
            Map.of("thread_name", "main")))).contains("No thread dumps");
        assertThat(executor.execute(new ToolCall("2", "search_stack_frames",
            Map.of("pattern", "foo")))).contains("No thread dumps");
        assertThat(executor.execute(new ToolCall("3", "get_lock_info", Map.of())))
            .contains("No thread dumps");
        assertThat(executor.execute(new ToolCall("4", "get_top_cpu_threads", Map.of())))
            .contains("No thread dumps");
        assertThat(executor.execute(new ToolCall("5", "compare_thread_across_dumps",
            Map.of("thread_name", "main")))).contains("No thread dumps");
        assertThat(executor.execute(new ToolCall("6", "get_raw_thread_dump_section",
            Map.of("thread_name", "main")))).contains("No thread dumps");
    }

    @Test
    void testGetTopCpuThreadsNoCpuData() {
        // Create thread with no CPU data (cpuTimeSec = 0)
        ThreadInfo thread = new ThreadInfo(
            "idle-thread", 1L, 100L, 5, false, Thread.State.WAITING,
            0.0, 10.0, List.of(), List.of(), null, null
        );
        ThreadDump dump = new ThreadDump(Instant.now(), "JVM", List.of(thread), null, null, null);
        ThreadDumpSnapshot snapshot = new ThreadDumpSnapshot(dump, "raw", null, null);
        ResolvedData data = ResolvedData.fromDumps(List.of(snapshot));

        AiTools tools = new AiTools(data);
        ToolExecutor executor = tools.createExecutor();

        String result = executor.execute(new ToolCall("1", "get_top_cpu_threads", Map.of()));
        assertThat(result).contains("No CPU time data");
    }

    @Test
    void testGetLockInfoNoBlocked() {
        // All threads RUNNABLE, none blocked
        ThreadInfo thread = new ThreadInfo(
            "happy-thread", 1L, 100L, 5, false, Thread.State.RUNNABLE,
            0.5, 10.0, List.of(), List.of(), null, null
        );
        ThreadDump dump = new ThreadDump(Instant.now(), "JVM", List.of(thread), null, null, null);
        ThreadDumpSnapshot snapshot = new ThreadDumpSnapshot(dump, "raw", null, null);
        ResolvedData data = ResolvedData.fromDumps(List.of(snapshot));

        AiTools tools = new AiTools(data);
        ToolExecutor executor = tools.createExecutor();

        String result = executor.execute(new ToolCall("1", "get_lock_info", Map.of()));
        assertThat(result).contains("No blocked threads found");
        assertThat(result).contains("No explicit lock holders found");
    }

    @Test
    void testToolDefinitionParameterRequired() {
        AiTools tools = new AiTools(createTestData());
        List<ToolDefinition> defs = tools.getToolDefinitions();

        // get_thread_stack_trace: thread_name required, dump_index optional
        ToolDefinition stackTraceTool = defs.stream()
            .filter(d -> d.name().equals("get_thread_stack_trace")).findFirst().orElseThrow();
        assertThat(stackTraceTool.parameters()).hasSize(2);
        assertThat(stackTraceTool.parameters().get(0).required()).isTrue();
        assertThat(stackTraceTool.parameters().get(1).required()).isFalse();

        // get_lock_info has no params
        ToolDefinition lockTool = defs.stream()
            .filter(d -> d.name().equals("get_lock_info")).findFirst().orElseThrow();
        assertThat(lockTool.parameters()).isEmpty();
    }

    @Test
    void testToolCallGetIntFromDouble() {
        // LLM often sends numbers as doubles (e.g. 3.0 instead of 3)
        ToolCall call = new ToolCall("1", "test", Map.of("count", 3.0));
        assertThat(call.getInt("count", 0)).isEqualTo(3);
    }

    @Test
    void testToolCallGetIntFromString() {
        // LLM might also send numbers as strings
        ToolCall call = new ToolCall("1", "test", Map.of("count", "5"));
        assertThat(call.getInt("count", 0)).isEqualTo(5);
    }

    @Test
    void testSearchStackFramesCaseInsensitive() {
        AiTools tools = new AiTools(createTestData());
        ToolExecutor executor = tools.createExecutor();

        // Upper case search should still find matches
        String result = executor.execute(new ToolCall("1", "search_stack_frames",
            Map.of("pattern", "COM.EXAMPLE.CACHE")));

        assertThat(result).contains("worker-1");
        assertThat(result).contains("lock-holder");
    }
}
