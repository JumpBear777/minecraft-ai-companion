# Player Behavior Test Plan

Last updated: 2026-07-04

## Purpose

This document tracks whether the AI Companion prototype can behave like a real Minecraft player at the game-mechanics level.

The current architecture uses a server-side `ServerPlayerEntity` with a fake/local `ClientConnection`. The goal of this test plan is to reduce risk before building AI, planning, memory, or autonomous skills.

## Current Result Summary

The current approach remains viable.

Verified so far:

- A companion can join the world as `AICompanion`.
- The companion is a `ServerPlayerEntity`.
- It has health, hunger, XP fields, inventory, main hand, and offhand.
- It can receive items into player inventory.
- It can be moved/teleported.
- It can be removed from the player lifecycle.
- It can be damaged by normal player attacks.
- It can be damaged through the server damage system.
- It can use `ServerPlayerInteractionManager.tryBreakBlock`.
- It can show client-visible hand swing animation.
- It can show client-visible block breaking cracks.
- It can perform a delayed visual mining flow and then break a block.

Important interpretation:

The companion is not a real network client, but it is increasingly behaving like a server-side autonomous player.

## Already Verified

### Lifecycle

Commands:

```text
/aicompanion spawn
/aicompanion status
/aicompanion move_here
/aicompanion remove
```

Pass criteria:

- Companion joins the game.
- Companion appears in the world.
- Status command can find it.
- Move command brings it near the user.
- Remove command makes it leave cleanly.

Status: Passed.

### Player State

Command:

```text
/aicompanion status
```

Pass criteria:

- Reports health.
- Reports hunger and saturation.
- Reports XP level and total XP.
- Reports game mode.
- Reports inventory occupied slots.
- Reports selected item and offhand item.

Status: Passed.

### Survival Damage

Commands:

```text
/aicompanion spawn
/aicompanion hurt
/aicompanion status
```

Pass criteria:

- Companion is forced into `SURVIVAL`.
- `canTakeDamage=true`.
- `/aicompanion hurt` reduces health.
- User can manually attack the companion and reduce health.

Status: Passed.

### Inventory Insert

Commands:

```text
/aicompanion give_wood
/aicompanion status
```

Pass criteria:

- Inventory occupied slot count increases.
- Selected stack can show oak logs.

Status: Passed.

### Instant Block Breaking

Command:

```text
/aicompanion break_front
```

Pass criteria:

- Companion attempts to break the block directly in front of it.
- Command returns `broken=true`.
- Target block disappears.
- Block drops behave according to vanilla rules.

Status: Passed.

Note:

This command verifies mechanics, not presentation. It uses `tryBreakBlock` and can break instantly.

### Visual Mining

Commands:

```text
/aicompanion swing
/aicompanion mine_front_visual
```

Pass criteria:

- User can see hand swing animation.
- User can see block breaking crack progress.
- Companion breaks the block after a short delay.

Status: Passed.

## Next Tests

### 1. Block Placement

Goal:

Verify that the companion can place blocks from its inventory using player-like interaction flow.

Candidate commands:

```text
/aicompanion give_blocks
/aicompanion place_front
```

Pass criteria:

- Companion has placeable blocks in inventory.
- Companion uses main hand item.
- A block appears in front of or adjacent to the companion.
- Inventory stack count decreases.
- Placement respects vanilla rules where practical.

Risk:

Block placement may require a correct `BlockHitResult`, hand item, target face, and player interaction context.

### 2. Item Use

Goal:

Verify that the companion can use items through vanilla item interaction paths.

Candidate commands:

```text
/aicompanion give_food
/aicompanion use_item
```

Pass criteria:

- Companion can hold a food item.
- Companion can trigger item use.
- Hunger/saturation changes if applicable.
- Stack count changes if applicable.
- Client can see hand animation if relevant.

Risk:

Some item usage is duration-based and may require ticked use state, not a one-shot call.

### 3. Equipment

Goal:

Verify armor and held equipment behavior.

Candidate commands:

```text
/aicompanion equip_armor
/aicompanion equip_tool
/aicompanion status
```

Pass criteria:

- Armor appears on the model.
- Main hand tool appears.
- Equipment affects damage or mining speed where expected.

