package com.bearingfreq.attribution;

import java.util.List;

/**
 * 打滑估计：打滑让四个轴承部位频率<b>统一</b>比理论值低同一个比例 s，
 * 转频及其整数倍不变。对归属到四个轴承族的配对，有
 * <pre>
 *   峰频率 = 理论目标 × (1 − s) × (1 + ε)，  ε 为相对残差
 * </pre>
 * 在最小二乘意义（Σε² 最小）下，s 的显式估计为
 * <pre>
 *   ŝ = 1 − Σ(峰频率·理论目标) / Σ(理论目标²)
 * </pre>
 * 即把各配对的「观测比例 r = 峰频率 / 理论目标」按理论目标²加权平均后取补。
 * 估计只使用归属到轴承部位的配对；转频族配对不参与（它们本就不打滑）。
 * 结果夹到调用方声明的允许范围 [slipMin, slipMax]；|ŝ| 小于零阈值时归零。
 */
final class SlipEstimator {

    /**
     * |打滑| 小于该阈值即归零。纯理论（或仅四舍五入到 Hz 第四位）的谱峰反推时，
     * 残差在 1e-8 量级纯属输入舍入，不应报成「有轻微打滑」；1e-8 远小于现场
     * 可辨识的打滑水平（默认窗口 2%），不会吞掉真实打滑。
     */
    static final double ZERO_SNAP = 1.0e-8;

    private SlipEstimator() {
    }

    /**
     * 用一次归属结果重新估计打滑系数。
     *
     * @param matching 归属结果（可为空配对）
     * @param slipMin  允许下限
     * @param slipMax  允许上限
     * @return 夹取并归零后的打滑系数；没有任何轴承部位配对时返回 null
     */
    static Double estimate(MatchingResult matching, double slipMin, double slipMax) {
        List<Assignment> bearingAssignments = matching.assignments().stream()
                .filter(a -> a.target().family().affectedBySlip())
                .toList();
        if (bearingAssignments.isEmpty()) {
            return null;
        }
        double weightedRatioNumerator = 0.0;
        double weightSum = 0.0;
        for (Assignment a : bearingAssignments) {
            double theoretical = a.target().theoreticalHz();
            double weight = theoretical * theoretical;
            weightedRatioNumerator += a.peak().frequencyHz() * theoretical;
            weightSum += weight;
        }
        double slip = 1.0 - weightedRatioNumerator / weightSum;
        return clampAndSnap(slip, slipMin, slipMax);
    }

    /** 夹到允许范围并做零值吸附。 */
    static double clampAndSnap(double slip, double slipMin, double slipMax) {
        if (Math.abs(slip) < ZERO_SNAP) {
            slip = 0.0;
        }
        return Math.min(slipMax, Math.max(slipMin, slip));
    }
}
