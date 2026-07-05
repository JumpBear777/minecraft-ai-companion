# Life System Research - Sprint 2

Date: 2026-07-05

## Goal

Sprint 2 is not about intelligence, planning, or LLM behavior.

The goal is to make the companion feel alive while idle by reusing vanilla behavior patterns wherever possible.

The project principle remains:

> Never reimplement Minecraft unless every vanilla behavior has been investigated.

## Vanilla Behavior Findings

### Sheep

Classes: `SheepEntity`, `WanderAroundFarGoal`, `LookAtEntityGoal`, `LookAroundGoal`, `EatGrassGoal`.

Behaviors:

- Wanders occasionally.
- Looks at nearby players.
- Randomly looks around.
- Stops to eat grass.
- Flees danger.

Reusable patterns:

- Low-frequency idle wandering.
- Short random look actions.
- Watching nearby players.
- Pauses between actions.

Do not copy: sheep-specific eating, breeding, temptation, and parent following.

### Wolf

Classes: `WolfEntity`, `FollowOwnerGoal`, `SitGoal`, `LookAtEntityGoal`, `LookAroundGoal`, `MeleeAttackGoal`.

Behaviors:

- Follows owner only outside comfortable distance.
- Can sit and remain still.
- Looks at nearby players.
- Watches threats and reacts to owner combat.

Reusable patterns:

- Comfortable-distance logic for future social behavior.
- Owner/player attention.
- Pausing as an intentional state.

Do not copy: taming, collar, anger, breeding, and owner-combat rules.

### Cat

Classes: `CatEntity`, `FollowOwnerGoal`, `CatSitOnBlockGoal`, `GoToBedAndSleepGoal`, `WanderAroundFarGoal`, `LookAtEntityGoal`.

Behaviors:

- Follows owner.
- Chooses places to sit.
- Sleeps near owner.
- Wanders very rarely.
- Looks at nearby players.

Reusable patterns:

- Very low-frequency movement can still feel alive.
- Idle placement matters.
- Resting and pausing should be normal.

Do not copy: cat-specific bed, gift, and sitting-on-block behavior for now.

### Fox

Classes: `FoxEntity`, `SitDownAndLookAroundGoal`, `StopWanderingGoal`, `WanderAroundFarGoal`, `FleeEntityGoal`, `PickupItemGoal`.

Behaviors:

- Sits and looks around in several directions.
- Stops wandering as its own behavior.
- Picks up items.
- Avoids daylight and dangerous entities.
- Hunts and pounces.

Reusable patterns:

- Attention switching while stationary.
- Idle state can have internal structure.
- Curiosity toward dropped items is a good future behavior.

Do not copy: fox hunting, pouncing, sleeping pose, daylight rules, and stealing behavior.

### Bee

Classes: `BeeEntity`, `BeeWanderAroundGoal`, `MoveToHiveGoal`, `MoveToFlowerGoal`, `PollinateGoal`.

Behaviors:

- Remembers hive and flower positions.
- Wanders when no target is active.
- Returns to known anchors.
- Gives up when a target is too far or unreachable.

Reusable patterns:

- Preferred area / anchor position.
- Returning to a local area instead of wandering forever.
- Timeouts for failed movement.

Do not copy: flight, pollination, hive rules, nectar particles, and crop behavior.

### Villager

Classes: `VillagerEntity`, `Brain`, `VillagerTaskListProvider`, `RandomTask`, `LookAtMobTask`, `StrollTask`, `WaitTask`, `VillagerWalkTowardsTask`.

Behaviors:

- Uses memories such as home, job site, meeting point, visible mobs, and players.
- Has schedules and activities.
- Randomly looks at cats, villagers, players, creatures, and monsters.
- Waits for 30-60 ticks as a valid idle task.
- Returns to points of interest.

Reusable patterns:

- Future Life System can become memory-driven.
- Random weighted idle choices are a good later structure.
- Waiting is an active behavior.
- Look targets and walk targets should be separate concepts.

Do not copy: full Brain architecture, schedules, professions, gossip, trades, and POI logic yet.

### Skeleton

Classes: `AbstractSkeletonEntity`, `WanderAroundFarGoal`, `LookAtEntityGoal`, `LookAroundGoal`, `AvoidSunlightGoal`, `EscapeSunlightGoal`.

