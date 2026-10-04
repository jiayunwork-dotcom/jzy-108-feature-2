package com.bearingfreq.attribution;

import com.bearingfreq.kinematics.BearingKinematics;
import com.bearingfreq.model.BearingGeometry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class TargetSpectrumTest {

    private static final BearingGeometry GEOMETRY_6205 =
            new BearingGeometry(9, 39.04, 7.94, 0.0);

    @Test
    @DisplayName("目标谱复用运动学结果：基频等于 BearingKinematics，阶次为整数倍")
    void reusesKinematicsAndBuildsHarmonics() {
        TargetSpectrum spectrum = TargetSpectrum.build(25.0, GEOMETRY_6205, 10);
        var base = BearingKinematics.compute(25.0, GEOMETRY_6205);

        assertThat(spectrum.size()).isEqualTo(50);
        assertThat(frequency(spectrum, FrequencyFamily.SHAFT, 1, 0.0)).isCloseTo(25.0, within(1e-12));
        assertThat(frequency(spectrum, FrequencyFamily.BPFO, 1, 0.0)).isCloseTo(base.bpfoHz(), within(1e-12));
        assertThat(frequency(spectrum, FrequencyFamily.BPFI, 3, 0.0)).isCloseTo(3 * base.bpfiHz(), within(1e-12));
        assertThat(frequency(spectrum, FrequencyFamily.FTF, 7, 0.0)).isCloseTo(7 * base.ftfHz(), within(1e-12));
        assertThat(frequency(spectrum, FrequencyFamily.BSF, 10, 0.0)).isCloseTo(10 * base.bsfHz(), within(1e-12));
    }

    @Test
    @DisplayName("打滑只压低四个轴承族，转频族纹丝不动")
    void slipScalesBearingFamiliesOnly() {
        TargetSpectrum spectrum = TargetSpectrum.build(25.0, GEOMETRY_6205, 4);
        double s = 0.02;

        for (CandidateTarget t : spectrum.atSlip(s)) {
            if (t.family() == FrequencyFamily.SHAFT) {
                assertThat(t.correctedHz()).isCloseTo(t.theoreticalHz(), within(1e-12));
            } else {
                assertThat(t.correctedHz()).isCloseTo(t.theoreticalHz() * (1 - s), within(1e-12));
            }
        }
    }

    @Test
    @DisplayName("族序固定为 shaft,bpfo,bpfi,ftf,bsf，每族阶次递增")
    void familyOrderIsFixed() {
        TargetSpectrum spectrum = TargetSpectrum.build(25.0, GEOMETRY_6205, 3);
        List<CandidateTarget> targets = spectrum.atSlip(0.0);
        FrequencyFamily[] expectedOrder = {
                FrequencyFamily.SHAFT, FrequencyFamily.BPFO, FrequencyFamily.BPFI,
                FrequencyFamily.FTF, FrequencyFamily.BSF};
        for (int block = 0; block < 5; block++) {
            for (int k = 0; k < 3; k++) {
                CandidateTarget t = targets.get(block * 3 + k);
                assertThat(t.family()).isEqualTo(expectedOrder[block]);
                assertThat(t.order()).isEqualTo(k + 1);
            }
        }
    }

    private static double frequency(TargetSpectrum spectrum, FrequencyFamily family,
                                    int order, double slip) {
        return spectrum.atSlip(slip).stream()
                .filter(t -> t.family() == family && t.order() == order)
                .findFirst().orElseThrow().correctedHz();
    }
}
