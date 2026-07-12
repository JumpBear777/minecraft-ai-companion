# AGENTS.md

Instructions for Codex and other AI coding agents working in this repository.

## Project Identity

Minecraft AI Companion is an open-source Fabric mod prototype for a future autonomous Minecraft teammate.

The project is not building a chatbot NPC, a command bot, or a generic automation tool. The long-term goal is a companion that behaves like another real player.

## Mandatory Engineering Principles

1. Vanilla first.

   Prefer vanilla Minecraft systems, APIs, and call paths before custom implementation.

2. Compatibility first.

   Assume users will run this mod alongside other mods. Favor behavior that stays compatible with vanilla mechanics, Fabric conventions, and modded Minecraft expectations.

3. Source before custom.

   Before implementing player behavior, inspect the Minecraft Yarn source and the available source index. Understand the vanilla path first.

4. Vanilla behavior chains, not only player classes.

   The goal is player-like behavior, but the implementation research must not stop at `PlayerEntity` or `ClientPlayerEntity`. If the player path depends on a real client or is not suitable for a fake server-side player, also inspect vanilla mob and shared entity paths such as `MobEntity`, `MoveControl`, `JumpControl`, `LivingEntity`, `Entity`, navigation, attributes, and interaction managers. Prefer the mature vanilla chain that best matches the situation.

5. Integrated vanilla chains before isolated features.

   Do not treat vanilla behavior as isolated features to reassemble manually. For movement-like behavior, first identify the full integrated chain: `Goal` / `Brain Task` -> targeting -> `NavigationConditions` -> `PathNodeMaker` / `PathNodeType` / pathfinding penalties -> `EntityNavigation` -> `MoveControl` / `JumpControl` -> entity movement. Safety, obstacle handling, fluids, hazards, and movement are part of one vanilla chain. If only part of the chain can be reused, document the exact break point and keep the adapter limited to the `ServerPlayerEntity` body difference.

6. Preserve player-like behavior.

   Movement, gravity, water physics, damage, knockback, item use, block interaction, inventory, equipment, sleeping, riding, containers, and chunk loading should behave as close to a real player as possible.

7. Patch narrowly.

   Mixins and internal hooks are allowed only when needed. Scope them to `AICompanion` where possible and document the vanilla behavior they preserve or restore.

8. Custom logic is fallback.

   Avoid reimplementing Minecraft physics, animation, inventory, or interaction rules unless source investigation proves that the vanilla path cannot be reused.

9. Document architectural exceptions.

   If a behavior cannot follow vanilla, document the reason, the tested alternatives, the remaining risk, and the likely version compatibility impact.

10. Leave extension seams, not future feature code.

   Do not build mod APIs, plugin systems, or broad abstraction layers before the vanilla companion is excellent. However, avoid designs that would force rewrites later. Ask two questions before adding architecture: if future mod support never happens, is this design still good? If future mod support happens, does this design avoid a rewrite?

11. Optimize for one player and one companion.

   The primary target is one computer, one human player, and one AI companion. Keep performance, UX, and architecture focused on that common case before scaling to many companions or server-wide automation.

12. Keep LLM usage sparse.

   Primitive skills must not call the LLM. Most moment-to-moment behavior should run through deterministic behavior and skill code. LLM calls should be reserved for conversation, major events, long-term planning, or meaningful plan revisions.

## Current Architecture Direction

The current prototype uses a server-side `ServerPlayerEntity` with a local fake `ClientConnection`.

The companion's fake connection is registered with the normal server network loop so `ServerPlayNetworkHandler` can tick. A narrow Mixin currently preserves vanilla physics results for `AICompanion` by preventing stale client-position rollback from overwriting server-calculated movement.

This is intentional: the project should recover vanilla behavior wherever possible instead of building a separate NPC simulation.

## Task System (Sprint 3)

The companion now has a lightweight Task System in the `task` subpackage. It is the foundation for
every future capability and must be respected:

- `CompanionTask` is the unit of autonomous work (`start`/`tick` -> `TaskStatus`/`stop`/`describe`),
  shaped after vanilla `Goal`. A task owns its own execution until it returns a terminal status.
- `CompanionTaskManager` owns one current task per companion and is the only seam a future planner
  uses: planners `assign` tasks and never inspect task internals.
- `CompanionNavigator` is the shared, already-validated movement adapter (vanilla villager
  pathfinding proxy + `applyServerTravelForward` follow). Tasks must reuse it for "walk to X"
  instead of writing their own movement. For tracking a moving entity (follow/collect/attack), use
  `tickFollow(entity, repathInterval)`, which handles repath + hazard-gated approach.
- Division of responsibility: **Tasks encapsulate execution. The Life System encapsulates presence.
  A future planner only assigns tasks. LLM never directly controls movement, mining, or combat.**

Rules for new tasks:

- Compose existing vanilla adapters (`CompanionNavigator`, mining/interaction paths); do not scatter
  new movement/physics logic inside a task.
- Do not teleport or use scripted shortcuts; prefer vanilla movement and let vanilla systems (pickup
  on collision, block breaking, damage) do the work.
- Always release control in `stop` so the Life System resumes a clean body.
- Before adding a task, confirm the design still fits `FollowPlayer`, `ChopTree`, `AttackTarget`,
  `BuildStructure`, `ExploreArea`, and future modded skills. If it does not, redesign first.

Tasks implemented so far: `CollectDroppedItemsTask`, `FollowPlayerTask`, `AttackTargetTask`.

A first `ChopTreeTask` was built and then **removed from `main`** — it had accumulated too many
interacting bugs to trust, and woodcutting is being redone from scratch. The pre-removal snapshot
lives on branch `archive/chop-module` (commit `5801ab0`); see `PROJECT_STATE.md` §7.4. Its two
reusable, chop-independent adapters were kept on `main` and are the intended foundation for the redo:
`CompanionPillar` (jump + place-at-apex to build straight up) and `CompanionHotbar` (bring an
inventory block/tool into hand via vanilla slot selection/swap). Both were spiked and validated
in-world on their own before being composed. When redoing woodcutting, recognize trees via the
vanilla `BlockTags.LOGS` tag, walk into `canInteractWithBlockAt` range through `CompanionNavigator`,
and reuse `CompanionMiningTasks` for vanilla block breaking rather than reimplementing a break loop —
and keep following the "extract a verified adapter, then compose" path rather than inlining new
movement/interaction primitives into one monolithic task (that monolith is exactly what accumulated
the bugs).

## Work Rules

- Do not implement AI, memory, planner, or LLM behavior unless explicitly requested. Sprint 3 is scoped to the Task System foundation; the planner that assigns tasks is a future sprint.
- Keep changes focused on feasibility, compatibility, and player-like behavior.
- Before changing Minecraft-facing behavior, inspect relevant Yarn source.
- Reuse already validated project paths before introducing a new experimental path. If a behavior was previously proven stable, extend or compose that path first.
- Do not add custom navigation or wandering shortcuts. Movement-like life behaviors must wait until a vanilla navigation chain or adapter is understood.
- Update `PROJECT_STATE.md` or `docs/` when an architectural conclusion changes.
- Run the Gradle build before committing when code changes are made.
- Do not commit generated run worlds, caches, or build outputs.

## Useful Commands

```powershell
$env:JAVA_HOME='D:\WorkHelper\Java\JDK-21'
$env:GRADLE_USER_HOME='D:\tools\gradle-home'
D:\tools\Gradle\gradle-9.6.1\bin\gradle.bat build --no-daemon
```

```powershell
$env:JAVA_HOME='D:\WorkHelper\Java\JDK-21'
$env:GRADLE_USER_HOME='D:\tools\gradle-home'
D:\tools\Gradle\gradle-9.6.1\bin\gradle.bat runClient --no-daemon
```
