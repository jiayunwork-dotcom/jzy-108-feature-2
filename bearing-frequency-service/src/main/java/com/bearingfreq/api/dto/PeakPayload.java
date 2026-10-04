package com.bearingfreq.api.dto;

/**
 * 峰值归属请求中的一根实测谱峰。
 *
 * @param frequencyHz 峰频率（Hz），必须为正
 * @param amplitude   峰幅值（非负；仅随结果回显，不参与配对代价）
 */
public record PeakPayload(Double frequencyHz, Double amplitude) {
}
