package com.vivahgo.backend.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vivahgo.backend.db.ReservationRecord;
import com.vivahgo.backend.db.ReservationRepository;
import com.vivahgo.backend.redis.HoldResult;
import com.vivahgo.backend.redis.LockConflictException;
import com.vivahgo.backend.redis.RedisLockManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.*;

/**
 * Service implementing business logic for all registered MCP Tools.
 */
public class McpService {
    private static final Logger log = LoggerFactory.getLogger(McpService.class);

    private final RedisLockManager lockManager;
    private final ReservationRepository repository;
    private final ObjectMapper mapper;

    // In-memory mock vendor directory matching VivahGo-Mobile taxonomy
    private final List<Map<String, Object>> mockVendors = new ArrayList<>();

    public McpService(RedisLockManager lockManager, ReservationRepository repository, ObjectMapper mapper) {
        this.lockManager = lockManager;
        this.repository = repository;
        this.mapper = mapper;
        initMockVendors();
    }

    private void initMockVendors() {
        mockVendors.add(Map.of(
                "id", "venue_grand_plaza_delhi",
                "businessName", "The Grand Plaza",
                "category", "Venue",
                "city", "Delhi",
                "state", "Delhi",
                "capacity", 600,
                "basePrice", 1800000,
                "rating", 4.9,
                "tier", "Plus",
                "portfolioUrl", "https://vivahgo.com/vendors/the-grand-plaza"
        ));
        mockVendors.add(Map.of(
                "id", "venue_riverside_manor_delhi",
                "businessName", "The Riverside Manor",
                "category", "Venue",
                "city", "Delhi",
                "state", "Delhi",
                "capacity", 500,
                "basePrice", 1600000,
                "rating", 4.8,
                "tier", "Plus",
                "portfolioUrl", "https://vivahgo.com/vendors/riverside-manor"
        ));
        mockVendors.add(Map.of(
                "id", "venue_royal_palace_noida",
                "businessName", "Royal Heritage Palace",
                "category", "Venue",
                "city", "Noida",
                "state", "Uttar Pradesh",
                "capacity", 800,
                "basePrice", 2200000,
                "rating", 4.7,
                "tier", "Plus",
                "portfolioUrl", "https://vivahgo.com/vendors/royal-palace"
        ));
        mockVendors.add(Map.of(
                "id", "photo_lens_craft_delhi",
                "businessName", "LensCraft Wedding Photography",
                "category", "Photography",
                "city", "Delhi",
                "state", "Delhi",
                "capacity", 1,
                "basePrice", 250000,
                "rating", 4.9,
                "tier", "Plus",
                "portfolioUrl", "https://vivahgo.com/vendors/lens-craft"
        ));
    }

    public ObjectNode getWeddingContext(String userId, String weddingId) {
        ObjectNode node = mapper.createObjectNode();
        node.put("weddingId", weddingId != null ? weddingId : "wedding_demo_101");
        node.put("userId", userId != null ? userId : "user_demo_1");
        node.put("bride", "Ananya");
        node.put("groom", "Kabir");
        node.put("targetDate", "2026-11-14");
        node.put("city", "Delhi");
        node.put("expectedGuests", 500);
        node.put("totalBudget", 3500000); // 35 Lakhs
        node.put("culturalTradition", "North Indian Hindu");
        return node;
    }

    public ObjectNode getBudgetAllocation(String weddingId) {
        ObjectNode root = mapper.createObjectNode();
        root.put("weddingId", weddingId);
        root.put("totalBudget", 3500000);

        ObjectNode categories = root.putObject("categories");
        categories.putObject("Venue").put("allocated", 1800000).put("spent", 0);
        categories.putObject("Catering").put("allocated", 800000).put("spent", 0);
        categories.putObject("Photography").put("allocated", 300000).put("spent", 0);
        categories.putObject("Decor").put("allocated", 400000).put("spent", 0);
        categories.putObject("Music_DJ").put("allocated", 200000).put("spent", 0);
        return root;
    }

