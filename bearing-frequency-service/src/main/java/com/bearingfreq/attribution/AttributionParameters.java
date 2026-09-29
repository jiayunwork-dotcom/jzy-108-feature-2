package com.bearingfreq.attribution;

import com.bearingfreq.model.BearingGeometry;

import java.util.List;

/**
 * 通过全部校验、默认值已补齐的峰值归属请求参数（不可变值对象）。
 *
 * <p>口径（与正算接口一致）：{@code rotationFrequencyHz} 与 {@code speedRpm}
 * 二选一，本对象只保留换算后的正转频；几何参数内联给出或引用轴承档，
 * 解析到本对象时二者的互斥校验已经完成。
 *
 * @param rotationFrequencyHz 转频（Hz，由转频或转速换算而来）
 * @param geometry            轴承几何（内联或从轴承档取出，二者结果等价）
 * @param bearingName         轴承档名称（内联几何时为 null，仅用于回显）
 * @param peaks               实测谱峰（1..{@link AttributionParameters#MAX_PEAKS} 根）
 * @param maxOrder            谐波阶次上限（1..{@link AttributionParameters#MAX_ORDER}）
 * @param relativeTolerance   相对容差（0, {@link AttributionParameters#MAX_RELATIVE_TOLERANCE}]）
 * @param frequencyResolutionHz 频谱分辨率（Hz，≥ 0，0 表示未知/忽略）
 * @param slipMin             允许打滑范围下限（[0, 0.5]，且 ≤ slipMax）
 * @param slipMax             允许打滑范围上限
 * @param lockedSlip          锁死的打滑系数（非 null 时只做一次归属、不迭代）
 */
public record AttributionParameters(
        double rotationFrequencyHz,
        BearingGeometry geometry,
        String bearingName,
        List<MeasuredPeak> peaks,
        int maxOrder,
        double relativeTolerance,
        double frequencyResolutionHz,
        double slipMin,
        double slipMax,
        Double lockedSlip) {

    /** 单次请求峰数上限。 */
    public static final int MAX_PEAKS = 500;

    /** 谐波阶次上限（含）：转频与四个轴承部位各自 1..该值 阶。 */
    public static final int MAX_ORDER = 10;

    /** 相对容差默认值：2%。 */
    public static final double DEFAULT_RELATIVE_TOLERANCE = 0.02;

    /** 相对容差允许上限：10%（下限为开区间 0）。 */
    public static final double MAX_RELATIVE_TOLERANCE = 0.10;

    /** 谐波阶次默认上限。 */
    public static final int DEFAULT_MAX_ORDER = 10;

    /** 频谱分辨率默认值：0（未知，窗口仅由相对容差决定）。 */
    public static final double DEFAULT_RESOLUTION_HZ = 0.0;

    /** 打滑允许范围默认 [0, 10%]。 */
    public static final double DEFAULT_SLIP_MIN = 0.0;
    public static final double DEFAULT_SLIP_MAX = 0.10;

    /** 允许调用方给出的打滑上限（物理上打滑不会超过一半）。 */
    public static final double HARD_SLIP_LIMIT = 0.50;
}
