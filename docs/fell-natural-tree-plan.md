# FellNaturalTreeTask 实现计划（2026-07-13）

将当前 `ReachAndChopTask` 降级为窄范围调试/回归用例；新增面向正式行为的
`FellNaturalTreeTask`。首版承诺「附近、可确认归属、可安全到达的自然树整树砍伐」，
**不**承诺全地形自动建桥、挖隧道、任意障碍可挖。本次只做**第一阶段（地面可达逐原木）**，
`CompanionPillar` 高树能力留作独立下一步。

借鉴 Numen 的仅是「目标集合 + 不可达隔离 + 实时交互验证」的决策模式；实现由本项目已验证的
Navigator / MiningTasks / Hotbar / Pillar 适配器组成。

---

## 行为契约（写进主任务 javadoc + PROJECT_STATE）

- **成功**：`TreePlan` 快照里每一块原木都已确认变为空气。
- **失败**：只要有计划原木残留即整体 `FAILURE`；保留残留清单 + 每块原因
  （`NO_PATH` / `OUT_OF_SIGHT` / `OUT_OF_REACH` / `PROTECTED` / `NO_TOOL` / `WEDGED`）。
- **归属不清 → 拒绝**：`TreeDetector` 拒绝则 `start` 判 `FAILURE`，不猜测。
- **收集分离**：调用侧 `assign(Fell...)` + `enqueueOnSuccess(CollectDroppedItemsTask)`
  （`CompanionTaskManager.enqueueOnSuccess` 已支持——失败的砍树不被空收集覆盖）。
- **首版不承诺**：自动建桥、挖隧道、任意障碍可挖。够不到就诚实失败。
- **已知残留风险（显式接受，不承诺根治）**：归属判定本质是启发式；玩家在真树 2 格外
  搭的原木借真树叶可能被判自然树（低概率）。

---

## 文件改动

### 1. 新增 `TreeChopStep.java` — 共享单步原语（防两任务判据 drift）
静态 `attempt(companion, target, plan) → StepResult`，枚举
`{ BROKEN, OUT_OF_SIGHT, OUT_OF_REACH, BLOCKED_BY_FOREIGN, IN_PROGRESS, FAILED }`。
**只用一套判据**：当前眼位 + `canInteractWithBlockAt(target,1.0)` + 真实
`world.raycast(OUTLINE)`。射线首命中：
- 命中 target → `CompanionHotbar.selectBestToolFor` + 启动/推进 `CompanionMiningTasks`，
  破坏后复验空气。
- 命中**快照内确认归属的叶**（`plan.ownLeaves` O(1) 判定）→ 只清这一片（走 MiningTasks），
  **有界**：每 target 最多清 `MAX_CLEAR_PER_TARGET` 片，超限记 FAILED。
- 命中其它任何方块 → `BLOCKED_BY_FOREIGN`，放弃该落脚点（**不**学 Numen 自动挖遮挡）。
`ReachAndChopTask` 与 `FellNaturalTreeTask` 都调它。

### 2. `TreeChopSight` — 统一遮挡模型（解决 P1）
门控保持 OUTLINE 射线为执行期权威判据。成功判据改成「从 navigator 实际停下的位置能否
实时够到并看到」，规划期 DDA 只当提示 → 规划/执行两套形状分歧被结构性消除，无需逐像素对齐。

### 3. `TreeDetector` — 保守形状校验（26 邻接 + 事后校验）
保留 26 邻洪泛 + `BlockTags.LOGS`。对结果簇加拒绝规则，`findNearestTree` 仍返回 `Optional`：
- 单一 Y 层原木数 > `MAX_LOGS_PER_LAYER`（3）→ 拒绝（墙/地板特征）。
- 矮簇水平跨度 ≫ 高度（`height ≤ 2` 且水平跨度 > height）→ 拒绝。
- 簇触顶 `MAX_TREE_LOGS` → 拒绝整棵（不砍一部分）。
- 归属叶收紧：`persistent=false` 且在本簇某原木衰减距离内。

### 4. 新增 `TreePlan.java` — 一次性固化的不可变快照
`record TreePlan(List<BlockPos> orderedLogs, Set<Long> ownLeaves, BlockPos base, …evidence)`。
`orderedLogs` = `TreeStructure.ordered`；`ownLeaves` = 允许清理叶的打包坐标 Set。
执行期只读快照，绝不重扫改变目标集。

### 5. `TreeApproach.plan` — 改返回排序候选列表（解决候选切换）
签名 `Optional<Approach>` → `List<Approach>`（沿用现有 6 键排序）。主任务逐候选尝试，
失败的落脚点标记耗尽不重试（避免 A→B→A 循环）。同步修 `tree_plan_show`（DebugCommands:1542）。

### 6. 新增 `FellNaturalTreeTask.java` — 主任务
按 `orderedLogs` 序逐块原木状态机：
- 先 `TreeChopStep.attempt` 从当前位置试（可能已够到，省一次导航）。
- 够不到 → 取该原木 `TreeApproach` 候选列表，逐个 `navigator.pathTo`；
  **`pathTo` 返回 false 立即换下一候选**（不再把 IDLE 当到达）。
- 导航只认 `ARRIVED`；`NO_PATH/STUCK` → 下一候选；候选耗尽 → 记原因，**继续下一块**。
- 到位后再 `attempt`；`BLOCKED_BY_FOREIGN` → 换候选。
- **任务内不再直接 `applyServerTravelForward`**；移动全走 `CompanionNavigator`。
- 反应式清叶有界。
- 结束：残留原木非空 → `FAILURE`（附清单/原因）；全清 → `SUCCESS`。

### 7. `ReachAndChopTask` — 降级为窄调试用例
- 删除 `[ChopDebug]` 全服广播；改用可选诊断 sink（构造器接 `ServerCommandSource`，
  仿 `CompanionMiningTasks.start(...,source,label)`，仅 `task chop_base` 命令传入 → 仅发起者可见）。
- 删任务内 `applyServerTravelForward` 微调；改调共享 `TreeChopStep`。保留作回归用例。

### 8. `CompanionDebugCommands` — 新命令
- `/aicompanion task fell_tree`：塞铁斧 → `assign(FellNaturalTreeTask(source))`
  + `enqueueOnSuccess(CollectDroppedItemsTask)`；诊断走命令 source。
- `/aicompanion wander on|off`：闲逛开关（替代注释掉代码）。
- 同步修 `tree_plan_show` 适配 `plan` 新返回类型。

### 9. `CompanionLifeSystem` — WANDER 开关化
恢复 :95-97 闲逛分支，改由静态开关（默认开）控制；`/aicompanion wander off` 供测试期原地不动。

---

## 验证
- Gradle build（每次代码变更后）。
- 实机矩阵（工作日志 §4 清场指令为基）：普通树 / 矮树 / 草丛遮挡 / 叶遮挡 / 台阶悬崖 /
  分支 / 深色橡木 / 相邻树 / 树旁建筑 / 无路径 / 保护拒绝 / 中途取消 / 挖掘期位移。
- 每组件配可重复 `/aicompanion` 场景命令：树识别拒绝、候选切换、视线门控、完成判定。

## 本次范围外（独立下一步）
- 第二阶段：超交互高度树干组合 `CompanionPillar`。
- 不引入 Numen 自建 A* / 挖隧道 / 自动搭桥 / 任意障碍可挖。