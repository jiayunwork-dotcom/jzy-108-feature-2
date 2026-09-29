package com.bearingfreq.attribution;

/**
 * 一根峰对一个目标的归属结果。
 *
 * @param peak           被归属的峰（保留原始提交下标）
 * @param target         归属目标（族 + 阶次）
 * @param adjustedTargetHz 归属时使用的修正目标频率（Hz）
 * @param deviationHz    带符号偏差 = 峰频率 − 修正目标频率（Hz）
 * @param relativeDeviation 带符号相对偏差 = deviationHz / 修正目标频率
 */
public record Assignment(
        MeasuredPeak peak,
        Target target,
        double adjustedTargetHz,
        double deviationHz,
        double relativeDeviation) {

    /** 归属的规范排序：族顺序、阶次、原始峰下标。 */
    public static java.util.Comparator<Assignment> canonicalOrder() {
        return java.util.Comparator
                .comparing((Assignment a) -> a.target().family().ordinal())
                .thenComparingInt(a -> a.target().order())
                .thenComparingInt(a -> a.peak().index());
    }
}
