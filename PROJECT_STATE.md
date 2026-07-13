# PROJECT_STATE.md

Project: Minecraft AI Companion  
Version: 0.1 (Sprint 3)  
Last Updated: 2026-07-09

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

## 6.0 Architecture Discipline

These are current design disciplines, not permanent laws. They should guide early development without forcing premature platform work.

1. Do not build for future features.

   Build so future features do not require rewrites. The project should not implement mod APIs, plugin systems, or broad extension frameworks before the vanilla companion is excellent.

2. Leave seams, not unused systems.

   Future extension points should remain thin and practical. The likely long-term seams are world view, behavior/skill execution, and knowledge. Do not add concrete support for future mods until a real use case exists.

3. Use two design checks.

   Before adding architecture, ask:

   ```text
   If future mod support never happens, is this design still good?
   If future mod support happens, does this design avoid a rewrite?
   ```

4. Prefer one player and one companion.

   The default product target is one computer, one human player, and one AI companion. Optimize the early architecture for this common use case before designing for many companions or server-scale automation.

5. Keep model calls sparse.

   The companion should not call an LLM every tick. Most behavior should be handled by deterministic behavior and skill systems. LLM calls should be reserved for conversation, major events, long-term planning, or meaningful plan revisions.

6. Separate primitive and reasoning skills later.

   This split is a likely future direction, but it should not be overbuilt now.

   ```text
   Primitive skills: MoveTo, MineBlock, Attack, Equip, Eat, OpenChest
   Reasoning skills: CollectWood, Explore, BuildShelter, OrganizeInventory
   ```

   Primitive skills should never call the LLM. Reasoning skills may request higher-level reasoning when the current plan genuinely needs revision.

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

Sprint 2 goal: Life System research and first prototype.

Do not implement AI gameplay yet.

Focus on:

- Vanilla life behavior research
- Background idle presence
- Pause and attention behavior
- Strict vanilla behavior reuse
- Avoiding custom navigation shortcuts
- Documentation of risks before AI systems are built

Current architecture direction:

Use a server-side `ServerPlayerEntity` with a local fake `ClientConnection`.

This is intentionally not a chatbot, not a command NPC, and not yet an autonomous AI. It is a technical probe for whether a non-networked player-like entity can become the body of the future companion.

Sprint 2 prototype direction:

Use a small background `CompanionLifeSystem` that only runs when the companion is not busy. The first version is intentionally conservative: pause, random look-around, look-at-nearby-player, and occasional short idle wandering. Wandering uses a vanilla `VillagerEntity` pathfinding proxy with `NoPenaltyTargeting`, `MobNavigation`, `LandPathNodeMaker`, `PathNodeType` penalties, and `MobEntity.setPositionTarget(...)`, then adapts the resulting path to the `ServerPlayerEntity` body.

Important Sprint 2 architecture principle:

Movement-like behavior must prefer complete vanilla behavior chains over isolated feature copying. For wandering, safety, obstacle handling, fluid handling, and hazard avoidance, the relevant chain is goal/task intent -> targeting -> navigation conditions -> path node maker/types/penalties -> navigation/path -> movement execution. If the chain must break because the companion body is a `ServerPlayerEntity`, the adapter should bridge only that body mismatch.

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
- Sprint 2 source research documented reusable vanilla life patterns from sheep, wolf, cat, fox, bee, villager, skeleton, and zombie.
- A first conservative `CompanionLifeSystem` prototype now runs in the background and provides idle pauses, random look-around, nearby-player attention, and occasional vanilla-proxy idle wandering.
- Idle wandering has been manually validated to be visible after cadence tuning and to avoid lava through the vanilla proxy/path penalty chain.

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
- Life System wandering is still a prototype. It cannot directly attach `WanderAroundGoal` because the companion is not a `PathAwareEntity`, but it now reuses the vanilla villager proxy/navigation/path penalty chain before adapting movement to the player body.

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

## 7.1 Sprint 3 - Task System Foundation

Sprint 3 goal: introduce the first reusable Task System.

Still not implementing AI, LLM, or planning. The objective is the smallest architecture that lets
the companion run an entire objective continuously (e.g. `CollectDroppedItemsTask`) instead of
requiring a sequence of debug commands, then hand control back to the Life System.

### Architecture chosen

Three small pieces in a new `task` subpackage:

