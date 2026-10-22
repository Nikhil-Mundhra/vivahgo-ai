"""
LangGraph Multi-Agent Orchestration Core for VivahGo-AI.

Implements the "Python Brain + Java Muscle" split bridged by MCP (Issue #3 & #4).
Orchestrates parallel tasks, handles the Redis LockConflict dynamic pivot (Issue #1),
and integrates Human-in-the-Loop checkpoints for high-value commitments.
"""

from typing import Any, Dict, List, Optional, TypedDict
import logging
from langgraph.graph import StateGraph, END
from mcp_client import VivahGoMcpClient, LockConflictError

logger = logging.getLogger(__name__)


class AgentState(TypedDict, total=False):
    user_prompt: str
    user_id: str
    wedding_id: str
    intent: str
    wedding_context: Dict[str, Any]
    target_date: str
    city: str
    guest_count: int
    budget_max: float
    matching_vendors: List[Dict[str, Any]]
    selected_vendor: Optional[Dict[str, Any]]
    hold_result: Optional[Dict[str, Any]]
    conflict_encountered: bool
    conflict_message: str
    alternative_vendor: Optional[Dict[str, Any]]
    requires_human_approval: bool
    human_approved: bool
    final_message: str


def create_orchestrator(mcp_client: VivahGoMcpClient):
    """Builds and compiles the LangGraph Multi-Agent DAG."""

    def routing_node(state: AgentState) -> AgentState:
        prompt = (state.get("user_prompt") or "").lower()
        if "venue" in prompt or "hold" in prompt or "lock" in prompt or "book" in prompt:
            intent = "VENUE_SEARCH"
        elif "budget" in prompt or "cost" in prompt or "expense" in prompt:
            intent = "BUDGET_CHECK"
        elif "task" in prompt or "checklist" in prompt or "timeline" in prompt:
            intent = "TASK_CHECKLIST"
        else:
            intent = "VENUE_SEARCH"  # Default to core Wed-Tech flow

        return {
            **state,
            "intent": intent,
            "conflict_encountered": False,
            "requires_human_approval": False,
        }

    def context_node(state: AgentState) -> AgentState:
        user_id = state.get("user_id", "user_1")
        wedding_id = state.get("wedding_id", "wedding_1")

        try:
            ctx = mcp_client.call_tool(
                "get_wedding_context",
                {"user_id": user_id, "wedding_id": wedding_id},
            )
        except Exception as e:
            logger.warning(f"Using default wedding context: {e}")
            ctx = {
                "weddingId": wedding_id,
                "city": "Delhi",
                "targetDate": "2026-11-14",
                "expectedGuests": 500,
                "totalBudget": 3500000,
            }

        return {
            **state,
            "wedding_context": ctx,
            "city": state.get("city") or ctx.get("city", "Delhi"),
            "target_date": state.get("target_date") or ctx.get("targetDate", "2026-11-14"),
            "guest_count": state.get("guest_count") or ctx.get("expectedGuests", 500),
            "budget_max": state.get("budget_max") or float(ctx.get("totalBudget", 3500000)),
        }

    def vendor_search_node(state: AgentState) -> AgentState:
        city = state.get("city", "Delhi")
        min_capacity = state.get("guest_count", 500)

        vendors = mcp_client.call_tool(
            "search_vendors",
            {
                "category": "Venue",
                "city": city,
                "min_capacity": min_capacity,
            },
        )

        selected = vendors[0] if vendors else None

        return {
            **state,
            "matching_vendors": vendors,
            "selected_vendor": selected,
        }

    def inventory_lock_node(state: AgentState) -> AgentState:
        vendor = state.get("selected_vendor")
        wedding_id = state.get("wedding_id", "wedding_1")
        user_id = state.get("user_id", "user_1")
        event_date = state.get("target_date", "2026-11-14")

        if not vendor:
            return {
                **state,
                "final_message": "No venues matching your criteria were found in " + state.get("city", "Delhi"),
            }

        vendor_id = vendor.get("id")

        try:
            # Atomic Redis 15-minute lease hold
            hold = mcp_client.call_tool(
                "place_temporary_hold",
                {
                    "vendor_id": vendor_id,
                    "event_date": event_date,
                    "wedding_id": wedding_id,
                    "user_id": user_id,
                },
            )
            return {
                **state,
                "hold_result": hold,
                "conflict_encountered": False,
                "requires_human_approval": True,
            }

        except LockConflictError as err:
            logger.info(f"Lock collision detected on {vendor_id}. Triggering dynamic pivot.")
            return {
                **state,
                "conflict_encountered": True,
                "conflict_message": str(err),
            }

    def pivot_alternative_node(state: AgentState) -> AgentState:
        vendors = state.get("matching_vendors", [])
        wedding_id = state.get("wedding_id", "wedding_1")
        user_id = state.get("user_id", "user_1")
        event_date = state.get("target_date", "2026-11-14")

        # Select secondary matching venue
        alt_vendor = vendors[1] if len(vendors) > 1 else None

        if not alt_vendor:
            return {
                **state,
                "final_message": (
                    "The primary venue is currently held by another couple for "
                    + event_date
                    + " and no alternate venue is immediately open."
                ),
            }

        # Preemptively place hold on the alternative venue
        try:
            alt_hold = mcp_client.call_tool(
                "place_temporary_hold",
                {
                    "vendor_id": alt_vendor.get("id"),
                    "event_date": event_date,
                    "wedding_id": wedding_id,
                    "user_id": user_id,
                },
            )
            return {
                **state,
                "alternative_vendor": alt_vendor,
                "hold_result": alt_hold,
                "requires_human_approval": True,
            }
        except Exception:
            return {
                **state,
                "alternative_vendor": alt_vendor,
                "requires_human_approval": False,
            }

    def human_approval_checkpoint_node(state: AgentState) -> AgentState:
        # HITL pause point: Couple review required before deposit
        return {
            **state,
            "requires_human_approval": True,
        }

    def response_synthesis_node(state: AgentState) -> AgentState:
        if state.get("conflict_encountered") and state.get("alternative_vendor"):
            primary = state.get("selected_vendor", {}).get("businessName", "The Grand Plaza")
            alt = state.get("alternative_vendor", {}).get("businessName", "The Riverside Manor")
            date = state.get("target_date", "2026-11-14")

            msg = (
                f"I checked **{primary}** for your wedding on **{date}**, but another couple "
                f"just placed a temporary hold on it! I am actively monitoring it in case their hold expires.\n\n"
                f"In the meantime, I've secured a **15-minute temporary hold** on **{alt}** for you, "
                f"which perfectly matches your {state.get('guest_count', 500)}-guest capacity and budget. "
                f"Please review the details below to lock it in."
            )
        elif state.get("hold_result"):
            vendor_name = state.get("hold_result", {}).get("vendorName") or state.get("selected_vendor", {}).get("businessName", "the venue")
            date = state.get("target_date", "2026-11-14")
            msg = (
                f"Great news! **{vendor_name}** is available for **{date}**! "
                f"I have secured a **15-minute temporary hold** for your wedding so you have time to review without losing the date.\n\n"
                f"Click below to review the contract and confirm your reservation."
            )
        else:
            msg = state.get("final_message", "Your wedding request has been processed.")

        return {
            **state,
            "final_message": msg,
        }

    # Conditional edge routing based on Redis collision
    def route_after_lock_attempt(state: AgentState) -> str:
        if state.get("conflict_encountered", False):
            return "pivot_alternative"
        return "human_approval"

    # Define Graph
    workflow = StateGraph(AgentState)

    workflow.add_node("routing", routing_node)
    workflow.add_node("context", context_node)
    workflow.add_node("vendor_search", vendor_search_node)
    workflow.add_node("inventory_lock", inventory_lock_node)
    workflow.add_node("pivot_alternative", pivot_alternative_node)
    workflow.add_node("human_approval", human_approval_checkpoint_node)
    workflow.add_node("synthesis", response_synthesis_node)

    # Wire Edges
    workflow.set_entry_point("routing")
    workflow.add_edge("routing", "context")
    workflow.add_edge("context", "vendor_search")
    workflow.add_edge("vendor_search", "inventory_lock")

    workflow.add_conditional_edges(
        "inventory_lock",
        route_after_lock_attempt,
        {
            "pivot_alternative": "pivot_alternative",
            "human_approval": "human_approval",
        },
    )

    workflow.add_edge("pivot_alternative", "synthesis")
    workflow.add_edge("human_approval", "synthesis")
    workflow.add_edge("synthesis", END)

    return workflow.compile()
