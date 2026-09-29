package com.bearingfreq.attribution;

import com.bearingfreq.model.BearingGeometry;
import com.bearingfreq.validation.BearingInputValidator;
import com.bearingfreq.validation.InvalidBearingInputException;

import java.util.ArrayList;
import java.util.List;

/**
 * 峰值归属请求的参数校验：在任何配对/打滑计算之前拦截非法输入并讲明原因。
 *
 * <p>转频/转速口径与几何合法性直接复用
 * {@link BearingInputValidator}，不重抄规则；本类只负责归属场景新增的约束
 * （峰列表、容差、分辨率、阶次、打滑范围与锁死值、几何/档名互斥）。
 */
public final class AttributionInputValidator {

    private AttributionInputValidator() {
    }

    /**
     * 校验并补齐默认值，产出不可变的 {@link AttributionParameters}。
     *
     * @param rotationFrequencyHz 转频（可空，与 speedRpm 二选一）
     * @param speedRpm            转速 rpm（可空）
     * @param geometry            内联几何（可空，与 bearingName 互斥）
     * @param bearingName         轴承档名称（可空，与 geometry 互斥；解析由编排层完成）
     * @param rawPeaks            原始峰列表（下标、频率 Hz、幅值）
     * @param maxOrder            谐波阶次上限（可空，默认 10）
     * @param relativeTolerance   相对容差（可空，默认 2%）
     * @param resolutionHz        频谱分辨率 Hz（可空，默认 0）
     * @param slipMin             打滑范围下限（可空，默认 0）
     * @param slipMax             打滑范围上限（可空，默认 0.10）
     * @param lockedSlip          锁死打滑值（可空；非空时只做一次归属）
     */
    public static AttributionParameters validate(
            Double rotationFrequencyHz, Double speedRpm,
            BearingGeometry geometry, String bearingName,
            List<RawPeak> rawPeaks,
            Integer maxOrder, Double relativeTolerance, Double resolutionHz,
            Double slipMin, Double slipMax, Double lockedSlip) {

        // 几何与档名互斥（二者只能给一个）；档是否存在由编排层按现有 404 口径处理。
        boolean hasGeometry = geometry != null;
        boolean hasName = bearingName != null && !bearingName.isBlank();
        if (hasGeometry && hasName) {
            throw new InvalidBearingInputException(
                    "geometry（内联几何）与 bearingName（轴承档名称）只能提供一个，不能同时给出");
        }
        if (!hasGeometry && !hasName) {
            throw new InvalidBearingInputException(
                    "必须提供 geometry（内联几何）或 bearingName（已登记轴承档名称）之一");
        }
        if (hasGeometry) {
            BearingInputValidator.validateGeometry(geometry);
        }

        // 沿用正算接口的二选一校验与换算
        double fr = BearingInputValidator.requireRotationFrequencyHz(rotationFrequencyHz, speedRpm);

        List<MeasuredPeak> peaks = requirePeaks(rawPeaks);
        int order = requireMaxOrder(maxOrder);
        double tolerance = requireRelativeTolerance(relativeTolerance);
        double resolution = requireResolution(resolutionHz);

        double min = slipMin == null ? AttributionParameters.DEFAULT_SLIP_MIN : slipMin;
        double max = slipMax == null ? AttributionParameters.DEFAULT_SLIP_MAX : slipMax;
        requireSlipRange(min, max);

        if (lockedSlip != null) {
            requireFinite("lockedSlip（锁死打滑系数）", lockedSlip);
            if (lockedSlip < min || lockedSlip > max) {
                throw new InvalidBearingInputException(
                        "锁死打滑系数 lockedSlip 必须在允许范围 [" + min + ", " + max
                                + "] 内，收到 " + lockedSlip);
            }
        }

        return new AttributionParameters(fr, geometry, hasName ? bearingName : null,
                List.copyOf(peaks), order, tolerance, resolution, min, max, lockedSlip);
    }

    /** 未解析前的峰入参（下标即提交序号，下标从 1 起用于错误提示）。 */
    public record RawPeak(double frequencyHz, double amplitude) {
    }

