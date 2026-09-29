package com.bearingfreq.model;

/**
 * 轴承几何参数（不可变值对象）。
 *
 * @param rollerCount      滚动体个数
 * @param pitchDiameterMm  节圆直径（mm）
 * @param rollerDiameterMm 滚动体直径（mm）
 * @param contactAngleDeg  接触角（度）
 */
public record BearingGeometry(
        int rollerCount,
        double pitchDiameterMm,
        double rollerDiameterMm,
        double contactAngleDeg) {
}