Behaviors:

- Wanders.
- Looks at players.
- Looks around.
- Avoids sunlight.
- Switches attack behavior based on equipment.

Reusable patterns:

- Even hostile mobs use the same basic idle life behaviors.
- Equipment can influence behavior in future combat skills.

Do not copy: hostile targeting, sunlight avoidance, and bow attack AI.

### Zombie

Classes: `ZombieEntity`, `ZombieAttackGoal`, `MoveThroughVillageGoal`, `WanderAroundFarGoal`, `LookAtEntityGoal`, `LookAroundGoal`, `ActiveTargetGoal`.

Behaviors:

- Tracks players and villagers.
- Wanders when idle.
- Looks at players.
- Looks around.
- Can move through villages.

Reusable patterns:

- Tracking is useful for future hostile awareness.
- Basic life is still built from wander, look-at, and look-around.

Do not copy: hostile targeting, door breaking, reinforcement, infection, and village attack behavior.

## Reusable Behavior Patterns

- Random look-around.
- Look at nearby player/entity.
- Idle pauses.
- Attention switching.
- Comfortable-distance behavior.

Useful but not implemented yet:

- Movement timeout.
- Preferred area / anchor position.
- Curiosity toward items or entities.
- Returning to a local area when drifting too far.

## Reusable Vanilla Movement Patterns

These vanilla movement patterns are useful for the companion, but they should be adopted according to their role instead of copied as a full mob AI stack.

Important principle:

Vanilla movement behavior is integrated, not isolated. "Wandering" is not separate from "safety" in practice: the high-level goal chooses intent, but the shared targeting, path node, navigation, movement control, and penalty systems decide whether a location is safe and reachable. The companion should therefore prefer whole vanilla movement chains over manually combining isolated behaviors.

For movement-like behavior, analyze the full chain:

```text
Goal / Brain Task
  -> targeting
  -> NavigationConditions
  -> PathNodeMaker / PathNodeType / pathfinding penalties
  -> EntityNavigation
  -> MoveControl / JumpControl
  -> entity movement
```

If the companion cannot reuse the full chain because its body is `ServerPlayerEntity`, the adapter should only bridge that body mismatch. It should not replace vanilla hazard, obstacle, fluid, or path penalty logic with custom shortcuts.

Vanilla hazard handling found in source:

- `PathNodeType.LAVA`, `FENCE`, `BLOCKED`, `POWDER_SNOW`, and several closed/invalid nodes have negative default penalties and are treated as invalid or avoided by navigation.
- `LandPathNodeMaker.getCommonNodeType(...)` maps lava, fire-damaging blocks, cactus, sweet berry bush, powder snow, fences, walls, doors, water, rails, leaves, and blocked collision into path node types.
- `LandPathNodeMaker.getNodeTypeFromNeighbors(...)` upgrades nearby fire/lava/damage/water into danger or border node types.
- `MobEntity.getPathfindingPenalty(...)` uses each `PathNodeType` default unless the entity overrides it.
- Passive animals and merchants override fire behavior: `DANGER_FIRE = 16`, `DAMAGE_FIRE = -1`.
- Therefore idle wandering should use a passive/humanoid proxy such as `VillagerEntity`, not a hostile proxy, when the goal is safe companion movement.

### Idle / Life System candidates

- `WanderAroundGoal`
  - Pattern: low-frequency idle movement.
  - Vanilla chain: chance gate -> `NoPenaltyTargeting.find(...)` -> `EntityNavigation.startMovingTo(...)`.
  - Companion use: best current basis for idle wandering.

- `WanderAroundFarGoal`
  - Pattern: broader wandering, with special water handling.
  - Vanilla chain: mostly `FuzzyTargeting.find(...)`, rarely no-penalty target.
  - Companion use: useful later if idle wandering feels too short, but should stay bounded so the companion does not drift away.

- `StrollTask`
  - Pattern: Brain-style random stroll that writes a `WalkTarget`.
  - Vanilla chain: `FuzzyTargeting` / `NoPenaltySolidTargeting` -> `WalkTarget` -> `MoveToTargetTask`.
  - Companion use: good architectural model because it separates "choose a place" from "move there".

