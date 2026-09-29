# bearing-frequency-service

滚动轴承特征频率核算服务。给定轴的转频（或转速）与轴承几何参数，按纯滚动、无打滑、外圈固定的运动学假设，计算四个特征频率，供对照振动频谱判断故障部位。仅提供 HTTP 接口，无页面。

- 运行时：JDK 17 · Spring Boot 3 · Maven 构建
- 主输出：**BPFO / BPFI / FTF / BSF** 四个特征频率（Hz）
- 可选附加：围绕转频的调制边带位置

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
├── validation/    参数合法性检查（BearingInputValidator，计算前拦截）
├── service/       编排：校验 → 计算 → 边带
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
