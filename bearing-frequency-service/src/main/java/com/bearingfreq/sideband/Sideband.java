package com.bearingfreq.sideband;

/**
 * 一条边带：以某特征频率为中心、间隔 k 倍转频的一对下/上边带。
 *
 * @param order   边带阶次 k（相对转频的倍数）
 * @param lowerHz 下边带频率 = 中心频率 − k·fr（Hz）
 * @param upperHz 上边带频率 = 中心频率 + k·fr（Hz）
 */
public record Sideband(int order, double lowerHz, double upperHz) {
}
