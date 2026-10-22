"""
FastAPI Server exposing the LangGraph AI Orchestrator to the Next.js frontend.
"""

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from typing import Optional, Dict, Any
import os
import uvicorn

from mcp_client import VivahGoMcpClient
from orchestrator import create_orchestrator, AgentState

app = FastAPI(title="VivahGo AI Orchestrator Service")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

MCP_SERVER_URL = os.getenv("MCP_SERVER_URL", "http://localhost:4567/mcp")
mcp_client = VivahGoMcpClient(endpoint=MCP_SERVER_URL)
orchestrator = create_orchestrator(mcp_client)


class ChatRequest(BaseModel):
    message: str
    userId: Optional[str] = "user_demo"
    weddingId: Optional[str] = "wedding_demo"
    targetDate: Optional[str] = "2026-11-14"
    city: Optional[str] = "Delhi"
    guestCount: Optional[int] = 500


class ApprovalRequest(BaseModel):
    reservationId: str
    depositAmount: float
    weddingId: str


@app.get("/health")
def health():
    return {"status": "UP", "service": "vivahgo-agent-orchestrator"}


@app.post("/api/chat")
def handle_chat(req: ChatRequest):
    initial_state: AgentState = {
        "user_prompt": req.message,
        "user_id": req.userId,
        "wedding_id": req.weddingId,
        "target_date": req.targetDate,
        "city": req.city,
        "guest_count": req.guestCount,
    }

    try:
        final_state = orchestrator.invoke(initial_state)
        return {
            "reply": final_state.get("final_message"),
            "intent": final_state.get("intent"),
            "conflictEncountered": final_state.get("conflict_encountered", False),
            "holdResult": final_state.get("hold_result"),
            "selectedVendor": final_state.get("selected_vendor"),
            "alternativeVendor": final_state.get("alternative_vendor"),
            "requiresHumanApproval": final_state.get("requires_human_approval", False),
        }
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


if __name__ == "__main__":
    port = int(os.getenv("PORT", "8000"))
    uvicorn.run(app, host="0.0.0.0", port=port)
