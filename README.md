# Minecraft AI Companion

Minecraft AI Companion is an open-source project for building an autonomous AI teammate for Minecraft.

The goal is not to create a chatbot NPC or a simple automation tool. The goal is to create a companion that feels closer to another player: it can observe, remember, plan, cooperate, make mistakes, and grow over time.

> Build a companion, not a tool.

## Current Status

Version: 0.1  
Sprint: 1 - Technical Feasibility Prototype

Sprint 1 currently focuses on reducing architecture risk around one question:

> What should represent the companion inside Minecraft?

The current prototype is intentionally minimal. It only proves that a server-side player-like companion can be spawned without a real client connection. It does not implement AI, memory, planning, pathfinding, or autonomous gameplay.

## Planned Stack

- Java 21
- Minecraft Fabric
- Gradle / Fabric Loom
- Ollama for local LLM experiments
- JSON persistence first, SQLite later

## Current Prototype

The prototype targets:

- Minecraft `1.21.11`
- Fabric Loader `0.19.3`
- Fabric API `0.141.4+1.21.11`
- Fabric Loom `1.17.13`
- Java `21`

It registers a command:

```text
/aicompanion spawn
```

The command creates an `AICompanion` `ServerPlayerEntity` using an internal fake/local `ClientConnection`. This is a technical feasibility test for a player-like companion, not a gameplay feature.

## Development Build

On this development machine, use the D drive JDK and keep Gradle caches on D drive:

```powershell
$env:JAVA_HOME='D:\WorkHelper\Java\JDK-21'
$env:GRADLE_USER_HOME='D:\tools\gradle-home'
.\gradlew.bat build --no-daemon
```

The first build can be slow because Fabric Loom downloads Minecraft, mappings, Fabric dependencies, and remaps jars. Normal mod users do not run Gradle; they only use the built mod jar.

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