Risk:

Server state may update correctly while client rendering needs explicit sync.

### 4. Pickup Items

Goal:

Verify whether the companion can pick up item entities like a normal player.

Candidate setup:

- Drop item near companion.
- Move companion over item.

Candidate commands:

```text
/aicompanion move_here
/aicompanion status
```

Pass criteria:

- Item entity disappears.
- Companion inventory receives item.
- Pickup animation/sound occurs if vanilla triggers it.

Risk:

Pickup may depend on movement, collision, and tick behavior.

### 5. Container Interaction

Goal:

Verify whether the companion can interact with containers such as chests.

Candidate commands:

```text
/aicompanion open_front_container
/aicompanion container_put_wood
/aicompanion container_take_first
```

Pass criteria:

- Companion can identify a container in front.
- Companion can insert an item into the container.
- Companion can remove an item from the container.
- Inventory and container contents update correctly.

Risk:

High. A real player relies on client screen handling and click packets. The AI companion probably needs server-side inventory manipulation rather than GUI-driven interaction.

### 6. Sleeping

Goal:

Verify whether the companion can sleep in a bed.

Candidate commands:

```text
/aicompanion sleep_front_bed
/aicompanion wake
```

Pass criteria:

- Companion enters sleeping pose.
- Bed occupancy behaves correctly.
- Wake command returns it to normal.

Risk:

Sleeping may interact with multiplayer sleep percentage and player list state.

### 7. Riding

Goal:

Verify whether the companion can mount and dismount entities.

Candidate commands:

```text
/aicompanion ride_nearest
/aicompanion dismount
```

Pass criteria:

- Companion mounts a nearby rideable entity.
- Client sees riding pose.
- Dismount works.

Risk:

Some rideables require ownership, saddles, or interaction-specific state.

### 8. Chunk Loading

Goal:

Verify whether the companion participates in natural player chunk tracking.

Candidate commands:

```text
/aicompanion teleport_far
/aicompanion status
```

Pass criteria:

- Companion can exist far away from the user.
- Its nearby chunks stay loaded/ticked as a player would require.
- Server does not crash or unload the companion unexpectedly.

Risk:

High performance impact. Autonomous exploration can load many chunks.

### 9. Movement And Rotation

Goal:

Verify that the companion can move and look around in a client-visible way.

Candidate commands:

```text
/aicompanion step_forward
/aicompanion look_at_me
/aicompanion walk_to_me
```

Pass criteria:

- Companion position updates smoothly enough for gameplay.
- Rotation/head direction updates visibly.
- Movement does not desync or rubber-band.

Risk:

A fake player has no client movement packets, so movement must be driven server-side.

### 10. Death And Respawn

Goal:

Verify player death lifecycle.

Candidate commands:

```text
/aicompanion kill
/aicompanion respawn
```

Pass criteria:

- Companion can die.
- Drops are produced according to rules.
- Death message/lifecycle does not crash.
- Respawn is possible or intentionally handled.

Risk:

High. Real player death/respawn expects network lifecycle and client state transitions.

## Known Design Direction

Recommended architecture remains:

```text
AI Core
  -> Intent / Goal / Skill
  -> Minecraft Adapter
       -> ServerPlayerEntity
       -> ServerPlayerInteractionManager
       -> Inventory / Equipment / ScreenHandler
       -> World / Chunk / Entity APIs
```

The AI Core must not depend on Minecraft classes.

The Minecraft Adapter should own all version-sensitive fake-player behavior.

## Current Risk Register

- Fake/local connection may fail in systems that expect a real client packet flow.
- Container interaction is likely the hardest near-term feature.
- Movement must be server-driven because there is no real client sending movement packets.
- Visual behavior may require explicit swing, block-breaking, rotation, and entity status updates.
- Chunk loading must be budgeted to avoid severe performance cost.
- Minecraft version updates may break internal player/network lifecycle APIs.

## Next Recommended Implementation

Implement block placement next.

Suggested command:

```text
/aicompanion place_front
```

Reason:

Together, inventory insert, visual mining, and block placement form the mechanical base for future skills such as collecting wood, building simple structures, and interacting with the world as a player-like agent.
