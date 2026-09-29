package com.bearingfreq.attribution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

/**
 * 全局最优一对一配对的性质测试：
 * 与「逐个就近认领」贪心的严格反例、窗口（含分辨率半 bin）边界、顺序无关性。
 */
class BipartiteMatcherTest {

    /**
     * 构造目标表：规范序在前的 t1 频率反而较小（shaft=100、bpfo=105）。
     * 贪心参照实现按目标规范序逐个取窗口内未占用的最近峰。
     */
    private static List<Target> twoTargets(double t1, double t2) {
        return List.of(
                new Target(BearingFamily.SHAFT, 1, t1, t1),
                new Target(BearingFamily.BPFO, 1, t2, t2));
    }

    private static List<MeasuredPeak> peaksOf(double... frequencies) {
        List<MeasuredPeak> peaks = new ArrayList<>();
        for (int i = 0; i < frequencies.length; i++) {
            peaks.add(new MeasuredPeak(i, frequencies[i], 1.0));
        }
        return peaks;
    }

    /** 参照贪心：按目标规范序，各取窗口内相对偏差平方最小、尚未占用的峰。 */
    private static double greedyTotalCost(List<Target> targets, List<MeasuredPeak> peaks,
                                          double tolerance) {
        Set<Integer> taken = new HashSet<>();
        double total = 0.0;
        for (Target target : targets) {
            double halfWindow = tolerance * target.theoreticalHz();
            int bestPeak = -1;
            double bestCost = Double.POSITIVE_INFINITY;
            for (int pi = 0; pi < peaks.size(); pi++) {
                if (taken.contains(pi)) {
                    continue;
                }
                double deviation = peaks.get(pi).frequencyHz() - target.theoreticalHz();
                if (Math.abs(deviation) <= halfWindow) {
                    double relative = deviation / target.theoreticalHz();
                    double cost = relative * relative;
                    if (cost < bestCost) {
                        bestCost = cost;
                        bestPeak = pi;
                    }
                }
            }
            if (bestPeak >= 0) {
                taken.add(bestPeak);
                total += bestCost;
            }
        }
        return total;
    }

    @Test
    @DisplayName("严格反例：逐个就近认领总偏差更大，全局配对两对都成且总偏差更小")
    void globalMatchingBeatsNearestFirstGreedy() {
        // t1=100、t2=105；峰 q1=96、q2=102；容差 9%（四根边全在窗内）。
        //   t1=100：q1 −4%（0.0016）、q2 +2%（0.0004）
        //   t2=105：q1 −8.571%（0.007347）、q2 −2.857%（0.000816）
        // 贪心按目标序先给 t1 拿最近的 q2(+2%)，t2 只能拿 q1(−8.57%)：
        //   总代价 0.0004 + 0.007347 = 0.007747
        // 全局交换：t1→q1(−4%)、t2→q2(−2.86%)：
        //   总代价 0.0016 + 0.000816 = 0.002416，两对全成，严格更小。
        List<Target> targets = twoTargets(100.0, 105.0);
        List<MeasuredPeak> peaks = peaksOf(96.0, 102.0);
        double tolerance = 0.09;

        MatchingResult global = BipartiteMatcher.match(peaks, targets, 0.0, tolerance, 0.0);

        assertThat(global.matchCount()).isEqualTo(2);
        assertThat(global.totalSquaredRelativeDeviation())
                .isCloseTo(0.0016 + 0.0008163265, offset(1e-9));
        double greedy = greedyTotalCost(targets, peaks, tolerance);
        assertThat(greedy).isCloseTo(0.0004 + 0.0073469388, offset(1e-9));
        assertThat(global.totalSquaredRelativeDeviation()).isLessThan(greedy);

        // 全局配对的具体归属：t1→96（带符号偏差 −4），t2→102（−3）
        var byTarget = new java.util.HashMap<java.util.List<Object>, Assignment>();
        for (Assignment a : global.assignments()) {
            byTarget.put(List.of(a.target().family(), a.target().order()), a);
        }
        Assignment t1Match = byTarget.get(List.of(BearingFamily.SHAFT, 1));
        Assignment t2Match = byTarget.get(List.of(BearingFamily.BPFO, 1));
        assertThat(t1Match.peak().frequencyHz()).isEqualTo(96.0);
        assertThat(t1Match.deviationHz()).isCloseTo(-4.0, offset(1e-9));
        assertThat(t2Match.peak().frequencyHz()).isEqualTo(102.0);
        assertThat(t2Match.deviationHz()).isCloseTo(-3.0, offset(1e-9));
    }

