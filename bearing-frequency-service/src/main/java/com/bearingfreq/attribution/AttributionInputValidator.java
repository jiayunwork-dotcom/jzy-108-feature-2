package com.bearingfreq.attribution;

import com.bearingfreq.validation.InvalidBearingInputException;

/**
 * 峰值归属请求的输入校验：在任何运动学计算与配对之前拦截非法输入，
 * 原因信息精确到字段与（峰列表的）第几根。
 *
 * <p>口径（默认值与允许范围）：
 * <ul>
 *   <li>峰列表：非空，至多 {@link #MAX_PEAKS} 根；频率须为正有限值，
 *       幅值须为非负有限值（指出第几根，下标从 0 起并同时给出 1 起的序号）；</li>
 *   <li>相对容差 relativeTolerance：缺省 {@link #DEFAULT_RELATIVE_TOLERANCE}（2%），
 *       允许 (0, {@link #MAX_RELATIVE_TOLERANCE}]；</li>
 *   <li>频谱分辨率 frequencyResolutionHz：缺省 0.0，允许 [0, +∞) 的有限值；</li>
 *   <li>谐波阶次 maxOrder：缺省 {@link #DEFAULT_MAX_ORDER}，允许 [1, {@link #MAX_ORDER}]；</li>
 *   <li>打滑范围 minSlip/maxSlip：缺省 [0, {@link #DEFAULT_MAX_SLIP}]（5%），
 *       允许区间整体落在 [0, {@link #HARD_MAX_SLIP}]（50%）内且 min ≤ max；</li>
 *   <li>锁死打滑 lockedSlip：若提供必须落在 [minSlip, maxSlip] 内；</li>
 *   <li>内联几何 geometry 与轴承档名称 bearingName 二者只能给一个。</li>
 * </ul>
 */
public final class AttributionInputValidator {

    /** 单次请求峰数上限。 */
    public static final int MAX_PEAKS = 500;

    /** 默认谐波阶次上限。 */
    public static final int DEFAULT_MAX_ORDER = 10;

    /** 谐波阶次硬上限。 */
    public static final int MAX_ORDER = 10;

    /** 默认匹配相对容差（2%）。 */
    public static final double DEFAULT_RELATIVE_TOLERANCE = 0.02;

    /** 相对容差上限（10%）。 */
    public static final double MAX_RELATIVE_TOLERANCE = 0.10;

    /** 默认打滑上界（5%）。 */
    public static final double DEFAULT_MAX_SLIP = 0.05;

    /** 打滑硬上界（50%）。 */
    public static final double HARD_MAX_SLIP = 0.50;

    private AttributionInputValidator() {
    }

    /** 校验峰列表并返回它本身；非法时抛出带「第几根」说明的异常。 */
    public static void validatePeaks(java.util.List<MeasuredPeak> peaks) {
        if (peaks == null || peaks.isEmpty()) {
            throw new InvalidBearingInputException("实测峰列表 peaks 不能为空");
        }
        if (peaks.size() > MAX_PEAKS) {
            throw new InvalidBearingInputException(
                    "实测峰数量上限为 " + MAX_PEAKS + " 根，收到 " + peaks.size() + " 根");
        }
        for (int i = 0; i < peaks.size(); i++) {
            MeasuredPeak peak = peaks.get(i);
            if (peak == null) {
                throw new InvalidBearingInputException(
                        "第 " + i + " 根峰（第 " + (i + 1) + " 根）为空");
            }
            if (!Double.isFinite(peak.frequencyHz()) || peak.frequencyHz() <= 0.0) {
                throw new InvalidBearingInputException(
                        "第 " + i + " 根峰（第 " + (i + 1) + " 根）频率必须为正有限值（Hz），收到 "
                                + peak.frequencyHz());
            }
            if (!Double.isFinite(peak.amplitude()) || peak.amplitude() < 0.0) {
                throw new InvalidBearingInputException(
                        "第 " + i + " 根峰（第 " + (i + 1) + " 根）幅值必须为非负有限值，收到 "
                                + peak.amplitude());
            }
        }
    }

