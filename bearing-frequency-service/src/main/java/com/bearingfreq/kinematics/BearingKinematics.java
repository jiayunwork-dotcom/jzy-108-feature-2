package com.bearingfreq.kinematics;

import com.bearingfreq.model.BearingGeometry;

/**
 * 滚动轴承特征频率运动学核心。
 *
 * <p>假设：纯滚动、无打滑、外圈固定。记：
 * <ul>
 *   <li>n  — 滚动体个数</li>
 *   <li>fr — 转频（Hz）</li>
 *   <li>d  — 滚动体直径</li>
 *   <li>D  — 节圆直径</li>
 *   <li>α  — 接触角</li>
 *   <li>β  = (d / D) · cos α</li>
 * </ul>
 *
 * <p>四条公式：
 * <pre>
 *   BPFO = n/2 · fr · (1 − β)        外圈通过频率
 *   BPFI = n/2 · fr · (1 + β)        内圈通过频率（与 BPFO 仅括号内符号相反）
 *   FTF  = 1/2 · fr · (1 − β)        保持架频率（与滚动体个数无关）
 *   BSF  = D/(2d) · fr · (1 − β²)    滚动体自转频率
 * </pre>
 *
 * <p>本类只做纯计算，假定输入已通过
 * {@link com.bearingfreq.validation.BearingInputValidator} 校验。
 */
public final class BearingKinematics {

    private BearingKinematics() {
    }

    /**
     * 按给定转频与几何参数计算四个特征频率。
     *
     * @param rotationFrequencyHz 转频（Hz），必须为正
     * @param geometry            轴承几何参数
     * @return 四个特征频率（Hz）
     */
    public static CharacteristicFrequencies compute(double rotationFrequencyHz, BearingGeometry geometry) {
        double cosAlpha = Math.cos(Math.toRadians(geometry.contactAngleDeg()));
        double beta = geometry.rollerDiameterMm() / geometry.pitchDiameterMm() * cosAlpha;

        double bpfo = geometry.rollerCount() / 2.0 * rotationFrequencyHz * (1.0 - beta);
        double bpfi = geometry.rollerCount() / 2.0 * rotationFrequencyHz * (1.0 + beta);
        double ftf = rotationFrequencyHz / 2.0 * (1.0 - beta);
        double bsf = geometry.pitchDiameterMm() / (2.0 * geometry.rollerDiameterMm())
                * rotationFrequencyHz * (1.0 - beta * beta);

        return new CharacteristicFrequencies(bpfo, bpfi, ftf, bsf);
    }
}
