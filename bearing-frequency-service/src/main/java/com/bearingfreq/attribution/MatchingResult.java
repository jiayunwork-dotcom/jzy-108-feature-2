package com.bearingfreq.attribution;

import java.util.List;

/**
 * 一次全局最优一对一归属的结果（对应某个打滑系数下的目标表）。
 *
 * <p>优化目标（字典序，前者优先）：
 * <ol>
 *   <li><b>配对数最大</b>——不允许为了总偏差好看而丢弃本可配上的一对；</li>
 *   <li>配对数相同的方案中，<b>总代价最小</b>——总代价为各配对相对偏差
 *       平方之和 {@link #totalSquaredRelativeDeviation()}；</li>
 *   <li>仍平局时按规范序裁决：优先把规范序靠前的目标（族顺序→阶次）
 *       配给规范序靠前的峰，因此同一输入永远给出同一组结果。</li>
 * </ol>
 *
 * @param assignments                     已归属配对（按规范序排列）
 * @param unmatchedPeakIndices            未归属峰的规范序下标（升序）
 * @param matchedPeakIndices              已归属峰的规范序下标（升序）
 * @param totalSquaredRelativeDeviation   总代价 Σ(δf/f)²
 * @param totalAbsDeviationHz             附带信息：Σ|带符号偏差|（Hz）
 */
public record MatchingResult(
        List<Assignment> assignments,
        List<Integer> matchedPeakIndices,
        List<Integer> unmatchedPeakIndices,
        double totalSquaredRelativeDeviation,
        double totalAbsDeviationHz) {

    /** 配上的对数。 */
    public int matchCount() {
        return assignments.size();
    }

    /** 其中归到四个轴承部位（受打滑影响）的配对数。 */
    public long bearingMatchCount() {
        return assignments.stream().filter(a -> a.target().family().affectedBySlip()).count();
    }
}