1. `CompanionTask` (interface) + `TaskStatus` (enum: RUNNING/SUCCESS/FAILURE/CANCELLED).

   The interface is `start` / `tick` -> `TaskStatus` / `stop` / `describe`. It is deliberately
   shaped after vanilla `net.minecraft.entity.ai.goal.Goal`
   (`canStart`/`start`/`tick`/`stop`), so the lifecycle is familiar and planner-friendly, but it
   does not drag in the goal-selector machinery, which assumes a `MobEntity`/`PathAwareEntity`
   body rather than a `ServerPlayerEntity`.

2. `CompanionTaskManager`.

   Owns exactly one current task per companion. `assign(task)` cancels any previous task, calls
   `start`, then ticks the task every `END_SERVER_TICK`. When `tick` returns a terminal status the
   manager calls `stop` and clears the task, which lets the Life System resume on the next tick.
   This is the seam a future planner will use: it only calls `assign`; it never inspects task
   internals.

3. `CompanionNavigator`.

   A reusable movement adapter. It is a straight extraction of the already-validated path-follow
   logic from `CompanionBehaviorTestTasks.NavigationForwardTask`: borrow vanilla pathfinding through
   a disposable villager proxy (`getNavigation().findPathTo`), then follow the path with
   `CompanionInputController.applyServerTravelForward`, with the existing stuck/obstacle jump and
   node-advance heuristics. Every future task (`FollowPlayer`, `ChopTree`, `AttackTarget`,
   `ExploreArea`) reuses this instead of writing its own movement.

### First prototype task

`CollectDroppedItemsTask`: repeatedly pick the nearest reachable `ItemEntity` within range,
navigate onto it (vanilla collision performs the actual pickup — no manual pickup call, no
teleport), skip items that cannot be pathed to or that time out, and finish `SUCCESS` when nothing
suitable remains. Control then returns to the Life System automatically.

### Alternatives rejected

- Full vanilla `Goal` / `GoalSelector` reuse: rejected because the selector attaches to
  `MobEntity`/`Brain` bodies and expects mob navigation/controls the companion `ServerPlayerEntity`
  does not have. We reuse the *shape* of `Goal`, not the machinery.
- Behavior tree: rejected as over-engineering for one companion and one active objective. A single
  `tick`-returns-status task covers current needs and composes into a tree later if ever needed.
- Planner / goal manager now: explicitly out of scope this sprint. The `assign` seam keeps it
  possible without a rewrite.
- Refactoring the existing debug tasks (`CompanionMiningTasks`, `CompanionBehaviorTestTasks`) onto
  the new framework: deferred, to avoid disturbing already-validated paths. They can migrate later.

### Future extension points (documented, not built)

- Task queue and `TaskPriority` (interrupt lower-priority work).
- Task interruption/resume and re-planning triggers.
- A planner that maps needs/goals to task assignments through `CompanionTaskManager.assign`.
- Composite/reasoning tasks that internally sequence primitive movement/mining/attack via the same
  `CompanionNavigator` and existing vanilla interaction paths.
- Done (was deferred): the "chase a moving entity -> approach into reach -> hazard gate -> abandon
  if unreachable" flow was lifted into `CompanionNavigator.tickFollow(entity, repathInterval)` once
  a second consumer existed. `CollectDroppedItemsTask` and `FollowPlayerTask` now share it;
  `AttackTargetTask` is expected to reuse it too (walk into range, then attack). See 7.2.

### Verified so far (Sprint 3)

- Project builds with the new `task` subpackage.
- `/aicompanion task collect` walks the companion to dropped items one by one, picks them up via
  vanilla collision, finishes cleanly, and resumes the Life System. Manually validated.
- `/aicompanion task cancel` returns control to the Life System immediately. Manually validated.
- Hazard avoidance validated: with items dropped across lava, the companion navigates around the
  lava to reach reachable items and refuses to walk onto lava for unreachable ones.

### Fixes and design refinements during Sprint 3

1. Freeze-on-repeat bug (fixed).

   The first `CollectDroppedItemsTask` treated the vanilla path "arrived" state (which stops ~1
   block short of the item) as done, but pickup needs bounding-box overlap. Arriving-without-pickup
   re-acquired the same item every tick with a perpetually reset timer, so the companion froze in
   place and only `spawn` (which teleports it) broke the loop. Fixed with an explicit per-target
   phase machine: ACQUIRE -> NAVIGATE -> APPROACH, where APPROACH walks the final short gap to
   trigger pickup collision, and the per-target tick budget spans both phases and only resets when
   the target actually changes.

