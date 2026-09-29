package com.bearingfreq.api.dto;

/**
 * POST /api/v1/frequencies 的请求体。
 * rotationFrequencyHz 与 speedRpm 二选一；sidebandOrder 缺省为 0（不输出边带）。
 */
public record FrequencyRequestPayload(
        Double rotationFrequencyHz,
        Double speedRpm,
        Integer sidebandOrder,
        GeometryPayload geometry) {
}