    private static List<MeasuredPeak> requirePeaks(List<RawPeak> rawPeaks) {
        if (rawPeaks == null || rawPeaks.isEmpty()) {
            throw new InvalidBearingInputException("峰列表 peaks 不能为空（至少提供一根实测谱峰）");
        }
        if (rawPeaks.size() > AttributionParameters.MAX_PEAKS) {
            throw new InvalidBearingInputException(
                    "峰数不得超过 " + AttributionParameters.MAX_PEAKS + " 根，收到 "
                            + rawPeaks.size() + " 根");
        }
        List<MeasuredPeak> peaks = new ArrayList<>(rawPeaks.size());
        for (int i = 0; i < rawPeaks.size(); i++) {
            RawPeak peak = rawPeaks.get(i);
            if (peak == null) {
                throw new InvalidBearingInputException("第 " + (i + 1) + " 根峰为空");
            }
            if (!Double.isFinite(peak.frequencyHz()) || peak.frequencyHz() <= 0.0) {
                throw new InvalidBearingInputException(
                        "第 " + (i + 1) + " 根峰频率必须为正的有限值（Hz），收到 "
                                + peak.frequencyHz());
            }
            if (!Double.isFinite(peak.amplitude()) || peak.amplitude() < 0.0) {
                throw new InvalidBearingInputException(
                        "第 " + (i + 1) + " 根峰幅值必须为非负的有限值，收到 " + peak.amplitude());
            }
            peaks.add(new MeasuredPeak(i, peak.frequencyHz(), peak.amplitude()));
        }
        return peaks;
    }

    private static int requireMaxOrder(Integer maxOrder) {
        int order = maxOrder == null ? AttributionParameters.DEFAULT_MAX_ORDER : maxOrder;
        if (order < 1 || order > AttributionParameters.MAX_ORDER) {
            throw new InvalidBearingInputException(
                    "谐波阶次上限 maxOrder 须在 1~" + AttributionParameters.MAX_ORDER
                            + " 之间，收到 " + order);
        }
        return order;
    }

    private static double requireRelativeTolerance(Double relativeTolerance) {
        double tolerance = relativeTolerance == null
                ? AttributionParameters.DEFAULT_RELATIVE_TOLERANCE : relativeTolerance;
        if (!Double.isFinite(tolerance) || tolerance <= 0.0
                || tolerance > AttributionParameters.MAX_RELATIVE_TOLERANCE) {
            throw new InvalidBearingInputException(
                    "相对容差 relativeTolerance 须在 (0, "
                            + AttributionParameters.MAX_RELATIVE_TOLERANCE
                            + "] 之间（缺省 " + AttributionParameters.DEFAULT_RELATIVE_TOLERANCE
                            + "），收到 " + tolerance);
        }
        return tolerance;
    }

    private static double requireResolution(Double resolutionHz) {
        double resolution = resolutionHz == null
                ? AttributionParameters.DEFAULT_RESOLUTION_HZ : resolutionHz;
        if (!Double.isFinite(resolution) || resolution < 0.0) {
            throw new InvalidBearingInputException(
                    "频谱分辨率 frequencyResolutionHz 必须为非负的有限值（Hz，0 表示未知），收到 "
                            + resolution);
        }
        return resolution;
    }

    private static void requireSlipRange(double slipMin, double slipMax) {
        requireFinite("slipMin（打滑范围下限）", slipMin);
        requireFinite("slipMax（打滑范围上限）", slipMax);
        if (slipMin < 0.0 || slipMax > AttributionParameters.HARD_SLIP_LIMIT) {
            throw new InvalidBearingInputException(
                    "打滑允许范围须落在 [0, " + AttributionParameters.HARD_SLIP_LIMIT
                            + "] 内，收到 [" + slipMin + ", " + slipMax + "]");
        }
        if (slipMin > slipMax) {
            throw new InvalidBearingInputException(
                    "打滑范围下限 slipMin 不得大于上限 slipMax，收到 ["
                            + slipMin + ", " + slipMax + "]");
        }
    }

    private static void requireFinite(String name, double value) {
        if (!Double.isFinite(value)) {
            throw new InvalidBearingInputException(name + " 必须为有限数值，收到 " + value);
        }
    }
}
