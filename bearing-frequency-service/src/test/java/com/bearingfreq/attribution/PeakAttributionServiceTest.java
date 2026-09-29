package com.bearingfreq.attribution;

import com.bearingfreq.catalog.BearingCatalog;
import com.bearingfreq.kinematics.BearingKinematics;
import com.bearingfreq.kinematics.CharacteristicFrequencies;
import com.bearingfreq.model.BearingGeometry;
import com.bearingfreq.validation.InvalidBearingInputException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * 「峰值归属 + 打滑估计」验收测试，逐点对应需求：
 * 纯理论零打滑、已知打滑反推（含转频倍频与干扰峰）、全局最优严格优于逐个贪心、
 * 顺序无关与可重复、分辨率为零时的尺度不变性、内联几何与轴承档等价及档间隔离、
 * 500 峰 × 10 阶 2 秒性能，以及全部输入校验拦截。
 */
class PeakAttributionServiceTest {

    /** 6205 几何：BPFO=89.6196、BPFI=135.3804、FTF=9.9577、BSF=58.9188（fr=25）。 */
    private static final BearingGeometry G6205 =
            new BearingGeometry(9, 39.04, 7.94, 0.0);

    /** 声明的打滑反推精度（详见已知打滑用例）。 */
    private static final double DECLARED_SLIP_ACCURACY = 6.0e-4;

    private final PeakAttributionService service =
            new PeakAttributionService(new BearingCatalog());

    // ---------------------------------------------------------------- helpers

    private static AttributionInputValidator.RawPeak peak(double frequencyHz) {
        return new AttributionInputValidator.RawPeak(frequencyHz, 1.0);
    }

    private static AttributionInputValidator.RawPeak peak(double frequencyHz, double amplitude) {
        return new AttributionInputValidator.RawPeak(frequencyHz, amplitude);
    }

    private static double baseHz(BearingFamily family, CharacteristicFrequencies f, double fr) {
        return switch (family) {
            case SHAFT -> fr;
            case BPFO -> f.bpfoHz();
            case BPFI -> f.bpfiHz();
            case FTF -> f.ftfHz();
            case BSF -> f.bsfHz();
        };
    }

    private static boolean isAssigned(AttributionResult result, int submittedIndex) {
        return result.matching().assignments().stream()
                .anyMatch(a -> a.peak().index() == submittedIndex);
    }

