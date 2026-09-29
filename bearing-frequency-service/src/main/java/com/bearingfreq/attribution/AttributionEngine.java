package com.bearingfreq.attribution;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 归属—打滑迭代控制：
 * <pre>
 *   按当前打滑修正目标 → 全局归属（{@link BipartiteMatcher}）
 *   → 用轴承部位配对重估打滑（{@link SlipEstimator}）→ 再归属 …
 * </pre>
 * 直到打滑系数稳定（归属不再有实质变化）或触发终止条件。
 *
 * <h3>初值</h3>
 * <p>直接从 0 起步在真实打滑较大时会「开局一根轴承峰都认不到」，从而再也估不出打滑。
 * 因此自由估计时先在允许范围 [slipMin, slipMax] 上做 21 点等距网格预扫：
 * 每个打滑点各做一次全局归属，按（轴承部位配对数多者优先 → 总代价小者优先 →
 * 打滑值小者优先）挑出起点，再进入精修迭代。网格只是初值策略，不改变优化口径。
 *
 * <h3>收敛与终止</h3>
 * <ul>
 *   <li><b>收敛</b>：相邻两轮打滑系数差 ≤ {@link #SLIP_CONVERGENCE_EPS}（1e-3，
 *       口径见该常量说明），或归属方案重复出现且打滑只在该带内抖动。</li>
 *   <li><b>锁死</b>：调用方给定打滑值时只做一次归属，不初扫、不迭代。</li>
 *   <li><b>无轴承配对</b>：网格各点（或锁死的一轮）下都没有任何轴承部位配对，
 *       打滑无从估计——不收敛并说明原因，仍返回该轮归属。</li>
 *   <li><b>翻转</b>：归属方案在两种（或多种）配对间来回出现（同一配对签名
 *       再次出现而打滑差超出稳定带）时停止，取历次访问过的方案中按
 *       （配对数多 → 总代价小 → 打滑小）规范裁决的最优一轮返回，标记未收敛。</li>
 *   <li><b>迭代上限</b>：精修最多 {@link #MAX_REFINE_ITERATIONS} 轮，
 *       超限则如实返回最后一轮并标记未收敛。</li>
 * </ul>
 *
 * <p>{@code iterations} 只计精修轮数（网格预扫固定 21 次匹配，作为初值策略
 * 不计入迭代）；锁死时恒为 1。
 */
final class AttributionEngine {

    /**
     * 打滑稳定阈值：相邻两轮 |Δs| ≤ 该值即视为已收敛。
     *
     * <p>打滑由归属残差反推，而归属方案是离散的（峰—目标配对只在整数集合间跳变）。
     * 配对固定后打滑还会在 1e-4 量级的噪声平台上微调、并可能与同一组配对交替出现，
     * 这是「离散归属 + 连续打滑」模型的正常定点行为，不是翻转。阈值取 1e-3：
     * 它是声明反推精度（见 README）的数倍余量，远小于默认匹配窗口（2%），
     * 落在带内的轮间差异已不改变任何归属结论。真正的翻转（打滑在两个相距明显的
     * 值之间带着配对来回跳）仍按未收敛处理并如实返回。
     */
    static final double SLIP_CONVERGENCE_EPS = 1.0e-3;

    /** 精修迭代轮数上限（含起点轮）。 */
    static final int MAX_REFINE_ITERATIONS = 30;

    /** 网格预扫点数（含两端）。 */
    private static final int GRID_POINTS = 21;

    private AttributionEngine() {
    }

    /** 一次迭代中访问过的状态（用于翻转检测与最优一轮回退）。 */
    private record VisitedState(double slip, MatchingResult matching) {
    }

    static AttributionResult run(AttributionParameters params) {
        List<MeasuredPeak> peaks = canonicalPeaks(params.peaks());
        List<Target> targets = TargetTable.build(
                params.rotationFrequencyHz(), params.geometry(), params.maxOrder());

        if (params.lockedSlip() != null) {
            MatchingResult matching = BipartiteMatcher.match(peaks, targets, params.lockedSlip(),
                    params.relativeTolerance(), params.frequencyResolutionHz());
            String message = "打滑系数已锁死为 " + params.lockedSlip()
                    + "，只做一次全局归属，不进行迭代；共 " + matching.matchCount() + " 对配对";
            return new AttributionResult(params, peaks, targets, matching, params.lockedSlip(), 1,
                    true, AttributionResult.REASON_LOCKED, message, params.lockedSlip());
        }

        // ---- 网格预扫，确定迭代起点 ----
        VisitedState start = gridInitialState(peaks, targets, params);
        if (start.matching().bearingMatchCount() == 0) {
            String message = "在打滑允许范围 [" + params.slipMin() + ", " + params.slipMax()
                    + "] 内的网格预扫中没有任何峰能配到四个轴承部位，"
                    + "打滑系数无从估计；返回配对数最多（仅转频族）的一轮归属，"
                    + "打滑保持预扫最优值 " + start.slip();
            return new AttributionResult(params, peaks, targets, start.matching(), start.slip(),
                    0, false, AttributionResult.REASON_NO_BEARING_MATCHES,
                    message, start.slip());
        }

        // ---- 精修迭代 ----
        double currentSlip = start.slip();
        MatchingResult current = start.matching();

        // 签名 → 该状态；同一归属方案再次出现即构成翻转环
        Map<List<Long>, VisitedState> history = new LinkedHashMap<>();
        List<VisitedState> visitedInOrder = new ArrayList<>();
        history.put(signature(current), start);
        visitedInOrder.add(start);

        int iterations = 1;
        while (true) {
            Double estimated = SlipEstimator.estimate(current, params.slipMin(), params.slipMax());
            if (estimated == null) {
                // 起点有轴承配对、途中反而全部丢失：按无轴承配对如实返回。
                VisitedState best = bestVisited(visitedInOrder);
                String message = "迭代第 " + iterations + " 轮后轴承部位配对全部丢失，"
                        + "打滑系数无法继续估计；返回历次中配对最多、总代价最小的一轮";
                return new AttributionResult(params, peaks, targets, best.matching(), best.slip(),
                        iterations, false, AttributionResult.REASON_NO_BEARING_MATCHES,
                        message, start.slip());
            }

            if (Math.abs(estimated - currentSlip) <= SLIP_CONVERGENCE_EPS) {
                // 打滑已稳定：以稳定值重拟一次，保证「打滑—归属」自洽。
                currentSlip = estimated;
                current = BipartiteMatcher.match(peaks, targets, currentSlip,
                        params.relativeTolerance(), params.frequencyResolutionHz());
                iterations++;
                String message = "打滑系数在第 " + iterations
                        + " 轮收敛（相邻轮差 ≤ " + SLIP_CONVERGENCE_EPS + "），最终打滑 "
                        + roundForMessage(currentSlip);
                return new AttributionResult(params, peaks, targets, current, currentSlip, iterations,
                        true, AttributionResult.REASON_CONVERGED, message, start.slip());
            }

            if (iterations >= MAX_REFINE_ITERATIONS) {
                String message = "迭代 " + iterations + " 轮后打滑系数仍未稳定（最后两轮差 "
                        + Math.abs(estimated - currentSlip) + " > " + SLIP_CONVERGENCE_EPS
                        + "）；如实返回最后一轮归属，打滑 " + roundForMessage(currentSlip);
                return new AttributionResult(params, peaks, targets, current, currentSlip, iterations,
                        false, AttributionResult.REASON_MAX_ITERATIONS, message, start.slip());
            }

            currentSlip = estimated;
            current = BipartiteMatcher.match(peaks, targets, currentSlip,
                    params.relativeTolerance(), params.frequencyResolutionHz());
            iterations++;

            List<Long> sig = signature(current);
            VisitedState previousSame = history.get(sig);
            if (previousSame != null) {
                // 同一归属方案再次出现。打滑还在收敛阈值内微调时（离散配对不动、
                // 打滑在噪声平台上抖动）视为收敛；只有打滑差值明显（配对在两个方案
                // 间来回、打滑也跟着跳）才算翻转。
                if (Math.abs(currentSlip - previousSame.slip()) <= SLIP_CONVERGENCE_EPS) {
                    String message = "归属方案已稳定，打滑系数在第 " + iterations
                            + " 轮收敛（配对不再变化，相邻轮差 ≤ " + SLIP_CONVERGENCE_EPS
                            + "），最终打滑 " + roundForMessage(currentSlip);
                    return new AttributionResult(params, peaks, targets, current, currentSlip, iterations,
                            true, AttributionResult.REASON_CONVERGED, message, start.slip());
                }
                VisitedState best = bestVisited(visitedInOrder);
                String message = "第 " + iterations + " 轮的归属方案与打滑为 "
                        + roundForMessage(previousSame.slip())
                        + " 的那一轮完全相同（打滑在 "
                        + roundForMessage(previousSame.slip()) + " 与 "
                        + roundForMessage(currentSlip)
                        + " 之间来回翻转），停止迭代；取历次访问方案中配对最多、"
                        + "总代价最小、打滑最小的一轮返回";
                return new AttributionResult(params, peaks, targets, best.matching(), best.slip(),
                        iterations, false, AttributionResult.REASON_OSCILLATING,
                        message, start.slip());
            }
            history.put(sig, new VisitedState(currentSlip, current));
            visitedInOrder.add(new VisitedState(currentSlip, current));
        }
    }

    /** 网格预扫：在允许范围等距取点，各自全局归属后规范择优作为迭代起点。 */
    private static VisitedState gridInitialState(List<MeasuredPeak> peaks, List<Target> targets,
                                                 AttributionParameters params) {
        VisitedState best = null;
        for (int i = 0; i < GRID_POINTS; i++) {
            double slip = params.slipMin()
                    + (params.slipMax() - params.slipMin()) * i / (GRID_POINTS - 1);
            slip = SlipEstimator.clampAndSnap(slip, params.slipMin(), params.slipMax());
            MatchingResult matching = BipartiteMatcher.match(peaks, targets, slip,
                    params.relativeTolerance(), params.frequencyResolutionHz());
            VisitedState candidate = new VisitedState(slip, matching);
            if (best == null || GridKey.of(candidate).compareTo(GridKey.of(best)) < 0) {
                best = candidate;
            }
        }
        return best;
    }

    /**
     * 网格择优口径（字典序，升序为优）：
     * 轴承配对数多（存负数）→ 总代价小 → 打滑小。
     */
    private record GridKey(long negativeBearingMatches, double totalCost, double slip)
            implements Comparable<GridKey> {

        static GridKey of(VisitedState state) {
            return new GridKey(-state.matching().bearingMatchCount(),
                    state.matching().totalSquaredRelativeDeviation(), state.slip());
        }

        @Override
        public int compareTo(GridKey o) {
            int c = Long.compare(negativeBearingMatches, o.negativeBearingMatches);
            if (c != 0) {
                return c;
            }
            c = Double.compare(totalCost, o.totalCost);
            if (c != 0) {
                return c;
            }
            return Double.compare(slip, o.slip);
        }
    }

    /** 历次访问状态中规范择优：总配对数多 → 总代价小 → 打滑小。 */
    private static VisitedState bestVisited(List<VisitedState> visited) {
        return visited.stream().min(Comparator
                .comparingLong((VisitedState s) -> -s.matching().matchCount())
                .thenComparingDouble(s -> s.matching().totalSquaredRelativeDeviation())
                .thenComparingDouble(VisitedState::slip))
                .orElseThrow();
    }

    /**
     * 配对方案签名：按目标规范序映射到峰规范序下标。
     * 签名相同即「同一组归属」，与打滑取值无关。
     */
    private static List<Long> signature(MatchingResult matching) {
        List<Long> pairs = new ArrayList<>(matching.assignments().size());
        List<Assignment> byTarget = new ArrayList<>(matching.assignments());
        byTarget.sort(Comparator
                .comparingInt((Assignment a) -> a.target().family().ordinal())
                .thenComparingInt(a -> a.target().order()));
        for (Assignment a : byTarget) {
            pairs.add((long) a.target().family().ordinal() * 1_000_000L
                    + a.target().order() * 1_000L + a.peak().index());
        }
        return List.copyOf(pairs);
    }

    /** 峰的规范序：频率升序 → 幅值升序 → 原始下标升序（打乱提交顺序不影响结果）。 */
    private static List<MeasuredPeak> canonicalPeaks(List<MeasuredPeak> submitted) {
        List<MeasuredPeak> sorted = new ArrayList<>(submitted);
        sorted.sort(Comparator
                .comparingDouble(MeasuredPeak::frequencyHz)
                .thenComparingDouble(MeasuredPeak::amplitude)
                .thenComparingInt(MeasuredPeak::index));
        return List.copyOf(sorted);
    }

    private static String roundForMessage(double slip) {
        return String.format("%.6g", slip);
    }
}
