# bearing-frequency-service

滚动轴承特征频率核算服务。给定轴的转频（或转速）与轴承几何参数，按纯滚动、无打滑、外圈固定的运动学假设，计算四个特征频率，供对照振动频谱判断故障部位。仅提供 HTTP 接口，无页面。

- 运行时：JDK 17 · Spring Boot 3 · Maven 构建
- 主输出：**BPFO / BPFI / FTF / BSF** 四个特征频率（Hz）
- 可选附加：围绕转频的调制边带位置
- 反向诊断：**实测谱峰全局归属 + 打滑系数估计**（见文末专节）

## 运动学公式

记 `n` 滚动体个数，`fr` 转频（Hz），`d` 滚动体直径，`D` 节圆直径，`α` 接触角，`β = (d/D)·cos α`：

| 频率 | 含义 | 公式 |
|---|---|---|
| BPFO | 外圈通过频率 | `n/2 · fr · (1 − β)` |
| BPFI | 内圈通过频率 | `n/2 · fr · (1 + β)` |
| FTF  | 保持架频率   | `1/2 · fr · (1 − β)`（与滚动体个数无关） |
| BSF  | 滚动体自转频率 | `D/(2d) · fr · (1 − β²)` |

边带：故障频率受转频调制时，在其两侧出现 `f ± k·fr`（k = 1..sidebandOrder）的边带。

## 代码分块

```
src/main/java/com/bearingfreq/
├── kinematics/    核心：四条特征频率公式（BearingKinematics）
├── sideband/      边带推导（SidebandAnalyzer）
├── catalog/       轴承档登记与检索（BearingCatalog，内存实现，重启需重新登记）
├── attribution/   峰值归属 + 打滑估计（目标谱、全局一对一配对、打滑迭代）
├── validation/    正算参数合法性检查（BearingInputValidator，计算前拦截）
├── service/       编排：校验 → 计算 → 边带 / 归属迭代
└── api/           接口层：仅请求解析与结果拼装
```

`attribution/` 内部四块各自独立：

| 类 | 职责 |
|---|---|
| `TargetSpectrum` | 复用 `BearingKinematics` 生成 5 族 × N 阶候选目标，按打滑线性缩放 |
| `PeakAssigner` | 全局最优一对一配对（最小费用最大流，整数费用，确定性平局裁决） |
| `SlipEstimator` | 由轴承族配对等权反推打滑系数并夹到允许范围 |
| `SlipAttributionEngine` | 迭代控制：收敛/翻转/上限判定，锁死时只做一轮 |
| `AttributionInputValidator` | 归属请求的参数与峰列表校验 |

## 构建与运行

本地（需 JDK 17 + Maven）：

```bash
mvn test          # 跑测试
mvn package       # 打包（含测试）
java -jar target/bearing-frequency-service-1.0.0.jar
```

Docker（一键构建，构建阶段会跑全部测试）：

```bash
docker build -t bearing-frequency-service .
docker run --rm -p 8080:8080 bearing-frequency-service
```

## HTTP 接口

### 1. 直接核算 `POST /api/v1/frequencies`

`rotationFrequencyHz`（转频 Hz）与 `speedRpm`（转速 rpm）二选一；`contactAngleDeg` 缺省 0；`sidebandOrder` 缺省 0（不输出边带，上限 5）。

```bash
curl -s localhost:8080/api/v1/frequencies -H 'Content-Type: application/json' -d '{
  "speedRpm": 1500,
  "sidebandOrder": 1,
  "geometry": {"rollerCount": 9, "pitchDiameterMm": 39.04, "rollerDiameterMm": 7.94, "contactAngleDeg": 0}
}'
```

```json
{
  "rotationFrequencyHz": 25.0,
  "frequencies": {"bpfoHz": 89.6196, "bpfiHz": 135.3804, "ftfHz": 9.9577, "bsfHz": 58.9188},
  "sidebands": {"bpfo": [{"order": 1, "lowerHz": 64.6196, "upperHz": 114.6196}], "...": "..."}
}
```

### 2. 轴承档（内存登记，重启后需重新登记）

```bash
# 登记（同名覆盖）
curl -s -X PUT localhost:8080/api/v1/catalog/6205 -H 'Content-Type: application/json' -d \
  '{"rollerCount":9,"pitchDiameterMm":39.04,"rollerDiameterMm":7.94,"contactAngleDeg":0}'

# 按档核算
curl -s localhost:8080/api/v1/catalog/6205/frequencies -H 'Content-Type: application/json' -d \
  '{"rotationFrequencyHz":25.0}'

# 检索 / 列表
curl -s localhost:8080/api/v1/catalog/6205
curl -s localhost:8080/api/v1/catalog
```

### 错误响应

