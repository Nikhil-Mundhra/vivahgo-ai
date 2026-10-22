"""
Unit and integration tests for the LangGraph multi-agent orchestrator.
"""

import pytest
from unittest.mock import MagicMock
from mcp_client import VivahGoMcpClient, LockConflictError
from orchestrator import create_orchestrator, AgentState


def test_agent_successful_venue_hold():
    mock_mcp = MagicMock(spec=VivahGoMcpClient)

    mock_mcp.call_tool.side_effect = lambda tool_name, args: {
        "get_wedding_context": {
            "weddingId": "w_1",
            "city": "Delhi",
            "targetDate": "2026-11-14",
            "expectedGuests": 500,
            "totalBudget": 3500000,
        },
        "search_vendors": [
            {
                "id": "venue_grand_plaza",
                "businessName": "The Grand Plaza",
                "category": "Venue",
                "city": "Delhi",
                "capacity": 600,
            }
        ],
        "place_temporary_hold": {
            "status": "HOLD_ACTIVE",
            "lockKey": "lock:vendor:venue_grand_plaza:date:2026-11-14",
            "ttlSeconds": 900,
            "vendorName": "The Grand Plaza",
        },
    }[tool_name]

    orchestrator = create_orchestrator(mock_mcp)

    state: AgentState = {
        "user_prompt": "Find me a venue in South Delhi for 500 guests and place a hold",
        "user_id": "u_1",
        "wedding_id": "w_1",
    }

    result = orchestrator.invoke(state)

    assert result["conflict_encountered"] is False
    assert result["requires_human_approval"] is True
    assert result["hold_result"]["status"] == "HOLD_ACTIVE"
    assert "The Grand Plaza" in result["final_message"]


def test_agent_dynamic_pivot_on_lock_conflict():
    """
    Validates Issue #1 & #4: When Redis raises a LockConflictError,
    the supervisor intercepts it and dynamically pivots to an alternative venue
    without crashing or returning an error to the user!
    """
    mock_mcp = MagicMock(spec=VivahGoMcpClient)

    def mock_call(tool_name, args):
        if tool_name == "get_wedding_context":
            return {
                "weddingId": "w_2",
                "city": "Delhi",
                "targetDate": "2026-11-14",
                "expectedGuests": 500,
                "totalBudget": 3500000,
            }
        elif tool_name == "search_vendors":
            return [
                {
                    "id": "venue_grand_plaza",
                    "businessName": "The Grand Plaza",
                    "category": "Venue",
                    "city": "Delhi",
                    "capacity": 600,
                },
                {
                    "id": "venue_riverside_manor",
                    "businessName": "The Riverside Manor",
                    "category": "Venue",
                    "city": "Delhi",
                    "capacity": 520,
                },
            ]
        elif tool_name == "place_temporary_hold":
            # Primary venue is contested!
            if args.get("vendor_id") == "venue_grand_plaza":
                raise LockConflictError(
                    message="Asset currently held",
                    lock_key="lock:vendor:venue_grand_plaza:date:2026-11-14",
                    current_holder="competing_couple_a",
                    remaining_ttl_seconds=840,
                )
            # Alternative venue succeeds!
            elif args.get("vendor_id") == "venue_riverside_manor":
                return {
                    "status": "HOLD_ACTIVE",
                    "lockKey": "lock:vendor:venue_riverside_manor:date:2026-11-14",
                    "ttlSeconds": 900,
                    "vendorName": "The Riverside Manor",
                }
        raise ValueError(f"Unexpected tool: {tool_name}")

    mock_mcp.call_tool.side_effect = mock_call

    orchestrator = create_orchestrator(mock_mcp)

    state: AgentState = {
        "user_prompt": "Lock in The Grand Plaza for Nov 14",
        "user_id": "u_2",
        "wedding_id": "w_2",
    }

    result = orchestrator.invoke(state)

    # Dynamic fallback check!
    assert result["conflict_encountered"] is True
    assert result["alternative_vendor"]["businessName"] == "The Riverside Manor"
    assert "Riverside Manor" in result["final_message"]
    assert "another couple" in result["final_message"].lower()
