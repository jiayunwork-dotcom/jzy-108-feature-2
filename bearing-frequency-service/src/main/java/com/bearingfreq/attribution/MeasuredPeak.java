package com.bearingfreq.attribution;

/**
 * 一根实测谱峰（已规范化）。{@code index} 是该峰在调用方提交列表中的
 * 0 基下标，用于错误提示与结果回填；求解前峰列表会被排序成规范序，
 * 但每根峰始终带着原始下标，因此「打乱顺序提交」不改变任何结果。
 *
 * @param index     原始提交下标（0 基）
 * @param frequencyHz 峰频率（Hz，为正）
 * @param amplitude   峰幅值（非负）
 */
public record MeasuredPeak(
        int index,
        double frequencyHz,
        double amplitude) {
}
