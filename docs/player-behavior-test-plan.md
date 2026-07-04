# 玩家行为测试计划

最后更新：2026-07-04

## 目的

这份文档用于跟踪 AI Companion 原型在游戏机制层面是否能像真实 Minecraft 玩家一样工作。

当前架构使用服务端 `ServerPlayerEntity` 加本地 fake `ClientConnection`。本测试计划的目标是在实现 AI、规划、记忆、自主技能之前，尽量提前降低架构风险。

## 当前结论摘要

当前方案仍然可行。

已经验证：

- 伴侣可以以 `AICompanion` 身份加入世界。
- 伴侣是 `ServerPlayerEntity`。
- 伴侣拥有生命值、饥饿值、经验字段、背包、主手和副手。
- 伴侣可以接收物品到玩家背包。
- 伴侣可以移动或传送。
- 伴侣可以从玩家生命周期中移除。
- 伴侣可以被真实玩家攻击伤害。
- 伴侣可以通过服务端伤害系统受伤。
- 伴侣可以使用 `ServerPlayerInteractionManager.tryBreakBlock`。
- 伴侣可以显示客户端可见的挥手动画。
- 伴侣可以显示客户端可见的方块破坏裂纹。
- 伴侣可以执行带进度表现的视觉挖掘流程，并最终破坏方块。
- 伴侣可以在服务端装备护甲和工具。
- 装备铁护甲可以触发原版进度状态。
- 伴侣可以通过 `finishUsing` 完成食物使用的机械效果。
- 本地 fake connection 可以注册进服务端网络 tick 循环。
- `ServerPlayNetworkHandler` 可以在没有真实网络客户端的情况下 tick。
- 修复 stale network position 回滚后，自然下落可以保持原版物理结果。
- 落水减速表现符合原版。
- 真实玩家手动攻击可以通过真实攻击触发，加服务端原版 `takeKnockback` 逻辑，产生可见击退。
- 真实掉落物拾取正常。
- 真实经验球吸收正常。
- 真实僵尸攻击正常。
- 僵尸着火后攻击伴侣，火焰传播到伴侣的表现正常。
- 水、仙人掌、火、岩浆、岩浆块等环境交互正常。
- 死亡后会正常掉落刚刚给予的物品。

重要解释：

伴侣不是真实网络客户端，但它正在越来越像一个服务端自主玩家。

目前最重要的兼容性发现是：被动物理应该尽量从原版玩家/实体逻辑中恢复，而不是由 Mod 自己重写一套。

## 真实触发批量测试

在继续添加更多原型代码之前，优先跑这一批测试。

测试规则：

- 优先使用真实游戏触发，而不是命令模拟结果。
- 本节中的命令只负责准备难以手动触发的条件。
- 最终判断应来自可见行为和 `/aicompanion status`，而不是 setup 命令本身。

基础准备：

```text
/aicompanion spawn
```

手动测试：

```text
把物品丢到 AICompanion 身上
把 AICompanion 推入或放入水中
把 AICompanion 放到空中，让它自然下落
作为真实玩家攻击 AICompanion
如果方便，把 AICompanion 放到仙人掌、火、岩浆块或岩浆附近
```

辅助场景命令：

```text
/aicompanion setup_pickup_item
/aicompanion setup_xp_orb
/aicompanion setup_zombie_attack
/aicompanion status
```

预期观察：

- 掉落物应通过 `PlayerEntity.tick -> ItemEntity.onPlayerCollision` 被拾取。
- 经验球应通过原版经验球碰撞路径被吸收。
- 僵尸攻击应使用真实 Mob AI 和真实伤害流程。
- 水、下落、环境方块伤害应使用正常实体/玩家物理。
- 任何失败项都应先查 Minecraft 源码，再决定是否添加自定义适配。

## 真实触发批量测试结果

日期：2026-07-04

测试方式：

```text
/aicompanion setup_pickup_item
/aicompanion setup_xp_orb
/aicompanion setup_zombie_attack
手动丢物品到 AICompanion 身上
真实玩家手动攻击
推入或放入水中
放到仙人掌、火、岩浆、岩浆块附近
死亡掉落观察
```

确认结果：

