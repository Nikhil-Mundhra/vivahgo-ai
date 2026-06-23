# VivahGo AI Agent Layer

This module represents the probabilistic AI "Brain" layer of the VivahGo architecture.

## Architecture

- **Python + LangGraph**: Uses a Directed Acyclic Graph (DAG) state machine to break down complex wedding planning prompts into manageable, parallel sub-tasks (e.g., Budget Node, Inventory Node, Routing Node).
- **Human-in-the-Loop (HITL)**: Can pause execution for human concierge review of high-value commitments (e.g., ₹5 Lakh catering deposit).
- **Model Context Protocol (MCP)**: Communicates securely via JSON-RPC to the Java backend (the MCP Server), mapping natural language intent into type-safe DB requests.
- **GCP Vertex AI Builder**: Deployment target ensuring traceability and observability of all LLM probabilistic decisions.
