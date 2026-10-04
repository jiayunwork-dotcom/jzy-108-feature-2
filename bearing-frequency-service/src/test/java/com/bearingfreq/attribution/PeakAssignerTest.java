package com.bearingfreq.attribution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 配对求解器单元测试：与暴力枚举的全局最优逐一对照，并钉住反贪心、
 * 超窗不配、平局确定性与提交顺序无关性。
 */
class PeakAssignerTest {

    private static CandidateTarget target(FrequencyFamily family, int order, double correctedHz) {
        return new CandidateTarget(family, order, correctedHz, correctedHz);
    }

    private static MeasuredPeak peak(double hz) {
        return new MeasuredPeak(hz, 1.0);
    }

    private static List<CandidateTarget> targets(double... hz) {
        List<CandidateTarget> list = new ArrayList<>();
        int k = 1;
        for (double h : hz) {
            list.add(target(FrequencyFamily.SHAFT, k++, h));
        }
        return list;
    }

    /**
     * 暴力枚举全部可行一对一配对，求「配对数最多、总相对偏差最小」的最优值。
     * 仅用于小规模随机对拍。
     */
    private static double bruteForceBest(List<MeasuredPeak> peaks,
                                         List<CandidateTarget> candidates,
                                         double tolerance, double resolutionHz) {
        int p = peaks.size();
        int t = candidates.size();
        boolean[][] legal = new boolean[p][t];
        double[][] rel = new double[p][t];
        for (int i = 0; i < p; i++) {
            for (int j = 0; j < t; j++) {
                double window = tolerance * candidates.get(j).correctedHz() + resolutionHz;
                legal[i][j] = Math.abs(peaks.get(i).frequencyHz()
                        - candidates.get(j).correctedHz()) <= window;
                rel[i][j] = Math.abs(peaks.get(i).frequencyHz()
                        - candidates.get(j).correctedHz()) / candidates.get(j).correctedHz();
            }
        }
        int[] bestCount = {-1};
        double[] bestCost = {Double.POSITIVE_INFINITY};
        search(legal, rel, new boolean[t], 0, 0, 0.0, bestCount, bestCost);
        return bestCost[0];
    }

    private static void search(boolean[][] legal, double[][] rel, boolean[] usedTarget,
                               int peak, int count, double cost,
                               int[] bestCount, double[] bestCost) {
        if (peak == legal.length) {
            if (count > bestCount[0] || (count == bestCount[0] && cost < bestCost[0])) {
                bestCount[0] = count;
                bestCost[0] = cost;
            }
            return;
        }
        // 当前峰不归属。
        search(legal, rel, usedTarget, peak + 1, count, cost, bestCount, bestCost);
        for (int j = 0; j < usedTarget.length; j++) {
            if (!usedTarget[j] && legal[peak][j]) {
                usedTarget[j] = true;
                search(legal, rel, usedTarget, peak + 1, count + 1, cost + rel[peak][j],
                        bestCount, bestCost);
                usedTarget[j] = false;
            }
        }
    }

    @Test
    @DisplayName("随机小例对拍：求解器与暴力枚举的配对数、总相对偏差一致")
    void matchesBruteForceOnRandomCases() {
        java.util.Random rnd = new java.util.Random(424242);
        for (int scenario = 0; scenario < 500; scenario++) {
            int targetCount = 1 + rnd.nextInt(4);
            int peakCount = 1 + rnd.nextInt(4);
            List<CandidateTarget> candidateList = new ArrayList<>();
            double cursor = 50.0;
            for (int j = 0; j < targetCount; j++) {
                cursor += 10 + rnd.nextInt(40);
                candidateList.add(target(FrequencyFamily.SHAFT, j + 1, cursor));
            }
            List<MeasuredPeak> peakList = new ArrayList<>();
            for (int i = 0; i < peakCount; i++) {
                double anchor = candidateList.get(rnd.nextInt(targetCount)).correctedHz();
                double jitter = (rnd.nextDouble() - 0.5) * 0.12 * anchor;
                peakList.add(peak(anchor + jitter));
            }
            double tolerance = 0.01 + rnd.nextDouble() * 0.04;
            double resolution = rnd.nextBoolean() ? 0.0 : rnd.nextDouble() * 2.0;

            AttributionResult result = PeakAssigner.assign(peakList, candidateList,
                    tolerance, resolution, 0.0);
            double brute = bruteForceBest(peakList, candidateList, tolerance, resolution);

            assertThat(result.totalAbsRelativeDeviation())
                    .as("scenario %d 总相对偏差应对齐暴力最优（允许 1e-6 级量化差）", scenario)
                    .isCloseTo(brute, within(1e-5));
            // 配对数也必须达到最优（暴力最优本身是字典序口径）。
            int[] counter = new int[1];
            bruteForceCount(peakList, candidateList, tolerance, resolution, counter);
            assertThat(result.matchedCount()).isEqualTo(counter[0]);
        }
    }

