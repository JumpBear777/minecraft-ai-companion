# 砍树功能重做 · 工作日志（2026-07-11）

> 本文档记录 2026-07-11 一整天围绕「AI 同伴砍树」重做的全部工作、决策、踩过的坑、以及**明天要接着解决的悬而未决问题**。目的是明天能直接接上，不用重新回忆。

---

## 0. 背景与总目标

砍树模块之前因 bug 太多已从 main 移除、归档到 `archive/chop-module`，地基适配器保留，2026-07-09 起重做（见 memory `chop-module-archived-redo`）。

今天的工作是**从零重建砍树能力**，采用「结构捕获一次、执行按存好的顺序回放」的架构，全程复用已验证的 vanilla 适配器，不自造物理/寻路/挖掘逻辑。

参考项目：GitHub `Dwinovo/minecraft-numen`（同样押注「真 ServerPlayer 身体」，已做到屠龙终局）——今天开头分析过它，值得借鉴的是 Tool/Task 分离、Skill=Markdown SOP、寻路参考 Baritone 重写。**但那些都还没动手**，今天只做砍树。

---

## 1. 今天新建/修改的文件

### 新建（都在 `src/main/java/dev/jumpbear/minecraft_ai_companion/task/`）
- **`TreeDetector.java`** — 无状态识别最近自然树。盒扫描找种子原木 → 26 邻接洪水填充成原木簇 → 用「周围有 ≥4 片 `persistent=false` 自然叶」判定真树；`rejected` 集合防死循环。返回 `Tree(logs, base)`，base = 最低原木（Y 最小，asLong 兜底）。**已验证可用。**
- **`TreeStructure.java`** — 无状态排序器。把 `Tree.logs` 排成砍伐序：**以竖列 (x,z) 为单位**，主干列（base 那列）在前，其余分叉列按离主干轴由近到远，**每列自底向上**。即「一列从下砍到顶，再换下一列」。**已验证顺序正确。**
- **`TreeApproach.java`** — 无状态落脚点选取（纯只读、纯数据查询、**不打射线、不调寻路器**）。渐进半径 3→4，竖向 base.y−1..base.y+3。三判据：站得上（脚下实心+2格身体空间）、够得到（复刻 vanilla `canInteractWithBlockAt(base,1.0)`）、看得见（沿眼→base 中心线段**逐格查扫描数据**，穿过本树叶记入待清、撞非本树方块则淘汰）。排序：**正对面优先**（正交方向胜斜角）→ 贴近 base 高度 → 需清叶最少 → 最近 → asLong。返回 `Approach(foothold, occluders, radius)`。**`tree_plan_show` 已验证选点正确。**
- **`TreeChopSight.java`** — 视线门控（命令和任务共用的单一实现）。`hasLineOfSight`：vanilla 距离判据 `canInteractWithBlockAt(target,1.0)` + 眼→目标中心**线段射线**（`world.raycast` 两点式，不依赖朝向）。`lookAndCheck` = 先 lookAt 再判。**`los_test` 已反复验证 PASS。**
- **`ReachAndChopTask.java`** — 砍树最小闭环任务（tick 驱动状态机）。**⚠️ 今天最后在调试，未跑通，见 §4。**
- **`PillarTestTask.java`** — 搭柱测试任务（更早的测试，已验证）。

### 修改
- **`CompanionDebugCommands.java`** — 加了一批 `/aicompanion` 子命令（见 §2）。
- **`CompanionLifeSystem.java`** — **临时注释掉了闲逛（WANDER）分支**（在 `chooseNextBehavior` 里），便于测试时同伴原地不动。**注意：需要恢复闲逛时取消注释即可。**

---

## 2. 今天加的调试命令（`/aicompanion ...`）

