package com.vivahgo.backend.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vivahgo.backend.db.DatabaseConfig;
import com.vivahgo.backend.db.ReservationRepository;
import com.vivahgo.backend.redis.LockConflictException;
import com.vivahgo.backend.redis.RedisLockManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import spark.Spark;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SparkJava MCP Server exposing the Model Context Protocol (MCP) transport.
 *
 * Utilizes Java 21 Virtual Threads to absorb high-concurrency tool calls from the
 * Python LangGraph AI Supervisor.
 */
public class McpServer {
    private static final Logger log = LoggerFactory.getLogger(McpServer.class);

    private final int port;
    private final McpService mcpService;
    private final ObjectMapper mapper;
    private final ExecutorService virtualThreadExecutor;
    private final List<McpTool> toolsRegistry = new ArrayList<>();

    public McpServer(int port, McpService mcpService) {
        this.port = port;
        this.mcpService = mcpService;
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        this.mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        initToolRegistry();
    }

    private void initToolRegistry() {
        toolsRegistry.add(McpTool.of(
                "get_wedding_context",
                "Fetches the foundational constraints for the couple's wedding (budget, city, guests, date).",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "user_id", Map.of("type", "string", "description", "The user ID"),
                                "wedding_id", Map.of("type", "string", "description", "The wedding ID")
                        )
                )
        ));

        toolsRegistry.add(McpTool.of(
                "get_budget_allocation",
                "Retrieves the financial breakdown and budget utilization across Indian wedding categories.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "wedding_id", Map.of("type", "string", "description", "The wedding plan ID")
                        ),
                        "required", List.of("wedding_id")
                )
        ));

        toolsRegistry.add(McpTool.of(
                "search_vendors",
                "Queries vetted vendors filtered by category, city, maximum price, and guest capacity.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "category", Map.of("type", "string", "description", "Category: Venue, Photography, Catering, Decor, etc."),
                                "city", Map.of("type", "string", "description", "Target city: Delhi, Mumbai, Jaipur, etc."),
                                "max_price", Map.of("type", "number", "description", "Maximum budget allocation in INR"),
                                "min_capacity", Map.of("type", "integer", "description", "Minimum guest capacity required")
                        ),
                        "required", List.of("category", "city")
                )
        ));

        toolsRegistry.add(McpTool.of(
                "check_vendor_availability",
                "High-speed query checking if a vendor has an open slot for a specific date.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "vendor_id", Map.of("type", "string", "description", "Vendor identifier"),
                                "event_date", Map.of("type", "string", "description", "Date in YYYY-MM-DD format")
                        ),
                        "required", List.of("vendor_id", "event_date")
                )
        ));

        toolsRegistry.add(McpTool.of(
                "place_temporary_hold",
                "Secures an atomic 15-minute Redis lease hold on a vendor and logs it into PostgreSQL. Returns LockConflict if already taken.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "vendor_id", Map.of("type", "string", "description", "Vendor identifier"),
                                "event_date", Map.of("type", "string", "description", "Date in YYYY-MM-DD format"),
                                "wedding_id", Map.of("type", "string", "description", "Wedding plan identifier"),
                                "user_id", Map.of("type", "string", "description", "User identifier")
                        ),
                        "required", List.of("vendor_id", "event_date", "wedding_id")
                )
        ));

        toolsRegistry.add(McpTool.of(
                "request_vendor_quote",
                "Dispatches a tailored quote inquiry to the vendor dashboard with couple's specifications.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "vendor_id", Map.of("type", "string"),
                                "wedding_id", Map.of("type", "string"),
                                "custom_requirements", Map.of("type", "string")
                        ),
                        "required", List.of("vendor_id", "wedding_id")
                )
        ));

        toolsRegistry.add(McpTool.of(
                "get_pending_tasks",
                "Fetches couple's pending milestone checklist items and deadlines.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "wedding_id", Map.of("type", "string")
                        ),
                        "required", List.of("wedding_id")
                )
        ));

        toolsRegistry.add(McpTool.of(
                "add_timeline_event",
                "Inserts a new milestone or task into the wedding planning timeline.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "wedding_id", Map.of("type", "string"),
                                "task_name", Map.of("type", "string"),
                                "due_date", Map.of("type", "string"),
                                "category", Map.of("type", "string")
                        ),
                        "required", List.of("wedding_id", "task_name", "due_date")
                )
        ));
    }

    public void start() {
        Spark.port(port);

        // CORS filter
        Spark.before((req, res) -> {
            res.header("Access-Control-Allow-Origin", "*");
            res.header("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            res.header("Access-Control-Allow-Headers", "Content-Type, Authorization");
        });

        Spark.options("/*", (req, res) -> "OK");

        // Health endpoint
        Spark.get("/health", (req, res) -> {
            res.type("application/json");
            return "{\"status\":\"UP\",\"service\":\"vivahgo-mcp-server\"}";
        });

        // MCP JSON-RPC 2.0 Endpoint
        Spark.post("/mcp", (req, res) -> {
            res.type("application/json");
            // Execute on Java 21 Virtual Threads
            return CompletableFuture.supplyAsync(() -> handleJsonRpc(req.body()), virtualThreadExecutor).get();
        });

        Spark.awaitInitialization();
        log.info("VivahGo MCP Server started on port {}", port);
    }

    public void stop() {
        Spark.stop();
        Spark.awaitStop();
        virtualThreadExecutor.shutdown();
        log.info("VivahGo MCP Server stopped");
    }

    public String handleJsonRpc(String requestBody) {
        try {
            JsonNode root = mapper.readTree(requestBody);
            String id = root.has("id") ? root.get("id").asText() : "1";
            String method = root.has("method") ? root.get("method").asText() : "";

            if ("tools/list".equals(method)) {
                ObjectNode response = mapper.createObjectNode();
                response.put("jsonrpc", "2.0");
                response.put("id", id);
                ObjectNode result = response.putObject("result");
                ArrayNode toolsArray = result.putArray("tools");
                for (McpTool tool : toolsRegistry) {
                    toolsArray.add(mapper.valueToTree(tool));
                }
                return mapper.writeValueAsString(response);
            }

            if ("tools/call".equals(method)) {
                JsonNode params = root.get("params");
                String toolName = params.has("name") ? params.get("name").asText() : "";
                JsonNode args = params.has("arguments") ? params.get("arguments") : mapper.createObjectNode();

                return executeTool(id, toolName, args);
            }

            // Unknown method
            ObjectNode err = mapper.createObjectNode();
            err.put("jsonrpc", "2.0");
            err.put("id", id);
            err.putObject("error")
                    .put("code", -32601)
                    .put("message", "Method not found: " + method);
            return mapper.writeValueAsString(err);

        } catch (Exception e) {
            log.error("Error processing JSON-RPC request", e);
            ObjectNode err = mapper.createObjectNode();
            err.put("jsonrpc", "2.0");
            err.put("id", "null");
            err.putObject("error")
                    .put("code", -32700)
                    .put("message", "Parse error: " + e.getMessage());
            try {
                return mapper.writeValueAsString(err);
            } catch (Exception ex) {
                return "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32700,\"message\":\"Parse error\"}}";
            }
        }
    }

    private String executeTool(String id, String toolName, JsonNode args) throws Exception {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        ObjectNode result = response.putObject("result");
        ArrayNode content = result.putArray("content");
        ObjectNode textBlock = content.addObject();
        textBlock.put("type", "text");

        try {
            switch (toolName) {
                case "get_wedding_context" -> {
                    String userId = args.has("user_id") ? args.get("user_id").asText() : "user_1";
                    String weddingId = args.has("wedding_id") ? args.get("wedding_id").asText() : "wedding_1";
                    textBlock.put("text", mapper.writeValueAsString(mcpService.getWeddingContext(userId, weddingId)));
                    result.put("isError", false);
                }
                case "get_budget_allocation" -> {
                    String weddingId = args.get("wedding_id").asText();
                    textBlock.put("text", mapper.writeValueAsString(mcpService.getBudgetAllocation(weddingId)));
                    result.put("isError", false);
                }
                case "search_vendors" -> {
                    String category = args.has("category") ? args.get("category").asText() : null;
                    String city = args.has("city") ? args.get("city").asText() : null;
                    Double maxPrice = args.has("max_price") ? args.get("max_price").asDouble() : null;
                    Integer minCap = args.has("min_capacity") ? args.get("min_capacity").asInt() : null;
                    textBlock.put("text", mapper.writeValueAsString(mcpService.searchVendors(category, city, maxPrice, minCap)));
                    result.put("isError", false);
                }
                case "check_vendor_availability" -> {
                    String vendorId = args.get("vendor_id").asText();
                    String eventDate = args.get("event_date").asText();
                    boolean available = mcpService.checkVendorAvailability(vendorId, eventDate);
                    ObjectNode res = mapper.createObjectNode()
                            .put("vendorId", vendorId)
                            .put("eventDate", eventDate)
                            .put("isAvailable", available);
                    textBlock.put("text", mapper.writeValueAsString(res));
                    result.put("isError", false);
                }
                case "place_temporary_hold" -> {
                    String vendorId = args.get("vendor_id").asText();
                    String eventDate = args.get("event_date").asText();
                    String weddingId = args.get("wedding_id").asText();
                    String userId = args.has("user_id") ? args.get("user_id").asText() : "user_agent";
                    textBlock.put("text", mapper.writeValueAsString(mcpService.placeTemporaryHold(vendorId, eventDate, weddingId, userId)));
                    result.put("isError", false);
                }
                case "request_vendor_quote" -> {
                    String vendorId = args.get("vendor_id").asText();
                    String weddingId = args.get("wedding_id").asText();
                    String reqs = args.has("custom_requirements") ? args.get("custom_requirements").asText() : "";
                    textBlock.put("text", mapper.writeValueAsString(mcpService.requestVendorQuote(vendorId, weddingId, reqs)));
                    result.put("isError", false);
                }
                case "get_pending_tasks" -> {
                    String weddingId = args.get("wedding_id").asText();
                    textBlock.put("text", mapper.writeValueAsString(mcpService.getPendingTasks(weddingId)));
                    result.put("isError", false);
                }
                case "add_timeline_event" -> {
                    String weddingId = args.get("wedding_id").asText();
                    String title = args.get("task_name").asText();
                    String date = args.get("due_date").asText();
                    String cat = args.has("category") ? args.get("category").asText() : "General";
                    textBlock.put("text", mapper.writeValueAsString(mcpService.addTimelineEvent(weddingId, title, date, cat)));
                    result.put("isError", false);
                }
                default -> {
                    result.put("isError", true);
                    textBlock.put("text", "Unknown tool: " + toolName);
                }
            }
        } catch (LockConflictException lce) {
            // High-speed collision signal: format structured error so LangGraph supervisor can pivot!
            result.put("isError", true);
            ObjectNode errNode = mapper.createObjectNode();
            errNode.put("error", "LOCK_CONFLICT");
            errNode.put("message", lce.getMessage());
            errNode.put("lockKey", lce.getLockKey());
            errNode.put("currentHolder", lce.currentHolder());
            errNode.put("remainingTtlSeconds", lce.getRemainingTtlSeconds());
            textBlock.put("text", mapper.writeValueAsString(errNode));
        } catch (Exception ex) {
            log.error("Tool execution failed: tool={}", toolName, ex);
            result.put("isError", true);
            textBlock.put("text", "Tool error: " + ex.getMessage());
        }

        return mapper.writeValueAsString(response);
    }

    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "4567"));
        String redisHost = System.getenv().getOrDefault("REDIS_HOST", "localhost");
        int redisPort = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379"));

        JedisPool jedisPool = new JedisPool(new JedisPoolConfig(), redisHost, redisPort);
        RedisLockManager lockManager = new RedisLockManager(jedisPool);

        DatabaseConfig dbConfig = DatabaseConfig.fromEnv();
        dbConfig.initSchema();
        ReservationRepository repo = new ReservationRepository(dbConfig.dsl());

        ObjectMapper mapper = new ObjectMapper();
        McpService service = new McpService(lockManager, repo, mapper);

        McpServer server = new McpServer(port, service);
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down MCP Server...");
            server.stop();
            dbConfig.close();
            jedisPool.close();
        }));
    }
}
