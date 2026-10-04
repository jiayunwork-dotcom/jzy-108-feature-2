package com.bearingfreq.attribution;

/**
 * 一根峰与一个候选目标的一对一归属结果。
 *
 * @param peakIndex             峰在调用方提交列表中的原始下标（从 0 起）
 * @param peakFrequencyHz       实测峰频率（Hz）
 * @param amplitude             实测峰幅值（原样回显）
 * @param family                归入的频率族
 * @param order                 归入的谐波阶次
 * @param theoreticalTargetHz   纯滚动理论目标频率（Hz）
 * @param correctedTargetHz     当前打滑系数下的修正目标频率（Hz）
 * @param deviationHz           带符号偏差（Hz）：峰频率 − 修正目标频率
 * @param relativeDeviation     带符号相对偏差：偏差 / 修正目标频率
 */
public record Assignment(int peakIndex,
                         double peakFrequencyHz,
                         double amplitude,
                         FrequencyFamily family,
                         int order,
                         double theoreticalTargetHz,
                         double correctedTargetHz,
                         double deviationHz,
                         double relativeDeviation) {
}
