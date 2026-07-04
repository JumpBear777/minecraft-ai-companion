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
- It can equip armor and tools on the server side.
- Equipping armor can trigger vanilla advancement state.
- It can consume food mechanically through `finishUsing`.
- A stronger hurt visual command can trigger visible hurt feedback.

Important interpretation:

The companion is not a real network client, but it is increasingly behaving like a server-side autonomous player.

## Latest Test Notes

Date: 2026-07-04

Commands tested:

```text
/aicompanion equip_tool
/aicompanion equip_armor
/aicompanion give_food
/aicompanion eat
/aicompanion hurt_visual
/aicompanion place_front
```

Observed results:

- Armor/tool server state works: `/aicompanion status` reports main-hand tool and armor slots.
- Vanilla advancement trigger works: equipping iron armor triggered `[Suit Up]` for `AICompanion`.
- Food mechanics partially work: eating an apple changed hunger from `12` to `16` and reduced apple count from `4` to `3`.
- Hurt visuals improved: hurt feedback is visible, but knockback is still not player-like enough.
- Block placement did not appear to work reliably.

New issues discovered:

- Gravity/movement is not player-like yet. If spawned in the air, the companion can remain floating instead of falling.
- Hurt knockback is incomplete. The companion shows hurt feedback, but does not perform a normal small backward knockback.
- Equipment server state does not guarantee client rendering. Armor/tool state exists and advancement fires, but the user did not see armor/tool rendered on the model.
- Eating is instant because the current command calls `finishUsing` directly. It does not yet perform vanilla-style duration-based item use.
- Block placement needs deeper debugging around `BlockHitResult`, target face, selected stack, support block, and interaction context.

Interpretation:

The server-side player state is increasingly credible, but client-visible synchronization and server-driven movement are now the main risk areas.

## Latest Test Notes - Sync And Movement Batch

Date: 2026-07-04

Commands tested:

```text
/aicompanion sync_equipment
/aicompanion use_item_visual
/aicompanion give_blocks
/aicompanion place_front_debug
/aicompanion gravity_test
/aicompanion velocity_test
/aicompanion hurt_visual
```

Observed results:

- `sync_equipment` works: armor and weapon became visible after explicitly sending equipment synchronization.
- `use_item_visual` is no longer instant and shows a duration-based use action.
- Item switching was still wrong during the test: food/block commands reported main-hand changes, but the rendered hand still showed the axe.
- Because the visible and effective main-hand state was stale, eating appeared as the companion swinging the axe instead of visibly eating food.
- Block placement still did not place a block.
- `gravity_test` teleported the companion upward, but natural falling did not occur.
- `velocity_test` produced no visible movement.
- `hurt_visual` still did not produce convincing knockback movement.
- Spawning the companion in midair still leaves it floating.

Interpretation:

Explicit equipment synchronization is required for client-visible state. More importantly, fake players do not receive real client movement packets, so natural player movement, gravity, and knockback cannot be assumed. The future Minecraft Adapter must own server-driven movement and velocity application.

Implementation response:

- Main-hand debug commands now use `setStackInHand` and immediately synchronize equipment to nearby clients.
- `gravity_test` now starts a server-driven fall after teleporting upward.
- `velocity_test` and `hurt_visual` now use server-driven movement instead of only setting velocity.
- `place_front_debug` now reports support block, target block, result, and stack count after attempting vanilla `interactBlock`.

## Latest Test Notes - Confirmed Behavior Batch

Date: 2026-07-04

Commands and manual actions tested:

```text
/aicompanion equip_armor
/aicompanion equip_tool
/aicompanion give_food
/aicompanion use_item_visual
/aicompanion give_blocks
/aicompanion place_front_debug
/aicompanion gravity_test
/aicompanion velocity_test
/aicompanion hurt_visual
manual player attack
```

Confirmed results:

- Armor and weapon rendering now work after explicit equipment synchronization.
- Food can be placed in the companion's main hand and renders correctly.
- Duration-based food use now shows the expected eating behavior.
- Using `use_item_visual` with an axe produces no eating-style action, which is expected because an axe is not a duration-use food item.
- Blocks can be placed in the companion's main hand and render correctly.
- Block placement now works through vanilla-style interaction.
- Server-driven `gravity_test` can make the companion fall and land correctly.
- `velocity_test` can produce a small horizontal movement.
- `hurt_visual` can produce visible damage feedback plus a small backward hop.
- Manual player attacks now produce visible damage feedback plus a small backward hop through an attack event hook.

Remaining architecture conclusion:

Spawning the fake player in midair still does not make it fall naturally. This is expected for the current architecture because the fake `ServerPlayerEntity` has no real client sending movement packets. Future movement, gravity, and knockback must be owned by the Minecraft Adapter as server-driven behavior.

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

Status: Attempted, not yet passed.

Latest note:

Passed after main-hand synchronization fix. `/aicompanion place_front_debug` can place blocks through vanilla-style interaction.

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

Status: Partially passed.

Latest note:

Passed for food. `/aicompanion use_item_visual` now supports duration-based eating behavior when the companion is holding food. Non-duration items such as an axe do not show food-use behavior, which is expected.

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

Status: Partially passed.

Latest note:

Passed after explicit synchronization. Armor and held weapons render correctly when server-side equipment changes are followed by equipment update packets.

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

Status: High priority.

Latest note:

Manual testing found that spawning the companion in the air leaves it floating. This confirms fake players do not naturally run normal client-driven player movement. Server-driven `gravity_test` can make it fall and land, so movement and gravity must be driven by the Minecraft Adapter.

### 10. Hurt Visuals And Knockback

Goal:

Verify that the companion responds to damage with player-like visual and movement feedback.

Candidate commands:

```text
/aicompanion hurt
/aicompanion hurt_visual
```

Pass criteria:

- Companion flashes red or plays hurt status.
- Companion receives visible knockback.
- Health decreases.
- Motion synchronizes to nearby clients.

Status: Partially passed.

Latest note:

Passed with server-driven knockback hop. `/aicompanion hurt_visual` and manual player attacks now produce visible damage feedback plus a small backward hop.

### 11. Death And Respawn

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
- Equipment may require explicit synchronization to nearby clients.
- Duration-based item use must be modeled as a ticked action, not a direct `finishUsing` call.
- Gravity and velocity need explicit validation because fake players do not receive client movement packets.
- Chunk loading must be budgeted to avoid severe performance cost.
- Minecraft version updates may break internal player/network lifecycle APIs.

## Next Recommended Implementation

Prioritize movement/gravity and client synchronization before adding more high-level skills.

Recommended next commands:

```text
/aicompanion gravity_test
/aicompanion velocity_test
/aicompanion sync_equipment
/aicompanion use_item_visual
/aicompanion place_front_debug
```

Reason:

The latest tests show that core server-side state is promising, but the next architecture risk is whether fake players can move, fall, synchronize equipment, and perform duration-based actions convincingly without a real client.