- `tree_scan` — 扫最近树，整簇原木→金块、base→钻石块（可视化识别结果）。
- `los_test` — 视线门控自检测试台（正前方放金块+泥土遮挡，同步测 A 有遮挡应 false、B 无遮挡应 true）。**PASS。**
- `tree_plan_show` — 接近规划「先画后验证」：落脚格→绿宝石块，待清遮挡叶→钻石块，聊天栏报半径/坐标/清叶数。**验证正确。**
- `task reach_tree` — 扫树存有序结构 + 走到 base 旁（存 `TREE_TEST_LAYOUTS`）。
- `tree_paint` — 按结构顺序把原木换成对应颜色羊毛（`序号%16`），保留最顶端原木供养树叶。验证砍伐顺序。
- `tree_chop_next` — 按内存结构顺序逐段砍（可重复），带视线门控。
- `task pillar` — 搭柱测试（塞泥土→随机寻址→搭柱→青金石块替换）。
- `task chop_base` — **今天最后在做的最小闭环**（塞铁斧→走到落脚点→清叶→换斧→挖 base+上面一块）。**未跑通。**

---

## 3. 已确认的决策（Decided，不要重开）

- **砍伐顺序**：竖列为单位，主干列在前，每列自底向上（`TreeStructure`）。
- **站位路线**：站正下方 + 搭方块（用户明确定的，否决了「站相邻格」的审查建议）。主干挖完先拆搭的方块，再去分叉列正下方。**搭方块本身今天还没做。**
- **视线门控**：眼→目标中心线段射线（不依赖朝向，避免「发包设朝向同 tick 读不到」的时序坑）+ vanilla `canInteractWithBlockAt(target,1.0)` 距离判据。**根治了「隔着方块挖穿目标」的 bug。**
- **落脚规划全用扫描数据**：不打射线、不调寻路器，在已读到的区域方块数据上逐格查（用户反复强调）。
- **落脚点范围**：渐进半径 3→4，竖向 base.y−1..base.y+3。找不到就永久跳过这棵树。
- **落脚排序**：正对 base 面优先（斜角常「清完也够不到」）。
- **上挖遮挡**：反应式清现场本树叶，不预规划。
- **树叶衰减**：`tree_paint` 保留最顶端原木供养树叶（防上色/测试期间衰减）。
- **每步可恢复**（用户口径）：指两层——(a) 代码上 Task 的 `stop()` 无条件幂等释放（navigator.stop+dispose、CompanionMiningTasks.cancel 清裂纹、releaseInput）；(b) 版本上，做砸了能回退（**今天所有改动都还没 git commit，天然可回退**）。

## 3b. 已知残留风险（暂不处理，已与用户确认接受）
- 「只有斜角候选」的树，排序救不了、清完可能够不到（只改排序的局限）。
- 伞状树头顶伞盖挡进入路径，判据不检测（低概率）。
- 丛林/深色橡木 2×2 粗主干：`TreeStructure` 只把 base 那列当主干，其余当分叉列。
- 矮树贴地树叶挡 villager 代理寻路（memory `leaves-block-pathfinding-short-trees`）。

---

## 4. ⚠️ 明天要接着解决：`task chop_base` 最小闭环未跑通

### 现象（今天最后一批实测日志）
`task chop_base` 有时成功、有时 `CHOP FAIL: no line-of-sight/reach`，有时 `NAVIGATE FAIL: STUCK`。

### 已定位的根因（按确定性排序）

1. **落脚点「精确到达」与 navigator 末端误差的矛盾（主因）**
   - 现象：规划落脚点 `-21,71,-60`（base 正对面，正确），但同伴实际停在 `-21,71,-61`（斜角）。从斜角看 base 视线被挡 → 门控 FAIL。
   - 中途把 `REACH_DISTANCE` 从 1 改成 0（要求精确到落脚格），结果 **STUCK 频发**——vanilla 寻路+假客户端行走有 ~0.45 格末端漂移，苛求精确到格走不到。
   - **这是核心矛盾**：落脚点是精确算好「站这儿能挖」的，但导航到不了精确格。
   - **明天的解法方向**（已想好，未实现）：`REACH_DISTANCE` 用 1 走到附近，**到达后不立刻判门控 FAIL，而是从当前实际位置就地微调对齐到落脚点**（走完最后一格），对齐后再门控挖掘。而不是苛求 navigator 一步到格。

