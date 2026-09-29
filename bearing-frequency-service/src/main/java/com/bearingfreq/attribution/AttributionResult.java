package com.bearingfreq.attribution;

import java.util.List;

/**
 * 「峰值归属 + 打滑估计」的完整结果。
 *
 * @param parameters                      本次请求的已校验参数（含默认值回显）
 * @param canonicalPeaks                  规范序（频率、幅值、原始下标）峰列表，
 *                                        {@code matching} 中的峰下标即指向此表
 * @param targets                         候选目标表（规范序）
 * @param matching                        最终一轮全局归属
 * @param slip                            最终打滑系数（锁死时即为锁死值）
 * @param iterations                      归属求解轮数（锁死时恒为 1）
 * @param converged                       是否收敛
 * @param terminationReason               终止/未收敛原因代码
 * @param terminationMessage              人类可读的终止说明
 * @param initialSlip                     迭代起点打滑系数（网格初值；锁死时为锁死值）
 */
public record AttributionResult(
        AttributionParameters parameters,
        List<MeasuredPeak> canonicalPeaks,
        List<Target> targets,
        MatchingResult matching,
        double slip,
        int iterations,
        boolean converged,
        String terminationReason,
        String terminationMessage,
        double initialSlip) {

    /** 终止原因代码（对外稳定标识）。 */
    public static final String REASON_LOCKED = "LOCKED_SLIP";
    public static final String REASON_CONVERGED = "CONVERGED";
    public static final String REASON_NO_BEARING_MATCHES = "NO_BEARING_MATCHES";
    public static final String REASON_OSCILLATING = "OSCILLATING_ASSIGNMENT";
    public static final String REASON_MAX_ITERATIONS = "MAX_ITERATIONS_REACHED";
}
