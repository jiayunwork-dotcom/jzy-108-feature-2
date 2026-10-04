package com.bearingfreq.api.dto;

import java.util.List;

/**
 * POST /api/v1/peak-attribution 的请求体。
 *
 * <p>rotationFrequencyHz 与 speedRpm 二选一；geometry 与 bearingName 二选一；
 * 匹配窗口、阶次、打滑范围与锁死值均可缺省（由校验层补默认值）。
 */
public record PeakAttributionRequestPayload(
        Double rotationFrequencyHz,
        Double speedRpm,
        List<PeakPayload> peaks,
        GeometryPayload geometry,
        String bearingName,
        Integer maxOrder,
        Double relativeTolerance,
        Double frequencyResolutionHz,
        Double minSlip,
        Double maxSlip,
        Double lockedSlip) {
}