2. Walking into lava during approach (fixed).

   The APPROACH phase walked straight at the item, bypassing the vanilla navigation chain that makes
   wandering avoid hazards. It therefore walked onto lava. Fixed by reusing the vanilla hazard
   classification instead of a hard-coded block list: `CompanionNavigator.isHazardAt(BlockPos)`
   borrows a disposable villager proxy and calls `LandPathNodeMaker.getLandNodeType(...)`, then
   treats the same `PathNodeType` values the pathfinder treats as impassable/damaging
   (`LAVA`, `DAMAGE_FIRE`, `DANGER_FIRE`, `DAMAGE_OTHER`, ...) as unsafe. APPROACH checks the item
   block and the next step each tick and abandons the item rather than step into danger. This keeps
   the direct-approach fallback aligned with the vanilla safety chain.

   Cost note: hazard checks and pathfinding share one cached villager proxy per `CompanionNavigator`
   (created lazily, rebuilt on world change, discarded in the task's `stop`), so APPROACH no longer
   allocates an entity per tick.

### Debug commands added

```text
/aicompanion task collect   # assign CollectDroppedItemsTask
/aicompanion task follow    # assign FollowPlayerTask (nearest real player)
/aicompanion task attack    # assign AttackTargetTask (nearest hostile mob)
/aicompanion task current   # describe the current task
/aicompanion task status    # running flag + current + last result
/aicompanion task cancel    # cancel and return control to Life System
```

## 7.2 Sprint 3 follow-up - FollowPlayerTask + shared entity tracking

After Sprint 3 was validated, a second task was added to pressure-test the framework's reusability
(the "will this still work for FollowPlayer/AttackTarget?" self-check).

- `CompanionNavigator.tickFollow(entity, repathInterval)`: the shared entity-tracking flow, extracted
  from `CollectDroppedItemsTask`. Periodic repath + path follow + hazard-gated direct approach for
  the final gap. Returns `MOVING`/`ARRIVED`/`STUCK`/`NO_PATH`. `CollectDroppedItemsTask` was
  refactored onto it and keeps only item-specific logic (target selection, pickup detection, loop).
- `FollowPlayerTask`: the framework's first open-ended task. It normally never returns a terminal
  status, proving the contract supports long-running presence behaviors. Modeled on vanilla
  `FollowOwnerGoal` min/max thresholds (with hysteresis: close in past `RESUME_DISTANCE`, stop within
  `STOP_DISTANCE`) but never teleports — it walks back via `tickFollow`. Ends cleanly (`SUCCESS`) when
  the player is gone/dead/changed world or lost beyond `LOSE_RANGE`, returning control to the Life
  System.
- Debug command: `/aicompanion task follow`.
- Manually validated: FollowPlayer keeps distance, walks back (avoiding hazards) when the player
  moves, and ends when the player leaves range; refactored CollectDroppedItems still avoids lava and
  now collects every reachable item in one run.
- Regression fixed during this follow-up: the CollectDroppedItems refactor briefly ended the task
  after the first pickup (pickup detection returned null with a cleared target, which the SUCCESS
  check read as "no items left"). Fixed by acquiring the next item in the same tick; the task only
  finishes when no target remains.

## 7.3 AttackTargetTask - reach-then-act

Third task, and the first that combines shared entity tracking with an existing vanilla interaction
path. It validates the "walk into range, then perform a vanilla action" shape that ChopTree /
BuildStructure will also use.

- `AttackTargetTask`: lock the nearest `HostileEntity`, `tickFollow` into range, then attack.
- Vanilla-first: attack range reads `getEntityInteractionRange()` (attribute, not a constant);
  swings only when `getAttackCooldownProgress(0.5F) >= 1.0` (a fully charged hit); damage/knockback/
  crits/sweeping/enchantments are all handled by `player.attack(target)`; chasing via `tickFollow`
  keeps the hazard gate, so it will not walk into lava to reach a mob.
- Retargets to the next mob in the same tick when one dies (same fix pattern as CollectDroppedItems),
  finishing `SUCCESS` only when no hostiles remain.
- Debug command: `/aicompanion task attack`. A weapon is optional (bare-hand attack works); use
  `/aicompanion equip_tool` for a sword during testing.
- Manually validated: companion walks to the nearest hostile, attacks on cooldown, retargets after a
  kill, finishes when none remain, and does not chase a mob into lava.

## 7.4 ChopTreeTask - ARCHIVED and removed from main (2026-07-08)

The first woodcutting task and everything built on top of it (`ChopTreeTask`, `HarvestLogsTask`,
`CompanionPacing`, the `CompanionPillarTasks` spike, and the `docs/chop-tree-bug-{review,audit}.md`
notes) **were removed from `main`**. The module had accumulated too many interacting bugs to trust,
so rather than keep patching it, the whole chop layer was snapshotted and deleted; woodcutting is
being **redone from scratch** on top of the reusable foundation it left behind.

- The full pre-removal state is preserved on branch **`archive/chop-module`** (commit
  `5801ab0 "Archive chop tree module before removal"`), recoverable at any time. It is **not** an
  ancestor of `main`.
- What was intentionally *kept* on `main` (validated, chop-free adapters — this is the redo's
  foundation, not throwaway code):
  - `CompanionHotbar` — held-item switching: `selectBlock`, `selectScaffoldBlock`,
    `selectBestToolFor` (picks the fastest inventory tool via vanilla
    `ItemStack.getMiningSpeedMultiplier`), `selectItem`. See §7.5.
  - `CompanionPillar` — pillar straight up N levels. Validated in-world. See §7.5.
  - `CompanionMiningTasks` — now exposes `pollResult` (BROKEN/FAILED), `cancel`, and
    unbreakable/out-of-reach/timeout bailouts, so a caller can tell whether a break actually
    destroyed the block.
  - `CompanionNavigator` — gained cliff/pit drop-hazard gating (`isDropHazard`), an approach
    stuck-detector, and a near-range "commit to the direct walk" rule that fixed the head-oscillation
    ("摇头") while approaching a fractional target.
  - `CompanionTaskManager` — a real per-companion task **queue**: `enqueue` and `enqueueOnSuccess`
    (run only if the predecessor finished SUCCESS). See §7.3 (task queue).
  - `CollectDroppedItemsTask` — a `Predicate<ItemStack>` filter (which drops are worth going to) plus
    a one-shot retry sweep of skipped items.
  - `CompanionWaterSafety` — self-rescue when the companion is in water; registered last so surfacing
    outranks whatever a task or the Life System set that tick.
- The `/aicompanion task chop` debug command was removed with the task.

**Redo status:** woodcutting is being rebuilt incrementally, one capability at a time, composing the
validated adapters above instead of one monolithic task. The subsections below (§7.5 adapters, §7.3
task queue) describe pieces that survived the removal and remain valid.

## 7.5 Pillar-up and held-item adapters (reach logs above interaction range)

Two reusable adapters, prompted by "a real player builds up to reach a high block instead of giving
up". Each was built and validated in-world as its own independently-verifiable step (same discipline
as the navigator extraction). They were originally composed into the now-archived ChopTree module
(§7.4) but are **chop-independent and remain on `main`** as general capabilities for the redo and
any future reach-a-high-block task.

