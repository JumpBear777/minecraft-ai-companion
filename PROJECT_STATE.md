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

8. Attribute-driven behavior.

   Player-like behavior must read vanilla player attributes and state whenever possible. Do not hard-code movement speed, mining speed, attack damage, attack speed, interaction range, sneaking speed, or similar values when the vanilla `ServerPlayerEntity` already exposes them through `EntityAttributes` or player methods. This is important for mod compatibility because many mods modify behavior through attributes.

9. Simulate the missing client boundary, not the whole player.

   The companion already is a `ServerPlayerEntity`, so server-side player state should not be reimplemented. Missing behavior should be modeled at the boundary where the real `ClientPlayerEntity` would normally read input, update local movement, and send packets to `ServerPlayNetworkHandler`.

10. Reuse vanilla behavior chains beyond players.

   The target behavior is player-like, but implementation research must not stop at player classes. If the player path depends on a real client or does not fit a fake server-side player, inspect vanilla mob and shared entity implementations such as `MobEntity`, `MoveControl`, `JumpControl`, `LivingEntity`, `Entity`, navigation, attributes, and interaction managers. Prefer the mature vanilla chain that best matches the behavior instead of writing a replacement.

## 6.2 Vanilla Source Audit

The current source audit focused on these vanilla classes:

- `Entity`
- `LivingEntity`
- `PlayerEntity`
- `ServerPlayerEntity`
- `ClientPlayerEntity`
- `KeyboardInput`
- `Input`
- `PlayerInput`
- `ServerPlayNetworkHandler`
- `ServerPlayerInteractionManager`
- `MobEntity`
- `MoveControl`
- `JumpControl`

Key findings:

1. Most player state is already inherited.

   Because the companion is a real `ServerPlayerEntity`, it already has the vanilla inventory, hunger manager, experience fields, equipment, abilities, screen handlers, attributes, status effects, statistics hooks, damage handling, sleeping state, riding state, and interaction manager.

2. Player attributes are already present.

   `PlayerEntity.createPlayerAttributes()` defines player defaults such as attack damage, movement speed, attack speed, luck, block interaction range, entity interaction range, block break speed, submerged mining speed, sneaking speed, mining efficiency, sweeping damage ratio, and waypoint ranges.

3. Mod compatibility should flow through attributes.

   Vanilla movement uses `EntityAttributes.MOVEMENT_SPEED`; sprinting applies a temporary movement-speed modifier; speed effects also modify movement speed. Mining uses selected stack speed plus mining-related attributes and status effects. Attacks use attack damage, attack speed, sweeping damage ratio, enchantments, item hooks, and cooldown state.

4. The missing part is not the server player; it is the client loop.

   `ClientPlayerEntity.tickMovement()` reads `Input`, updates sprinting, sneaking, swimming, flying, gliding, mount jump, and movement state, then `sendMovementPackets()` sends position/look/on-ground packets. A fake player has no real `ClientPlayerEntity`, so this loop must be represented by a narrow server-side adapter.

5. Do not copy the whole client class.

   `ClientPlayerEntity` depends on client-only systems such as `MinecraftClient`, screens, options, tutorial state, camera state, and rendering-side concerns. The project should copy the shape of the boundary, not the implementation wholesale.

6. Preferred architecture for active control:

   ```text
   AI Skill Intent
     -> Companion input state
     -> Vanilla-aware fake client movement/input adapter
     -> PlayerInputC2SPacket / PlayerMoveC2SPacket / ClientCommandC2SPacket / interaction packets
     -> ServerPlayNetworkHandler / ServerPlayerInteractionManager
     -> ServerPlayerEntity
   ```

7. Server-side vanilla APIs remain the default for passive and authoritative state.

   Damage, physics tick, status effects, inventory, item pickup, XP pickup, sleeping, riding, block interaction, item use, and mining should stay on vanilla server paths whenever they are available.

8. Current `CompanionInputController` is only a first adapter.

   It proved the packet path works, and it now reads `player.getMovementSpeed()` instead of hard-coding sprint speed. It is not yet a full replacement for the real `ClientPlayerEntity` movement loop.

