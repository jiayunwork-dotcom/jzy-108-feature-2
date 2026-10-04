package com.bearingfreq.api.dto;

import com.bearingfreq.attribution.EngineOutcome;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 峰值归属 + 打滑估计响应。
 *
 * @param bearingName                引用的轴承档名（内联几何时不出现该字段）
 * @param rotationFrequencyHz        解析出的转频（Hz）
 * @param slipCoefficient            最终打滑系数 s（轴承族频率 = 理论值 ×(1 − s)）
 * @param converged                  是否收敛（锁死时恒为 true）
 * @param iterations                 实际归属轮数（锁死时为 1）
 * @param slipAtAllowedBound         打滑估计是否触及允许范围边界
 * @param statusReason               收敛/未收敛原因（未收敛时如实说明）
 * @param matchedCount               配上的对数
 * @param totalAbsDeviationHz        总绝对偏差（Hz）
 * @param totalAbsRelativeDeviation  总绝对相对偏差（优化目标量）
 * @param assignments                归属明细（按族序、阶次排列）
 * @param unassignedPeakIndexes      未归属峰的原始下标（升序）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PeakAttributionResponse(
        String bearingName,
        double rotationFrequencyHz,
        double slipCoefficient,
        boolean converged,
        int iterations,
        boolean slipAtAllowedBound,
        String statusReason,
        int matchedCount,
        double totalAbsDeviationHz,
        double totalAbsRelativeDeviation,
        List<AssignmentResponse> assignments,
        List<Integer> unassignedPeakIndexes) {

    public static PeakAttributionResponse of(String bearingName, double rotationFrequencyHz,
                                             EngineOutcome outcome) {
        var result = outcome.result();
        List<AssignmentResponse> assignments = result.assignments().stream()
                .map(AssignmentResponse::of)
                .toList();
        return new PeakAttributionResponse(
                bearingName,
                rotationFrequencyHz,
                outcome.slip(),
                outcome.converged(),
                outcome.iterations(),
                outcome.slipAtBound(),
                outcome.reason(),
                result.matchedCount(),
                result.totalAbsDeviationHz(),
                result.totalAbsRelativeDeviation(),
                assignments,
                result.unassignedPeakIndexes());
    }
}
