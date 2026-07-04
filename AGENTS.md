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

5. Preserve player-like behavior.

   Movement, gravity, water physics, damage, knockback, item use, block interaction, inventory, equipment, sleeping, riding, containers, and chunk loading should behave as close to a real player as possible.

6. Patch narrowly.

   Mixins and internal hooks are allowed only when needed. Scope them to `AICompanion` where possible and document the vanilla behavior they preserve or restore.

7. Custom logic is fallback.

   Avoid reimplementing Minecraft physics, animation, inventory, or interaction rules unless source investigation proves that the vanilla path cannot be reused.

8. Document architectural exceptions.

   If a behavior cannot follow vanilla, document the reason, the tested alternatives, the remaining risk, and the likely version compatibility impact.

9. Leave extension seams, not future feature code.

   Do not build mod APIs, plugin systems, or broad abstraction layers before the vanilla companion is excellent. However, avoid designs that would force rewrites later. Ask two questions before adding architecture: if future mod support never happens, is this design still good? If future mod support happens, does this design avoid a rewrite?

10. Optimize for one player and one companion.

   The primary target is one computer, one human player, and one AI companion. Keep performance, UX, and architecture focused on that common case before scaling to many companions or server-wide automation.

11. Keep LLM usage sparse.

   Primitive skills must not call the LLM. Most moment-to-moment behavior should run through deterministic behavior and skill code. LLM calls should be reserved for conversation, major events, long-term planning, or meaningful plan revisions.

## Current Architecture Direction

The current prototype uses a server-side `ServerPlayerEntity` with a local fake `ClientConnection`.

The companion's fake connection is registered with the normal server network loop so `ServerPlayNetworkHandler` can tick. A narrow Mixin currently preserves vanilla physics results for `AICompanion` by preventing stale client-position rollback from overwriting server-calculated movement.

This is intentional: the project should recover vanilla behavior wherever possible instead of building a separate NPC simulation.

## Work Rules

- Do not implement AI, memory, planner, or LLM behavior during Sprint 1 unless explicitly requested.
- Keep changes focused on feasibility, compatibility, and player-like behavior.
- Before changing Minecraft-facing behavior, inspect relevant Yarn source.
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
