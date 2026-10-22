package com.vivahgo.backend.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.fppt.jedismock.RedisServer
import com.vivahgo.backend.db.DatabaseConfig
import com.vivahgo.backend.db.ReservationRepository
import com.vivahgo.backend.redis.RedisLockManager
import redis.clients.jedis.JedisPool
import spock.lang.Shared
import spock.lang.Specification

/**
 * End-to-end specification verifying the SparkJava MCP JSON-RPC transport
 * matching Issue #2 and Issue #3.
 */
class McpProtocolSpec extends Specification {

    @Shared
    RedisServer mockRedis

    @Shared
    JedisPool jedisPool

    @Shared
    DatabaseConfig dbConfig

    McpServer mcpServer
    ObjectMapper mapper = new ObjectMapper()

    def setupSpec() {
        mockRedis = RedisServer.newRedisServer().start()
        jedisPool = new JedisPool(mockRedis.getHost(), mockRedis.getBindPort())
        dbConfig = DatabaseConfig.inMemoryH2()
    }

    def cleanupSpec() {
        if (jedisPool != null) jedisPool.close()
        if (mockRedis != null) mockRedis.stop()
        if (dbConfig != null) dbConfig.close()
    }

    def setup() {
        jedisPool.getResource().withCloseable { jedis -> jedis.flushAll() }
        def lockManager = new RedisLockManager(jedisPool)
        def repository = new ReservationRepository(dbConfig.dsl())
        def service = new McpService(lockManager, repository, mapper)
        mcpServer = new McpServer(4567, service)
    }

    def "tools/list returns the full catalog of required MCP tools"() {
        given: "a JSON-RPC 2.0 tools/list request"
        def req = '{"jsonrpc":"2.0","id":"req-1","method":"tools/list"}'

        when: "the MCP server processes the request"
        def jsonResp = mcpServer.handleJsonRpc(req)
        JsonNode root = mapper.readTree(jsonResp)

        then: "response has tools list containing all core wed-tech tools"
        root.get("jsonrpc").asText() == "2.0"
        root.get("id").asText() == "req-1"

        def tools = root.get("result").get("tools")
        tools.size() >= 8

        def toolNames = tools.collect { it.get("name").asText() }
        toolNames.containsAll([
                "get_wedding_context",
                "get_budget_allocation",
                "search_vendors",
                "check_vendor_availability",
                "place_temporary_hold",
                "request_vendor_quote",
                "get_pending_tasks",
                "add_timeline_event"
        ])
    }

    def "search_vendors tool filters inventory by category and city"() {
        given: "a tools/call request for Venues in Delhi"
        def req = '''
        {
            "jsonrpc": "2.0",
            "id": "req-2",
            "method": "tools/call",
            "params": {
                "name": "search_vendors",
                "arguments": {
                    "category": "Venue",
                    "city": "Delhi"
                }
            }
        }
        '''

        when: "the tool is executed"
        def jsonResp = mcpServer.handleJsonRpc(req)
        JsonNode root = mapper.readTree(jsonResp)

        then: "matching vendors are returned"
        !root.get("result").get("isError").asBoolean()
        def contentText = root.get("result").get("content").get(0).get("text").asText()
        JsonNode vendors = mapper.readTree(contentText)
        vendors.size() >= 2
        vendors.get(0).get("category").asText() == "Venue"
        vendors.get(0).get("city").asText() == "Delhi"
    }

    def "place_temporary_hold secures 15-min lease and competing call receives typed LockConflict"() {
        given: "Couple A places a temporary hold on The Grand Plaza for 2026-11-14"
        def holdReqA = '''
        {
            "jsonrpc": "2.0",
            "id": "req-hold-a",
            "method": "tools/call",
            "params": {
                "name": "place_temporary_hold",
                "arguments": {
                    "vendor_id": "venue_grand_plaza_delhi",
                    "event_date": "2026-11-14",
                    "wedding_id": "wedding_couple_a",
                    "user_id": "user_a"
                }
            }
        }
        '''

        when: "Couple A triggers hold"
        def respA = mcpServer.handleJsonRpc(holdReqA)
        JsonNode rootA = mapper.readTree(respA)

        then: "Couple A successfully secures hold"
        !rootA.get("result").get("isError").asBoolean()
        def payloadA = mapper.readTree(rootA.get("result").get("content").get(0).get("text").asText())
        payloadA.get("status").asText() == "HOLD_ACTIVE"
        payloadA.get("ttlSeconds").asInt() == 900
        payloadA.get("weddingId").asText() == "wedding_couple_a"

        when: "Couple B triggers competing hold for the same vendor and date"
        def holdReqB = '''
        {
            "jsonrpc": "2.0",
            "id": "req-hold-b",
            "method": "tools/call",
            "params": {
                "name": "place_temporary_hold",
                "arguments": {
                    "vendor_id": "venue_grand_plaza_delhi",
                    "event_date": "2026-11-14",
                    "wedding_id": "wedding_couple_b",
                    "user_id": "user_b"
                }
            }
        }
        '''
        def respB = mcpServer.handleJsonRpc(holdReqB)
        JsonNode rootB = mapper.readTree(respB)

        then: "Couple B receives isError=true with LOCK_CONFLICT signal so AI can pivot"
        rootB.get("result").get("isError").asBoolean()
        def conflictData = mapper.readTree(rootB.get("result").get("content").get(0).get("text").asText())
        conflictData.get("error").asText() == "LOCK_CONFLICT"
        conflictData.get("currentHolder").asText() == "wedding_couple_a"
        conflictData.get("remainingTtlSeconds").asInt() > 0
    }
}
