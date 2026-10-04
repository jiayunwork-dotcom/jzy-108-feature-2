package com.bearingfreq.attribution;

/**
 * 一根实测谱峰：频率（Hz）与幅值。
 *
 * <p>不可变值对象，假定已通过输入校验（频率为正有限值、幅值非负）。
 *
 * @param frequencyHz 峰频率（Hz）
 * @param amplitude   峰幅值（任意线性单位，仅随结果回显，不参与配对代价）
 */
public record MeasuredPeak(double frequencyHz, double amplitude) {
}
