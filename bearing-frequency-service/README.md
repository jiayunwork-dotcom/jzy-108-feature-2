# bearing-frequency-service

滚动轴承特征频率核算服务。给定轴的转频（或转速）与轴承几何参数，按纯滚动、无打滑、外圈固定的运动学假设，计算四个特征频率，供对照振动频谱判断故障部位。仅提供 HTTP 接口，无页面。

除正算外，还提供**峰值归属 + 打滑估计**：把实测谱峰一对一归给「转频与四个轴承部位的各阶谐波」，并在归属的同时反推统一打滑系数。

- 运行时：JDK 17 · Spring Boot 3 · Maven 构建
- 主输出：**BPFO / BPFI / FTF / BSF** 四个特征频率（Hz）
- 可选附加：围绕转频的调制边带位置
- 反向诊断：谱峰归属（全局最优一对一配对）+ 打滑系数迭代估计

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
├── attribution/   峰值归属 + 打滑估计（见下节）
├── validation/    参数合法性检查（BearingInputValidator，计算前拦截）
├── service/       正算编排：校验 → 计算 → 边带
└── api/           接口层：仅请求解析与结果拼装
```

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

## 峰值归属 + 打滑估计

`POST /api/v1/peak-attributions`（内联几何或引用轴承档，二选一）与
`POST /api/v1/catalog/{name}/peak-attributions`（几何取自该档）。

给定实测谱峰（频率 + 幅值）、转频/转速（与正算同口径）和几何，服务把每根峰
归到候选目标「转频 1..N 阶、BPFO/BPFI/FTF/BSF 各 1..N 阶」中的**至多一个**，
每个目标也**只认一根峰**；落不进窗口的峰标为未归属。同时反推四个轴承部位
统一承受的打滑系数。

### 候选目标、窗口与默认口径

- 候选目标：`shaft`（=k·fr）、`bpfo`、`bpfi`、`ftf`、`bsf`，各族 1..`maxOrder` 阶。
  第 1 阶基频复用 `BearingKinematics`，不另抄公式。
- 匹配窗口（单侧半宽，Hz）：`relativeTolerance × 修正后目标频率 + 0.5 × frequencyResolutionHz`。
  相对容差覆盖系统性/小幅偏差，半个频谱 bin 覆盖 FFT 栅格化误差。
- 默认值与允许范围：

  | 参数 | 缺省 | 允许范围 |
  |---|---|---|
  | `maxOrder` 谐波阶数上限 | 10 | 1 ~ 10 |
  | `relativeTolerance` 相对容差 | 0.02（2%） | (0, 0.10] |
  | `frequencyResolutionHz` 频谱分辨率 | 0（未知，窗口只取相对部分） | ≥ 0 |
  | `slipMin` / `slipMax` 打滑允许范围 | 0.0 / 0.10 | 0 ≤ min ≤ max ≤ 0.50 |
  | `lockedSlip` 锁死打滑 | 不提供（自由估计） | 必须落在 [slipMin, slipMax] |
  | `peaks` 峰数 | 必填、非空 | 1 ~ 500 根；频率为正有限值，幅值为非负有限值 |

### 偏差度量与优化口径（设计定案）

- 带符号偏差：`deviationHz = 峰频率 − 修正后目标频率`（正＝峰偏高）。
- 相对偏差：`relativeDeviation = deviationHz / 修正后目标频率`。
- 一条合法边的代价：`relativeDeviation²`。**不按幅值加权**——幅值只用于现场
  判读，不参与归属，避免一根强峰把本该属于弱峰的目标抢走。
- 优化目标按字典序：**① 配对数最大（先保证配上的尽量多，绝不靠少配换小偏差）
  → ② 总代价最小（Σ相对偏差²）→ ③ 规范序平局裁决**。
  求解用矩形匈牙利（Kuhn–Munkres）最小权分配，是严格全局最优，不是逐个就近认领。
- 平局裁决（保证同输入永远同结果）：代价仍相等时，优先把规范序靠前的目标
  （族顺序 shaft→bpfo→bpfi→ftf→bsf，再按阶次）配给规范序靠前的峰
  （峰按频率、幅值、原始下标排序）。因此**打乱峰的提交顺序不改变任何归属结论**：
  打滑、配对数、总偏差以及「哪根频率的峰归到哪一族第几阶、修正目标与偏差」
  逐字段相同；`submittedIndex`/`peaks` 视图按定义回显提交位置，仅随提交顺序变化。

### 打滑模型、迭代与精度

- 打滑只压低四个轴承部位：`修正目标 = 理论目标 × (1 − slip)`；
  转频及其整数倍不修正。
- 估计（最小二乘，只用归属到四个轴承族的配对）：
  `ŝ = 1 − Σ(峰频率·理论目标) / Σ(理论目标²)`，
  等价于把各配对观测比例按理论目标²加权平均后取补，再夹到 [slipMin, slipMax]。
- 初值：自由估计时先在打滑允许范围做 21 点等距网格预扫，各点各做一次全局归属，
  按「轴承配对数多 → 总代价小 → 打滑小」取起点，避免真实打滑较大时开局一根
  轴承峰都认不到。网格预扫不计入迭代轮数。
- 迭代：`修正目标 → 全局归属 → 重估打滑`，直到收敛或终止。收敛阈值
  |Δslip| ≤ **1e-3**：归属是离散方案，配对固定后打滑在 1e-4 量级噪声平台上
  抖动属正常定点；同一组配对重复出现且打滑差在该带内也算收敛。
- 归属在两个方案间来回翻转且打滑差超出该带：停止并取历次访问方案中
  「配对数最多 → 总代价最小 → 打滑最小」的一轮，`converged=false`、
  `terminationReason=OSCILLATING_ASSIGNMENT`。精修上限 **30 轮**，超限返回
  最后一轮并标 `MAX_ITERATIONS_REACHED`；网格后没有任何轴承配对则标
  `NO_BEARING_MATCHES`。不收敛时**绝不**静默给看似正常的答案——最后一轮的
  归属、打滑与原因都如实返回。
- 锁死：提供 `lockedSlip` 时只做一次归属，`iterations=1`、
  `terminationReason=LOCKED_SLIP`，不初扫、不迭代。
- 网格预扫后完全没有轴承部位配对时 `iterations=0`、
  `terminationReason=NO_BEARING_MATCHES`。
- 声明精度：用已知打滑（3%）合成、每根峰加 ±2e-4 确定扰动时，反推打滑误差
  **≤ 6e-4**（自动化测试钉住）；纯理论合成峰反推打滑为 0（1e-8 以内吸附为零）。

### 请求/响应示例

```bash
curl -s localhost:8080/api/v1/peak-attributions -H 'Content-Type: application/json' -d '{
  "rotationFrequencyHz": 25.0,
  "maxOrder": 10,
  "relativeTolerance": 0.02,
  "frequencyResolutionHz": 0.0,
  "peaks": [
    {"frequencyHz": 25.0, "amplitude": 1.0},
    {"frequencyHz": 86.93, "amplitude": 2.4},
    {"frequencyHz": 999.0, "amplitude": 0.2}
  ],
  "bearingName": "6205"
}'
```

```json
{
  "bearingName": "6205",
  "rotationFrequencyHz": 25.0,
  "maxOrder": 10,
  "relativeTolerance": 0.02,
  "frequencyResolutionHz": 0.0,
  "slipMin": 0.0,
  "slipMax": 0.10,
  "slipCoefficient": 0.03,
  "iterations": 2,
  "converged": true,
  "terminationReason": "CONVERGED",
  "terminationMessage": "…",
  "initialSlipCoefficient": 0.03,
  "matchedPeakCount": 2,
  "unmatchedPeakCount": 1,
  "totalSquaredRelativeDeviation": 0.00000022,
  "totalAbsDeviationHz": 0.0013,
  "assignments": [
    {"submittedIndex": 0, "peakFrequencyHz": 25.0, "peakAmplitude": 1.0,
     "family": "shaft", "order": 1,
     "theoreticalTargetHz": 25.0, "adjustedTargetHz": 25.0,
     "deviationHz": 0.0, "relativeDeviation": 0.0},
    {"submittedIndex": 1, "peakFrequencyHz": 86.93, "peakAmplitude": 2.4,
     "family": "bpfo", "order": 1,
     "theoreticalTargetHz": 89.6196, "adjustedTargetHz": 86.9310,
     "deviationHz": -0.001, "relativeDeviation": -0.000012}
  ],
  "peaks": [
    {"submittedIndex": 0, "frequencyHz": 25.0, "amplitude": 1.0, "assigned": true,
     "family": "shaft", "order": 1, "adjustedTargetHz": 25.0,
     "deviationHz": 0.0, "relativeDeviation": 0.0}
  ],
  "unmatchedPeaks": [
    {"submittedIndex": 2, "frequencyHz": 999.0, "amplitude": 0.2}
  ]
}
```

- `assignments`：已配上的归属，按族/阶次规范序；含族、阶次、**理论目标频率、
  打滑修正后目标频率、带符号偏差（Hz 与相对）**。
- `peaks`：**与提交顺序逐根对齐**的视图（未归属者 `assigned=false`）。
- `unmatchedPeaks`：未归属峰，按规范序（频率、幅值、原始下标）。
- `lockedSlip` 仅在锁死时出现；`bearingName` 仅在引用轴承档时出现。

归属请求额外拦截（400，均在计算前）：峰列表为空或超过 500 根；某根峰频率
非正/非有限或幅值为负（指出第几根，1 基）；容差不在 (0, 0.10]；分辨率为负；
阶数不在 1~10；打滑范围越界或下限大于上限；锁死值不在允许范围内；
`geometry` 与 `bearingName` 同时给或都不给。几何合法性沿用正算拦截；
引用不存在的档返回 404。

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

另有校验拦截、边带对称性、轴承档隔离（两档同查不串参数）与 HTTP 端到端测试。

### 峰值归属测试钉住的事实

`PeakAttributionServiceTest` / `BipartiteMatcherTest` / `HungarianAlgorithmTest`
/ `PeakAttributionApiTest`：

1. 纯理论频率及谐波：打滑为零（吸附阈值 1e-8），归属与锁死打滑为零逐对一致；
2. 已知 3% 打滑统一压低四族谐波（夹 1×/4× 转频倍频与三根干扰峰，每根加
   ±2e-4 确定性扰动）：反推误差 ≤ 6e-4，干扰峰全部未归属，转频倍频修正目标
   等于理论值（不乘打滑）；
3. 全局最优严格反例（t=100/105、峰 96/102，容差 9%）：逐个就近认领总代价
   7.75e-3，全局交换两对全成、总代价 2.42e-3；
4. 峰列表轮转+反转打乱后提交、同请求重复提交：逐字段相同；
5. 分辨率为 0 时转频与所有峰同乘 7.3：归属、相对偏差（1e-12 内）、打滑
   （1e-12 内）不变，绝对频率与目标同比放大；
6. 内联几何与同参数轴承档结果一致；A/B 两档各算各的、互不串参数；
7. 500 峰 × 10 阶：自由估计与锁死各一次，均在 2 秒内返回（实测约百毫秒级）；
8. 全部非法输入（空/超限峰列表、指出第几根的坏峰、容差/分辨率/阶次/打滑
   范围/锁死值越界、几何与档名同给或都不给）在计算前 400 拦截并讲明原因；
   未知档 404。
