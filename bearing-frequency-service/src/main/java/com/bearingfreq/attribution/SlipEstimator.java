package com.bearingfreq.attribution;

import java.util.List;

/**
 * 打滑系数估计：由归属到四个轴承族的配对，反推使理论目标与实测峰对齐的统一比例 s。
 *
 * <p>打滑模型：轴承族实测频率 = 理论频率 × (1 − s)，同一根轴承的四个族共享同一个 s；
 * 转频族不打滑，不参与估计。由每根配对解出 {@code s_i = 1 − 峰频/理论频率}，
 * 对全部轴承族配对取等权平均：
 *
 * <ul>
 *   <li><b>等权（不按幅值、不按阶次加权）</b>：幅值受测点与工况影响，权重会引入与
 *       打滑无关的偏置；相对偏差已在配对代价里归一，高阶目标不因绝对偏差大而被放大。</li>
 *   <li>估计值夹到调用方声明的允许范围 [minSlip, maxSlip] 内；触及边界时由调用方标记。</li>
 * </ul>
 *
 * <p>没有任何轴承族配对时打滑不可观测，返回 {@link Double#NaN}。
 */
public final class SlipEstimator {

    private SlipEstimator() {
    }

    /**
     * @param assignments 一轮归属的全部配对
     * @param minSlip     允许的打滑系数下界
     * @param maxSlip     允许的打滑系数上界
     * @return 夹到允许范围内的打滑系数；无轴承族配对时为 NaN
     */
    public static double estimate(List<Assignment> assignments, double minSlip, double maxSlip) {
        double sum = 0.0;
        int count = 0;
        for (Assignment a : assignments) {
            if (a.family().isSlipAffected()) {
                sum += 1.0 - a.peakFrequencyHz() / a.theoreticalTargetHz();
                count++;
            }
        }
        if (count == 0) {
            return Double.NaN;
        }
        double slip = sum / count;
        if (slip < minSlip) {
            return minSlip;
        }
        if (slip > maxSlip) {
            return maxSlip;
        }
        return slip;
    }
}
