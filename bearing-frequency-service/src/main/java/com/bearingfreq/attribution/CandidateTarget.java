package com.bearingfreq.attribution;

/**
 * 一个候选归属目标：某频率族的某一阶谐波。
 *
 * <p>{@code theoreticalHz} 是纯滚动理论目标频率（与打滑无关，缓存自运动学计算结果）；
 * {@code correctedHz} 是按当前打滑系数 s 修正后的目标频率：
 * 轴承族为 theoreticalHz·(1 − s)，转频族恒等于 theoreticalHz。
 *
 * @param family        频率族
 * @param order         谐波阶次（从 1 起）
 * @param theoreticalHz 理论目标频率（Hz）
 * @param correctedHz   当前打滑系数下的修正目标频率（Hz）
 */
public record CandidateTarget(FrequencyFamily family,
                              int order,
                              double theoreticalHz,
                              double correctedHz) {
}