- `CompanionPillar` (in `task`): pillar the companion straight up N levels — jump, place a block
  from the main hand onto the stand block during the jump apex, land on it, repeat. The verified
  timing: `World.canPlace` checks the candidate block against the player collision box, so the feet
  block can only be filled once the jump lifts the player a full block clear of it (place when
  `getY() - standY >= 1.0`, near the apex). Reuses `CompanionInputController.pressJumpOnly` +
  `interactionManager.interactBlock`; swings on placement so the animation shows. No new Mixin, no
  access widener. Spike lives in `CompanionPillarTasks` (`/aicompanion pillar_up`,
  `/aicompanion pillar_up_n <count>`).
- `CompanionHotbar` (in `task`): bring an inventory item into the main hand via the vanilla selection
  path — `setSelectedSlot` if it is already in the hotbar, else `swapSlotWithHotbar` from the lower
  inventory. Never force-sets the stack, so no dupe/loss. All post-change sync converges in
  `onHeldItemChanged`, which today only sends the main-hand `EntityEquipmentUpdateS2CPacket` to nearby
  viewers; that method is the seam a future "view the companion's inventory" feature would extend.
  Debug command `/aicompanion select_block`.
- Verified in-world (2026-07-06):
  - `pillar_up`: one level, `success=true`, Y 77.00 -> 78.00, block placed, 14 ticks.
  - `pillar_up_n <count>`: builds exactly `count` levels via the adapter.
  - `select_block` with an axe in hand and `/give AICompanion oak_planks 16` into the inventory:
    `found=true`, selected slot 0 -> 1, hand axe -> 16x oak planks (axe preserved, nothing lost),
    held item re-rendered for viewers.
