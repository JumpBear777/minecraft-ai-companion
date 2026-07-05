# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Read These First

Two documents govern this repo and take precedence over anything here:

- **`AGENTS.md`** — mandatory engineering principles for all AI coding agents. The rules on *vanilla-first*, *narrow patching*, and *Sprint scope* are binding, not advisory.
- **`PROJECT_STATE.md`** — the living record of the vision, architecture decisions, current sprint, verified capabilities, and known risks. Update it when an architectural conclusion changes.

## Build & Run

The project requires a specific JDK 21 and Gradle toolchain via environment variables (Windows). The first build is slow because Fabric Loom downloads Minecraft, mappings, and remaps jars.

```bash
# Build
JAVA_HOME='D:\WorkHelper\Java\JDK-21' \
GRADLE_USER_HOME='D:\tools\gradle-home' \
'D:\tools\Gradle\gradle-9.6.1\bin\gradle.bat' build --no-daemon

# Run the dev client
JAVA_HOME='D:\WorkHelper\Java\JDK-21' \
GRADLE_USER_HOME='D:\tools\gradle-home' \
'D:\tools\Gradle\gradle-9.6.1\bin\gradle.bat' runClient --no-daemon
```

`run-client.bat` wraps `runClient`. **Run the Gradle build before committing whenever code changes.** There is no test suite yet; validation is manual, in-world, via the debug commands below.

## Testing = In-World Debug Commands

This is a feasibility prototype with **no automated tests**. Every capability is validated by hand through `/aicompanion <subcommand>`, registered in `CompanionDebugCommands.java`. Typical loop: `/aicompanion spawn` → run a movement/interaction subcommand → observe. See `docs/player-behavior-test-plan.md` for the intended manual test matrix and `PROJECT_STATE.md` §7 for what has already been validated and what to test next.

## Minecraft Source (for "source before custom")

Decompiled Yarn source for the exact target version lives at **`D:\WorkSpace\mc-sources\minecraft-1.21.11-yarn`** (packages under `net/minecraft/...`, plus `com/mojang/...`). This is the authoritative reference for the *inspect the vanilla path before implementing* rule. Grep it before writing any Minecraft-facing behavior — key classes to consult are `ServerPlayerEntity`, `ClientPlayerEntity`, `ServerPlayNetworkHandler`, `ServerPlayerInteractionManager`, `LivingEntity`, `Entity`, `MobEntity`, `MoveControl`, `JumpControl`, navigation/`PathNodeMaker`, and the C2S/S2C packet classes.

## Core Architecture

### The central technical bet
The companion **is a real server-side `ServerPlayerEntity`** driven by a fake, non-networked `ClientConnection` — not a `MobEntity` or NPC. This means it inherits vanilla inventory, hunger, attributes, damage, equipment, interaction manager, etc. for free. The entire project exists to answer: *can a client-less player entity become the body of an autonomous companion while preserving vanilla behavior?*

- `FakeCompanionSpawner` — creates the singleton `AICompanion` player (fixed name/UUID), builds a `LocalClientConnection`, registers it with `server.getNetworkIo().getConnections()` so its `ServerPlayNetworkHandler` gets ticked, and connects it via `PlayerManager.onPlayerConnect`. `isCompanion(...)` is the identity check used everywhere.
- `LocalClientConnection` — the fake connection. Notably it mirrors the vanilla client teleport handshake (replying `TeleportConfirmC2SPacket` to server position packets) so `onPlayerMove` processing isn't blocked.

### The one Mixin (keep it this narrow)
`mixin/ServerPlayNetworkHandlerMixin` redirects `ServerPlayerEntity.updatePositionAndAngles` inside `tickMovement` **only for the companion**. Vanilla rolls a player back to its last client-accepted network position; the companion has no real client, so without this redirect vanilla server-side physics results get overwritten every tick. This is the sole compatibility patch — new Mixins are a last resort and must be scoped to the companion.

### Movement has two distinct paths (this matters)
1. **Fake-client packet path** (`CompanionInputController`) — synthesizes `PlayerInputC2SPacket` / `PlayerMoveC2SPacket` / `ClientCommandC2SPacket` and feeds them to `networkHandler`, imitating what a real `ClientPlayerEntity` would send. Good for look/rotation, sprint state, simple steps. Movement distance is derived from `player.getMovementSpeed()` (vanilla `EntityAttributes.MOVEMENT_SPEED`), **never hard-coded**, for mod compatibility.
2. **Server-side travel path** (`CompanionVanillaMoveControl`, and `applyServerTravelForward`) — reuses the vanilla mob chain `MoveControl → JumpControl → LivingEntity.travel() → Entity.move()`. This is required for real collision/step/obstacle handling; the packet path alone failed to cross a one-block obstacle.

When adding movement-like behavior, prefer the complete vanilla chain (goal/task → targeting → navigation conditions → path node maker/penalties → navigation → move control). If the chain must break because the body is a `ServerPlayerEntity`, bridge only that body mismatch and document the break point.

### Behavior modules (registered in `MinecraftAiCompanionMod.onInitialize`)
- `CompanionLifeSystem` — the current Sprint 2 focus. A conservative background idle presence (pause, random look-around, look-at-nearby-player, occasional wandering). Wandering uses a vanilla `VillagerEntity` pathfinding proxy (`NoPenaltyTargeting` + `MobNavigation` + `LandPathNodeMaker` + `PathNodeType` penalties) because the companion isn't a `PathAwareEntity`, then adapts the resulting path onto the player body.
- `CompanionMiningTasks` / `CompanionBehaviorTestTasks` — ticked task registrations for delayed/visual actions (mining cracks, item use duration, etc.). Duration-based item use must be *ticked*, not finished directly.
- `CompanionCombatHooks` — real attack triggering plus a server-side `takeKnockback` follow-up (the fake player has no client to retain player-target velocity).
- `CompanionDebugCommands` — all `/aicompanion` subcommands.

### The intended (not-yet-built) AI architecture
The AI Core is deliberately **absent** and must stay decoupled from Minecraft:
`Minecraft (world only) → World Snapshot → AI Core (Memory / Planner / Goal / Skill / Needs / LLM) → Action → Minecraft`.
Invariants: Memory never plans, Planner never moves, Skill never thinks, Minecraft holds no AI logic, LLM never directly controls the entity. **Do not implement AI, memory, planner, or LLM behavior unless explicitly requested** — Sprint 2 is scoped to the vanilla life-system prototype.

## Non-Negotiable Working Rules (from AGENTS.md)

- **Inspect the Yarn source before implementing any Minecraft-facing behavior.** Understand the vanilla path first; custom logic is a documented fallback only after source investigation proves vanilla can't be reused.
- **Reuse already-validated project paths** before introducing a new experimental one.
- **No custom navigation/wandering shortcuts** — movement must go through an understood vanilla navigation chain or adapter.
- **Read player-like values from attributes** (`EntityAttributes`), never hard-code speed/damage/range/etc.
- Optimize for **one human + one companion on one machine**; don't build mod APIs, plugin systems, or broad abstraction layers before the vanilla companion is excellent.
- Keep LLM usage sparse (future): primitive skills never call the LLM.
- Don't commit generated run worlds, caches, or build outputs.