- `setup_pickup_item` 正常。
- `setup_xp_orb` 正常。
- `setup_zombie_attack` 正常。
- 僵尸着火后攻击伴侣时，伴侣同样着火，火焰传播符合原版预期。
- 直接把物品丢到伴侣身上可以正常拾取。
- 真实玩家手打伴侣正常。
- 推入或放入水中正常。
- 放到仙人掌、火、岩浆、岩浆块附近正常。
- 死亡后可以正常掉落刚刚给它的物品。

结论：

这一批测试覆盖了大量被动原版机制，包括物品拾取、经验吸收、Mob 攻击、火焰传播、环境伤害、水中物理和死亡掉落。当前可以认为同类被动机制整体可信，不需要继续逐项重复测试。后续重点应转向主动行为和高风险交互，例如主动移动、容器、睡觉、骑乘、区块加载和重生生命周期。

## 最新测试记录

日期：2026-07-04

测试过的命令：

```text
/aicompanion equip_tool
/aicompanion equip_armor
/aicompanion give_food
/aicompanion eat
/aicompanion hurt_visual
/aicompanion place_front
```

观察结果：

- 护甲/工具的服务端状态有效，`/aicompanion status` 可以报告主手工具和护甲槽位。
- 原版进度触发有效，装备铁护甲时 `AICompanion` 触发了 `[整装上阵]`。
- 食物机制部分有效，吃苹果可以改变饥饿值并减少物品数量。
- 受伤视觉有改善，可以看到受伤反馈，但击退仍不够像玩家。
- 方块放置最初不稳定。

当时发现的问题：

- 重力/移动还不像玩家。如果在空中生成，伴侣可能悬浮不下落。
- 受击击退不完整。伴侣会有受伤反馈，但没有正常的小幅后退。
- 服务端装备状态不一定保证客户端渲染。护甲/工具状态存在，进度也会触发，但模型上未必立即显示。
- 直接调用 `finishUsing` 会让吃东西瞬间完成，不是原版持续使用表现。
- 方块放置需要进一步调试 `BlockHitResult`、目标面、选中物品、支撑方块和交互上下文。

解释：

服务端玩家状态已经有可信度，但客户端可见同步和服务端驱动移动曾经是主要风险区。

## 同步与移动批量测试记录

日期：2026-07-04

测试过的命令：

```text
/aicompanion sync_equipment
/aicompanion use_item_visual
/aicompanion give_blocks
/aicompanion place_front_debug
/aicompanion gravity_test
/aicompanion velocity_test
/aicompanion hurt_visual
```

观察结果：

- `sync_equipment` 有效，显式发送装备同步后，护甲和武器可见。
- `use_item_visual` 不再是瞬间完成，可以看到持续使用动作。
- 早期测试中，物品切换仍有问题：命令报告主手变化，但渲染手上仍显示斧子。
- 因为可见主手状态滞后，吃东西看起来像在挥斧，而不是吃食物。
- 方块放置最初仍没有成功。
- 早期 `gravity_test` 把伴侣传送到上方，但自然下落没有发生。
- 早期 `velocity_test` 没有可见移动。
- 早期 `hurt_visual` 没有产生可信的击退移动。
- 空中生成仍然悬浮。

解释：

客户端可见状态需要显式装备同步。更重要的是，fake player 没有真实客户端移动包，所以不能假设自然玩家移动、重力、击退都会自动发生。

实现响应：

- 主手调试命令改为 `setStackInHand`，并立即向附近客户端同步装备。
- 早期 `gravity_test` 曾使用服务端驱动下落。
- 早期 `velocity_test` 和 `hurt_visual` 曾使用服务端驱动移动。
- `place_front_debug` 报告支撑方块、目标方块、结果和堆叠数量。

## 已确认行为批次

日期：2026-07-04

