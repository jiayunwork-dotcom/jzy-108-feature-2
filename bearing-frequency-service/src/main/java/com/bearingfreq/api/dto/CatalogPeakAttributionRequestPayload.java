package com.bearingfreq.api.dto;

import java.util.List;

/**
 * POST /api/v1/catalog/{name}/peak-attributions 的请求体（几何取自轴承档，
 * 不再接受 geometry / bearingName 字段）。
 */
public record CatalogPeakAttributionRequestPayload(
        Double rotationFrequencyHz,
        Double speedRpm,
        List<PeakPayload> peaks,
        Integer maxOrder,
        Double relativeTolerance,
        Double frequencyResolutionHz,
        Double slipMin,
        Double slipMax,
        Double lockedSlip) {
}