    @Test
    @DisplayName("超出窗口的边不合法：全部超窗则零配对，峰全部未归属")
    void peaksOutsideWindowAreUnmatched() {
        List<Target> targets = twoTargets(100.0, 200.0);
        List<MeasuredPeak> peaks = peaksOf(150.0, 300.0);
        MatchingResult result = BipartiteMatcher.match(peaks, targets, 0.0, 0.01, 0.0);
        assertThat(result.matchCount()).isZero();
        assertThat(result.unmatchedPeakIndices()).containsExactly(0, 1);
        assertThat(result.totalSquaredRelativeDeviation()).isZero();
    }

    @Test
    @DisplayName("分辨率贡献半个 bin：分辨率 0 时配不上的峰，加大分辨率后可配")
    void resolutionAddsHalfBinToWindow() {
        List<Target> targets = twoTargets(100.0, 200.0);
        List<MeasuredPeak> peaks = peaksOf(101.0);
        // 偏差 1 Hz；相对容差 0.5% 给 0.5 Hz 窗口 → 配不上
        assertThat(BipartiteMatcher.match(peaks, targets, 0.0, 0.005, 0.0).matchCount()).isZero();
        // 分辨率 1.5 Hz 增加 0.75 Hz 半窗 → 总半窗 1.25 Hz → 可配
        assertThat(BipartiteMatcher.match(peaks, targets, 0.0, 0.005, 1.5).matchCount()).isOne();
    }

    @Test
    @DisplayName("一根峰只能归一个目标、一个目标只认一根峰（峰数少于目标数）")
    void oneToOneAssignment() {
        List<Target> targets = List.of(
                new Target(BearingFamily.SHAFT, 1, 100.0, 100.0),
                new Target(BearingFamily.BPFO, 1, 100.0, 100.0),
                new Target(BearingFamily.BPFI, 1, 100.0, 100.0));
        // 三个目标频率完全重合，只有一根峰：恰好一对，目标各不相争同一根峰
        List<MeasuredPeak> peaks = peaksOf(100.0);
        MatchingResult result = BipartiteMatcher.match(peaks, targets, 0.0, 0.01, 0.0);
        assertThat(result.matchCount()).isOne();
        assertThat(result.matchedPeakIndices()).containsExactly(0);
        // 平局裁决确定：规范序最靠前的目标（shaft）拿到峰
        assertThat(result.assignments().get(0).target().family()).isEqualTo(BearingFamily.SHAFT);
    }

    @Test
    @DisplayName("峰重排后（规范下标重编号），配对数与总代价不变")
    void invariantToPeakOrder() {
        List<Target> targets = twoTargets(100.0, 105.0);
        List<MeasuredPeak> ordered = peaksOf(96.0, 102.0);
        List<MeasuredPeak> reversed = new ArrayList<>();
        reversed.add(new MeasuredPeak(0, 102.0, 1.0));
        reversed.add(new MeasuredPeak(1, 96.0, 1.0));

        MatchingResult a = BipartiteMatcher.match(ordered, targets, 0.0, 0.09, 0.0);
        MatchingResult b = BipartiteMatcher.match(reversed, targets, 0.0, 0.09, 0.0);
        assertThat(b.matchCount()).isEqualTo(a.matchCount());
        assertThat(b.totalSquaredRelativeDeviation())
                .isCloseTo(a.totalSquaredRelativeDeviation(), offset(1e-18));
        // 归到同一组频率：96 与 102 各被一根目标认领
        assertThat(b.assignments()).extracting(x -> x.peak().frequencyHz())
                .containsExactlyInAnyOrder(96.0, 102.0);
    }
}
