package com.bearingfreq.api.dto;

import com.bearingfreq.kinematics.CharacteristicFrequencies;
import com.bearingfreq.service.ComputationResult;
import com.bearingfreq.sideband.Sideband;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * 特征频率核算响应。主输出是四个特征频率；
 * bearingName 与 sidebands 仅在适用时出现。
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record FrequencyResponse(
        String bearingName,
        double rotationFrequencyHz,
        CharacteristicFrequencies frequencies,
        Map<String, List<Sideband>> sidebands) {

    public static FrequencyResponse of(ComputationResult result) {
        return new FrequencyResponse(null, result.rotationFrequencyHz(),
                result.frequencies(), result.sidebands());
    }

    public static FrequencyResponse of(String bearingName, ComputationResult result) {
        return new FrequencyResponse(bearingName, result.rotationFrequencyHz(),
                result.frequencies(), result.sidebands());
    }
}
