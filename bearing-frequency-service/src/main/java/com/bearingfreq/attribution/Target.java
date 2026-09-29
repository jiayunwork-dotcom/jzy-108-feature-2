package com.bearingfreq.attribution;

/**
 * 一个待匹配目标：某一族（{@link BearingFamily}）的某一阶整数倍频率。
 *
 * <p>{@code theoreticalHz} 是纯运动学理论值（复用
 * {@link com.bearingfreq.kinematics.BearingKinematics} 的计算结果按阶次倍乘），
 * {@code baseHz} 是该族第 1 阶理论值。匹配用的修正目标频率在
 * {@code 修正系数} 确定后由 {@link #adjustedHz(double)} 给出：
 * 转频族不修正，四个轴承族乘以 {@code (1 − slip)}。
 *
 * @param family        目标族
 * @param order         谐波阶次（1..maxOrder）
 * @param baseHz        该族第 1 阶理论频率（Hz）
 * @param theoreticalHz 该阶理论目标频率 = baseHz × order（Hz）
 */
public record Target(
        BearingFamily family,
        int order,
        double baseHz,
        double theoreticalHz) {

    /**
     * 按当前打滑系数给出参与匹配的目标频率。
     *
     * @param slip 打滑系数（0 = 纯滚动无打滑）
     * @return 修正后的目标频率（Hz）
     */
    public double adjustedHz(double slip) {
        return family.affectedBySlip() ? theoreticalHz * (1.0 - slip) : theoreticalHz;
    }
}
