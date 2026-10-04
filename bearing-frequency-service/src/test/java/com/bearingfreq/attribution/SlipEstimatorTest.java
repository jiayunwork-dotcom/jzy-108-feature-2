package com.bearingfreq.attribution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class SlipEstimatorTest {

    private static Assignment bearing(FrequencyFamily family, double peakHz, double theoreticalHz) {
        return new Assignment(0, peakHz, 1.0, family, 1, theoreticalHz, theoreticalHz, 0.0, 0.0);
    }

    @Test
    @DisplayName("轴承族配对按 1 − 峰频/理论频率 等权平均，转频族不参与")
    void averagesBearingFamiliesOnly() {
        double slip = SlipEstimator.estimate(List.of(
                bearing(FrequencyFamily.BPFO, 89.6196 * 0.99, 89.6196),
                bearing(FrequencyFamily.BPFI, 135.3804 * 0.99, 135.3804),
                bearing(FrequencyFamily.SHAFT, 25.0, 25.0)), 0.0, 0.5);

        assertThat(slip).isCloseTo(0.01, within(1e-12));
    }

    @Test
    @DisplayName("等权：高阶与低阶、小幅值与大幅值权重相同")
    void weightsAreEqualAcrossOrdersAndAmplitudes() {
        double slip = SlipEstimator.estimate(List.of(
                bearing(FrequencyFamily.FTF, 9.9577 * 0.97, 9.9577),
                bearing(FrequencyFamily.BSF, 589.188 * 1.0, 589.188)), 0.0, 0.5);
        // 第一根 s=0.03，第二根 s=0，等权平均 0.015。
        assertThat(slip).isCloseTo(0.015, within(1e-12));
    }

    @Test
    @DisplayName("估计值夹到允许范围，触界返回边界")
    void clampsToAllowedRange() {
        assertThat(SlipEstimator.estimate(List.of(
                bearing(FrequencyFamily.BPFO, 89.6196 * 0.5, 89.6196)),
                0.0, 0.05)).isEqualTo(0.05, within(1e-12));
        // 峰高于理论（模型外）给出负 s，夹到下界 0。
        assertThat(SlipEstimator.estimate(List.of(
                bearing(FrequencyFamily.BPFO, 89.6196 * 1.02, 89.6196)),
                0.0, 0.05)).isZero();
    }

    @Test
    @DisplayName("没有轴承族配对时返回 NaN（打滑不可观测）")
    void nanWhenNoBearingAssignments() {
        double slip = SlipEstimator.estimate(List.of(
                bearing(FrequencyFamily.SHAFT, 25.0, 25.0)), 0.0, 0.05);
        assertThat(slip).isNaN();
    }
}
