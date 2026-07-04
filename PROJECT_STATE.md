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

Sprint 1 goal: bootstrap the project.

Do not implement gameplay yet.

Focus on:

- Repository
- Architecture
- Project structure
- Agent skeleton

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
