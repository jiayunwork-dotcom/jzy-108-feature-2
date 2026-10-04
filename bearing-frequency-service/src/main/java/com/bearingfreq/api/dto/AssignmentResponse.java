package com.bearingfreq.api.dto;

import com.bearingfreq.attribution.Assignment;
import com.bearingfreq.attribution.FrequencyFamily;

/**
 * 一根峰的归属明细。
 *
 * @param peakIndex           峰在提交列表中的原始下标（从 0 起）
 * @param peakFrequencyHz     实测峰频率（Hz）
 * @param amplitude           实测峰幅值（原样回显）
 * @param family              归入的频率族：shaft/bpfo/bpfi/ftf/bsf
 * @param order               谐波阶次（从 1 起）
 * @param theoreticalTargetHz 纯滚动理论目标频率（Hz）
 * @param correctedTargetHz   最终打滑系数下的修正目标频率（Hz）
 * @param deviationHz         带符号偏差（Hz）：峰频率 − 修正目标频率
 * @param relativeDeviation   带符号相对偏差：偏差 / 修正目标频率
 */
public record AssignmentResponse(int peakIndex,
                                 double peakFrequencyHz,
                                 double amplitude,
                                 FrequencyFamily family,
                                 int order,
                                 double theoreticalTargetHz,
                                 double correctedTargetHz,
                                 double deviationHz,
                                 double relativeDeviation) {

    public static AssignmentResponse of(Assignment a) {
        return new AssignmentResponse(a.peakIndex(), a.peakFrequencyHz(), a.amplitude(),
                a.family(), a.order(), a.theoreticalTargetHz(), a.correctedTargetHz(),
                a.deviationHz(), a.relativeDeviation());
    }
}