测试过的命令和手动行为：

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
真实玩家手动攻击
```

确认结果：

- 显式装备同步后，护甲和武器渲染正常。
- 食物可以放到伴侣主手并正确显示。
- 持续使用食物可以显示预期的吃东西行为。
- 对斧子执行 `use_item_visual` 不出现吃东西动作，这是预期行为，因为斧子不是持续食用物品。
- 方块可以放到伴侣主手并正确显示。
- 方块放置可以通过原版式交互成功。
- 早期服务端驱动 `gravity_test` 可以让伴侣下落并落地。
- 早期 `velocity_test` 可以产生小幅水平移动。
- 早期 `hurt_visual` 可以产生受伤反馈和小幅后退。
- 真实玩家攻击可以通过攻击事件 hook 产生受伤反馈和小幅后退。

后续架构结论：

早期测试显示 fake player 在空中会悬浮。源码调查发现原因是：`ServerPlayNetworkHandler.tickMovement` tick 玩家后，会把玩家位置重置回最后一次客户端移动位置。fake player 没有真实客户端移动包，因此原版物理结果被覆盖。

实现响应：

- 将本地 fake connection 注册进 `server.getNetworkIo().getConnections()`，让正常网络循环 tick 伴侣的 packet listener。
- 保持连接为 local，并在显式移除前保持 open。
- 添加只针对 `AICompanion` 的窄 Mixin，跳过 `ServerPlayNetworkHandler.tickMovement` 中 stale position reset。

复测结果：

- 在空中生成或放置伴侣后，不需要运行 `/aicompanion gravity_test`，它也会自然下落。
- 落入水中会像原版一样减速，而不是旧服务端驱动测试那样直直下坠。
- 真实玩家攻击更接近原版受击移动。
- `/aicompanion hurt` 仍主要是机械伤害，不应作为击退主测试。
- `/aicompanion hurt_visual` 是辅助诊断行为，不应作为最终架构依据。

## 真实触发击退记录

日期：2026-07-04

主测试：

```text
真实玩家手动攻击
```

重要测试规则：

命令行为不足以证明伤害/击退结论。对于伤害和击退，主要验证路径必须是真实玩家攻击 `AICompanion`。

源码发现：

- 原版 `PlayerEntity.attack` 会计算攻击效果，并调用 `knockbackTarget`。
- 当目标是 `ServerPlayerEntity` 时，原版会向目标客户端发送 `EntityVelocityUpdateS2CPacket`，然后恢复服务端目标速度。
- 这对真实玩家客户端是正确的，但 `AICompanion` 没有真实客户端保留这个速度，所以服务端速度恢复会导致伴侣只红闪、不移动。

实现响应：

- 曾尝试直接对 `PlayerEntity` 写 Mixin，但运行时注入目标不稳定，会导致客户端启动崩溃，因此撤回。
- 真实手动攻击现在通过 `AttackEntityCallback` 处理。
- 在下一次服务端 tick 中，伴侣使用攻击者 yaw 和原版强度执行 `takeKnockback`。
- 旧的 teleport-based knockback hop task 已删除。

复测结果：

- 客户端启动恢复稳定。
- 真实玩家攻击再次产生可见击退。
- 这仍然是针对 fake-player 生命周期差异的窄适配，但比 teleport 驱动移动更接近原版，因为它使用 `LivingEntity.takeKnockback`，而不是自定义位置动画。

## 已验证项目

### 生命周期

命令：

```text
/aicompanion spawn
/aicompanion status
/aicompanion move_here
/aicompanion remove
```

通过标准：

- 伴侣加入游戏。
- 伴侣出现在世界中。
- 状态命令可以找到它。
- 移动命令可以把它带到玩家附近。
- 移除命令可以让它干净离开。

状态：已通过。

### 玩家状态

命令：

```text
/aicompanion status
```

通过标准：

- 报告生命值。
- 报告饥饿值和饱和度。
- 报告经验等级和总经验。
- 报告游戏模式。
- 报告背包占用槽位。
- 报告选中物品和副手物品。

状态：已通过。

### 生存伤害

命令：

```text
/aicompanion spawn
/aicompanion hurt
/aicompanion status
```

通过标准：

- 伴侣被强制设为 `SURVIVAL`。
- `canTakeDamage=true`。
- `/aicompanion hurt` 会减少生命值。
- 玩家可以手动攻击伴侣并减少生命值。

状态：已通过。

### 背包插入

命令：

```text
/aicompanion give_wood
/aicompanion status
```

通过标准：

- 背包占用槽位增加。
- 选中物品可以显示橡木原木。

状态：已通过。

### 瞬间破坏方块

命令：

```text
/aicompanion break_front
```

通过标准：

- 伴侣尝试破坏正前方方块。
- 命令返回 `broken=true`。
- 目标方块消失。
- 方块掉落遵循原版规则。

状态：已通过。

备注：

此命令验证机制，不验证表现。它使用 `tryBreakBlock`，可以瞬间破坏。

### 视觉挖掘

命令：

```text
/aicompanion swing
/aicompanion mine_front_visual
```

通过标准：

- 可以看到主手挥动动画。
- 可以看到方块破坏裂纹进度。
- 伴侣在短暂延迟后破坏方块。

状态：已通过。

## 后续测试项

### 1. 方块放置

目标：

验证伴侣能否通过玩家式交互流程放置背包中的方块。

候选命令：

```text
/aicompanion give_blocks
/aicompanion place_front
```

通过标准：

- 伴侣拥有可放置方块。
- 伴侣使用主手物品。
- 方块出现在伴侣前方或相邻位置。
- 背包堆叠数量减少。
- 在可行范围内遵守原版放置规则。

风险：

方块放置可能依赖正确的 `BlockHitResult`、手上物品、目标面和玩家交互上下文。

状态：已通过。

最新备注：

主手同步修复后，`/aicompanion place_front_debug` 可以通过原版式交互放置方块。

### 2. 物品使用

目标：

验证伴侣能否通过原版物品交互路径使用物品。

候选命令：

```text
/aicompanion give_food
/aicompanion use_item_visual
```

通过标准：

- 伴侣可以持有食物。
- 伴侣可以触发物品使用。
- 适用时，饥饿值/饱和度变化。
- 适用时，堆叠数量变化。
- 客户端可见相关手部动画。

风险：

部分物品使用是持续型，需要 ticked use state，而不是一次性调用。

状态：食物已通过。

最新备注：

`/aicompanion use_item_visual` 在伴侣持有食物时支持持续吃东西表现。斧子等非持续食用物品不会显示吃东西行为，这是预期结果。

### 3. 装备

目标：

验证护甲和手持装备行为。

候选命令：

```text
/aicompanion equip_armor
/aicompanion equip_tool
/aicompanion status
```

通过标准：

- 护甲显示在模型上。
- 主手工具可见。
- 装备在预期情况下影响伤害或挖掘速度。

风险：

服务端状态可能正确更新，但客户端渲染需要显式同步。

状态：已通过。

最新备注：

显式同步后，护甲和手持武器可以正确渲染。

### 4. 拾取物品

目标：

验证伴侣是否能像普通玩家一样拾取物品实体。

候选设置：

- 手动把物品丢到伴侣身边或身上。
- 使用 `/aicompanion setup_pickup_item` 生成真实掉落物。

候选命令：

```text
/aicompanion setup_pickup_item
/aicompanion status
```

通过标准：

- 物品实体消失。
- 伴侣背包收到物品。
- 如果原版触发拾取动画/声音，则客户端可见或可听。

风险：

拾取依赖移动、碰撞和 tick 行为。

状态：已通过。

最新备注：

`/aicompanion setup_pickup_item` 和手动丢物品到伴侣身上都已正常通过。拾取路径符合 `PlayerEntity.tick -> ItemEntity.onPlayerCollision` 的原版触发方式。

### 5. 容器交互

目标：

验证伴侣能否与箱子等容器交互。

候选命令：

```text
/aicompanion open_front_container
/aicompanion container_put_wood
/aicompanion container_take_first
```

通过标准：

- 伴侣可以识别前方容器。
- 伴侣可以向容器放入物品。
- 伴侣可以从容器取出物品。
- 背包和容器内容正确更新。

风险：

高。真实玩家依赖客户端 screen handler 和点击包。AI Companion 可能需要服务端背包操作，而不是 GUI 驱动交互。

### 6. 睡觉

目标：

验证伴侣能否在床上睡觉。

候选命令：

```text
/aicompanion sleep_front_bed
/aicompanion wake
```

通过标准：

- 伴侣进入睡觉姿势。
- 床占用状态正确。
- 唤醒命令恢复正常状态。

风险：

睡觉会和多人睡眠比例、玩家列表状态交互。

### 7. 骑乘

目标：

验证伴侣能否骑乘和下马。

候选命令：

```text
/aicompanion ride_nearest
/aicompanion dismount
```

通过标准：

- 伴侣骑上附近可骑乘实体。
- 客户端可见骑乘姿势。
- 下马正常。

风险：

部分可骑乘实体需要所有权、鞍或特定交互状态。

### 8. 区块加载

目标：

验证伴侣是否参与自然玩家区块跟踪。

候选命令：

```text
/aicompanion teleport_far
/aicompanion status
```

通过标准：

- 伴侣可以存在于远离玩家的位置。
- 它附近区块像玩家需要的那样保持加载/tick。
- 服务端不崩溃，也不会异常卸载伴侣。

风险：

性能影响高。自主探索可能加载大量区块。

### 9. 移动与旋转

目标：

验证伴侣是否能以客户端可见的方式移动和看向目标。

候选命令：

```text
/aicompanion step_forward
/aicompanion look_at_me
/aicompanion walk_to_me
```

通过标准：

- 伴侣位置更新足够平滑。
- 旋转/头部方向可见更新。
- 移动不出现不同步或橡皮筋。

风险：

fake player 没有客户端移动包，因此主动移动仍需研究。

状态：高优先级。

最新备注：

最初手动测试发现空中生成会悬浮。将 fake connection 注册进 `ServerNetworkIo`，并只为 `AICompanion` 跳过 stale network-position rollback 后，自然下落和落水减速已经符合原版。

这改变了架构结论：被动物理应尽可能通过正常玩家 tick 路径保留。Minecraft Adapter 不应默认自定义重力/物理，除非某个行为明确无法从原版系统恢复。

### 10. 受伤视觉与击退

目标：

验证伴侣受伤时是否有类似玩家的视觉和移动反馈。

主测试：

```text
真实玩家手动攻击
```

辅助命令：

```text
/aicompanion hurt
/aicompanion hurt_visual
```

通过标准：

- 伴侣红闪或播放受伤状态。
- 伴侣获得可见击退。
- 生命值减少。
- 运动同步到附近客户端。

状态：真实手打已通过。

最新备注：

真实玩家攻击现在通过真实攻击触发和原版 `takeKnockback` 数学产生可见击退。`/aicompanion hurt` 仍是机械伤害，不应作为主要击退测试。`/aicompanion hurt_visual` 是辅助诊断行为，不是主架构验证。

### 11. 死亡与重生

目标：

验证玩家死亡生命周期。

候选命令：

```text
/aicompanion kill
/aicompanion respawn
```

通过标准：

- 伴侣可以死亡。
- 掉落物按规则产生。
- 死亡消息/生命周期不崩溃。
- 可以重生，或有意地处理重生逻辑。

风险：

高。真实玩家死亡/重生依赖网络生命周期和客户端状态切换。

## 已知设计方向

推荐架构仍然是：

```text
AI Core
  -> Intent / Goal / Skill
  -> Minecraft Adapter
       -> ServerPlayerEntity
       -> Local fake ClientConnection
       -> ServerPlayNetworkHandler tick lifecycle
       -> ServerPlayerInteractionManager
       -> Inventory / Equipment / ScreenHandler
       -> World / Chunk / Entity APIs
