# Minecraft AI Companion

Minecraft AI Companion is an open-source project for building an autonomous AI teammate for Minecraft.

The goal is not to create a chatbot NPC or a simple automation tool. The goal is to create a companion that feels closer to another player: it can observe, remember, plan, cooperate, make mistakes, and grow over time.

> Build a companion, not a tool.

## Current Status

Version: 0.1  
Sprint: 1 - Repository Bootstrap

Sprint 1 focuses on project structure, documentation, and the initial AI Core skeleton. Gameplay integration is intentionally out of scope for this sprint.

## Planned Stack

- Java 21
- Minecraft Fabric
- Gradle
- Ollama for local LLM experiments
- JSON persistence first, SQLite later

## Architecture Direction

The project keeps the AI Core independent from Minecraft:

```text
Minecraft
  -> World Snapshot
  -> AI Core
       -> Memory
       -> Scheduler
       -> Planner
       -> Goal Manager
       -> Skill Manager
       -> LLM Provider
  -> Action
  -> Minecraft
```

Core rule: Minecraft provides the world. The AI Core makes decisions. Skills execute actions. The LLM reasons, but never directly controls the game.

## First MVP

The first playable milestone is an autonomous decision loop:

1. The AI notices that wood is insufficient.
2. The Planner creates a `CollectWoodGoal`.
3. The `CollectWoodSkill` executes.
4. Memory records the result.

## License

This project is licensed under the MIT License.