2. **测试环境脏了（放大了问题）**
   - 有一组日志 `base=-17,73,-57`（**base 在半空 y=73**）——因为上一轮测试把树砍了一半，残桩悬空，`TreeDetector` 把半空原木当 base，`TreeApproach` 落脚点选到 3 格外高台，必然够不到。
   - **明天务必用干净场地测**：
     ```
     清场（站空地）：/fill ~-8 ~-2 ~-8 ~8 ~12 ~8 air
     放标准树：
       /setblock ~2 ~ ~ oak_log
       /setblock ~2 ~1 ~ oak_log
       /setblock ~2 ~2 ~ oak_log
       /setblock ~2 ~3 ~ oak_log
       /fill ~ ~3 ~-2 ~4 ~5 ~2 oak_leaves[persistent=false]
     然后 /aicompanion spawn + task chop_base
     ```
   - 用干净树的日志才能分清「是逻辑 bug 还是脏输入」。

### 调试脚手架（记得清理）
`ReachAndChopTask` 里加了临时的 `debug(companion, msg)` 方法，把 `[ChopDebug] ...` 广播到所有玩家聊天栏，标注每个阶段转换和失败原因。**定位完成后要删掉。**

### 当前 `REACH_DISTANCE` 值
现在是 **0**（改坏了会 STUCK）。明天要么改回 1 + 到达后微调对齐，要么另想。

---

## 5. 明天的建议起手式

1. 用 §4 的清场+标准树指令，跑一次干净的 `task chop_base`，发日志。
2. 若确认是「末端误差/精确到达」问题（大概率）：实现「到达附近→就地微调对齐落脚点→再门控挖掘」。
3. 跑通最小闭环（挖 base+上面一块）后，再扩展：整根主干列 → `CompanionPillar` 搭方块够高处 → 主干挖完拆方块 → 分叉列。
4. 全部跑通、删掉 `[ChopDebug]` 脚手架后，考虑 git commit 一个稳定点。

---

## 6. 构建/测试速查
- 构建：`JAVA_HOME='D:\WorkHelper\Java\JDK-21' GRADLE_USER_HOME='D:\tools\gradle-home' 'D:\tools\Gradle\gradle-9.6.1\bin\gradle.bat' build --no-daemon`
- 今天所有改动**均已通过编译**（最后一次 BUILD SUCCESSFUL）。
- 无自动化测试，全靠 `/aicompanion` 子命令在世界里手测。

---

## 7. 2026-07-12 追加：五项修复 + 二次审查（含 Numen 对比）

### 已改（已编译通过，均未在世界内验证）
- **ReachAndChopTask**：`REACH_DISTANCE` 0→1，新增 `ALIGN` 相位（寻路粗到达后驱到落脚中心≤0.2，30tick 无进展 FAIL）；`tickChop` 挖掘期每 tick lookAt。
- **TreeApproach**：`sightThroughOwnLeaves` 手写 0.25 步长采样 → Amanatides-Woo 精确体素 DDA（消采样缝隙）。
- **TreeStructure**：`columnKey` `^`→`|`（**注：原 `^` 因高低位不重叠本就无碰撞，此改仅澄清意图，非修 bug**）。

### 二次审查发现的问题（21 条 finding → 对抗验证后 8 条 CONFIRMED）

**新引入 / 未解决的关键缺口：**
1. **【P1 最痛】DDA 与门控用两套遮挡形状**：`TreeApproach:209` 用 `getCollisionShape`，`TreeChopSight:34` 用 OUTLINE 射线，双向误判。
   - 方向A（漏判→硬失败）：草/花/树苗/藤蔓/蜘蛛网 `noCollision` 但 OUTLINE 非空 → DDA 判通、门控恒 false，且非本树叶 CLEAR 不清 → base 永远砍不动，`tickChop` 首次门控 FAIL。**base 地面高度最常见（草/藤/蛛网）。**
   - 方向B（误杀）：下半砖/≥2层雪 碰撞非空但视线可从上方掠过，DDA 整格判死 → 误剔落脚点。（1层雪碰撞为空、不中招。）
   - 修法：DDA 改 OUTLINE + 真正线段-VoxelShape 相交（对齐 `BlockView.raycast` 语义），别再"整格非空即挡"。