不合几何常理的输入在计算前被拦下，返回 400（未知轴承档返回 404），响应为 RFC 7807 ProblemDetail，`reason` 字段讲明缘由：

```json
{"title": "输入参数不合法", "status": 400,
 "detail": "滚动体直径必须小于节圆直径（几何上不成立）：滚动体直径 40.0 mm，节圆直径 39.04 mm",
 "reason": "..."}
```

拦截规则：滚动体直径 ≥ 节圆直径；滚动体个数 < 3；转频不为正；接触角超出 0~90° 或余弦绝对值 > 1；转频/转速同时提供或都不提供。

## 峰值归属 + 打滑估计 `POST /api/v1/peak-attribution`

反向诊断能力：交一串实测谱峰（频率 + 幅值），服务判定每根峰是「谁的第几阶谐波」，并把滚动体打滑造成的系统性偏低一并反推出来。转频/转速沿用二选一口径；轴承几何可内联给（`geometry`）或引用已登记轴承档（`bearingName`），二者只能给一个。

### 候选目标与匹配窗口

候选目标 = 转频族 `shaft` 与四个轴承族 `bpfo/bpfi/ftf/bsf`，各自第 1 阶到 `maxOrder` 阶的整数倍。运动学基础频率**直接复用** `BearingKinematics.compute`，不重复公式。

一根峰能配某个目标，当且仅当

```
|峰频 − 修正后目标频率| ≤ 相对容差 × 修正后目标频率 + 频谱分辨率
```

| 参数 | 默认 | 允许范围 | 含义 |
|---|---|---|---|
| `relativeTolerance` | 0.02（2%） | (0, 0.10] | 相对匹配容差 |
| `frequencyResolutionHz` | 0.0 | [0, +∞) 有限值 | 频谱分辨率（Hz），给所有目标补同一个绝对窗宽 |
| `maxOrder` | 10 | [1, 10] | 谐波阶次上限 |
| `minSlip` / `maxSlip` | 0 / 0.05 | 整体落在 [0, 0.50] 且 min ≤ max | 打滑系数允许范围 |
| `lockedSlip` | 不提供 | 必须 ∈ [minSlip, maxSlip] | 锁死打滑，只做一次归属不迭代 |
| `peaks` | — | 非空、≤ 500 根 | 峰频率为正有限值，幅值非负有限值 |

### 归属是全局最优的一对一配对

每根峰至多归一个目标，每个目标至多认一根峰，超窗不配，配不上的峰进 `unassignedPeakIndexes`。在全部合法方案里按**字典序**择优：

1. **配对数最多**（先保证尽量多的峰被认领）；
2. 配对数相同，**总绝对相对偏差最小**：Σ |峰频 − 修正目标| / 修正目标；
3. 仍相同，**平局确定裁决**：优先族序（shaft→bpfo→bpfi→ftf→bsf）与阶次编号更小的目标，再取规范化后编号更小的峰。

设计说明：

- 偏差用**相对**量纲而非绝对 Hz——高频谐波的绝对偏差天然更大，等权绝对偏差会让高阶目标没人敢认；
- **不按幅值加权**——幅值受测点、增益与工况影响，权重会引入与打滑无关的偏置；幅值仍随每根归属明细原样回显；
- 求解用单位容量网络的**最小费用最大流**（Dijkstra + 节点势，整数费用编码三级目标），不是「谁离得近谁先拿」的逐个贪心；峰先按（频率、幅值、原始下标）规范化排序。同一请求永远得到同一结果，与峰列表提交顺序无关；
- 500 峰 × 10 阶在普通环境下单次约几十毫秒（测试上限 2 秒）。

### 打滑模型与迭代

打滑让四个轴承部位频率统一按 `(1 − s)` 偏低，**转频及其整数倍不修正**：

```
修正后目标 = 理论目标 × (1 − s)        （轴承四族）
修正后目标 = 理论目标                  （shaft 族）
```

估计：对每根归到轴承族的配对解 `s_i = 1 − 峰频 / 理论目标`，取**等权平均**后夹到 [minSlip, maxSlip]（不按幅值/阶次加权，理由同上）。没有任何轴承族配对时打滑不可观测，结果中如实标注。

迭代：`按当前 s 修正目标 → 全局归属 → 重估 s`，直到：

- **收敛**：相邻两轮 |Δs| ≤ **1e-6**（或同一配对方案在容差内重现的不动点）；返回时以最终 s 再做一次归属，保证方案与系数同源；
- **方案翻转**：同一配对方案在不同 s 下重现且 Δs 超容差，判为两套方案来回翻转，**停止迭代并标 `converged=false`**，在历轮中取「配对数最多、总相对偏差最小、最早出现」的一轮返回，`statusReason` 写明翻转的轮次；
- **迭代上限**：最多 **30** 轮，仍不稳定则 `converged=false`，返回最后重估 s 下的归属并注明原因，绝不静默给看似正常的答案。

