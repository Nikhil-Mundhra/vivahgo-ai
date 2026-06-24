# VivahGo Backend

This module represents the Core Java Backend (Execution Layer) of VivahGo's multi-agent architecture.
It serves as a highly performant, type-safe, transactional muscle exposing endpoints to the AI Agent via the Model Context Protocol (MCP).

## Architecture Details

- **Java 21 Virtual Threads**: Handles massive I/O traffic spikes from parallel sub-agent workers seamlessly.
- **SparkJava**: Lightweight web framework exposing the native MCP endpoint.
- **jOOQ & PostgreSQL**: Provides hard transactional truth (ACID Ledger) and type-safe data mapping without traditional ORMs.
- **Redis Distributed Locks**: Manages 15-minute temporary holds to serialize atomic executions and prevent high-speed booking collisions.
