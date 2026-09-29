package com.bearingfreq.attribution;

/**
 * 一根峰与一个目标之间的一条候选配对边。
 *
 * <p>偏差定义（全局优化以此为唯一代价口径）：
 * <ul>
 *   <li>带符号偏差 {@code deviationHz = 峰频率 − 修正后目标频率}（正＝峰偏高，负＝偏低）；</li>
 *   <li>相对偏差 {@code relativeDeviation = deviationHz / 修正后目标频率}；</li>
 *   <li>边代价 {@code cost = relativeDeviation²}，即相对偏差的平方——
 *       不按幅值加权（幅值不参与归属决策，避免强峰抢占本该属于弱峰的目标）。</li>
 * </ul>
 * 仅当 {@code |deviationHz| ≤ 半窗口} 时边才合法。半窗口
 * {@code relativeTolerance × 修正后目标频率 + 0.5 × 频谱分辨率}：
 * 相对容差负责系统性偏差与小幅波动，半个 bin 负责频谱栅格化误差。
 *
 * @param target           目标
 * @param peakIndex        峰在规范序中的下标
 * @param adjustedTargetHz 修正后的目标频率（Hz）
 * @param deviationHz      带符号偏差（Hz）
 * @param relativeDeviation 带符号相对偏差（无量纲）
 * @param cost             边代价（相对偏差平方）
 */
public record Edge(
        Target target,
        int peakIndex,
        double adjustedTargetHz,
        double deviationHz,
        double relativeDeviation,
        double cost) {
}
