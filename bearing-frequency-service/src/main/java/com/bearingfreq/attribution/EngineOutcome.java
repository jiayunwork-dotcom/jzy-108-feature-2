package com.bearingfreq.attribution;

/**
 * 迭代归属的整体结果。
 *
 * @param result      最终返回的归属方案（收敛时为收敛方案；未收敛时为按既定规则选出的方案）
 * @param slip        最终打滑系数（锁死时即锁死值；不可观测时为起步值）
 * @param iterations  实际执行的归属轮数（锁死时为 1）
 * @param converged   是否收敛
 * @param reason      收敛/未收敛的具体原因（如实说明，未收敛不静默）
 * @param slipAtBound 打滑估计是否触及允许范围边界
 */
public record EngineOutcome(AttributionResult result,
                            double slip,
                            int iterations,
                            boolean converged,
                            String reason,
                            boolean slipAtBound) {
}
