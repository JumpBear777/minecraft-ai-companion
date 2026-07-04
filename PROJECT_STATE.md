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

## 6.1 Vanilla And Compatibility Principles

These principles are mandatory for all future implementation work.

1. Vanilla first.

   Every Minecraft-facing behavior should prefer vanilla Minecraft systems, APIs, and call paths before custom logic.

2. Compatibility first.

   The companion is expected to run in modded Minecraft environments. Implementation choices should maximize compatibility with vanilla mechanics and other mods.

3. Source before custom.

   Before implementing a new player behavior, inspect the Minecraft Yarn source and available source index to understand how vanilla implements the same behavior.

4. Preserve original behavior.

   Movement, physics, item use, block interaction, damage, inventory, sleeping, riding, chunk loading, and container behavior should stay as close as possible to real player behavior.

5. Patch narrowly.

   Mixins or internal hooks are allowed only when necessary. They must be scoped as narrowly as possible, preferably to `AICompanion` only, and documented with the vanilla reason they exist.

6. Custom logic is a fallback.

   Custom physics, animation, interaction, and state synchronization should be treated as debug tools or fallback adapters, not the default architecture.

7. Document every architectural exception.

   If a feature cannot use the vanilla path, document why, what was tested, what risk remains, and what future Minecraft version changes may affect it.

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
- Explicit equipment synchronization can make armor and held weapons visible.
- Food can be applied mechanically, and duration-based item use can be represented with a ticked task.
- Block placement works through vanilla-style player interaction.
- The local fake connection can be registered with `ServerNetworkIo` so the companion's `ServerPlayNetworkHandler` is ticked.
- With the stale network-position rollback skipped only for `AICompanion`, vanilla player/entity physics can persist.
- Natural falling now works without the debug `gravity_test` driver.
- Falling into water now slows down like vanilla behavior, which strongly suggests the original physics path is being preserved.
- Manual player attacks now produce more vanilla-like damage movement.

Known current risks:

- Fake players do not receive real client movement packets.
- Client-driven movement packets still do not exist, so future active movement cannot rely on a real client input loop.
- `ServerPlayNetworkHandler` normally rolls player position back to the last accepted network position; the companion needs a narrow compatibility patch to preserve server-calculated physics.
- Active walking, steering, jumping, swimming, and knockback still need deeper validation through vanilla player APIs before any custom movement adapter is accepted.
- Equipment and main-hand changes require explicit client synchronization for rendering.
- Duration-based item use must be ticked instead of calling `finishUsing` directly.
- Block placement needs better `BlockHitResult` and final-world-state debugging.

Latest implementation response:

- `give_food`, `give_blocks`, and `equip_tool` now use `setStackInHand` plus explicit equipment synchronization.
- `equip_armor` now synchronizes equipment immediately.
- `gravity_test` now starts a server-driven fall from the current position.
- `velocity_test` uses server-driven horizontal movement as a diagnostic command.
- `hurt_visual` and manual player attacks now use a server-driven backward hop.
- `place_front_debug` reports support block, target block, action result, stack count, and final world state.
- The local fake connection is now added to `server.getNetworkIo().getConnections()` so its packet listener is ticked by the normal server network loop.
- A narrow Mixin redirects `ServerPlayNetworkHandler.tickMovement` for `AICompanion` only, preventing the stale client-position reset from overwriting vanilla physics results.
- Manual testing confirmed natural falling and water slowdown now behave like vanilla.

Current debug commands to test next:

```text
/aicompanion velocity_test
/aicompanion hurt
/aicompanion hurt_visual
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
