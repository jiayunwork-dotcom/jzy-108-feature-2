package com.bearingfreq.service;

import com.bearingfreq.kinematics.CharacteristicFrequencies;
import com.bearingfreq.sideband.Sideband;

import java.util.List;
import java.util.Map;

/**
 * 一次特征频率核算的结果：转频、四个特征频率，以及（可选的）边带信息。
 */
public record ComputationResult(
        double rotationFrequencyHz,
        CharacteristicFrequencies frequencies,
        Map<String, List<Sideband>> sidebands) {
}
