package com.bearingfreq.attribution;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 打滑迭代归属：把「按当前打滑修正目标 → 全局一对一归属 → 由轴承族配对重估打滑」
 * 反复迭代，直到归属方案与打滑系数同时稳定。
 *
 * <p><b>收敛判据</b>：第 r 轮归属后由配对重估得 s_{r+1}，若 |s_{r+1} − s_r| ≤
 * {@link #SLIP_TOLERANCE}（1e-6），视为不动点收敛，再以 s_{r+1} 做最后一次归属输出。
 *
 * <p><b>方案翻转</b>：若同一组配对（以「峰→目标」配对集合为签名）在不同打滑下
 * 重复出现，说明归属在两套方案之间来回：
 * <ul>
 *   <li>打滑差也在容差内 → 数值不动点，按收敛处理；</li>
 *   <li>否则为循环翻转，停止迭代，判未收敛，并在历轮中取「配对数最多、
 *       总相对偏差最小、轮次最早」的一轮返回，如实给出原因。</li>
 * </ul>
 *
 * <p><b>迭代上限</b>：{@link #MAX_ITERATIONS} 轮仍未稳定则判未收敛，
 * 返回最后一轮结果并注明原因，绝不静默给出看似正常的答案。
 *
 * <p><b>打滑锁死</b>：调用方给定固定 s 时只做一次归属，iterations = 1，按收敛返回。
 */
public final class SlipAttributionEngine {

    /** 打滑系数不动点容差（绝对）。 */
    public static final double SLIP_TOLERANCE = 1e-6;

    /** 迭代轮数上限。 */
    public static final int MAX_ITERATIONS = 30;

    private SlipAttributionEngine() {
    }

    /**
     * 执行迭代归属。
     *
     * @param peaks             实测峰
     * @param spectrum          候选目标谱（理论值缓存）
     * @param relativeTolerance 匹配相对容差
     * @param resolutionHz      频谱分辨率（Hz）
     * @param minSlip           打滑系数允许下界
     * @param maxSlip           打滑系数允许上界
     * @param lockedSlip        锁死的打滑系数；null 表示自由估计
     */
    public static EngineOutcome run(List<MeasuredPeak> peaks, TargetSpectrum spectrum,
                                    double relativeTolerance, double resolutionHz,
                                    double minSlip, double maxSlip, Double lockedSlip) {
        if (lockedSlip != null) {
            double s = lockedSlip;
            AttributionResult only = PeakAssigner.assign(peaks, spectrum.atSlip(s),
                    relativeTolerance, resolutionHz, s);
            return new EngineOutcome(only, s, 1, true, "打滑系数已锁死为 " + s
                    + "，只执行一次归属，不迭代。", false);
        }

        double slip = clampInitial(minSlip, maxSlip);
        Map<String, Double> seenSignatures = new LinkedHashMap<>();
        List<AttributionResult> rounds = new ArrayList<>();

        AttributionResult result = null;
        boolean slipAtBound = false;

        for (int round = 1; round <= MAX_ITERATIONS; round++) {
            result = PeakAssigner.assign(peaks, spectrum.atSlip(slip),
                    relativeTolerance, resolutionHz, slip);
            rounds.add(result);

            double estimated = SlipEstimator.estimate(result.assignments(), minSlip, maxSlip);
            if (Double.isNaN(estimated)) {
                // 没有任何轴承族配对：打滑无从估计，归属本身已确定。
                return new EngineOutcome(result, slip, round, true,
                        "没有任何峰归属到轴承部位，打滑系数不可观测，保留起步值 "
                                + slip + "。", slip == minSlip || slip == maxSlip);
            }
            slipAtBound = estimated == minSlip || estimated == maxSlip;

            String signature = signatureOf(result);
            if (seenSignatures.containsKey(signature)) {
                double previousSlip = seenSignatures.get(signature);
                if (Math.abs(estimated - previousSlip) <= SLIP_TOLERANCE) {
                    AttributionResult finalResult = reassign(peaks, spectrum,
                            relativeTolerance, resolutionHz, estimated);
                    String suffix = slipAtBound
                            ? "（打滑估计触及允许范围边界，请核对范围与实测峰）"
                            : "";
                    return new EngineOutcome(finalResult, estimated, round, true,
                            "归属方案与打滑系数在容差 " + SLIP_TOLERANCE
                                    + " 内稳定，收敛（不动点）。" + suffix, slipAtBound);
                }
                AttributionResult best = bestOf(rounds);
                return new EngineOutcome(best, best.slip(), round, false,
                        "归属在两套配对方案之间来回翻转（第 "
                                + firstRoundOf(rounds, signature)
                                + " 轮的方案在第 " + round + " 轮重现但打滑系数由 "
                                + previousSlip + " 变为 " + estimated
                                + "），未收敛；已返回配对数最多、总相对偏差最小的一轮。",
                        best.slip() == minSlip || best.slip() == maxSlip);
            }
            seenSignatures.put(signature, estimated);

            if (Math.abs(estimated - slip) <= SLIP_TOLERANCE) {
                AttributionResult finalResult = reassign(peaks, spectrum,
                        relativeTolerance, resolutionHz, estimated);
                String suffix = slipAtBound
                        ? "（打滑估计触及允许范围边界，请核对范围与实测峰）"
                        : "";
                return new EngineOutcome(finalResult, estimated, round, true,
                        "打滑系数相邻轮变化不超过 " + SLIP_TOLERANCE + "，已收敛。" + suffix,
                        slipAtBound);
            }
            slip = estimated;
        }

        // 以最后一次重估的打滑补做一次归属，保证回传方案与打滑系数同源。
        AttributionResult finalResult = reassign(peaks, spectrum,
                relativeTolerance, resolutionHz, slip);
        return new EngineOutcome(finalResult, slip, MAX_ITERATIONS, false,
                "迭代 " + MAX_ITERATIONS + " 轮后打滑系数仍未稳定（最后重估值 s = "
                        + slip + "），未收敛；已返回该打滑下的归属结果。",
                slip == minSlip || slip == maxSlip);
    }

    private static double clampInitial(double minSlip, double maxSlip) {
        double start = 0.0;
        if (start < minSlip) {
            return minSlip;
        }
        if (start > maxSlip) {
            return maxSlip;
        }
        return start;
    }

    private static AttributionResult reassign(List<MeasuredPeak> peaks, TargetSpectrum spectrum,
                                              double relativeTolerance, double resolutionHz,
                                              double slip) {
        return PeakAssigner.assign(peaks, spectrum.atSlip(slip),
                relativeTolerance, resolutionHz, slip);
    }

    /** 配对方案签名：峰原始下标 →（族, 阶），按峰下标排序后拼接。 */
    private static String signatureOf(AttributionResult result) {
        List<Assignment> byPeak = new ArrayList<>(result.assignments());
        byPeak.sort(java.util.Comparator.comparingInt(Assignment::peakIndex));
        StringBuilder sb = new StringBuilder();
        for (Assignment a : byPeak) {
            sb.append(a.peakIndex()).append(':')
                    .append(a.family().getId()).append(a.order()).append(';');
        }
        return sb.toString();
    }

    /** 历轮中按「配对数最多 → 总相对偏差最小 → 轮次最早」取最优一轮。 */
    private static AttributionResult bestOf(List<AttributionResult> rounds) {
        AttributionResult best = rounds.get(0);
        for (AttributionResult candidate : rounds) {
            if (candidate.matchedCount() > best.matchedCount()
                    || (candidate.matchedCount() == best.matchedCount()
                    && candidate.totalAbsRelativeDeviation() < best.totalAbsRelativeDeviation()
                    - 1e-12)) {
                best = candidate;
            }
        }
        return best;
    }

    private static int firstRoundOf(List<AttributionResult> rounds, String signature) {
        for (int i = 0; i < rounds.size(); i++) {
            if (signatureOf(rounds.get(i)).equals(signature)) {
                return i + 1;
            }
        }
        return rounds.size();
    }
}