    private static Assignment assignmentOf(AttributionResult result,
                                           BearingFamily family, int order) {
        return result.matching().assignments().stream()
                .filter(a -> a.target().family() == family && a.target().order() == order)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "缺少归属：" + family + " 第 " + order + " 阶"));
    }

    /** 生成某族 1..orders 阶理论峰（无扰动）。 */
    private static List<Double> theoreticalHarmonics(BearingFamily family,
                                                     CharacteristicFrequencies f,
                                                     double fr, int orders) {
        double base = baseHz(family, f, fr);
        List<Double> values = new ArrayList<>();
        for (int k = 1; k <= orders; k++) {
            values.add(base * k);
        }
        return values;
    }

    // ------------------------------------------------ 1. 纯理论 → 零打滑

    @Test
    @DisplayName("纯理论频率及其谐波：估出打滑为零（浮点误差内），"
            + "且与锁死打滑为零的归属逐字段一致")
    void pureTheoreticalPeaksGiveZeroSlipAndMatchLockedZero() {
        double fr = 25.0;
        CharacteristicFrequencies f = BearingKinematics.compute(fr, G6205);
        List<AttributionInputValidator.RawPeak> peaks = new ArrayList<>();
        for (BearingFamily family : BearingFamily.values()) {
            for (double hz : theoreticalHarmonics(family, f, fr, 10)) {
                peaks.add(peak(hz));
            }
        }

        AttributionResult estimated = service.attribute(
                fr, null, G6205, null, peaks, 10, null, null, null, null, null);
        AttributionResult lockedZero = service.attribute(
                fr, null, G6205, null, peaks, 10, null, null, null, null, 0.0);

        // 打滑：估计为零、收敛。
        // 说明：BPFO1 与 FTF9 理论频率完全相同（BPFO 基频恒为 FTF 的 n 倍），
        // 合成时它们是两根独立提交的谱峰记录（仅频率相同），一对一针对「峰记录」
        // 而非频率去重，故 50 根峰 × 50 个目标恰好 50 对全部配上。
        assertThat(estimated.converged()).isTrue();
        assertThat(estimated.slip()).isCloseTo(0.0, within(1e-12));
        assertThat(estimated.terminationReason()).isEqualTo(AttributionResult.REASON_CONVERGED);
        assertThat(estimated.matching().matchCount()).isEqualTo(50);
        assertThat(estimated.matching().unmatchedPeakIndices()).isEmpty();

        // 与锁死零打滑逐字段一致：逐对（族，阶）比较峰、目标、偏差
        assertThat(lockedZero.matching().matchCount()).isEqualTo(50);
        assertThat(estimated.matching().assignments())
                .containsExactlyElementsOf(lockedZero.matching().assignments());
        assertThat(estimated.matching().totalSquaredRelativeDeviation())
                .isCloseTo(lockedZero.matching().totalSquaredRelativeDeviation(), within(1e-24));
        assertThat(estimated.matching().totalAbsDeviationHz())
                .isCloseTo(lockedZero.matching().totalAbsDeviationHz(), within(1e-9));
        for (Assignment a : estimated.matching().assignments()) {
            assertThat(a.deviationHz()).isCloseTo(0.0, within(1e-9));
        }
    }

    // ------------------------------------------------ 2. 已知打滑反推

    @Test
    @DisplayName("已知打滑统一压低轴承谐波（夹转频倍频、干扰峰、确定性小扰动）："
            + "打滑反推达标，干扰峰未归属，转频倍频不走打滑修正")
    void recoverKnownSlipWithShaftHarmonicsAndInterference() {
        double fr = 25.0;
        double trueSlip = 0.03;
        CharacteristicFrequencies f = BearingKinematics.compute(fr, G6205);

        // 零均值、确定的小扰动系数（±2e-4 内），按提交序号循环取用
        double[] perturb = {1.5e-4, -1.5e-4, 0.8e-4, -0.8e-4, 0.0};

        List<AttributionInputValidator.RawPeak> peaks = new ArrayList<>();
        record Spec(double hz, BearingFamily family, int order, boolean bearing) {
        }
        List<Spec> specs = new ArrayList<>();

        // 四个轴承族各 1..5 阶，统一乘 (1 − 打滑) 再加确定扰动
        for (BearingFamily family : List.of(BearingFamily.BPFO, BearingFamily.BPFI,
                BearingFamily.FTF, BearingFamily.BSF)) {
            double base = baseHz(family, f, fr);
            for (int k = 1; k <= 5; k++) {
                double hz = base * k * (1.0 - trueSlip);
                specs.add(new Spec(hz, family, k, true));
            }
        }
        // 两根转频倍频：1× 与 4×，不受打滑、不加扰动，放在最后以免影响扰动序号含义
        specs.add(new Spec(fr, BearingFamily.SHAFT, 1, false));
        specs.add(new Spec(fr * 4, BearingFamily.SHAFT, 4, false));

        for (int i = 0; i < specs.size(); i++) {
            Spec spec = specs.get(i);
            double factor = 1.0 + (spec.bearing() ? perturb[i % perturb.length] : 0.0);
            peaks.add(peak(spec.hz() * factor, 2.5));
        }
        int bearingCount = 20;

        // 三根无关干扰峰：高于全部候选目标（最高 BPFI×10≈1354、打滑修正后更低），
        // 幅值故意更大，验证不按幅值抢占
        List<Double> interference = List.of(2000.0, 2500.0, 3000.0);
        for (double hz : interference) {
            peaks.add(peak(hz, 50.0));
        }

        AttributionResult result = service.attribute(
                fr, null, G6205, null, peaks, 10, 0.02, 0.0, null, null, null);

        assertThat(result.converged())
                .as(result.terminationReason() + " " + result.terminationMessage())
                .isTrue();
        assertThat(Math.abs(result.slip() - trueSlip))
                .as("反推打滑 %.6f vs 真值 %.6f".formatted(result.slip(), trueSlip))
                .isLessThan(DECLARED_SLIP_ACCURACY);

        // 22 根候选峰全部正确归属（20 轴承 + 2 转频）；3 根干扰峰未归属
        assertThat(result.matching().matchCount()).isEqualTo(22);
        for (int i = 0; i < bearingCount + 2; i++) {
            assertThat(isAssigned(result, i)).as("第 %d 根候选峰应被归属".formatted(i)).isTrue();
        }
        for (int i = bearingCount + 2; i < peaks.size(); i++) {
            assertThat(isAssigned(result, i)).as("第 %d 根干扰峰应未归属".formatted(i)).isFalse();
        }

        // 转频倍频：修正后目标 = 理论值（不乘打滑）
        for (int order : new int[]{1, 4}) {
            Assignment shaft = assignmentOf(result, BearingFamily.SHAFT, order);
            assertThat(shaft.adjustedTargetHz())
                    .isCloseTo(shaft.target().theoreticalHz(), within(1e-9));
            assertThat(shaft.adjustedTargetHz()).isCloseTo(fr * order, within(1e-9));
        }
        // 轴承族：修正目标 = 理论 × (1 − ŝ)，偏差即扰动量级
        for (BearingFamily family : List.of(BearingFamily.BPFO, BearingFamily.BPFI,
                BearingFamily.FTF, BearingFamily.BSF)) {
            double base = baseHz(family, f, fr);
            for (int k = 1; k <= 5; k++) {
                Assignment a = assignmentOf(result, family, k);
                assertThat(a.adjustedTargetHz())
                        .isCloseTo(base * k * (1.0 - result.slip()), within(1e-6));
                assertThat(Math.abs(a.relativeDeviation())).isLessThan(3.0e-4);
            }
        }
    }

    // ------------------------------------------------ 3. 全局最优严格优于贪心

    @Test
    @DisplayName("逐个就近认领总偏差更大的现场式构造：服务给出总偏差更小的配对")
    void serviceBeatsGreedyNearestFirst() {
        // 取几何使 BPFO 基频恰为 1.05·fr：
        // n=3、d/D=0.3、α=0 → BPFO = 3/2·(1−0.3)·fr = 1.05·fr
        // fr=100 → shaft 1 阶 100，BPFO 1 阶 105；maxOrder=1 避免其他候选干扰。
        BearingGeometry geometry = new BearingGeometry(3, 40.0, 12.0, 0.0);
        double fr = 100.0;
        var peaks = List.of(peak(96.0), peak(102.0));

        // 锁死零打滑，隔离匹配器行为；容差 9% 让四根边全合法
        AttributionResult result = service.attribute(
                fr, null, geometry, null, peaks, 1, 0.09, 0.0, null, null, 0.0);

        // 先核对其余三个族（BPFI=195、FTF=35、BSF=151.67）在 9% 窗内都不认这两根峰
        assertThat(result.matching().matchCount()).isEqualTo(2);
        Assignment shaft = assignmentOf(result, BearingFamily.SHAFT, 1);
        Assignment bpfo = assignmentOf(result, BearingFamily.BPFO, 1);
        // 全局（非交叉）：shaft→96（−4%）、bpfo→102（−2.86%）
        assertThat(shaft.peak().frequencyHz()).isEqualTo(96.0);
        assertThat(bpfo.peak().frequencyHz()).isEqualTo(102.0);

        double globalCost = result.matching().totalSquaredRelativeDeviation();
        // 逐个贪心（shaft 先拿最近的 102，BPFO 被迫拿 96）的总代价：
        // (2/100)² + (9/105)²
        double greedyCost = (2.0 / 100.0) * (2.0 / 100.0)
                + (9.0 / 105.0) * (9.0 / 105.0);
        assertThat(globalCost).isCloseTo(
                (4.0 / 100.0) * (4.0 / 100.0) + (3.0 / 105.0) * (3.0 / 105.0),
                within(1e-12));
        // 两对全成——全局不是靠少配一对换来更小的总代价
        assertThat(result.matching().matchCount()).isEqualTo(2);
        assertThat(globalCost).isLessThan(greedyCost);
    }

    // ------------------------------------------------ 4. 顺序无关 + 可重复

    @Test
    @DisplayName("峰列表打乱提交：逐字段相同；同一请求重复提交结果相同")
    void shuffleInvariantAndRepeatable() {
        double fr = 25.0;
        CharacteristicFrequencies f = BearingKinematics.compute(fr, G6205);
        List<AttributionInputValidator.RawPeak> ordered = new ArrayList<>();
        double[] jitter = {3.0e-4, -2.0e-4, 1.1e-4};
        int idx = 0;
        for (BearingFamily family : List.of(BearingFamily.SHAFT, BearingFamily.BPFO,
                BearingFamily.FTF, BearingFamily.BPFI, BearingFamily.BSF)) {
            for (int k = 1; k <= 4; k++) {
                double hz = baseHz(family, f, fr) * k * (1.0 + jitter[idx % jitter.length]);
                ordered.add(peak(hz, 1.0 + 0.01 * idx));
                idx++;
            }
        }
        ordered.add(peak(1410.0, 9.0)); // 干扰峰（高于最高 10 阶目标 BPFI×8≈1083）
        ordered.add(peak(1550.0, 0.1));

        List<AttributionInputValidator.RawPeak> shuffled = new ArrayList<>(ordered);
        // 确定性地「洗」成与原序不同的排列（轮转 + 反转），保持多重集不变
        java.util.Collections.rotate(shuffled, 7);
        java.util.Collections.reverse(shuffled);
        assertThat(shuffled).isNotEqualTo(ordered);

        java.util.function.Function<List<AttributionInputValidator.RawPeak>, AttributionResult> run =
                p -> service.attribute(
                        fr, null, G6205, null, p, 8, 0.02, 0.5, null, null, null);

        AttributionResult a = run.apply(ordered);
        AttributionResult b = run.apply(shuffled);
        AttributionResult c = run.apply(ordered);

        for (AttributionResult r : List.of(b, c)) {
            assertThat(r.slip()).isCloseTo(a.slip(), within(0.0));
            assertThat(r.iterations()).isEqualTo(a.iterations());
            assertThat(r.converged()).isEqualTo(a.converged());
            assertThat(r.terminationReason()).isEqualTo(a.terminationReason());
            assertThat(r.matching().totalSquaredRelativeDeviation())
                    .isCloseTo(a.matching().totalSquaredRelativeDeviation(), within(0.0));
            assertThat(r.matching().totalAbsDeviationHz())
                    .isCloseTo(a.matching().totalAbsDeviationHz(), within(0.0));
            assertThat(r.matching().matchCount()).isEqualTo(a.matching().matchCount());
            // 逐对归属：（族，阶）→（被配峰的频率与幅值、修正目标、带符号偏差）
            assertThat(describe(r)).isEqualTo(describe(a));
            // 未归属峰的频率多重集一致
            assertThat(r.matching().unmatchedPeakIndices()).hasSameSizeAs(
                    a.matching().unmatchedPeakIndices());
        }
    }

    /** 把归属降维成与提交顺序无关、可逐字段比较的描述。 */
    private static List<List<Number>> describe(AttributionResult r) {
        List<List<Number>> rows = new ArrayList<>();
        for (Assignment a : r.matching().assignments()) {
            rows.add(List.of(
                    a.target().family().ordinal(), a.target().order(),
                    a.peak().frequencyHz(), a.peak().amplitude(),
                        a.adjustedTargetHz(), a.deviationHz(), a.relativeDeviation()));
        }
        rows.sort((x, y) -> {
            int c = Integer.compare(x.get(0).intValue(), y.get(0).intValue());
            return c != 0 ? c : Integer.compare(x.get(1).intValue(), y.get(1).intValue());
        });
        return rows;
    }

    // ------------------------------------------------ 5. 尺度不变性

    @Test
    @DisplayName("分辨率为零时整体同乘正数：归属、相对偏差、打滑系数不变")
    void uniformScaleInvariantWhenResolutionZero() {
        double fr = 25.0;
        double trueSlip = 0.04;
        CharacteristicFrequencies f = BearingKinematics.compute(fr, G6205);
        // maxOrder=3：15 个候选（5 族 × 3 阶，最高 BPFI3≈406），
        // 每根候选峰唯一对应一个目标，干扰峰 501 高于全部候选，不会有意外边。
        List<AttributionInputValidator.RawPeak> peaks = new ArrayList<>();
        for (BearingFamily family : List.of(BearingFamily.SHAFT, BearingFamily.BPFO,
                BearingFamily.FTF, BearingFamily.BSF, BearingFamily.BPFI)) {
            for (int k = 1; k <= 3; k++) {
                double hz = baseHz(family, f, fr) * k;
                if (family.affectedBySlip()) {
                    hz *= 1.0 - trueSlip;
                }
                peaks.add(peak(hz * (1.0 + (k - 2) * 1.0e-4)));
            }
        }
        peaks.add(peak(501.0));

        double scale = 7.3;
        List<AttributionInputValidator.RawPeak> scaled = peaks.stream()
                .map(p -> peak(p.frequencyHz() * scale, p.amplitude()))
                .toList();

        AttributionResult base = service.attribute(
                fr, null, G6205, null, peaks, 3, 0.02, 0.0, null, null, null);
        AttributionResult scaledResult = service.attribute(
                fr * scale, null, G6205, null, scaled, 3, 0.02, 0.0, null, null, null);

        assertThat(base.matching().matchCount()).isEqualTo(15);
        // 尺度无关：打滑系数（1e-12）与逐对相对偏差（1e-12）一致；
        // 绝对频率与目标频率随尺度同比放大（偏差 Hz 也同比），逐对归属相同。
        assertThat(scaledResult.slip()).isCloseTo(base.slip(), within(1e-12));
        assertThat(scaledResult.converged()).isEqualTo(base.converged());
        assertThat(scaledResult.matching().matchCount()).isEqualTo(base.matching().matchCount());

        java.util.Map<List<Object>, Assignment> baseByKey = new java.util.HashMap<>();
        for (Assignment a : base.matching().assignments()) {
            baseByKey.put(List.of(a.target().family(), a.target().order(), a.peak().index()), a);
        }
        for (Assignment a : scaledResult.matching().assignments()) {
            Assignment b = baseByKey.get(List.of(a.target().family(), a.target().order(),
                    a.peak().index()));
            assertThat(b).as("缩放后归属发生变化：%s%d→第%d根".formatted(
                    a.target().family(), a.target().order(), a.peak().index())).isNotNull();
            assertThat(a.relativeDeviation())
                    .isCloseTo(b.relativeDeviation(), within(1e-12));
            assertThat(a.deviationHz() / a.adjustedTargetHz())
                    .isCloseTo(b.deviationHz() / b.adjustedTargetHz(), within(1e-12));
            assertThat(a.adjustedTargetHz())
                    .isCloseTo(scale * b.adjustedTargetHz(), within(1e-6 * scale));
        }
    }

    // ------------------------------------------------ 6. 内联几何 ↔ 轴承档等价 & 档间隔离

    @Test
    @DisplayName("内联几何与同参数轴承档结果一致；两个轴承档各算各的，互不串参数")
    void inlineGeometryEqualsCatalogAndEntriesStayIsolated() {
        var catalog = new BearingCatalog();
        var serviceWithCatalog = new PeakAttributionService(catalog);

        BearingGeometry gA = new BearingGeometry(8, 40.0, 6.0, 0.0);
        BearingGeometry gB = new BearingGeometry(16, 40.0, 6.0, 0.0);
        catalog.register("A", gA);
        catalog.register("B", gB);

        double fr = 30.0;
        var fA = BearingKinematics.compute(fr, gA);
        var fB = BearingKinematics.compute(fr, gB);
        // A 的峰：shaft1 与 BPFO1..2（A 的 BPFO=102）
        var peaksA = List.of(peak(fr), peak(fA.bpfoHz()), peak(2 * fA.bpfoHz()), peak(610.0));
        // B 的峰：shaft1 与 BPFO1（B 的 BPFO=204）
        var peaksB = List.of(peak(fr), peak(fB.bpfoHz()), peak(610.0));

        AttributionResult inlineA = serviceWithCatalog.attribute(
                fr, null, gA, null, peaksA, 2, 0.02, 0.0, null, null, null);
        AttributionResult entryA1 = serviceWithCatalog.attribute(
                fr, null, null, "A", peaksA, 2, 0.02, 0.0, null, null, null);
        AttributionResult entryB = serviceWithCatalog.attribute(
                fr, null, null, "B", peaksB, 2, 0.02, 0.0, null, null, null);
        AttributionResult entryA2 = serviceWithCatalog.attribute(
                fr, null, null, "A", peaksA, 2, 0.02, 0.0, null, null, null);

        // 内联与档结果逐字段一致
        assertThat(describe(entryA1)).isEqualTo(describe(inlineA));
        assertThat(entryA1.slip()).isCloseTo(inlineA.slip(), within(0.0));
        assertThat(entryA1.matching().totalSquaredRelativeDeviation())
                .isCloseTo(inlineA.matching().totalSquaredRelativeDeviation(), within(0.0));

        // B 档 BPFO=204，恰为 A 档 102 的两倍：B 的 BPFO1 与 A 的 BPFO2 频率不同
        Assignment bBpfo = assignmentOf(entryB, BearingFamily.BPFO, 1);
        assertThat(bBpfo.adjustedTargetHz()).isCloseTo(204.0, within(1e-9));
        assertThat(bBpfo.peak().frequencyHz()).isCloseTo(204.0, within(1e-9));
        Assignment aBpfo2 = assignmentOf(entryA2, BearingFamily.BPFO, 2);
        assertThat(aBpfo2.adjustedTargetHz()).isCloseTo(204.0, within(1e-9));
        // A 的 BPFO1=102 在 B 档候选（shaft=30、bpfo=204…）窗内配不到 → B 的这根 102
        // 并不存在；反向验证：拿 A 的峰列表给 B 档算，102 的 BPFO 峰必须未归属
        AttributionResult bWithAPeaks = serviceWithCatalog.attribute(
                fr, null, null, "B", peaksA, 2, 0.02, 0.0, null, null, null);
        assertThat(bWithAPeaks.matching().assignments().stream()
                .noneMatch(a -> a.target().family() == BearingFamily.BPFO
                        && a.peak().frequencyHz() == fA.bpfoHz())).isTrue();

        // 查过 B 之后再查 A：结果不变
        assertThat(describe(entryA2)).isEqualTo(describe(entryA1));
        assertThat(entryA2.parameters().bearingName()).isEqualTo("A");
        assertThat(entryB.parameters().bearingName()).isEqualTo("B");
        assertThat(inlineA.parameters().bearingName()).isNull();
    }

    // ------------------------------------------------ 7. 性能

    @Test
    @DisplayName("规模：500 峰 × 10 阶，单次请求 2 秒内返回（自由估计与锁死各一）")
    void performanceUnderFiveHundredPeaks() {
        double fr = 25.0;
        double slip = 0.025;
        CharacteristicFrequencies f = BearingKinematics.compute(fr, G6205);
        List<AttributionInputValidator.RawPeak> peaks = new ArrayList<>();
        // 468 根确定性干扰峰全部铺在 1600 Hz 以上——全部候选目标（≤ 10 阶）
        // 最高为 BPFI×10 ≈ 1354 Hz，干扰峰不可能落进任何匹配窗口
        for (int i = 0; i < 468; i++) {
            double hz = 1600.0 + i * 3.1;
            peaks.add(peak(hz, 0.3));
        }
        // 32 根轴承谐波（4 族 × 1..8 阶，带 2.5% 打滑与小扰动）
        for (BearingFamily family : List.of(BearingFamily.BPFO, BearingFamily.BPFI,
                BearingFamily.FTF, BearingFamily.BSF)) {
            double base = baseHz(family, f, fr);
            for (int k = 1; k <= 8; k++) {
                double hz = base * k * (1.0 - slip) * (1.0 + (((k * 37) % 5) - 2) * 1e-4);
                peaks.add(peak(hz, 4.0));
            }
        }
        assertThat(peaks).hasSize(500);

        long t0 = System.nanoTime();
        AttributionResult free = service.attribute(
                fr, null, G6205, null, peaks, 10, 0.02, 0.0, null, null, null);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        assertThat(elapsedMs).isLessThan(2000L);
        assertThat(free.slip()).isCloseTo(slip, within(3.0e-3));

        long t1 = System.nanoTime();
        AttributionResult locked = service.attribute(
                fr, null, G6205, null, peaks, 10, 0.02, 0.0, null, null, slip);
        long lockedMs = (System.nanoTime() - t1) / 1_000_000;
        assertThat(lockedMs).isLessThan(2000L);
        assertThat(locked.iterations()).isOne();
    }

    private static int i2(int k) {
        return k;
    }

    // ------------------------------------------------ 8. 输入校验

    @Test
    @DisplayName("空峰列表：计算前拦截")
    void emptyPeaksRejected() {
        assertThatThrownBy(() -> service.attribute(
                25.0, null, G6205, null, List.of(), 10, null, null, null, null, null))
                .isInstanceOf(InvalidBearingInputException.class)
                .hasMessageContaining("peaks 不能为空");
    }

    @Test
    @DisplayName("峰数超 500：拦截并指出数量")
    void tooManyPeaksRejected() {
        List<AttributionInputValidator.RawPeak> peaks = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            peaks.add(peak(10.0 + i));
        }
        assertThatThrownBy(() -> service.attribute(
                25.0, null, G6205, null, peaks, null, null, null, null, null, null))
                .isInstanceOf(InvalidBearingInputException.class)
                .hasMessageContaining("500");
    }

    @Test
    @DisplayName("非正峰频率或负幅值：指出是第几根（1 基）")
    void invalidPeakValuesReportPosition() {
        var badFreq = List.of(peak(100.0), peak(0.0));
        assertThatThrownBy(() -> service.attribute(
                25.0, null, G6205, null, badFreq, null, null, null, null, null, null))
                .isInstanceOf(InvalidBearingInputException.class)
                .hasMessageContaining("第 2 根峰频率");

        var negativeAmp = List.of(peak(100.0), peak(200.0), peak(300.0, -0.001));
        assertThatThrownBy(() -> service.attribute(
                25.0, null, G6205, null, negativeAmp, null, null, null, null, null, null))
                .isInstanceOf(InvalidBearingInputException.class)
                .hasMessageContaining("第 3 根峰幅值");

        var nanFreq = List.of(peak(Double.NaN));
        assertThatThrownBy(() -> service.attribute(
                25.0, null, G6205, null, nanFreq, null, null, null, null, null, null))
                .isInstanceOf(InvalidBearingInputException.class)
                .hasMessageContaining("第 1 根峰频率");
    }

    @Test
    @DisplayName("容差/分辨率/阶次越界：拦截并说明允许范围")
    void outOfRangeControlsRejected() {
        var peaks = List.of(peak(100.0));
        assertThatThrownBy(() -> service.attribute(
                25.0, null, G6205, null, peaks, 0, null, null, null, null, null))
                .isInstanceOf(InvalidBearingInputException.class)
                .hasMessageContaining("maxOrder");
        assertThatThrownBy(() -> service.attribute(
                25.0, null, G6205, null, peaks, 11, null, null, null, null, null))
                .isInstanceOf(InvalidBearingInputException.class)
                .hasMessageContaining("maxOrder");
        assertThatThrownBy(() -> service.attribute(
                25.0, null, G6205, null, peaks, null, 0.0, null, null, null, null))
                .isInstanceOf(InvalidBearingInputException.class)
                .hasMessageContaining("relativeTolerance");
        assertThatThrownBy(() -> service.attribute(
                25.0, null, G6205, null, peaks, null, 0.11, null, null, null, null))
                .isInstanceOf(InvalidBearingInputException.class)
                .hasMessageContaining("relativeTolerance");
        assertThatThrownBy(() -> service.attribute(
                25.0, null, G6205, null, peaks, null, null, -1.0, null, null, null))
                .isInstanceOf(InvalidBearingInputException.class)
                .hasMessageContaining("frequencyResolutionHz");
    }

    @Test
    @DisplayName("打滑范围非法或锁死值越界：拦截")
    void invalidSlipRangeAndLockedSlipRejected() {
        var peaks = List.of(peak(100.0));
        assertThatThrownBy(() -> service.attribute(
                25.0, null, G6205, null, peaks, null, null, null, 0.1, 0.05, null))
                .isInstanceOf(InvalidBearingInputException.class)
                .hasMessageContaining("slipMin");
        assertThatThrownBy(() -> service.attribute(
                25.0, null, G6205, null, peaks, null, null, null, -0.01, 0.05, null))
                .isInstanceOf(InvalidBearingInputException.class)
                .hasMessageContaining("[0,");
        assertThatThrownBy(() -> service.attribute(
                25.0, null, G6205, null, peaks, null, null, null, 0.0, 0.1, 0.2))
                .isInstanceOf(InvalidBearingInputException.class)
                .hasMessageContaining("lockedSlip");
    }

    @Test
    @DisplayName("几何与档名同时给或都不给：拦截")
    void geometryAndNameExclusivity() {
        var peaks = List.of(peak(100.0));
        assertThatThrownBy(() -> service.attribute(
                25.0, null, G6205, "A", peaks, null, null, null, null, null, null))
                .isInstanceOf(InvalidBearingInputException.class)
                .hasMessageContaining("只能提供一个");
        assertThatThrownBy(() -> service.attribute(
                25.0, null, null, null, peaks, null, null, null, null, null, null))
                .isInstanceOf(InvalidBearingInputException.class)
                .hasMessageContaining("必须提供");
    }

    @Test
    @DisplayName("沿用几何校验与转频二选一校验；引用不存在的档照 404 口径")
    void reuseExistingValidationAndCatalog404() {
        var badGeometry = new BearingGeometry(9, 39.04, 40.0, 0.0);
        assertThatThrownBy(() -> service.attribute(
                25.0, null, badGeometry, null, List.of(peak(100.0)),
                null, null, null, null, null, null))
                .isInstanceOf(InvalidBearingInputException.class)
                .hasMessageContaining("滚动体直径必须小于节圆直径");
        assertThatThrownBy(() -> service.attribute(
                null, null, G6205, null, List.of(peak(100.0)),
                null, null, null, null, null, null))
                .isInstanceOf(InvalidBearingInputException.class)
                .hasMessageContaining("必须提供");
        assertThatThrownBy(() -> service.attribute(
                25.0, null, null, "GHOST", List.of(peak(100.0)),
                null, null, null, null, null, null))
                .isInstanceOf(com.bearingfreq.catalog.CatalogEntryNotFoundException.class)
                .hasMessageContaining("GHOST");
    }

    @Test
    @DisplayName("锁死打滑：只做一次归属，不迭代；打滑固定为给定值")
    void lockedSlipRunsSinglePass() {
        double fr = 25.0;
        double slip = 0.03;
        CharacteristicFrequencies f = BearingKinematics.compute(fr, G6205);
        List<AttributionInputValidator.RawPeak> peaks = new ArrayList<>();
        for (BearingFamily family : List.of(BearingFamily.BPFO, BearingFamily.BPFI,
                BearingFamily.FTF, BearingFamily.BSF)) {
            double base = baseHz(family, f, fr);
            for (int k = 1; k <= 4; k++) {
                peaks.add(peak(base * k * (1.0 - slip)));
            }
        }
        AttributionResult locked = service.attribute(
                fr, null, G6205, null, peaks, 10, 0.02, 0.0, null, null, slip);
        assertThat(locked.iterations()).isOne();
        assertThat(locked.slip()).isEqualTo(slip);
        assertThat(locked.terminationReason()).isEqualTo(AttributionResult.REASON_LOCKED);
        assertThat(locked.matching().matchCount()).isEqualTo(16);

        // 同样的峰自由估计应收敛回同一个打滑
        AttributionResult free = service.attribute(
                fr, null, G6205, null, peaks, 10, 0.02, 0.0, null, null, null);
        assertThat(free.converged()).isTrue();
        assertThat(free.slip()).isCloseTo(slip, within(DECLARED_SLIP_ACCURACY));
    }

    @Test
    @DisplayName("全是干扰峰（无任何候选邻近）：如实返回未收敛与原因，不编造打滑")
    void noBearingMatchesIsReportedHonestly() {
        var peaks = List.of(peak(12345.0), peak(23456.0));
        AttributionResult result = service.attribute(
                25.0, null, G6205, null, peaks, 10, 0.02, 0.0, null, null, null);
        assertThat(result.converged()).isFalse();
        assertThat(result.terminationReason())
                .isEqualTo(AttributionResult.REASON_NO_BEARING_MATCHES);
        assertThat(result.matching().matchCount()).isZero();
        assertThat(result.terminationMessage()).contains("无从估计");
    }
}