    private static void bruteForceCount(List<MeasuredPeak> peaks,
                                        List<CandidateTarget> candidateList,
                                        double tolerance, double resolutionHz,
                                        int[] counter) {
        // 复用 bruteForceBest 的配对数（bestCount 内联），这里直接重算一遍取计数。
        int p = peaks.size();
        int t = candidateList.size();
        boolean[][] legal = new boolean[p][t];
        for (int i = 0; i < p; i++) {
            for (int j = 0; j < t; j++) {
                double window = tolerance * candidateList.get(j).correctedHz() + resolutionHz;
                legal[i][j] = Math.abs(peaks.get(i).frequencyHz()
                        - candidateList.get(j).correctedHz()) <= window;
            }
        }
        searchCount(legal, new boolean[t], 0, 0, counter);
    }

    private static void searchCount(boolean[][] legal, boolean[] used,
                                    int peak, int count, int[] counter) {
        if (peak == legal.length) {
            counter[0] = Math.max(counter[0], count);
            return;
        }
        searchCount(legal, used, peak + 1, count, counter);
        for (int j = 0; j < used.length; j++) {
            if (!used[j] && legal[peak][j]) {
                used[j] = true;
                searchCount(legal, used, peak + 1, count + 1, counter);
                used[j] = false;
            }
        }
    }

    @Test
    @DisplayName("反贪心：逐个就近认领更差时，求解器给出全局总偏差更小的配对")
    void beatsNearestFirstGreedy() {
        // 两个目标 A=98.5（2% 窗 [96.53, 100.47]）、B=96.0（2% 窗 [94.08, 97.92]）；
        // 两根峰 p1=97.0、p2=96.0，两峰对两目标都在窗内。
        List<CandidateTarget> candidateList = List.of(
                target(FrequencyFamily.SHAFT, 1, 98.5),
                target(FrequencyFamily.SHAFT, 2, 96.0));
        List<MeasuredPeak> peakList = List.of(peak(97.0), peak(96.0));

        AttributionResult global = PeakAssigner.assign(peakList, candidateList,
                0.02, 0.0, 0.0);

        // 「谁离得近谁先拿」按 p1 先处理：p1 离 B 差 1.0（离 A 差 1.5）先占 B；
        // p2 只能改认 A（差 2.5）。配 2 对，总绝对偏差 3.5。
        double greedyIfP1GrabsNearest = Math.abs(97.0 - 96.0) + Math.abs(96.0 - 98.5);
        assertThat(greedyIfP1GrabsNearest).isEqualTo(3.5, within(1e-12));

        // 全局最优：p2→B（0），p1→A（1.5），总绝对偏差 1.5，严格更小。
        assertThat(global.matchedCount()).isEqualTo(2);
        assertThat(global.totalAbsDeviationHz()).isCloseTo(1.5, within(1e-9));
        assertThat(global.totalAbsDeviationHz()).isLessThan(greedyIfP1GrabsNearest - 1e-9);
    }

    @Test
    @DisplayName("超出匹配窗口的峰不允许配对，标记为未归属")
    void peaksOutsideWindowStayUnassigned() {
        List<CandidateTarget> candidateList = targets(100.0, 200.0);
        List<MeasuredPeak> peakList = List.of(peak(100.0), peak(150.0), peak(500.0));

        AttributionResult result = PeakAssigner.assign(peakList, candidateList,
                0.02, 0.0, 0.0);

        assertThat(result.matchedCount()).isEqualTo(1);
        assertThat(result.unassignedPeakIndexes()).containsExactly(1, 2);
        assertThat(result.assignments().get(0).peakIndex()).isZero();
    }

