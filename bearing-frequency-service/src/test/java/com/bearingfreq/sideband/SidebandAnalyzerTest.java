package com.bearingfreq.sideband;

import com.bearingfreq.kinematics.CharacteristicFrequencies;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class SidebandAnalyzerTest {

    @Test
    @DisplayName("边带以特征频率为中心、按转频整数倍对称分布")
    void sidebandsAreSymmetricAroundCharacteristicFrequencies() {
        var frequencies = new CharacteristicFrequencies(100.0, 140.0, 10.0, 60.0);
        var sidebands = SidebandAnalyzer.aroundRotationFrequency(frequencies, 25.0, 2);

        assertThat(sidebands).containsOnlyKeys("bpfo", "bpfi", "ftf", "bsf");

        var bpfoBands = sidebands.get("bpfo");
        assertThat(bpfoBands).hasSize(2);
        assertThat(bpfoBands.get(0).order()).isEqualTo(1);
        assertThat(bpfoBands.get(0).lowerHz()).isCloseTo(75.0, within(1e-9));
        assertThat(bpfoBands.get(0).upperHz()).isCloseTo(125.0, within(1e-9));
        assertThat(bpfoBands.get(1).lowerHz()).isCloseTo(50.0, within(1e-9));
        assertThat(bpfoBands.get(1).upperHz()).isCloseTo(150.0, within(1e-9));

        var ftfBands = sidebands.get("ftf");
        assertThat(ftfBands.get(0).lowerHz()).isCloseTo(-15.0, within(1e-9));
        assertThat(ftfBands.get(0).upperHz()).isCloseTo(35.0, within(1e-9));
    }
}