    /** 解析相对容差：缺省 2%，允许 (0, 10%]。 */
    public static double requireRelativeTolerance(Double relativeTolerance) {
        double value = relativeTolerance == null ? DEFAULT_RELATIVE_TOLERANCE : relativeTolerance;
        if (!Double.isFinite(value) || value <= 0.0 || value > MAX_RELATIVE_TOLERANCE) {
            throw new InvalidBearingInputException(
                    "相对容差 relativeTolerance 须在 (0, " + MAX_RELATIVE_TOLERANCE
                            + "] 之间，缺省 " + DEFAULT_RELATIVE_TOLERANCE + "，收到 "
                            + relativeTolerance);
        }
        return value;
    }

    /** 解析频谱分辨率：缺省 0 Hz，须为非负有限值。 */
    public static double requireResolutionHz(Double frequencyResolutionHz) {
        double value = frequencyResolutionHz == null ? 0.0 : frequencyResolutionHz;
        if (!Double.isFinite(value) || value < 0.0) {
            throw new InvalidBearingInputException(
                    "频谱分辨率 frequencyResolutionHz 须为非负有限值（Hz），缺省 0，收到 "
                            + frequencyResolutionHz);
        }
        return value;
    }

    /** 解析谐波阶次上限：缺省 10，允许 [1, 10]。 */
    public static int requireMaxOrder(Integer maxOrder) {
        int value = maxOrder == null ? DEFAULT_MAX_ORDER : maxOrder;
        if (value < 1 || value > MAX_ORDER) {
            throw new InvalidBearingInputException(
                    "谐波阶次上限 maxOrder 须在 1~" + MAX_ORDER + " 之间，缺省 "
                            + DEFAULT_MAX_ORDER + "，收到 " + maxOrder);
        }
        return value;
    }

    /**
     * 解析打滑允许范围：minSlip 缺省 0，maxSlip 缺省 5%；
     * 整体须落在 [0, 50%] 内且 min ≤ max。
     *
     * @return 二元组 [minSlip, maxSlip]
     */
    public static double[] requireSlipRange(Double minSlip, Double maxSlip) {
        double lo = minSlip == null ? 0.0 : minSlip;
        double hi = maxSlip == null ? DEFAULT_MAX_SLIP : maxSlip;
        if (!Double.isFinite(lo) || !Double.isFinite(hi)
                || lo < 0.0 || hi > HARD_MAX_SLIP || lo > hi) {
            throw new InvalidBearingInputException(
                    "打滑范围须满足 0 ≤ minSlip ≤ maxSlip ≤ " + HARD_MAX_SLIP
                            + "，缺省 [0, " + DEFAULT_MAX_SLIP + "]，收到 minSlip="
                            + minSlip + ", maxSlip=" + maxSlip);
        }
        return new double[]{lo, hi};
    }

    /** 锁死打滑值若提供，必须落在允许范围内。 */
    public static double requireLockedSlip(Double lockedSlip, double minSlip, double maxSlip) {
        if (lockedSlip == null) {
            return Double.NaN;
        }
        if (!Double.isFinite(lockedSlip) || lockedSlip < minSlip || lockedSlip > maxSlip) {
            throw new InvalidBearingInputException(
                    "锁死打滑系数 lockedSlip 须在允许范围 [" + minSlip + ", " + maxSlip
                            + "] 内，收到 " + lockedSlip);
        }
        return lockedSlip;
    }

    /**
     * 几何来源二选一：内联 geometry 与轴承档名 bearingName 必须且只能提供一个。
     *
     * @param hasInlineGeometry 请求是否携带内联几何对象
     * @param bearingName       轴承档名（null 表示未给）
     */
    public static void requireExactlyOneGeometrySource(boolean hasInlineGeometry,
                                                       String bearingName) {
        boolean byName = bearingName != null && !bearingName.isBlank();
        if (hasInlineGeometry && byName) {
            throw new InvalidBearingInputException(
                    "内联几何 geometry 与轴承档名 bearingName 只能提供一个，不能同时给出");
        }
        if (!hasInlineGeometry && !byName) {
            throw new InvalidBearingInputException(
                    "必须提供内联几何 geometry 或已登记的轴承档名 bearingName 之一");
        }
    }
}