    public ArrayNode searchVendors(String category, String city, Double maxPrice, Integer minCapacity) {
        ArrayNode results = mapper.createArrayNode();
        for (Map<String, Object> v : mockVendors) {
            if (category != null && !category.equalsIgnoreCase((String) v.get("category"))) continue;
            if (city != null && !city.equalsIgnoreCase((String) v.get("city"))) continue;
            if (maxPrice != null && ((Number) v.get("basePrice")).doubleValue() > maxPrice) continue;
            if (minCapacity != null && ((Number) v.get("capacity")).intValue() < minCapacity) continue;

            ObjectNode item = mapper.createObjectNode();
            v.forEach((k, val) -> {
                if (val instanceof String s) item.put(k, s);
                else if (val instanceof Integer i) item.put(k, i);
                else if (val instanceof Double d) item.put(k, d);
            });
            results.add(item);
        }
        return results;
    }

    public boolean checkVendorAvailability(String vendorId, String eventDate) {
        LocalDate date = LocalDate.parse(eventDate);
        // Check persistent confirmed/active reservations in PostgreSQL
        Optional<ReservationRecord> activeHold = repository.findActiveHold(vendorId, date);
        if (activeHold.isPresent()) {
            return false;
        }
        // Check Redis 15-min transient hold
        return lockManager.isAvailable(vendorId, eventDate);
    }

    public ObjectNode placeTemporaryHold(String vendorId, String eventDate, String weddingId, String userId) {
        // Find vendor metadata
        Map<String, Object> vendorMeta = mockVendors.stream()
                .filter(v -> v.get("id").equals(vendorId))
                .findFirst()
                .orElse(Map.of("businessName", vendorId, "category", "Venue"));

        String vendorName = (String) vendorMeta.get("businessName");
        String vendorCategory = (String) vendorMeta.get("category");

        // Attempt Redis atomic hold (throws LockConflictException on conflict)
        HoldResult hold = lockManager.acquireHold(vendorId, eventDate, weddingId);

        // Record in ACID PostgreSQL vault
        ReservationRecord record = repository.recordHold(hold, vendorName, vendorCategory, userId);

        // Audit log
        repository.logOperation(
                "InventoryLockAgent",
                weddingId,
                "place_temporary_hold",
                hold.lockKey(),
                "SUCCESS",
                "Hold placed until " + hold.expiresAt()
        );

        ObjectNode response = mapper.createObjectNode();
        response.put("reservationId", record.id().toString());
        response.put("lockKey", hold.lockKey());
        response.put("vendorId", vendorId);
        response.put("vendorName", vendorName);
        response.put("eventDate", eventDate);
        response.put("weddingId", weddingId);
        response.put("status", "HOLD_ACTIVE");
        response.put("ttlSeconds", hold.ttlSeconds());
        response.put("expiresAt", hold.expiresAt().toString());
        response.put("message", String.format("Successfully placed a 15-minute hold on %s for %s.", vendorName, eventDate));
        return response;
    }

    public ObjectNode requestVendorQuote(String vendorId, String weddingId, String customRequirements) {
        ObjectNode response = mapper.createObjectNode();
        response.put("quoteRequestId", "quote_" + UUID.randomUUID());
        response.put("vendorId", vendorId);
        response.put("weddingId", weddingId);
        response.put("status", "SENT_TO_VENDOR");
        response.put("message", "Custom quote request dispatched to vendor dashboard.");
        return response;
    }

    public ArrayNode getPendingTasks(String weddingId) {
        ArrayNode tasks = mapper.createArrayNode();
        tasks.addObject()
                .put("taskId", "task_1")
                .put("title", "Finalize Wedding Venue & Deposit")
                .put("dueDate", "2026-09-01")
                .put("status", "in_progress")
                .put("category", "Venue");
        tasks.addObject()
                .put("taskId", "task_2")
                .put("title", "Select Catering Menu for Sangeet & Reception")
                .put("dueDate", "2026-09-15")
                .put("status", "pending")
                .put("category", "Catering");
        tasks.addObject()
                .put("taskId", "task_3")
                .put("title", "Book Photographer & Videographer")
                .put("dueDate", "2026-09-30")
                .put("status", "pending")
                .put("category", "Photography");
        return tasks;
    }

    public ObjectNode addTimelineEvent(String weddingId, String taskName, String dueDate, String category) {
        ObjectNode node = mapper.createObjectNode();
        node.put("taskId", "task_" + UUID.randomUUID().toString().substring(0, 8));
        node.put("weddingId", weddingId);
        node.put("title", taskName);
        node.put("dueDate", dueDate);
        node.put("category", category != null ? category : "General");
        node.put("status", "pending");
        return node;
    }
}
