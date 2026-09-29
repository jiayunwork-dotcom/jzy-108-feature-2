package com.bearingfreq.validation;

import com.bearingfreq.model.BearingGeometry;

/**
 * 参数合法性检查：在任何运动学计算之前拦截不合几何常理的输入，
 * 并以带原因的 {@link InvalidBearingInputException} 打回，
 * 避免算到中途才因负数开方、除零等崩溃。
 */
public final class BearingInputValidator {

    /** 边带阶次上限，防止滥用。 */
    public static final int MAX_SIDEBAND_ORDER = 5;

    /** 余弦幅值检查的容差：合法余弦最大为 1.0，留 1 ulp 级余量防浮点误判。 */
    private static final double COSINE_TOLERANCE = 1e-9;

    private BearingInputValidator() {
    }

    /**
     * 解析转频：rotationFrequencyHz 与 speedRpm 必须且只能提供一个。
     *
     * @return 正的转频（Hz）
     */
    public static double requireRotationFrequencyHz(Double rotationFrequencyHz, Double speedRpm) {
        if (rotationFrequencyHz != null && speedRpm != null) {
            throw new InvalidBearingInputException(
                    "rotationFrequencyHz 与 speedRpm 只能二选一，不能同时提供");
        }
        if (rotationFrequencyHz == null && speedRpm == null) {
            throw new InvalidBearingInputException(
                    "必须提供 rotationFrequencyHz（转频 Hz）或 speedRpm（转速 rpm）之一");
        }
        double fr = rotationFrequencyHz != null ? rotationFrequencyHz : speedRpm / 60.0;
        requirePositiveRotationFrequency(fr);
        return fr;
    }

    /** 转频必须为正的有限值。 */
    public static void requirePositiveRotationFrequency(double rotationFrequencyHz) {
        if (!Double.isFinite(rotationFrequencyHz) || rotationFrequencyHz <= 0.0) {
            throw new InvalidBearingInputException(
                    "转频必须为正数，收到 " + rotationFrequencyHz + " Hz");
        }
    }

    /** 几何参数必须满足基本几何常理。 */
    public static void validateGeometry(BearingGeometry geometry) {
        if (geometry == null) {
            throw new InvalidBearingInputException("缺少轴承几何参数 geometry");
        }
        if (geometry.rollerCount() < 3) {
            throw new InvalidBearingInputException(
                    "滚动体个数不得小于 3，收到 " + geometry.rollerCount());
        }
        if (!Double.isFinite(geometry.pitchDiameterMm()) || geometry.pitchDiameterMm() <= 0.0) {
            throw new InvalidBearingInputException(
                    "节圆直径必须为正数，收到 " + geometry.pitchDiameterMm() + " mm");
        }
        if (!Double.isFinite(geometry.rollerDiameterMm()) || geometry.rollerDiameterMm() <= 0.0) {
            throw new InvalidBearingInputException(
                    "滚动体直径必须为正数，收到 " + geometry.rollerDiameterMm() + " mm");
        }
        if (geometry.rollerDiameterMm() >= geometry.pitchDiameterMm()) {
            throw new InvalidBearingInputException(
                    "滚动体直径必须小于节圆直径（几何上不成立）：滚动体直径 "
                            + geometry.rollerDiameterMm() + " mm，节圆直径 "
                            + geometry.pitchDiameterMm() + " mm");
        }
        requireContactAngle(geometry.contactAngleDeg());
    }

    /** 接触角须为 [0, 90] 度内的有限值，且其余弦幅值不得大于 1。 */
    public static void requireContactAngle(double contactAngleDeg) {
        if (!Double.isFinite(contactAngleDeg)) {
            throw new InvalidBearingInputException(
                    "接触角必须为有限数值，收到 " + contactAngleDeg);
        }
        if (contactAngleDeg < 0.0 || contactAngleDeg > 90.0) {
            throw new InvalidBearingInputException(
                    "接触角须在 0~90 度之间，收到 " + contactAngleDeg + " 度");
        }
        requireCosineMagnitudeWithinOne(Math.cos(Math.toRadians(contactAngleDeg)));
    }

    /**
     * 防御性检查：接触角余弦的绝对值不得大于 1。
     * 对实数角度本不会触发，但作为参与运算前的最后防线保留。
     */
    static void requireCosineMagnitudeWithinOne(double cosAlpha) {
        if (Double.isNaN(cosAlpha) || Math.abs(cosAlpha) > 1.0 + COSINE_TOLERANCE) {
            throw new InvalidBearingInputException(
                    "接触角余弦的绝对值不得大于 1，收到 " + cosAlpha);
        }
    }

    /** 解析边带阶次：缺省为 0（不输出边带），须在 0..{@link #MAX_SIDEBAND_ORDER} 之间。 */
    public static int requireSidebandOrder(Integer sidebandOrder) {
        if (sidebandOrder == null) {
            return 0;
        }
        if (sidebandOrder < 0 || sidebandOrder > MAX_SIDEBAND_ORDER) {
            throw new InvalidBearingInputException(
                    "边带阶次 sidebandOrder 须在 0~" + MAX_SIDEBAND_ORDER
                            + " 之间，收到 " + sidebandOrder);
        }
        return sidebandOrder;
    }
}
