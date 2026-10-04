package com.bearingfreq.attribution;

import com.bearingfreq.model.BearingGeometry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class SlipAttributionEngineTest {

    static final BearingGeometry GEOMETRY_6205 =
            new BearingGeometry(9, 39.04, 7.94, 0.0);

    private static TargetSpectrum spectrum(double fr, int maxOrder) {
        return TargetSpectrum.build(fr, GEOMETRY_6205, maxOrder);
    }

    private static MeasuredPeak peak(double hz) {
        return new MeasuredPeak(hz, 1.0);
    }

    /** 从理论谱取指定族指定阶频率。 */
    private static double theory(TargetSpectrum s, FrequencyFamily family, int order) {
        return s.atSlip(0.0).stream()
                .filter(t -> t.family() == family && t.order() == order)
                .findFirst().orElseThrow().theoreticalHz();
    }

    @Test
    @DisplayName("无打滑纯理论峰：s=0 一步收敛，与锁死 0 的归属完全一致")
    void zeroSlipConvergesInOneRoundAndMatchesLockedZero() {
        TargetSpectrum spectrum = spectrum(25.0, 6);
        List<MeasuredPeak> peaks = List.of(
                peak(theory(spectrum, FrequencyFamily.SHAFT, 1)),
                peak(theory(spectrum, FrequencyFamily.BPFO, 1)),
                peak(theory(spectrum, FrequencyFamily.BPFI, 1)),
                peak(theory(spectrum, FrequencyFamily.FTF, 1)),
                peak(theory(spectrum, FrequencyFamily.BSF, 1)));

        EngineOutcome free = SlipAttributionEngine.run(peaks, spectrum,
                0.02, 0.0, 0.0, 0.05, null);
        EngineOutcome locked = SlipAttributionEngine.run(peaks, spectrum,
                0.02, 0.0, 0.0, 0.05, 0.0);

        assertThat(free.slip()).isCloseTo(0.0, within(1e-12));
        assertThat(free.iterations()).isEqualTo(1);
        assertThat(free.converged()).isTrue();
        assertThat(free.result().assignments()).hasSize(5);
        assertThat(locked.result()).usingRecursiveComparison()
                .ignoringFields("slip").isEqualTo(free.result());
        assertThat(locked.slip()).isCloseTo(free.slip(), within(0.0));
    }

    @Test
    @DisplayName("已知打滑合成峰：反推打滑系数，转频倍频不被修正")
    void recoversKnownSlipAndLeavesShaftAlone() {
        double trueSlip = 0.0123;
        TargetSpectrum spectrum = spectrum(25.0, 8);
        List<MeasuredPeak> peaks = new ArrayList<>();
        // 转频倍频不压低。
        peaks.add(peak(theory(spectrum, FrequencyFamily.SHAFT, 1)));
        peaks.add(peak(theory(spectrum, FrequencyFamily.SHAFT, 2)));
        // 轴承族谐波统一乘 (1 − s)。
        for (FrequencyFamily family : List.of(FrequencyFamily.BPFO, FrequencyFamily.BPFI,
                FrequencyFamily.FTF, FrequencyFamily.BSF)) {
            for (int order : new int[]{1, 2, 3}) {
                peaks.add(peak(theory(spectrum, family, order) * (1.0 - trueSlip)));
            }
        }

        EngineOutcome outcome = SlipAttributionEngine.run(peaks, spectrum,
                0.02, 0.0, 0.0, 0.05, null);

        assertThat(outcome.converged()).isTrue();
        assertThat(outcome.slip()).isCloseTo(trueSlip, within(2e-3));
        // 两根转频峰归 shaft，修正目标即理论值，偏差为 0。
        List<Assignment> shaft = outcome.result().assignments().stream()
                .filter(a -> a.family() == FrequencyFamily.SHAFT).toList();
        assertThat(shaft).hasSize(2);
        assertThat(shaft).allSatisfy(a -> {
            assertThat(a.theoreticalTargetHz()).isCloseTo(a.correctedTargetHz(), within(1e-12));
            assertThat(a.deviationHz()).isCloseTo(0.0, within(1e-9));
        });
        // 全部 14 根峰都配上。
        assertThat(outcome.result().matchedCount()).isEqualTo(14);
    }

    @Test
    @DisplayName("无任何轴承族峰时：打滑不可观测，按收敛返回并说明原因")
    void unobservableWhenOnlyShaftAndNoise() {
        TargetSpectrum spectrum = spectrum(25.0, 6);
        List<MeasuredPeak> peaks = List.of(
                peak(theory(spectrum, FrequencyFamily.SHAFT, 1)),
                peak(432.17)); // 远离所有目标的干扰峰

        EngineOutcome outcome = SlipAttributionEngine.run(peaks, spectrum,
                0.02, 0.0, 0.0, 0.05, null);

        assertThat(outcome.converged()).isTrue();
        assertThat(outcome.reason()).contains("不可观测");
        assertThat(outcome.result().unassignedPeakIndexes()).containsExactly(1);
        assertThat(outcome.result().matchedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("干扰峰标为未归属，不污染打滑估计")
    void noisePeaksStayUnassigned() {
        double trueSlip = 0.01;
        TargetSpectrum spectrum = spectrum(25.0, 6);
        // 避开 BPFO1≡FTF9、BSF1≈SHAFT2 这类固有近重合：只用特征鲜明的高阶轴承峰。
        List<MeasuredPeak> peaks = new ArrayList<>();
        peaks.add(peak(theory(spectrum, FrequencyFamily.BPFO, 3) * (1 - trueSlip)));
        peaks.add(peak(theory(spectrum, FrequencyFamily.BPFI, 3) * (1 - trueSlip)));
        peaks.add(peak(theory(spectrum, FrequencyFamily.BSF, 2) * (1 - trueSlip)));
        peaks.add(peak(41.11));
        peaks.add(peak(112.0));
        peaks.add(peak(432.17));

        EngineOutcome outcome = SlipAttributionEngine.run(peaks, spectrum,
                0.02, 0.0, 0.0, 0.05, null);

        assertThat(outcome.converged()).isTrue();
        assertThat(outcome.slip()).isCloseTo(trueSlip, within(2e-3));
        // 干扰峰选在各谐波网格之间，原样标为未归属。
        assertThat(outcome.result().unassignedPeakIndexes())
                .containsExactly(3, 4, 5);
    }

    @Test
    @DisplayName("锁死打滑：只做一次归属，iterations=1，目标按锁死值修正")
    void lockedSlipRunsSingleAssignment() {
        double locked = 0.02;
        TargetSpectrum spectrum = spectrum(25.0, 4);
        List<MeasuredPeak> peaks = List.of(
                peak(theory(spectrum, FrequencyFamily.BPFO, 1) * (1 - locked)),
                peak(theory(spectrum, FrequencyFamily.SHAFT, 1)));

        EngineOutcome outcome = SlipAttributionEngine.run(peaks, spectrum,
                0.02, 0.0, 0.0, 0.05, locked);

        assertThat(outcome.iterations()).isEqualTo(1);
        assertThat(outcome.converged()).isTrue();
        assertThat(outcome.slip()).isCloseTo(locked, within(0.0));
        Assignment bpfo = outcome.result().assignments().stream()
                .filter(a -> a.family() == FrequencyFamily.BPFO).findFirst().orElseThrow();
        assertThat(bpfo.correctedTargetHz())
                .isCloseTo(bpfo.theoreticalTargetHz() * (1 - locked), within(1e-12));
        assertThat(bpfo.deviationHz()).isCloseTo(0.0, within(1e-9));
    }

    @Test
    @DisplayName("打滑估计触界：标记 slipAtBound 且原因中提示")
    void boundarySlipIsFlagged() {
        TargetSpectrum spectrum = spectrum(25.0, 4);
        // 允许范围只给到 5%：锚点峰压低 5%（在边界上，首轮即能配上），
        // 另两根峰压 10%，把平均打滑往上推，估计被夹到上界 5%。
        List<MeasuredPeak> peaks = List.of(
                peak(theory(spectrum, FrequencyFamily.BPFO, 1) * 0.95),
                peak(theory(spectrum, FrequencyFamily.BPFI, 2) * 0.90),
                peak(theory(spectrum, FrequencyFamily.BSF, 3) * 0.90));

        EngineOutcome outcome = SlipAttributionEngine.run(peaks, spectrum,
                0.12, 0.0, 0.0, 0.05, null);

        assertThat(outcome.slipAtBound()).isTrue();
        assertThat(outcome.slip()).isCloseTo(0.05, within(1e-12));
        assertThat(outcome.reason()).contains("边界");
    }

    @Test
    @DisplayName("对抗性满谱（含 BPFO_k≡FTF_9k 等固有重合）：结果确定且结果与打滑系数同源")
    void adversarialDenseSpectrumIsStableAndSelfConsistent() {
        TargetSpectrum spectrum = spectrum(25.0, 10);
        // 全部 50 个理论目标各给一根峰，轴承族再统一压 1%——目标之间大量重合/近重合。
        List<MeasuredPeak> peaks = new ArrayList<>();
        for (CandidateTarget t : spectrum.atSlip(0.0)) {
            peaks.add(peak(t.family().isSlipAffected()
                    ? t.theoreticalHz() * 0.99 : t.theoreticalHz()));
        }

        EngineOutcome a = SlipAttributionEngine.run(peaks, spectrum, 0.02, 0.0, 0.0, 0.05, null);
        EngineOutcome b = SlipAttributionEngine.run(peaks, spectrum, 0.02, 0.0, 0.0, 0.05, null);

        assertThat(a.reason()).isNotBlank();
        assertThat(a.iterations()).isBetween(1, SlipAttributionEngine.MAX_ITERATIONS);
        assertThat(b).usingRecursiveComparison().isEqualTo(a);
        // 回传方案的修正目标必须与回传打滑系数一致（同源）。
        for (Assignment x : a.result().assignments()) {
            double expected = x.family().isSlipAffected()
                    ? x.theoreticalTargetHz() * (1 - a.slip())
                    : x.theoreticalTargetHz();
            assertThat(x.correctedTargetHz()).isCloseTo(expected, within(1e-9));
        }
        // 无论收敛与否，converged=false 时原因必须显式写「未收敛」。
        if (!a.converged()) {
            assertThat(a.reason()).contains("未收敛");
        }
    }

    @Test
    @DisplayName("重复执行与乱序提交：结果完全一致")
    void deterministicAndOrderIndependent() {
        double trueSlip = 0.008;
        TargetSpectrum spectrum = spectrum(25.0, 8);
        List<MeasuredPeak> peaks = new ArrayList<>();
        peaks.add(peak(theory(spectrum, FrequencyFamily.SHAFT, 1)));
        for (FrequencyFamily family : FrequencyFamily.values()) {
            peaks.add(peak(theory(spectrum, family, 1) * (1 - trueSlip)));
        }
        peaks.add(peak(333.33));
        List<MeasuredPeak> reversed = new ArrayList<>(peaks);
        java.util.Collections.reverse(reversed);

        EngineOutcome a = SlipAttributionEngine.run(peaks, spectrum, 0.02, 0.0, 0.0, 0.05, null);
        EngineOutcome b = SlipAttributionEngine.run(peaks, spectrum, 0.02, 0.0, 0.0, 0.05, null);
        EngineOutcome c = SlipAttributionEngine.run(reversed, spectrum, 0.02, 0.0, 0.0, 0.05, null);

        assertThat(b).usingRecursiveComparison().isEqualTo(a);
        // 乱序提交：物理峰（按频率识别）的归属与偏差逐字段相同；原始下标随提交位置不同。
        assertThat(c.slip()).isCloseTo(a.slip(), within(0.0));
        assertThat(c.iterations()).isEqualTo(a.iterations());
        assertThat(c.converged()).isEqualTo(a.converged());
        assertThat(c.result().matchedCount()).isEqualTo(a.result().matchedCount());
        assertThat(c.result().unassignedPeakIndexes().stream()
                .map(i -> reversed.get(i).frequencyHz()).sorted().toList())
                .isEqualTo(a.result().unassignedPeakIndexes().stream()
                        .map(i -> peaks.get(i).frequencyHz()).sorted().toList());
        for (Assignment aa : a.result().assignments()) {
            Assignment cc = c.result().assignments().stream()
                    .filter(x -> Math.abs(x.peakFrequencyHz() - aa.peakFrequencyHz()) < 1e-9)
                    .findFirst().orElseThrow();
            assertThat(cc.family()).isEqualTo(aa.family());
            assertThat(cc.order()).isEqualTo(aa.order());
            assertThat(cc.correctedTargetHz()).isCloseTo(aa.correctedTargetHz(), within(1e-12));
            assertThat(cc.deviationHz()).isCloseTo(aa.deviationHz(), within(1e-12));
            assertThat(cc.relativeDeviation()).isCloseTo(aa.relativeDeviation(), within(1e-12));
        }
    }
}
