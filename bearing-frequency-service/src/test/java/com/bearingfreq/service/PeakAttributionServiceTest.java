package com.bearingfreq.service;

import com.bearingfreq.attribution.EngineOutcome;
import com.bearingfreq.attribution.FrequencyFamily;
import com.bearingfreq.attribution.MeasuredPeak;
import com.bearingfreq.catalog.BearingCatalog;
import com.bearingfreq.catalog.CatalogEntryNotFoundException;
import com.bearingfreq.model.BearingGeometry;
import com.bearingfreq.validation.InvalidBearingInputException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * 峰值归属服务的验收测试：覆盖需求中逐点可构造的验收场景。
 */
class PeakAttributionServiceTest {

    private static final BearingGeometry GEOMETRY_6205 =
            new BearingGeometry(9, 39.04, 7.94, 0.0);

    private final PeakAttributionService service = new PeakAttributionService(new BearingCatalog());

    private double theory(double fr, FrequencyFamily family, int order) {
        var base = com.bearingfreq.kinematics.BearingKinematics.compute(fr, GEOMETRY_6205);
        double baseHz = switch (family) {
            case SHAFT -> fr;
            case BPFO -> base.bpfoHz();
            case BPFI -> base.bpfiHz();
            case FTF -> base.ftfHz();
            case BSF -> base.bsfHz();
        };
        return baseHz * order;
    }

    private static MeasuredPeak p(double hz) {
        return new MeasuredPeak(hz, 1.0);
    }

    private EngineOutcome run(double fr, List<MeasuredPeak> peaks, Double locked,
                              double tolerance) {
        return service.attribute(new AttributionQuery(fr, null, peaks, GEOMETRY_6205, null,
                10, tolerance, 0.0, 0.0, 0.05, locked));
    }

    private EngineOutcome runDefaults(double fr, List<MeasuredPeak> peaks, Double locked) {
        return run(fr, peaks, locked, 0.02);
    }

    @Test
    @DisplayName("验收1：纯理论频率合成峰 ⇒ 打滑为零（浮点误差内），与锁死零完全一致")
    void zeroSlipTheoreticalPeaksMatchesLockedZero() {
        double fr = 25.0;
        List<MeasuredPeak> peaks = new ArrayList<>();
        for (FrequencyFamily family : FrequencyFamily.values()) {
            for (int order = 1; order <= 4; order++) {
                peaks.add(p(theory(fr, family, order)));
            }
        }

        EngineOutcome free = runDefaults(fr, peaks, null);
        EngineOutcome locked = runDefaults(fr, peaks, 0.0);

        assertThat(free.converged()).isTrue();
        assertThat(free.slip()).isCloseTo(0.0, within(1e-12));
        assertThat(free.result().matchedCount()).isEqualTo(20);
        assertThat(locked.result()).usingRecursiveComparison()
                .isEqualTo(free.result());
        assertThat(locked.slip()).isCloseTo(free.slip(), within(0.0));
        assertThat(locked.iterations()).isEqualTo(1);
    }

    @Test
    @DisplayName("验收2：已知打滑压低轴承谐波（夹转频倍频、干扰峰、小扰动）⇒ 反推打滑，"
            + "干扰未归属，转频不修正")
    void recoverKnownSlipWithShaftAndNoise() {
        double fr = 25.0;
        double trueSlip = 0.008;
        Random jitter = new Random(20261004);
        List<MeasuredPeak> peaks = new ArrayList<>();
        // 转频 1、3 倍频，不压低，加小的确定性扰动。
        peaks.add(p(fr + 0.03));
        peaks.add(p(3 * fr - 0.02));
        // 四个轴承族各取若干阶（避开近重合阶），统一压低后加 ±0.05 Hz 内确定性扰动。
        int[] orders = {1, 2, 3};
        for (FrequencyFamily family : List.of(FrequencyFamily.BPFO, FrequencyFamily.BPFI,
                FrequencyFamily.FTF, FrequencyFamily.BSF)) {
            for (int order : orders) {
                double noise = (jitter.nextDouble() - 0.5) * 0.1;
                peaks.add(p(theory(fr, family, order) * (1 - trueSlip) + noise));
            }
        }
        // 三根远离全部谐波网格的干扰峰。
        peaks.add(p(41.11));
        peaks.add(p(112.0));
        peaks.add(p(432.17));
        int noiseCount = 3;
        int bearingCount = 12;

        EngineOutcome outcome = runDefaults(fr, peaks, null);

        assertThat(outcome.converged()).isTrue();
        // 声明精度：默认窗口与该扰动水平下 |ŝ − s| < 2e-3。
        assertThat(outcome.slip()).isCloseTo(trueSlip, within(2e-3));
        assertThat(outcome.result().matchedCount()).isEqualTo(bearingCount + 2);

        // 转频倍频归 shaft，修正目标 == 理论目标（不受打滑修正）。
        List<com.bearingfreq.attribution.Assignment> shaft = outcome.result().assignments().stream()
                .filter(a -> a.family() == FrequencyFamily.SHAFT).toList();
        assertThat(shaft).hasSize(2);
        assertThat(shaft).allSatisfy(a -> assertThat(a.correctedTargetHz())
                .isCloseTo(a.theoreticalTargetHz(), within(1e-12)));
        assertThat(shaft).extracting(a -> a.order()).containsExactlyInAnyOrder(1, 3);

        // 最后三根为干扰峰，全部未归属。
        assertThat(outcome.result().unassignedPeakIndexes())
                .hasSize(noiseCount)
                .contains(peaks.size() - 1, peaks.size() - 2, peaks.size() - 3);
    }