```

AI Core 不能依赖 Minecraft 类。

Minecraft Adapter 应负责所有和版本相关的 fake-player 行为。

兼容性优先级：

优先使用原版玩家/实体系统。自定义服务端驱动移动应是兜底方案，而不是默认架构，因为项目需要尽可能兼容原版机制和其他 Mod。

## 当前风险登记

- fake/local connection 可能在依赖真实客户端包流的系统中失败。
- 当前伴侣仍需要一个针对 `ServerPlayNetworkHandler.tickMovement` 的 Mixin，这对版本敏感。
- 曾尝试更宽的 `PlayerEntity` 击退 Mixin，但启动不稳定，已撤回；除非证明必要且稳定，否则优先使用事件/API 适配。
- 容器交互可能是近期最难的功能。
- 主动移动仍需研究，因为没有真实客户端发送移动包。
- 视觉行为可能需要显式挥手、破坏进度、旋转和实体状态更新。
- 装备可能需要显式同步到附近客户端。
- 持续型物品使用必须建模为 ticked action，而不是直接调用 `finishUsing`。
- 重力已通过保留原版物理验证，真实手打击退也可见。跳跃、游泳、转向、疾跑仍需验证。
- 区块加载必须做预算，否则会有严重性能风险。
- Minecraft 版本更新可能破坏内部玩家/网络生命周期 API。

## 下一步建议

同类被动机制已经通过批量真实触发测试，不必继续逐项重复验证。下一步应转向更有架构风险的主动行为和复杂交互。

推荐下一轮测试：

```text
主动移动与转向
容器交互
睡觉
骑乘
远距离区块加载
死亡后重生
```

原因：

最新测试表明，被动原版物理、真实攻击击退、拾取、经验、Mob 攻击、环境伤害和死亡掉落已经足够支撑原型继续推进。剩余风险主要集中在需要主动控制或复杂客户端生命周期的系统。