- `LookAroundGoal` and `LookAtEntityGoal`
  - Pattern: stationary attention.
  - Companion use: already approximated, but still not using vanilla `LookControl`.

### Social / companion-distance candidates

- `FollowOwnerGoal`
  - Pattern: follow only when farther than a minimum distance, stop inside comfortable range, periodically repath.
  - Companion use: strong future model for "stay near the player", but it is more than idle life and should not be mixed into passive wandering yet.

- `GoToLookTargetTask` / `WalkTowardsLookTargetTask`
  - Pattern: turn an attention target into a movement target.
  - Companion use: useful later for curiosity or observing the player, but requires a small `LookTarget` / `WalkTarget` concept first.

### Safety / environment candidates

- `EscapeDangerGoal`
  - Pattern: react to recent dangerous damage; if on fire, prefer nearby water.
  - Companion use: future self-preservation behavior, not idle life.

- `FleeEntityGoal`
  - Pattern: choose a position away from a nearby entity using `NoPenaltyTargeting.findFrom(...)`, then path away; speed increases when too close.
  - Companion use: future threat avoidance or social spacing.

- `SwimGoal`
  - Pattern: in deep water or lava, repeatedly activate jump control.
  - Companion use: useful if player-body swimming/floating needs explicit support, but should be tested against vanilla player swimming first.

- `WalkTowardsLandTask` / `SeekWaterTask`
  - Pattern: scan nearby blocks for land or water and write a `WalkTarget`.
  - Companion use: future environmental awareness, not current idle wandering.

### Anchor / memory candidates

- `GoToRememberedPositionTask`
  - Pattern: use a remembered position or entity as an anchor, then find a fuzzy target relative to it.
  - Companion use: future "return near camp/player/home" behavior once the project has a minimal memory/anchor concept.

### Current recommendation

For Sprint 2, keep the Life System limited to:

- Pause.
- Look around.
- Look at nearby real player.
- Occasional short wandering based on `WanderAroundGoal` / `StrollTask` patterns.

Do not add follow, flee, panic, water seeking, curiosity, or home-return behaviors yet. They are useful vanilla patterns, but they belong to later social, safety, or memory systems.

## Recommended Life System Architecture

The Life System should be a background Minecraft-side system:

```text
Server tick
  -> find companion
  -> skip if busy
  -> update LifeState
  -> run one small idle behavior
```

It is not AI Core, a Planner, an LLM, a Skill, or a full mob Brain.

Initial code should remain intentionally small:

- One state object per companion.
- A few behavior phases: pause, look around, look at nearby player.
- No external mod API.
- No memory system.
- No planning.
- No custom navigation.

## First Prototype Behaviors

1. Pause naturally.

   Vanilla animals and villagers wait frequently. Stillness should feel intentional.

2. Look around.

   Based on `LookAroundGoal`, `LookAroundTask`, and fox sitting behavior.

3. Look at nearby real player.

   Based on `LookAtEntityGoal` and villager `LookAtMobTask`. This is only attention, not following or social AI.

## Prototype Validation

Manual test result:

- The companion randomly looks around while idle.
- The first conservative version did not wander or move on its own.
- A later Sprint 2 prototype adds conservative idle wandering by using a vanilla pathfinding proxy with `NoPenaltyTargeting.find(...)` and `MobNavigation`, then following the resulting path with the validated player-body movement adapter.
- This keeps the Life System aligned with vanilla wandering patterns without directly attaching `WanderAroundGoal`, which requires `PathAwareEntity`.

## Vanilla Reuse Debt

These parts are intentionally recorded as not yet fully vanilla-chain implementations:

- Idle look-around currently copies the shape of `LookAroundGoal`, but it does not directly reuse `MobEntity.getLookControl().lookAt(...)`.
- Look-at-player currently copies the shape of `LookAtEntityGoal`, but target selection and look execution are implemented in `CompanionLifeSystem` for `ServerPlayerEntity`.
- Player movement tests use the real player input/travel path, but movement decisions are not yet driven by vanilla `EntityNavigation`.
- `CompanionVanillaMoveControl` is an adapter based on vanilla `MoveControl`. It keeps the target yaw, speed scaling, and simple jump trigger logic, then outputs `PlayerInputC2SPacket` because the companion body is a `ServerPlayerEntity`, not a `MobEntity`.