    @Test
    @DisplayName("验收3：逐个就近认领更差的输入 ⇒ 服务给出总偏差更小的全局配对")
    void globalOptimumBeatsGreedy() {
        // 用 6205 真实几何取两个近重合目标：shaft2≈50、ftf5≈49.7887（fr=25）。
        double fr = 25.0;
        double shaft2 = theory(fr, FrequencyFamily.SHAFT, 2);
        double ftf5 = theory(fr, FrequencyFamily.FTF, 5);
        // 两根峰：一根在二者之间偏 shaft，一根钉在 ftf5。
        double mid = (shaft2 + ftf5) / 2.0 + 0.15;
        List<MeasuredPeak> peaks = List.of(p(mid), p(ftf5));

        EngineOutcome outcome = runDefaults(fr, peaks, 0.0);

        assertThat(outcome.result().matchedCount()).isEqualTo(2);
        double globalTotal = outcome.result().totalAbsDeviationHz();
        // 全局最优：mid→shaft2、ftf5→ftf5。
        double greedyGrab = Math.abs(mid - ftf5) + Math.abs(ftf5 - shaft2);
        assertThat(globalTotal).isCloseTo(Math.abs(mid - shaft2), within(1e-9));
        assertThat(globalTotal).isLessThan(greedyGrab);
    }

    @Test
    @DisplayName("验收4：乱序提交逐字段相同；同一请求重复提交相同")
    void shuffledAndRepeatedRequestsAreIdentical() {
        double fr = 25.0;
        double s = 0.006;
        List<MeasuredPeak> peaks = new ArrayList<>();
        peaks.add(p(theory(fr, FrequencyFamily.SHAFT, 1) + 0.01));
        for (FrequencyFamily family : FrequencyFamily.values()) {
            peaks.add(p(theory(fr, family, 1) * (1 - s)));
        }
        peaks.add(p(333.33));
        List<MeasuredPeak> shuffled = new ArrayList<>(peaks);
        java.util.Collections.shuffle(shuffled, new Random(7));

        EngineOutcome a = runDefaults(fr, peaks, null);
        EngineOutcome again = runDefaults(fr, peaks, null);
        EngineOutcome b = runDefaults(fr, shuffled, null);

        assertThat(again).usingRecursiveComparison().isEqualTo(a);
        assertThat(b.slip()).isCloseTo(a.slip(), within(0.0));
        assertThat(b.iterations()).isEqualTo(a.iterations());
        assertThat(b.converged()).isEqualTo(a.converged());
        assertThat(b.reason()).isEqualTo(a.reason());
        assertThat(b.result().matchedCount()).isEqualTo(a.result().matchedCount());
        // 逐根物理峰（按频率）比对归属字段。
        for (var aa : a.result().assignments()) {
            var bb = b.result().assignments().stream()
                    .filter(x -> Math.abs(x.peakFrequencyHz() - aa.peakFrequencyHz()) < 1e-9)
                    .findFirst().orElseThrow();
            assertThat(bb.family()).isEqualTo(aa.family());
            assertThat(bb.order()).isEqualTo(aa.order());
            assertThat(bb.correctedTargetHz()).isCloseTo(aa.correctedTargetHz(), within(1e-12));
            assertThat(bb.deviationHz()).isCloseTo(aa.deviationHz(), within(1e-12));
            assertThat(bb.relativeDeviation()).isCloseTo(aa.relativeDeviation(), within(1e-12));
        }
        // 未归属的物理峰集合相同。
        assertThat(b.result().unassignedPeakIndexes().stream()
                .map(i -> shuffled.get(i).frequencyHz()).sorted().toList())
                .isEqualTo(a.result().unassignedPeakIndexes().stream()
                        .map(i -> peaks.get(i).frequencyHz()).sorted().toList());
    }

