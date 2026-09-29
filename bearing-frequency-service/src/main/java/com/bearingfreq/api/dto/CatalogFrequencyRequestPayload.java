package com.bearingfreq.api.dto;

/**
 * POST /api/v1/catalog/{name}/frequencies 的请求体（几何参数取自轴承档）。
 */
public record CatalogFrequencyRequestPayload(
        Double rotationFrequencyHz,
        Double speedRpm,
        Integer sidebandOrder) {
}
