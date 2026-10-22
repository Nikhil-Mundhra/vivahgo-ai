"""
MCP JSON-RPC Client for VivahGo-AI.

Communicates with the Java 21 SparkJava MCP Server over JSON-RPC 2.0.
Handles typed errors, specifically detecting 'LOCK_CONFLICT' so the LangGraph
AI supervisor can dynamically pivot without crashing.
"""

from dataclasses import dataclass
import json
import logging
from typing import Any, Dict, List, Optional
import httpx

logger = logging.getLogger(__name__)


@dataclass
class LockConflictError(Exception):
    message: str
    lock_key: str
    current_holder: str
    remaining_ttl_seconds: int

    def __str__(self) -> str:
        return (
            f"LockConflict: {self.message} (Key={self.lock_key}, "
            f"HeldBy={self.current_holder}, TTL={self.remaining_ttl_seconds}s)"
        )


class VivahGoMcpClient:
    def __init__(self, endpoint: str = "http://localhost:4567/mcp"):
        self.endpoint = endpoint
        self._client = httpx.Client(timeout=15.0)

    def list_tools(self) -> List[Dict[str, Any]]:
        """Queries the Java MCP server for all registered tools."""
        payload = {
            "jsonrpc": "2.0",
            "id": "list-tools-req",
            "method": "tools/list",
            "params": {},
        }
        resp = self._client.post(self.endpoint, json=payload)
        resp.raise_for_status()
        data = resp.json()
        return data.get("result", {}).get("tools", [])

    def call_tool(self, tool_name: str, args: Dict[str, Any]) -> Any:
        """
        Executes an MCP tool call on the Java server.

        Raises LockConflictError if the asset is contested so the LangGraph
        supervisor can intercept and branch to alternative inventory.
        """
        payload = {
            "jsonrpc": "2.0",
            "id": f"call-{tool_name}",
            "method": "tools/call",
            "params": {
                "name": tool_name,
                "arguments": args,
            },
        }

        resp = self._client.post(self.endpoint, json=payload)
        resp.raise_for_status()
        data = resp.json()

        result = data.get("result", {})
        is_error = result.get("isError", False)
        content = result.get("content", [])
        text_content = content[0].get("text", "") if content else ""

        # Attempt to parse json payload
        try:
            parsed = json.loads(text_content)
        except (json.JSONDecodeError, TypeError):
            parsed = text_content

        if is_error:
            if isinstance(parsed, dict) and parsed.get("error") == "LOCK_CONFLICT":
                raise LockConflictError(
                    message=parsed.get("message", "Asset currently held"),
                    lock_key=parsed.get("lockKey", ""),
                    current_holder=parsed.get("currentHolder", "unknown"),
                    remaining_ttl_seconds=parsed.get("remainingTtlSeconds", 0),
                )
            raise RuntimeError(f"MCP tool '{tool_name}' failed: {parsed}")

        return parsed

    def close(self):
        self._client.close()
