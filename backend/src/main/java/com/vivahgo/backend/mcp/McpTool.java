package com.vivahgo.backend.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * MCP Tool definitions conforming to Model Context Protocol specification.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record McpTool(
        String name,
        String description,
        Map<String, Object> inputSchema
) {
    public static McpTool of(String name, String description, Map<String, Object> inputSchema) {
        return new McpTool(name, description, inputSchema);
    }
}
