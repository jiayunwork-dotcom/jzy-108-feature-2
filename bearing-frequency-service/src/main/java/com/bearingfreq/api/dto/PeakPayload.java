package com.bearingfreq.api.dto;

/**
 * 归属请求中的一根实测谱峰。
 *
 * @param frequencyHz 峰频率（Hz，必须为正）
 * @param amplitude   峰幅值（非负；不参与归属代价，仅供调用方自行判读）
 */
public record PeakPayload(
        Double frequencyHz,
        Double amplitude) {
}