These are acceptable as research prototypes, but should not be treated as finished architecture.

## Movement Research

## Wandering Adapter

Short local wandering was intentionally not implemented in the first prototype. It is now present only as a conservative adapter prototype.

Reason:

- Vanilla wandering depends on `PathAwareEntity`, `EntityNavigation`, `MoveControl`, `JumpControl`, targeting helpers, and path node logic.
- `ServerPlayerEntity` cannot directly use `WanderAroundGoal`.
- A custom simplified wander target picker would skip mature vanilla handling for obstacles, holes, fluids, lava, and path penalties.

Additional source finding:

- `WanderAroundGoal` is thin: it chooses a no-penalty target through `NoPenaltyTargeting.find(...)`, then calls `mob.getNavigation().startMovingTo(...)`.
- `NoPenaltyTargeting` depends on `PathAwareEntity`, `NavigationConditions`, pathfinding penalties, position targets, and `EntityNavigation.isValidPosition(...)`.
- `EntityNavigation` is hard-bound to `MobEntity`, path nodes, `PathNodeMaker`, and `MoveControl`.
- `MoveControl` is hard-bound to `MobEntity`, but its core move-to behavior can be adapted: turn toward target, scale speed by `MOVEMENT_SPEED`, and request jump when blocked or stepping upward.
- Because the companion is a `ServerPlayerEntity`, the adapted output must become player input/movement packets, not direct mob control fields alone.
- `ServerPlayNetworkHandler.onPlayerInput(...)` only stores `ServerPlayerEntity.playerInput`; a real client normally computes movement and sends `PlayerMoveC2SPacket`. Since the companion has no real client, the adapter must also emit movement packets after making vanilla-style movement decisions.
- A first navigation experiment used an unspawned vanilla mob as a pathfinding proxy. This lets the prototype call vanilla `MobNavigation`, `LandPathNodeMaker`, and `PathNodeNavigator`, then feed the resulting `Path` nodes into the `ServerPlayerEntity` movement adapter.
- The proxy is now a `VillagerEntity` rather than a `ZombieEntity`. Villagers are passive, humanoid, can open doors, can swim, and inherit merchant fire path penalties (`DANGER_FIRE = 16`, `DAMAGE_FIRE = -1`). This is closer to a safe companion idle-wander chain than hostile zombie pathfinding.
- Idle wandering now also uses vanilla `MobEntity.setPositionTarget(...)` on the proxy. This lets `NoPenaltyTargeting` and `FuzzyPositions.towardTarget(...)` keep random movement near an anchor through the vanilla position-target mechanism instead of a custom distance clamp.
- After manual observation, idle wander scheduling was tuned to be more visible: shorter pauses, higher wander selection weight, wider horizontal search, and a wider vanilla position-target anchor. This changes cadence only; target safety and path validity still come from the vanilla proxy/navigation chain.
- The proxy approach proves whether "vanilla path result -> player body execution" can work, but it is not final architecture: the proxy still has vanilla villager assumptions rather than exact player semantics.
- Jump execution needs special care. `ServerPlayNetworkHandler.onPlayerMove(...)` already triggers `player.jump()` when an on-ground player sends an upward, not-on-ground movement packet. The adapter should prefer that vanilla player path instead of directly calling `ServerPlayerEntity.jump()` and then sending a second vertical movement.
- Current stable path following uses vanilla path nodes from the proxy, then follows them with the validated player-body movement execution path. The experimental `CompanionVanillaMoveControl` remains research debt until its jump timing matches stable behavior.
- The Life System must yield to active behavior/mining tasks without calling `releaseInput(...)`. Background idle behavior should never clear movement input owned by a foreground task.

Next research should determine whether the proxy can be replaced or refined to match player dimensions and pathfinding penalties more closely.

## Future Behaviors

Do not implement now:

- Curiosity toward dropped items.
- Watching hostile mobs.
- Following at comfortable distance.
- Weather awareness.
- Sitting/resting poses.
- Social/emotional expression.
- Full weighted behavior scheduler.
- Memory-backed home/work/meeting anchors.
- Vanilla navigation adapter for wandering.

These should be added only after the vanilla companion body is stable and the matching vanilla behavior chain has been investigated.
