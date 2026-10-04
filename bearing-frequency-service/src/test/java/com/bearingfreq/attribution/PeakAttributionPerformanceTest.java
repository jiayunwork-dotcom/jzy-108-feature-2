package com.bearingfreq.attribution;

import com.bearingfreq.model.BearingGeometry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 规模压测：峰数上限 500、谐波阶数上限 10 时，单次请求须在 2 秒内返回
 * （含自由迭代与锁死单轮两种形态）。
 */
class PeakAttributionPerformanceTest {

    private static final BearingGeometry GEOMETRY_6205 =
            new BearingGeometry(9, 39.04, 7.94, 0.0);

    private static final int MAX_PEAKS = 500;
    private static final int MAX_ORDER = 10;
    private static final double FR = 25.0;
    private static final double TRUE_SLIP = 0.007;

    /**
     * 合成 500 根峰：
     * 50 个目标（5 族 × 10 阶）各一根，轴承族统一压低 TRUE_SLIP 并加小扰动；
     * 其余为散布在 5~600 Hz 的干扰峰。固定随机种子，结果确定。
     */
    private static List<MeasuredPeak> synthesize() {
        TargetSpectrum spectrum = TargetSpectrum.build(FR, GEOMETRY_6205, MAX_ORDER);
        Random rnd = new Random(987654321L);
        List<MeasuredPeak> peaks = new ArrayList<>(MAX_PEAKS);
        for (CandidateTarget t : spectrum.atSlip(0.0)) {
            double hz = t.family() == FrequencyFamily.SHAFT
                    ? t.theoreticalHz()
                    : t.theoreticalHz() * (1 - TRUE_SLIP);
            hz += (rnd.nextDouble() - 0.5) * 0.04;
            peaks.add(new MeasuredPeak(hz, rnd.nextDouble()));
        }
        while (peaks.size() < MAX_PEAKS) {
            peaks.add(new MeasuredPeak(5.0 + rnd.nextDouble() * 595.0, rnd.nextDouble()));
        }
        return peaks;
    }

    @Test
    @DisplayName("500 峰 × 10 阶（自由打滑迭代）：2 秒内返回，结果合理且确定")
    void freeEstimationWithinTwoSeconds() {
        List<MeasuredPeak> peaks = synthesize();
        TargetSpectrum spectrum = TargetSpectrum.build(FR, GEOMETRY_6205, MAX_ORDER);

        long start = System.nanoTime();
        EngineOutcome first = SlipAttributionEngine.run(peaks, spectrum,
                0.02, 0.0, 0.0, 0.05, null);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).isLessThan(2000L);
        System.out.println("[perf] 500 峰自由迭代耗时 " + elapsedMs + " ms，配对 "
                + first.result().matchedCount() + " 对，迭代 " + first.iterations() + " 轮");
        assertThat(first.converged()).isTrue();
        // 50 个目标峰都在窗口内（扰动 ±0.02 Hz 远小于 2% 窗），配满 50 对。
        assertThat(first.result().matchedCount()).isEqualTo(50);
        assertThat(first.slip()).isCloseTo(TRUE_SLIP, within(3e-3));
        assertThat(first.result().unassignedPeakIndexes()).hasSize(MAX_PEAKS - 50);

        // 确定性：再来一次耗时与结果一致。
        EngineOutcome second = SlipAttributionEngine.run(peaks, spectrum,
                0.02, 0.0, 0.0, 0.05, null);
        assertThat(second).usingRecursiveComparison().isEqualTo(first);
    }

    @Test
    @DisplayName("500 峰 × 10 阶（打滑锁死单轮）：2 秒内返回")
    void lockedSlipWithinTwoSeconds() {
        List<MeasuredPeak> peaks = synthesize();
        TargetSpectrum spectrum = TargetSpectrum.build(FR, GEOMETRY_6205, MAX_ORDER);

        long start = System.nanoTime();
        EngineOutcome outcome = SlipAttributionEngine.run(peaks, spectrum,
                0.02, 0.0, 0.0, 0.05, TRUE_SLIP);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).isLessThan(2000L);
        assertThat(outcome.iterations()).isEqualTo(1);
        assertThat(outcome.result().matchedCount()).isEqualTo(50);
    }
}