`lockedSlip` 锁死时只做一次归属，`iterations=1`、`converged=true`。

### 请求与响应

```bash
curl -s localhost:8080/api/v1/peak-attribution -H 'Content-Type: application/json' -d '{
  "rotationFrequencyHz": 25.0,
  "maxOrder": 10,
  "relativeTolerance": 0.02,
  "frequencyResolutionHz": 0.0,
  "minSlip": 0.0, "maxSlip": 0.05,
  "geometry": {"rollerCount":9,"pitchDiameterMm":39.04,"rollerDiameterMm":7.94,"contactAngleDeg":0},
  "peaks": [
    {"frequencyHz": 25.0,   "amplitude": 1.0},
    {"frequencyHz": 88.72,  "amplitude": 2.4},
    {"frequencyHz": 333.33, "amplitude": 0.2}
  ]
}'
```

```json
{
  "rotationFrequencyHz": 25.0,
  "slipCoefficient": 0.01003,
  "converged": true,
  "iterations": 2,
  "slipAtAllowedBound": false,
  "statusReason": "打滑系数相邻轮变化不超过 1.0E-6，已收敛。",
  "matchedCount": 2,
  "totalAbsDeviationHz": 0.0063,
  "totalAbsRelativeDeviation": 0.000071,
  "assignments": [
    {"peakIndex":0,"peakFrequencyHz":25.0,"amplitude":1.0,"family":"shaft","order":1,
     "theoreticalTargetHz":25.0,"correctedTargetHz":25.0,"deviationHz":0.0,"relativeDeviation":0.0},
    {"peakIndex":1,"peakFrequencyHz":88.72,"amplitude":2.4,"family":"bpfo","order":1,
     "theoreticalTargetHz":89.6196,"correctedTargetHz":88.7207,
     "deviationHz":-0.0007,"relativeDeviation":-7.9e-6}
  ],
  "unassignedPeakIndexes": [2]
}
```

`deviationHz = 峰频 − 修正目标` 带符号（正=峰偏高，负=偏低）。引用档名时多一个 `bearingName` 字段。整体频率尺度乘任意正数（转频、峰、分辨率同乘）时，归属、相对偏差与打滑系数不变。

### 错误响应（计算前拦截）

沿用 RFC 7807，400 的 `reason` 精确到字段与「第几根峰」（下标从 0 起，括号里给 1 起序号）：峰列表为空/超 500；某根峰频率非正或幅值为负；容差/分辨率/阶次/打滑范围越界；`lockedSlip` 超出允许范围；`geometry` 与 `bearingName` 同时给或都不给；内联几何不合法沿用既有几何拦截；引用不存在的档名返回 404。

### 归属能力的验收测试

`mvn test` 中覆盖：纯理论峰 ⇒ s≈0 且与锁死零逐字段一致；已知 s 合成（夹转频倍频、干扰峰与确定性扰动）⇒ 反推 |ŝ−s| < 2e-3、干扰峰未归属、转频不修正；构造逐个就近认领更差的输入 ⇒ 全局方案总偏差严格更小；乱序/重复提交结果一致；整体乘正尺度不变；内联几何与同参数档一致、两档互不串参；500 峰 × 10 阶 2 秒内返回（实测约 90 ms）。

## 基准算例（回归测试钉住）

6205 深沟球轴承（n=9，D=39.04 mm，d=7.94 mm，α=0°），fr = 25 Hz（1500 rpm），手算 β = 7.94/39.04 ≈ 0.203381：

| 频率 | 手算值 |
|---|---|
| BPFO | 4.5·25·(1−β) ≈ **89.6196 Hz** |
| BPFI | 4.5·25·(1+β) ≈ **135.3804 Hz**（> BPFO ✓） |
| FTF  | 12.5·(1−β) ≈ **9.9577 Hz** |
| BSF  | (39.04/15.88)·25·(1−β²) ≈ **58.9188 Hz** |

## 测试覆盖的运动学事实

`mvn test` 运行，核心事实钉在 `BearingKinematicsTest`：

1. 转频翻一倍 → 四个特征频率全部翻倍；
2. 滚动体个数翻倍 → BPFO/BPFI 翻倍，FTF（与 BSF）纹丝不动；
3. 外圈固定常规工况 → BPFI > BPFO；
4. 接触角 0°→15° → 余弦项变化如实反映到各频率（漏掉 cos 会让频率纹丝不动，测试会抓住）；
5. 6205 基准算例与手算值一致。

另有校验拦截、边带对称性、轴承档隔离（两档同查不串参数）与 HTTP 端到端测试；峰值归属能力另有与暴力枚举对拍、反贪心、打滑反演、顺序/尺度不变性与 500 峰规模压测，详见上节。
