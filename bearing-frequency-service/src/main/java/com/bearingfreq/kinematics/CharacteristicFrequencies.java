package com.bearingfreq.kinematics;

/**
 * 四个特征频率的计算结果（单位均为 Hz）。
 *
 * @param bpfoHz 外圈通过频率 (Ball Pass Frequency Outer race)
 * @param bpfiHz 内圈通过频率 (Ball Pass Frequency Inner race)
 * @param ftfHz  保持架频率 (Fundamental Train Frequency)
 * @param bsfHz  滚动体自转频率 (Ball Spin Frequency)
 */
public record CharacteristicFrequencies(
        double bpfoHz,
        double bpfiHz,
        double ftfHz,
        double bsfHz) {
}