- Note: use vanilla `/give AICompanion <item> <n>` to place items in the inventory for testing; the
  debug `give_blocks`/`equip_tool` both `setStackInHand` into the selected slot and overwrite each
  other, so they cannot set up "hand holds X, want Y from the inventory".

## 7.3 Task queue + woodcutting features (queue KEPT, chop features ARCHIVED)

This section originally documented four woodcutting features plus the first real task queue, built on
the ChopTree state machine. When ChopTree was removed (§7.4), the **task queue survived on `main`**
(it is chop-independent) while the **four chop-specific features went to `archive/chop-module`** with
the task. Split below so the redo knows exactly what it can still build on.

### Task queue (KEPT on main)

`CompanionTaskManager` has a per-companion `Deque` and two enqueue entry points:

- `enqueue(task)` — run after the active task (and anything already queued) finishes, regardless of
  how the predecessor ended.
- `enqueueOnSuccess(task)` — run only if the task immediately before it finished `SUCCESS`; otherwise
  the task is dropped without running and without overwriting the last status, so a failed
  predecessor stays visible instead of being masked by an empty follow-up pass.
- `assign(task)` is the hard-interrupt entry (cancel current + clear queue); `cancel` clears the whole
  queue.

This realizes the previously-deferred "task queue" extension point. It is how one command can express
a multi-step chore — e.g. a future "fell this tree, then pick up the wood" would be
`assign(ChopTree)` + `enqueueOnSuccess(Collect with a log/sapling filter)`.

### Woodcutting features (ARCHIVED with ChopTree on `archive/chop-module`)

These were removed from `main` along with ChopTree. Recorded here as design reference for the redo,
**not as current behavior**:

1. Tool switching — `CompanionHotbar.selectBestToolFor` picks the fastest inventory tool via vanilla
   `ItemStack.getMiningSpeedMultiplier(state)` (no hard-coded block→tool table). **The `CompanionHotbar`
   adapter itself is KEPT on main (§7.5);** only its ChopTree wiring is gone.
2. Replant sapling — a `REPLANT` finishing phase that planted a matching sapling on the stump. Removed
   with ChopTree.
3. Reclaim self-placed blocks — a `RECLAIM` phase that mined the pillar column back down. Removed with
   ChopTree.
4. Gather own harvest — `CollectDroppedItemsTask` gained a `Predicate<ItemStack>` filter. **The filter
   is KEPT on main (§7.4 kept-list);** only ChopTree's use of it (`isTreeYield`) is gone.

Reference groundwork (still valid): MineColonies (模拟殖民地) source cloned to
`D:\WorkSpace\mc-sources\minecolonies` (MC 1.20.1 / Forge — **architecture reference only, not a 1.21
API reference**) and indexed via `codebase-memory-mcp`; this project's `.mcp.json` makes that MCP
available. Its lumberjack chain (`EntityAIWorkLumberjack`/`Tree`/`PathJobFindTree`/`AbstractPathJob`)
is a useful design reference for the redo — note it fells top-down whereas our body is a real player
with ~4.5-block reach, so the redo will need its own reach strategy (pillar up, or fell bottom-up).

## 7.6 Chop-tree redo — FellNaturalTreeTask (2026-07-13)

Woodcutting rebuilt as a real behavior task, `FellNaturalTreeTask`, replacing the debug-only minimal
loop. Borrows only Numen's *decision model* (frozen target set + isolate-the-unreachable + real-time
interaction validation); the implementation is composed entirely from this project's already-validated
adapters (`CompanionNavigator`, `CompanionMiningTasks`, `CompanionHotbar`, and — stage 2 — `CompanionPillar`).

### Behavior contract (stage 1)
- **Success**: every log in the frozen `TreePlan` snapshot is confirmed air.
- **Failure**: any planned log remaining ⇒ overall `FAILURE`; each residual log's reason is recorded
  (`no-foothold` / `no-path` / `out-of-sight` / `out-of-reach` / `blocked-by-foreign` / `mining-failed`
  / `nav-timeout`). Judged from the **actual world state**, not from an internal "I think I broke it".
