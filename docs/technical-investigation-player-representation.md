# Player Representation Technical Investigation

Last updated: 2026-07-04

## Question

What should represent the AI Companion inside Minecraft if the long-term goal is to behave as close as possible to a real player?

## Short Recommendation

Use a server-side player implementation based on `ServerPlayerEntity` plus an internal fake/local connection.

This should be treated as a Minecraft integration adapter, not as part of the AI Core. The AI Core should eventually produce high-level intents; the Minecraft adapter translates those intents into player interactions.

## Version Decision

Fabric's metadata currently lists newer stable game versions in the `26.x` line, but Yarn mappings for `26.2` were not available through the checked Fabric meta endpoint during this investigation. The prototype therefore targets Minecraft `1.21.11`, which is stable, has Yarn mappings, and has a published Fabric API version.

This is a deliberate feasibility choice, not a permanent product version decision. Before a public release, revisit the target Minecraft version and update the adapter behind a small compatibility boundary.

## Validation Result

The prototype compiles successfully with:

- Minecraft `1.21.11`
- Yarn `1.21.11+build.6`
- Fabric Loader `0.19.3`
- Fabric API `0.141.4+1.21.11`
- Fabric Loom `1.17.13`
- Java `21`

The current prototype proves compile-time feasibility of creating a no-real-client `ServerPlayerEntity` path. Runtime validation inside a Minecraft dev client/server is still required before treating the approach as fully proven.

## Compared Approaches

| Approach | Advantages | Disadvantages | Complexity | Compatibility | Extensibility | Performance | Multiplayer | Singleplayer |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `ServerPlayerEntity` with fake/local connection | Closest to vanilla player systems: inventory, health, hunger, XP, equipment, hands, stats, game mode, player list, chunk tracking | Uses internal server/network lifecycle; version-sensitive; must prevent real packet IO assumptions | High | Best vanilla compatibility if lifecycle is correct | Best base for future AI-controlled player behavior | Similar to a real player; chunk loading can be expensive if many companions explore | Good, but must handle tab list, permissions, name/UUID, anti-cheat expectations | Good because integrated server still has server player machinery |
| Existing FakePlayer pattern | Proven by projects such as Carpet; often implemented by subclassing/constructing a server player with fake connection | Not a first-class Fabric API abstraction; implementation details vary by version | Medium-high | Very good for vanilla mechanics that check player type | Good, especially if wrapped behind our own adapter | Similar to `ServerPlayerEntity` | Good if packet/list lifecycle is handled | Good |
| Custom player-like entity | Full control over data model and rendering; avoids fake networking | Reimplementing inventory, hunger, sleeping, containers, block breaking, advancements, stats, chunk loading, dimensions, and many player-only checks is a long-term trap | Very high | Poor-to-medium; many vanilla/modded systems expect `PlayerEntity`/`ServerPlayerEntity` | Poor unless we rebuild lots of vanilla behavior | Can be cheaper than player, but only by giving up compatibility | Risky; other mods may not recognize it | Easier to spawn, harder to make real |
| Mob-based implementation | Simple; pathfinding and AI goals already exist | Behaves like a mob, not a player; no real player inventory/hunger/XP/container semantics | Low at first, high later | Poor for player-specific mechanics | Poor for this project vision | Efficient | Good as an NPC, bad as a player | Good as an NPC, bad as a player |
| External bot client | Uses an actual network client; closest to real multiplayer packet flow; naturally loads chunks | Requires separate process/account/session; not a mod-contained companion; hard singleplayer story | High operational complexity | Excellent from server perspective | Good for bot research, awkward for embedded AI companion | Expensive: full client/bot process | Excellent, if accounts and server rules allow it | Poor unless hosting a local server |

## Why `ServerPlayerEntity`

The desired companion is player-shaped at the mechanics level. Vanilla Minecraft routes many systems through server-side player classes:

- Inventory and equipment
- Health, hunger, experience, and game mode
- Container screen handlers
- Block breaking and item use interaction managers
- Sleeping and riding
- Advancements, stats, recipes, and permissions
- Chunk watching around players

A mob or custom entity can look like a player, but it will not naturally participate in these systems.

## Recommended Architecture

Use three layers:

```text
AI Core
  Pure Java/Kotlin domain logic. No Minecraft imports.

Companion Controller
  Converts AI intents into abstract actions such as move, use item, break block, open container.

Minecraft Player Adapter
  Owns ServerPlayerEntity lifecycle, fake connection, interaction manager calls, world observation, and vanilla integration.
```

The adapter should be the only layer that knows about `ServerPlayerEntity`, `ClientConnection`, `ServerWorld`, or Fabric events.

This keeps the version-sensitive code small. If Minecraft internals change, the project repairs the adapter instead of rewriting the AI Core.

## Prototype Scope

The prototype intentionally proves only one thing:

> Can a no-real-client `ServerPlayerEntity` be created and connected to the server enough to exist in the world?

It does not implement AI, memory, pathfinding, planning, autonomous actions, or LLM integration.

## Prototype Implementation

The Fabric mod registers `/aicompanion spawn`.

When executed, it:

1. Creates a stable `GameProfile` for `AICompanion`.
2. Creates a `ServerPlayerEntity` in the command source's `ServerWorld`.
3. Creates a `LocalClientConnection` that satisfies the server player connection lifecycle without real socket IO.
4. Calls `PlayerManager.onPlayerConnect(...)`.
5. Teleports the companion near the command source.

This is intentionally small and disposable. It is a risk-reduction artifact, not the final companion runtime.

## Important APIs

- `ModInitializer`: Fabric entrypoint for common initialization.
- `CommandRegistrationCallback`: Fabric API event used to register `/aicompanion spawn`.
- `ServerPlayerEntity`: the server-side player object that owns player state.
- `PlayerManager`: the server service responsible for creating and connecting players.
- `ClientConnection`: the network connection object normally backed by Netty.
- `ConnectedClientData`: client profile/options data used during player connection.
- `ServerWorld`: the server-side world in which the fake player is placed.

## Interpretation

The player-based approach is the only one that directly aligns with the desired long-term mechanics:

- player inventory
- health and hunger
- XP and stats
- equipment and hands
- item use
- block interaction
- container screens
- sleeping and riding
- vanilla chunk watching around players

The cost is that the implementation depends on internal Minecraft server lifecycle details. That cost is acceptable only if isolated behind an adapter.

## Risks

- The fake connection path touches Minecraft internals and may break across versions.
- Some logic may assume a real network connection and packet listener.
- The companion may appear in player lists unless filtered later.
- Chunk loading by autonomous companions can become expensive.
- Multiplayer servers may have mod, permission, or anti-cheat expectations around players.
- Name and UUID policy must be stable to avoid save/stat/advancement corruption.
- Player lifecycle cleanup must be explicit to avoid ghost players.
- Fabric does not provide a single official FakePlayer abstraction, so we should own a small compatibility layer.
- Runtime testing may reveal server packet/listener assumptions that compile-time validation cannot catch.
- Companion chunk loading must be budgeted. A player-like entity exploring far away can load chunks like a real player.
- Future local LLM work must be asynchronous and rate-limited so it never blocks the server tick thread.

## Architecture Boundary

Recommended future package split:

```text
agent-core/
  Pure AI state, memory, planning, goals, and decisions.

minecraft-adapter/
  ServerPlayerEntity companion lifecycle, observation, and action execution.

mod/
  Fabric entrypoint, commands, config, and integration glue.
```

The AI Core must not depend on Minecraft classes. The Minecraft adapter may depend on both Minecraft and the AI Core interfaces.
