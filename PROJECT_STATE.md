# PROJECT_STATE.md

Project: Minecraft AI Companion  
Version: 0.1 (Sprint 1)  
Last Updated: 2026-07-04

## 1. Project Vision

Minecraft AI Companion is an open-source project that aims to create a truly autonomous AI teammate for Minecraft.

The goal is not to create a chatbot NPC.

The goal is not to create an automation tool.

The goal is to create an AI companion that feels like another player.

The Companion should:

- Think
- Remember
- Plan
- Explore
- Cooperate
- Learn
- Make mistakes
- Grow over time

Slogan:

> Build a companion, not a tool.

## 2. Development Philosophy

1. AI should be proactive instead of command-driven.
2. Documentation evolves with the project.
3. Build small vertical slices instead of huge systems.
4. Every sprint must produce a runnable result.
5. Architecture should remain modular.
6. AI Core must never depend directly on Minecraft.
7. LLM is only responsible for reasoning, not controlling the game.
8. Minecraft only provides the world.

## 3. Current Development Strategy

Use Agile + Prototype + Architecture Decision Records (ADR).

Workflow:

```text
Idea
  -> Small Design
  -> Prototype
  -> Experiment
  -> Review
  -> Update Documentation
  -> Next Sprint
```

## 4. Overall Architecture

```text
Minecraft
  -> World Snapshot
  -> AI Core
       -> Memory
       -> Scheduler
       -> Planner
       -> Goal Manager
       -> Skill Manager
       -> Needs Engine
       -> Personality (future)
       -> Relationship (future)
       -> LLM Provider
  -> Action
  -> Minecraft
```

## 5. Responsibilities

Minecraft:

- Entity
- Animation
- Pathfinding
- Inventory
- Block interaction

AI Core:

- Thinking
- Planning
- Memory
- Goal selection
- Decision making

LLM:

- Long-term reasoning
- High-level planning

Skills:

- Execute actions
- Never make decisions

## 6. Core Principles

- Memory never plans.
- Planner never moves.
- Skill never thinks.
- Minecraft never contains AI logic.
- LLM never directly controls the NPC.

## 7. Current Sprint

Sprint 1 goal: technical feasibility study for the in-game companion representation.

Do not implement AI gameplay yet.

Focus on:

- Player-like representation
- Vanilla compatibility
- Server-side autonomy feasibility
- Debug commands that prove or disprove Minecraft mechanics
- Documentation of risks before AI systems are built

Current architecture direction:

Use a server-side `ServerPlayerEntity` with a local fake `ClientConnection`.

This is intentionally not a chatbot, not a command NPC, and not yet an autonomous AI. It is a technical probe for whether a non-networked player-like entity can become the body of the future companion.

Verified so far:

- Companion can join and leave the world.
- Companion is represented as a `ServerPlayerEntity`.
- Companion has health, hunger, XP, inventory, main hand, offhand, and equipment slots.
- Companion can be damaged in survival mode.
- Companion can receive inventory items.
- Companion can show swing animation and block breaking cracks.
- Companion can perform delayed visual mining.
- Armor/tool server state works and can trigger vanilla advancement state.
- Food can be applied mechanically, but instant eating is not player-like enough.

Known current risks:

- Fake players do not receive real client movement packets.
- Gravity and velocity behavior must be verified and may need server-driven movement.
- Equipment exists server-side but may require explicit client synchronization for rendering.
- Duration-based item use must be ticked instead of calling `finishUsing` directly.
- Block placement needs better `BlockHitResult` and final-world-state debugging.

Current debug commands to test next:

```text
/aicompanion gravity_test
/aicompanion velocity_test
/aicompanion sync_equipment
/aicompanion use_item_visual
/aicompanion place_front_debug
```

## 8. First MVP

The first playable milestone is not chatting.

The first milestone is:

```text
AI notices that wood is insufficient.
  -> Planner creates CollectWoodGoal.
  -> CollectWoodSkill executes.
  -> Memory records completion.
```

That completes the first autonomous decision loop.

## 9. Role Assignment

Human: Project Owner  
ChatGPT: Software Architect  
Codex: Software Engineer

ChatGPT focuses on architecture, design, review, and planning.

Codex focuses on implementation, refactoring, unit tests, and code generation.
