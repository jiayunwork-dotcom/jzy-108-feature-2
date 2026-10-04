package com.bearingfreq.attribution;

import java.util.List;

/**
 * 一次全局归属的结果：配对明细、未归属峰下标，以及该方案的总偏差度量。
 *
 * <p>配对明细按「族序、阶次」的目标规范化次序排列；未归属峰按提交时的原始下标升序排列。
 *
 * @param slip                     本次归属所用的打滑系数
 * @param assignments              全部配上的一对一归属
 * @param unassignedPeakIndexes    未配上任何目标的峰的原始下标（升序）
 * @param matchedCount             配上的对数
 * @param totalAbsDeviationHz      总绝对偏差（Hz）：|峰 − 修正目标| 之和
 * @param totalAbsRelativeDeviation 总绝对相对偏差：|偏差/修正目标| 之和（优化目标量）
 */
public record AttributionResult(double slip,
                                 List<Assignment> assignments,
                                 List<Integer> unassignedPeakIndexes,
                                 int matchedCount,
                                 double totalAbsDeviationHz,
                                 double totalAbsRelativeDeviation) {
}
