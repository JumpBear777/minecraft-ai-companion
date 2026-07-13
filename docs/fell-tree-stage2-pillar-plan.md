# 高树搭柱砍伐 · 第二阶段实现计划（2026-07-13）

在已跑通的 `FellNaturalTreeTask`（第一阶段：地面可达整树砍伐）之上，加入**搭柱升高**能力，
处理超交互高度、地面落脚点够不到的主干原木。分叉在第一阶段已跑通（BRANCHY 7/7），本阶段
不单独处理；升高过程中够得到的高处分叉顺带砍掉，够不到的孤立高分叉诚实记失败。

## 已确认决策（用户拍板）
- **脚手架方块**：`fell_tree` 命令额外塞几组泥土进背包（自洽，仿现在塞铁斧）；搭柱走已验证的
  `CompanionHotbar.selectScaffoldBlock`（只认满方块，排除原木/树苗/掉落方块）。
- **柱子回收**：整树砍完后，从记录栈顶（最高、最后搭的）向下逐块挖回（真玩家式，不留残柱），
  走 `CompanionMiningTasks` + 记录栈，回收前校验该位仍是脚手架方块（不盲信记忆）。
- **分叉范围**：本轮只做主干列升高；够不到的孤立高分叉记失败，不为它单独搭柱。

## 问题定位
高处主干原木现在的失败表现是 `no-foothold`：`TreeApproach` 竖向只探 `target.y-1..+3` 且要求
落脚格脚下有实心地面。悬在半空的高处树干下方无地面 → 扫不到合格落脚格 → 返回空。搭柱正是补这个缺口。

## 设计：站在主干旁的相邻列上，逐格搭柱升高

主干自底向上砍。同伴站在**主干相邻的落脚列**（第一阶段 `TreeApproach` 选出的正对面落脚格所在列），
砍到当前站位够不到的高度时，就在**自己脚下这一列**原地搭一格柱升高、重试；升到够得到为止。这样柱子
始终在树干旁的固定 (x,z) 列，砍完后原地回收，不需要横移。

### FellNaturalTreeTask 新增 PILLAR 相位与状态
- 新增 `Phase.PILLAR` 和 `Phase.RECLAIM`。
- 新增字段：`CompanionPillar pillar`、`Deque<BlockPos> placedScaffold`（自底向上入栈）、
  `BlockPos pillarColumn`（升高所在的 (x,z) 列，即成功落脚点的列）。
- 每块原木流程改为：
  1. `TreeChopStep` 从当前位置实时试（可能已够到）。
  2. 够不到 → 走 `TreeApproach` 候选（第一阶段逻辑不变）。
  3. **候选耗尽且失败原因是「够不到高处」（out-of-reach / no-foothold 且 target 明显高于当前站位）**
     → 进入 PILLAR 相位：确认脚下有已站稳的落脚列后，`selectScaffoldBlock` + `pillar.begin(1)`
     搭一格；`lastPlacedPos()` 入 `placedScaffold` 栈；升完一格回到 CHOP 重试当前原木。
  4. 搭柱失败（无脚手架 / 放置窗口不开 / 头顶被挡）→ 记该原木失败原因（no-scaffold / pillar-blocked），
     继续下一块。
- **升高上限**：设 `MAX_PILLAR_LrEVELS`（如 8），防止对一棵畸形高树无限叠柱。到顶仍够不到就记失败。

### 终局回收（RECLAIM 相位）
- 所有原木处理完后，若 `placedScaffold` 非空，进入 RECLAIM：从栈顶（最高）向下逐块
  `TreeChopStep`/`CompanionMiningTasks` 挖回，挖前校验仍是脚手架方块（对不上就跳过）。
- 回收也要有界 + 视线门控（同伴此时站在柱顶，逐格向下挖脚下方块会自然下降——复用挖掘适配器，
  下降靠 vanilla 重力）。回收失败不翻转已成功的砍树结果，但要在诊断里报告残留柱。
- 回收完成 → 按残留原木判定最终 SUCCESS/FAILURE（同第一阶段契约）。

### stop() 幂等释放扩展
`stop` 增加 `pillar.stop()`；其余（navigator、mining、input）不变。搭柱中途被打断，已搭的柱子
留在世界（下次任务不认得它，但不影响安全）——记录栈只活在任务实例内。

## TreeApproach 是否要改
**不改 `plan` 的竖向范围**。搭柱升高由任务在「站定的落脚列上」驱动，不依赖 `TreeApproach` 去
凭空找半空落脚点。第一阶段的 `plan` 仍负责地面可达落脚点的选取；高处够取由 PILLAR 相位补。

## CompanionDebugCommands
- `taskFellTree`：额外 `giveItemStack` 几组泥土（如 2×64）作脚手架。
- 新增测试场景 `setup_tree_tall`：清场 + 一棵 ~8 段高单主干橡木（超交互高度），验证搭柱升高 + 回收。

## 验证
- Gradle build。
- 实机：`setup_tree_tall` → `fell_tree`，观察逐格搭柱升高、砍到顶、从顶回收柱子、最终 SUCCESS。
- 回归：重跑第一阶段矩阵（standard/branchy/dark-oak/grass/short/cliff）确认搭柱逻辑不影响矮树/地面树
  （它们根本不该进 PILLAR 相位）。

## 本轮范围外
- 孤立高分叉单独搭柱。
- 跨列横移搭桥、挖隧道、任意障碍可挖。