    @Test
    @DisplayName("分辨率放宽窗口：窗半宽 = 相对容差×目标 + 分辨率")
    void resolutionWidensWindow() {
        List<CandidateTarget> candidateList = targets(100.0);
        // 偏差 3 Hz：相对容差窗 2 Hz 不配，加 1.5 Hz 分辨率窗后可配。
        AttributionResult without = PeakAssigner.assign(List.of(peak(103.0)),
                candidateList, 0.02, 0.0, 0.0);
        AttributionResult with = PeakAssigner.assign(List.of(peak(103.0)),
                candidateList, 0.02, 1.5, 0.0);

        assertThat(without.matchedCount()).isZero();
        assertThat(with.matchedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("一对一：一根峰不能占两个目标，一个目标也不能认两根峰")
    void oneToOneIsEnforced() {
        // 两根峰都只在同一个目标窗口内。
        List<CandidateTarget> candidateList = targets(100.0);
        List<MeasuredPeak> peakList = List.of(peak(100.1), peak(99.9));

        AttributionResult result = PeakAssigner.assign(peakList, candidateList,
                0.02, 0.0, 0.0);

        assertThat(result.matchedCount()).isEqualTo(1);
        assertThat(result.unassignedPeakIndexes()).hasSize(1);
    }

    @Test
    @DisplayName("平局裁决确定：同频目标（族序在先者）获胜，且重复调用一致")
    void tiesAreResolvedDeterministically() {
        // 两个修正频率完全相同的目标：代价一致，族序 shaft 在 bpfi 之前，故 shaft 获胜。
        List<CandidateTarget> candidateList = List.of(
                target(FrequencyFamily.SHAFT, 4, 100.0),
                target(FrequencyFamily.BPFI, 2, 100.0));
        AttributionResult first = PeakAssigner.assign(List.of(peak(100.0)),
                candidateList, 0.02, 0.0, 0.0);
        AttributionResult second = PeakAssigner.assign(List.of(peak(100.0)),
                candidateList, 0.02, 0.0, 0.0);

        assertThat(first.assignments()).hasSize(1);
        assertThat(first.assignments().get(0).family()).isEqualTo(FrequencyFamily.SHAFT);
        assertThat(first.assignments().get(0).order()).isEqualTo(4);
        assertThat(second).usingRecursiveComparison().isEqualTo(first);
    }

    @Test
    @DisplayName("提交顺序打乱：归属逐字段相同，未归属下标按原始位置报告")
    void resultIsIndependentOfSubmissionOrder() {
        List<CandidateTarget> candidateList = List.of(
                target(FrequencyFamily.SHAFT, 1, 100.0),
                target(FrequencyFamily.BPFO, 1, 230.0));
        List<MeasuredPeak> ordered = List.of(
                peak(100.05), peak(33.3), peak(229.8), peak(777.0));
        List<MeasuredPeak> shuffled = Arrays.asList(
                peak(777.0), peak(229.8), peak(33.3), peak(100.05));

        AttributionResult a = PeakAssigner.assign(ordered, candidateList, 0.02, 0.0, 0.01);
        AttributionResult b = PeakAssigner.assign(shuffled, candidateList, 0.02, 0.0, 0.01);

        // 未归属的是同样两根物理峰（33.3、777），原始下标随提交位置变化。
        assertThat(b.unassignedPeakIndexes().stream().map(i -> shuffled.get(i).frequencyHz()).sorted().toList())
                .isEqualTo(a.unassignedPeakIndexes().stream().map(i -> ordered.get(i).frequencyHz()).sorted().toList());
        assertThat(b.matchedCount()).isEqualTo(a.matchedCount());
        assertThat(b.totalAbsDeviationHz()).isCloseTo(a.totalAbsDeviationHz(), within(1e-12));
        // 同一根物理峰（按频率识别）归到同一族同一阶。
        assertThat(a.assignments()).hasSize(2);
        for (Assignment aa : a.assignments()) {
            Assignment bb = b.assignments().stream()
                    .filter(x -> Math.abs(x.peakFrequencyHz() - aa.peakFrequencyHz()) < 1e-9)
                    .findFirst().orElseThrow();
            assertThat(bb.family()).isEqualTo(aa.family());
            assertThat(bb.order()).isEqualTo(aa.order());
            assertThat(bb.deviationHz()).isCloseTo(aa.deviationHz(), within(1e-12));
        }
        // 原始下标报告：100.05 在有序表为 0、在乱序表为 3。
        Assignment fromOrdered = a.assignments().stream()
                .filter(x -> x.peakFrequencyHz() == 100.05).findFirst().orElseThrow();
        Assignment fromShuffled = b.assignments().stream()
                .filter(x -> x.peakFrequencyHz() == 100.05).findFirst().orElseThrow();
        assertThat(fromOrdered.peakIndex()).isZero();
        assertThat(fromShuffled.peakIndex()).isEqualTo(3);
    }

    @Test
    @DisplayName("同频峰按幅值、原始下标稳定排序，结果仍确定")
    void equalFrequencyPeaksAreHandledDeterministically() {
        List<CandidateTarget> candidateList = targets(100.0);
        List<MeasuredPeak> p1 = List.of(new MeasuredPeak(100.0, 1.0), new MeasuredPeak(100.0, 2.0));
        List<MeasuredPeak> p2 = List.of(new MeasuredPeak(100.0, 2.0), new MeasuredPeak(100.0, 1.0));

        AttributionResult a = PeakAssigner.assign(p1, candidateList, 0.02, 0.0, 0.0);
        AttributionResult b = PeakAssigner.assign(p2, candidateList, 0.02, 0.0, 0.0);

        // 幅值更大（规范化序更靠前）的峰拿到唯一目标：p1 中是下标 1，p2 中是下标 0。
        assertThat(a.assignments().get(0).peakIndex()).isEqualTo(1);
        assertThat(b.assignments().get(0).peakIndex()).isZero();
        assertThat(a.assignments().get(0).amplitude()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("偏差字段为带符号值：峰高于目标为正，低于目标为负")
    void deviationsAreSigned() {
        AttributionResult high = PeakAssigner.assign(List.of(peak(100.5)),
                targets(100.0), 0.02, 0.0, 0.0);
        AttributionResult low = PeakAssigner.assign(List.of(peak(99.5)),
                targets(100.0), 0.02, 0.0, 0.0);

        assertThat(high.assignments().get(0).deviationHz()).isCloseTo(0.5, within(1e-12));
        assertThat(high.assignments().get(0).relativeDeviation()).isCloseTo(0.005, within(1e-12));
        assertThat(low.assignments().get(0).deviationHz()).isCloseTo(-0.5, within(1e-12));
        assertThat(low.assignments().get(0).relativeDeviation()).isCloseTo(-0.005, within(1e-12));
    }
}