9. Vanilla mob movement is a useful reference for fake server-side autonomy.

   Sheep and other passive animals do not use client input. Their movement goes through `MoveControl`, `JumpControl`, `LivingEntity.travel()`, and `Entity.move()`. This chain naturally preserves movement speed, jump strength, step height, gravity, fluids, collision, slipperiness, and modded attribute changes. For active movement without a real client, this path can be a better implementation reference than player movement packets alone.

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
- Real manual player attacks now produce visible damage feedback and player-like knockback movement.
- Real dropped item pickup works.
- Real experience orb pickup works.
- Real zombie attacks work.
- Fire spread from a burning zombie to the companion works.
- Water, cactus, fire, lava, and magma block interactions work.
- Death drops work for items recently given to the companion.
- Fake client teleport confirmation now works: when the server sends `PlayerPositionLookS2CPacket`, the local fake connection replies with `TeleportConfirmC2SPacket`, matching the vanilla client handshake.
- Active rotation through `PlayerMoveC2SPacket.LookAndOnGround` works.
- Active single-step movement through `PlayerMoveC2SPacket.Full` works.
- Continuous forward movement through `PlayerInputC2SPacket` plus `PlayerMoveC2SPacket` works.
- Forward jump movement through the same input/move packet path works.
- Active movement now reads `player.getMovementSpeed()`, which reflects vanilla `EntityAttributes.MOVEMENT_SPEED` and speed modifiers.
- Vanilla Speed status effect changes movement behavior through the same movement-speed attribute path.
- Sneak-forward movement has been manually validated.
- Swim-up input has been manually validated.
- Walk-to-interact movement can now cross a one-block dirt obstacle after switching from position-packet movement to a vanilla server-side `LivingEntity.travel()` style path inspired by sheep/mob movement.
- Boat riding and dismounting have been manually validated.
- Bed sleep has been manually validated: daytime sleep fails as vanilla expects, nighttime sleep works, day transition wakes the companion, and explicit wake works.
- A first `CompanionInputController` now centralizes vanilla-style look, forward, jump, and input-release operations for future AI-controlled skills.

Known current risks:

- Fake players do not receive real client movement packets.
- Client-driven movement packets still do not exist, so future active movement cannot rely on a real client input loop.
- The project now has a small server-side input controller, but it is not a full client movement simulator yet.
- `ServerPlayNetworkHandler` normally rolls player position back to the last accepted network position; the companion needs a narrow compatibility patch to preserve server-calculated physics.
- Active walking, steering, jumping, sneaking, and water jump/upward input have passed first manual validation through vanilla packet paths; climbing and obstacle navigation still need deeper validation.
- Position-packet movement is not enough for robust obstacle handling. One-block obstacle traversal failed until the test used vanilla server-side movement state and let `LivingEntity.travel()` / `Entity.move()` handle collision and step behavior.
- Sprinting state works, but the movement adapter must keep matching vanilla client movement semantics and attributes instead of using fixed distances.
- Player-vs-companion knockback still needs a compatibility note: real attacks use the vanilla attack event, but the fake player needs a server-side `takeKnockback` follow-up because it has no real client to retain player-target velocity.
- Equipment and main-hand changes require explicit client synchronization for rendering.
- Duration-based item use must be ticked instead of calling `finishUsing` directly.
- Complex interactions such as containers, sleeping, riding, chunk loading, and respawn remain higher-risk than passive vanilla mechanics.

Latest implementation response:

- `give_food`, `give_blocks`, and `equip_tool` now use `setStackInHand` plus explicit equipment synchronization.
- `equip_armor` now synchronizes equipment immediately.
- `gravity_test` now observes vanilla falling from the current position instead of driving fall movement.
- `velocity_test` now applies vanilla `addVelocity` and observes the result instead of driving teleport-based movement.
- Manual player attacks now use a real attack trigger plus vanilla `takeKnockback` math on the server tick; command-based hurt visuals are no longer treated as the primary validation path.
- `place_front_debug` reports support block, target block, action result, stack count, and final world state.
- The local fake connection is now added to `server.getNetworkIo().getConnections()` so its packet listener is ticked by the normal server network loop.
- A narrow Mixin redirects `ServerPlayNetworkHandler.tickMovement` for `AICompanion` only, preventing the stale client-position reset from overwriting vanilla physics results.
- Manual testing confirmed natural falling and water slowdown now behave like vanilla.
- Manual testing confirmed real player attacks now produce visible knockback again without the previous teleport-based hop task.
- Batch real-trigger testing confirmed item pickup, XP pickup, zombie attacks, fire spread, environmental damage, water interaction, and death drops.
- The local fake connection now mirrors vanilla client teleport confirmation, which unblocks subsequent `ServerPlayNetworkHandler.onPlayerMove` processing after server teleports.
- `/aicompanion look_at_me`, `/aicompanion step_forward`, `/aicompanion walk_forward`, and `/aicompanion jump_forward` have been manually validated.
- `CompanionInputController` was introduced as the first Minecraft-facing input adapter. Future AI skills should call this controller or similar adapter APIs instead of directly mutating entity coordinates.
- `CompanionInputController` now derives forward movement from `player.getMovementSpeed()`, so vanilla movement-speed attributes and speed effects are reflected in fake-client position reports.
- `/aicompanion give_speed` was added to verify vanilla Speed effects and movement attributes.
- `/aicompanion sneak_forward`, `/aicompanion swim_up`, `/aicompanion setup_boat_ride`, `/aicompanion dismount`, `/aicompanion setup_bed_sleep`, and `/aicompanion wake` have been manually validated.
- Source audit of sheep movement found the reusable vanilla chain `MoveControl -> JumpControl -> LivingEntity.travel() -> Entity.move()`. The walk-to-use-block task now uses a server-side travel-style movement path for obstacle traversal instead of relying only on `PlayerMoveC2SPacket` position updates.
- Manual testing confirmed the updated walk-to-use-block task can move toward an interaction target and cross a one-block dirt obstacle.

Current debug commands to test next:

```text
sprinting semantics / climbing / obstacle navigation
container interaction
far chunk loading
death and respawn lifecycle
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