2. **【ALIGN 缺陷】卡死检测用错信号 + 无绝对超时**：`ALIGN_PROGRESS_EPSILON` 只识别贴墙零速楔死；真正的非收敛是**侧滑/振荡**（每tick 0.05-0.15>阈值 → alignTicks 永复位），而成功窗口 0.2 又因全速 travel 动量冲过头永不满足 → **无限 RUNNING 挂起**（外层无 per-task 墙钟）。修法：改"到中心距离高水位线 + 未改善计tick"，或给 ALIGN 绝对上限。
3. **【P3】挖掘期每 tick lookAt 对宣称目的无效**：vanilla reach 是纯眼位距离、与朝向无关，且只在 START/STOP 校验；lookAt 只发 look 包不移身。瞄准无害但注释误导。
4. **【P3】`stop()` 未 `resetPitch`**：砍完移交后头部俯仰留在最后 lookAt 值（平地约 +7°，1-2s 后 Life System 自动回正，轻微瑕疵）。

**既存遗留（重写前就有）：**
5. **【P2】`isStandable` 纯局部几何**：不验可达性/侧向逃逸/是否被夹死；`plan()` 排序无"离 AI 近/在 AI 一侧"项（四面对称、packed 坐标定胜）；`start()` 锁定后无回退次选。**这正是"正常树跑到台阶低侧楔死"的根因。**
6. **【P2】26 邻域洪泛并树**：对角相邻两树 Chebyshev≤1 即融合，base 取合并簇全局最低（从不按离 companion 距离）→ 可能选错树。密林/2×2深色橡木/藤蔓贴近触发。
7. **【P3】`isNaturalTree` 借邻树叶误判**：radius=2 数叶不校验归属，玩家在真树 2 格外搭的原木结构借真树叶被判自然树 → 砍玩家建筑。

### 与 Numen 对比结论
- **Numen 明显更强**：到达精度（re-localization 整格判定+过冲容忍，不要求亚格）、卡死检测（`maxIndexReached`/`ticksSinceProgress` 净进度高水位，免疫振荡）。
- **本项目更强**：只清本树叶（不误伤）、`TreeStructure` 列优先顺序、幂等 stop、纯无状态分析层、DDA 无采样缝隙。
- **最高杠杆 3 借鉴（借思路非代码）**：①用实时漂移容忍门控取代 ALIGN 精确驱中心（`TreeChopSight` 已按实时眼位验 reach+sight，直接砍身体合法到达处，删 0.2 驱动——同时消掉问题1的规划/执行分歧根因与 ALIGN 挂起）；②净进度高水位卡死检测；③有界反应式破挡回退（仍限本树叶）。

### 下一步优先级
1. [P1 先做] 统一 DDA 与门控遮挡模型（OUTLINE + 线段-VoxelShape 相交）。
2. [采纳 Numen①] 用实时门控替代 ALIGN 精确驱中心（一并解 ALIGN 挂起 + 规划/执行分歧）。
3. [P2] 落脚点接入可达性 + 就近偏好 + 次选回退（解台阶楔死）。
4. [P2] 洪泛改 6 邻域 或 base 按离 companion 最近 + 单树干校验。
5. [采纳 Numen②] 卡死检测改净进度高水位（ReachAndChop + CompanionNavigator）。
6. [P3 收尾] stop 补 resetPitch；修/删每 tick lookAt 误导注释；isNaturalTree 叶归属校验。

> 审查工作流脚本：`.claude/chop-review-workflow.mjs`（可 resume）。
- Yarn 源码参考：`D:\WorkSpace\mc-sources\minecraft-1.21.11-yarn`