    @Test
    @DisplayName("验收5：分辨率为零时整体乘正尺度 ⇒ 归属、相对偏差、打滑不变")
    void uniformFrequencyScalingLeavesAssignmentInvariant() {
        double fr = 25.0;
        double s = 0.009;
        List<MeasuredPeak> peaks = new ArrayList<>();
        peaks.add(p(theory(fr, FrequencyFamily.SHAFT, 1)));
        peaks.add(p(theory(fr, FrequencyFamily.BPFO, 2) * (1 - s)));
        peaks.add(p(theory(fr, FrequencyFamily.BPFI, 3) * (1 - s)));
        peaks.add(p(theory(fr, FrequencyFamily.FTF, 4) * (1 - s)));
        peaks.add(p(theory(fr, FrequencyFamily.BSF, 2) * (1 - s)));
        peaks.add(p(41.11));

        double k = 13.7;
        List<MeasuredPeak> scaled = peaks.stream()
                .map(peak -> new MeasuredPeak(peak.frequencyHz() * k, peak.amplitude()))
                .toList();

        EngineOutcome base = runDefaults(fr, peaks, null);
        EngineOutcome scaledOutcome = runDefaults(fr * k, scaled, null);

        assertThat(scaledOutcome.slip()).isCloseTo(base.slip(), within(1e-12));
        assertThat(scaledOutcome.converged()).isEqualTo(base.converged());
        assertThat(scaledOutcome.iterations()).isEqualTo(base.iterations());
        assertThat(scaledOutcome.result().matchedCount()).isEqualTo(base.result().matchedCount());
        assertThat(scaledOutcome.result().unassignedPeakIndexes())
                .containsExactlyElementsOf(base.result().unassignedPeakIndexes());
        assertThat(scaledOutcome.result().totalAbsRelativeDeviation())
                .isCloseTo(base.result().totalAbsRelativeDeviation(), within(1e-12));
        for (var a : base.result().assignments()) {
            var c = scaledOutcome.result().assignments().stream()
                    .filter(x -> x.peakIndex() == a.peakIndex()).findFirst().orElseThrow();
            assertThat(c.family()).isEqualTo(a.family());
            assertThat(c.order()).isEqualTo(a.order());
            assertThat(c.relativeDeviation()).isCloseTo(a.relativeDeviation(), within(1e-12));
            assertThat(c.correctedTargetHz()).isCloseTo(a.correctedTargetHz() * k, within(1e-9));
        }
    }

    @Test
    @DisplayName("验收6：内联几何与同参数轴承档结果一致；两档并行不串参数")
    void inlineGeometryMatchesCatalogAndEntriesStayIsolated() {
        double fr = 25.0;
        List<MeasuredPeak> peaks = List.of(
                p(theory(fr, FrequencyFamily.BPFO, 1) * 0.995),
                p(theory(fr, FrequencyFamily.BPFI, 2) * 0.995),
                p(theory(fr, FrequencyFamily.SHAFT, 1)));

        EngineOutcome inline = service.attribute(new AttributionQuery(
                fr, null, peaks, GEOMETRY_6205, null, 10, 0.02, 0.0, 0.0, 0.05, null));

        BearingCatalog catalog = new BearingCatalog();
        PeakAttributionService byCatalog = new PeakAttributionService(catalog);
        catalog.register("6205", GEOMETRY_6205);
        EngineOutcome named = byCatalog.attribute(new AttributionQuery(
                fr, null, peaks, null, "6205", 10, 0.02, 0.0, 0.0, 0.05, null));

        assertThat(named.result()).usingRecursiveComparison()
                .isEqualTo(inline.result());
        assertThat(named.slip()).isCloseTo(inline.slip(), within(0.0));

        // 两档参数不同：A(n=8) 与 B(n=16)，同转速、同峰集，各算各的。
        BearingGeometry geomA = new BearingGeometry(8, 40.0, 6.0, 0.0);
        BearingGeometry geomB = new BearingGeometry(16, 40.0, 6.0, 0.0);
        catalog.register("A", geomA);
        catalog.register("B", geomB);
        double fr2 = 30.0;
        double bpfoA = com.bearingfreq.kinematics.BearingKinematics.compute(fr2, geomA).bpfoHz();
        double bpfoB = com.bearingfreq.kinematics.BearingKinematics.compute(fr2, geomB).bpfoHz();
        List<MeasuredPeak> aPeaks = List.of(p(bpfoA * 0.99), p(fr2));
        List<MeasuredPeak> bPeaks = List.of(p(bpfoB * 0.99), p(fr2));

        EngineOutcome ea = byCatalog.attribute(new AttributionQuery(
                fr2, null, aPeaks, null, "A", 10, 0.02, 0.0, 0.0, 0.05, null));
        EngineOutcome eb = byCatalog.attribute(new AttributionQuery(
                fr2, null, bPeaks, null, "B", 10, 0.02, 0.0, 0.0, 0.05, null));
        EngineOutcome eaAgain = byCatalog.attribute(new AttributionQuery(
                fr2, null, aPeaks, null, "A", 10, 0.02, 0.0, 0.0, 0.05, null));

        var assignedA = ea.result().assignments().stream()
                .filter(x -> x.family() == FrequencyFamily.BPFO).findFirst().orElseThrow();
        var assignedB = eb.result().assignments().stream()
                .filter(x -> x.family() == FrequencyFamily.BPFO).findFirst().orElseThrow();
        assertThat(assignedA.correctedTargetHz()).isCloseTo(bpfoA * (1 - ea.slip()), within(1e-9));
        assertThat(assignedB.correctedTargetHz()).isCloseTo(bpfoB * (1 - eb.slip()), within(1e-9));
        assertThat(eaAgain.result()).usingRecursiveComparison()
                .isEqualTo(ea.result());
    }

