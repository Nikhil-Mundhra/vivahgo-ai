# VivahGo Frontend

This module is the user interface for VivahGo, built with Expo (React Native & React Web).

## Architecture

- **User UI**: Provides a simple chat or form interface.
- **Workflow**:
  - User enters constraints (e.g., "Find me a venue in South Delhi for 500 guests under ₹20 Lakhs...").
  - The frontend sends this natural language prompt to the AI Orchestrator.
  - Receives real-time UI updates (e.g., "I've placed a 15-minute hold on The Grand Plaza. Click to review.") as the AI agent communicates with the backend via MCP.
