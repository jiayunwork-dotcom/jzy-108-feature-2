package com.bearingfreq.api.dto;

import java.util.List;

/**
 * POST /api/v1/peak-attributions 的请求体。
 *
 * <p>{@code rotationFrequencyHz} 与 {@code speedRpm} 二选一（同正算接口）；
 * {@code geometry} 与 {@code bearingName} 二选一（内联几何或引用已登记轴承档）。
 * 其余字段缺省时由校验层补默认值：maxOrder=10、relativeTolerance=0.02、
 * frequencyResolutionHz=0、slipRange=[0, 0.10]。
 */
public record PeakAttributionRequestPayload(
        Double rotationFrequencyHz,
        Double speedRpm,
        GeometryPayload geometry,
        String bearingName,
        List<PeakPayload> peaks,
        Integer maxOrder,
        Double relativeTolerance,
        Double frequencyResolutionHz,
        Double slipMin,
        Double slipMax,
        Double lockedSlip) {
}
