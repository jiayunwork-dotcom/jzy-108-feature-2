package com.bearingfreq.sideband;

import com.bearingfreq.kinematics.CharacteristicFrequencies;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 边带推导：轴承故障特征频率常被转频调制，在其两侧出现间隔为转频整数倍的边带。
 * 本类对四个特征频率分别给出 f ± k·fr（k = 1..order）的边带位置，
 * 供对照频谱时识别调制边带。主输出始终是四个特征频率，边带只是附加信息。
 */
public final class SidebandAnalyzer {

    private SidebandAnalyzer() {
    }

    /**
     * 计算四个特征频率两侧、以转频为间隔的边带。
     *
     * @param frequencies         四个特征频率
     * @param rotationFrequencyHz 转频（Hz）
     * @param order               边带阶次（1 阶 = ±fr，2 阶 = ±2fr，依此类推）
     * @return 以特征频率名称（bpfo/bpfi/ftf/bsf）为键的边带列表
     */
    public static Map<String, List<Sideband>> aroundRotationFrequency(
            CharacteristicFrequencies frequencies, double rotationFrequencyHz, int order) {
        Map<String, List<Sideband>> result = new LinkedHashMap<>();
        result.put("bpfo", sidebandsOf(frequencies.bpfoHz(), rotationFrequencyHz, order));
        result.put("bpfi", sidebandsOf(frequencies.bpfiHz(), rotationFrequencyHz, order));
        result.put("ftf", sidebandsOf(frequencies.ftfHz(), rotationFrequencyHz, order));
        result.put("bsf", sidebandsOf(frequencies.bsfHz(), rotationFrequencyHz, order));
        return result;
    }

    private static List<Sideband> sidebandsOf(double centerHz, double rotationFrequencyHz, int order) {
        List<Sideband> bands = new ArrayList<>(order);
        for (int k = 1; k <= order; k++) {
            bands.add(new Sideband(k,
                    centerHz - k * rotationFrequencyHz,
                    centerHz + k * rotationFrequencyHz));
        }
        return bands;
    }
}