    @Test
    @DisplayName("引用不存在的轴承档：沿用 404 语义")
    void unknownCatalogEntryIs404() {
        assertThatThrownBy(() -> service.attribute(new AttributionQuery(
                25.0, null, List.of(p(25.0)), null, "MISSING",
                null, null, null, null, null, null)))
                .isInstanceOf(CatalogEntryNotFoundException.class)
                .hasMessageContaining("MISSING");
    }

    @Test
    @DisplayName("校验：空峰/超限/坏峰（指出第几根）/越界参数/几何档名冲突都在计算前拦截")
    void invalidInputsRejectedBeforeComputation() {
        List<MeasuredPeak> one = List.of(p(25.0));

        assertThatThrownBy(() -> service.attribute(new AttributionQuery(
                25.0, null, List.of(), GEOMETRY_6205, null, null, null, null, null, null, null)))
                .isInstanceOf(InvalidBearingInputException.class).hasMessageContaining("不能为空");

        List<MeasuredPeak> tooMany = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            tooMany.add(p(10 + i));
        }
        assertThatThrownBy(() -> run(25.0, tooMany, null, 0.02))
                .isInstanceOf(InvalidBearingInputException.class).hasMessageContaining("500");

        List<MeasuredPeak> badFreq = new ArrayList<>(one);
        badFreq.add(new MeasuredPeak(-1.0, 1.0));
        assertThatThrownBy(() -> run(25.0, badFreq, null, 0.02))
                .isInstanceOf(InvalidBearingInputException.class).hasMessageContaining("第 1 根峰");

        List<MeasuredPeak> negAmp = new ArrayList<>();
        negAmp.add(new MeasuredPeak(25.0, -3.0));
        assertThatThrownBy(() -> run(25.0, negAmp, null, 0.02))
                .isInstanceOf(InvalidBearingInputException.class).hasMessageContaining("第 0 根峰");

        assertThatThrownBy(() -> service.attribute(new AttributionQuery(
                25.0, null, one, GEOMETRY_6205, null, 11, null, null, null, null, null)))
                .isInstanceOf(InvalidBearingInputException.class);
        assertThatThrownBy(() -> run(25.0, one, null, 0.5))
                .isInstanceOf(InvalidBearingInputException.class);
        assertThatThrownBy(() -> service.attribute(new AttributionQuery(
                25.0, null, one, GEOMETRY_6205, null, null, null, null, 0.0, 0.6, null)))
                .isInstanceOf(InvalidBearingInputException.class);
        assertThatThrownBy(() -> service.attribute(new AttributionQuery(
                25.0, null, one, GEOMETRY_6205, null, null, null, null, 0.0, 0.05, 0.5)))
                .isInstanceOf(InvalidBearingInputException.class);
        // 几何与档名同时给 / 都不给。
        assertThatThrownBy(() -> service.attribute(new AttributionQuery(
                25.0, null, one, GEOMETRY_6205, "6205", null, null, null, null, null, null)))
                .isInstanceOf(InvalidBearingInputException.class);
        assertThatThrownBy(() -> service.attribute(new AttributionQuery(
                25.0, null, one, null, null, null, null, null, null, null, null)))
                .isInstanceOf(InvalidBearingInputException.class);
        // 非法几何沿用现有拦截。
        BearingGeometry badGeometry = new BearingGeometry(9, 39.04, 39.04, 0.0);
        assertThatThrownBy(() -> service.attribute(new AttributionQuery(
                25.0, null, one, badGeometry, null, null, null, null, null, null, null)))
                .isInstanceOf(InvalidBearingInputException.class)
                .hasMessageContaining("滚动体直径必须小于节圆直径");
        // 转频/转速口径沿用：都不给。
        assertThatThrownBy(() -> service.attribute(new AttributionQuery(
                null, null, one, GEOMETRY_6205, null, null, null, null, null, null, null)))
                .isInstanceOf(InvalidBearingInputException.class);
    }
}