- **Ambiguous ownership ⇒ refuse**: `TreeDetector`'s conservative shape validation rejects the cluster,
  and the task fails at `start` — it never guesses.
- **Collection is separate**: the task only fells. Wood pickup is a follow-up via
  `enqueueOnSuccess(CollectDroppedItemsTask)`, so a failed chop is never masked by an empty collect pass.
- **Does NOT promise** (stage 1): auto-bridging, tunneling, or "dig any obstacle". Out of reach ⇒ honest failure.
- **Known residual risk (accepted, not fixed)**: ownership is heuristic; a player's log structure built
  within 2 blocks of a real tree can borrow its natural leaves and be accepted (low probability).

### Structure captured once, execution consumes it
`TreePlan` (new, immutable) is frozen at `start` from a `TreeDetector.Tree`: ordered logs
(`TreeStructure.ordered`) + the allowed-to-clear own-leaf set (packed `asLong` keys, single source of
truth for leaf ownership, Chebyshev ≤ 2 of some log). Execution reads only the snapshot; leaf decay or
another entity's edits can never change the target/ownership set mid-run.

### Per-log reachability re-planning
For each log in order: try `TreeChopStep` from the **current position** first (may already be in reach,
saving a navigation); if not, take that log's sorted `TreeApproach` footholds and try each via
`CompanionNavigator` — `pathTo`==false or `NO_PATH`/`STUCK`/`IDLE` ⇒ next candidate immediately (a
consumed candidate is never retried, so no A→B→A loop); only `ARRIVED` counts as in position. Candidates
exhausted ⇒ record the log's reason and **continue to the next log** (the rest of the tree may still be
reachable). No in-task `applyServerTravelForward`; all movement goes through `CompanionNavigator`.

### One shared judgment model — `TreeChopStep`
Both `FellNaturalTreeTask` and the downgraded debug `ReachAndChopTask` drive a `TreeChopStep`, so the
"can I mine it / is an own-leaf in the way / is a foreign block in the way" decision lives in exactly
one place (the archived module's recurring bug was planning and execution using two different sight
algorithms). Every decision uses the **live eye position** + vanilla `canInteractWithBlockAt` +
`world.raycast(OUTLINE)`. This structurally dissolves the old plan-vs-execute occluder-shape divergence:
`TreeApproach`'s DDA is only a *hint*; the executor's real ray is authoritative. Reactive leaf clearing
is **own-tree-only and bounded** (≤ 6 leaves/target); a ray hitting anything not owned ⇒
`BLOCKED_BY_FOREIGN` and the caller relocates — it never digs a block it does not own.

### TreeDetector — conservative shape validation (26-neighbor floodfill kept)
26-neighbor floodfill retained (keeps natural diagonal branches together). Post-cluster rejection added:
a single Y layer with > 9 logs (wall/floor), a height ≤ 2 cluster spread ≥ 3 horizontally (log
row/floor), or a cluster hitting `MAX_TREE_LOGS`(128) — the whole tree is rejected (never partially
felled). Acceptance now returns `TreePlan.Evidence` (log count, natural leaves, densest layer, height,
horizontal span) for diagnostics and repeatable test assertions.

### Cleanups (regressions that had reached main)
- Removed the `[ChopDebug]` **server-wide broadcast** — diagnostics now go only to the command source
  (`ReachAndChopTask`/`FellNaturalTreeTask` take an optional `ServerCommandSource` sink).
- Restored idle **WANDER** (had been commented out for testing and committed) behind a runtime toggle:
  `/aicompanion wander on|off` (default on).

### Debug commands
- `/aicompanion task fell_tree` — give iron axe, `assign(FellNaturalTreeTask)` + `enqueueOnSuccess(collect)`.
- `/aicompanion task chop_base` — unchanged name, now the narrow 2-block regression driver.
- `/aicompanion wander on|off` — idle-wander toggle.
- `/aicompanion tree_plan_show` — now reports the best of N sorted foothold candidates.

### Scope / next
Stage 1 = ground-reachable per-log felling (normal / branchy / multi-trunk). **Stage 2 (not built)**:
compose `CompanionPillar` for trunk logs above interaction height, only when necessary and safe. Build
passes; **not yet validated in-world** — needs the §Verification matrix run.

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
