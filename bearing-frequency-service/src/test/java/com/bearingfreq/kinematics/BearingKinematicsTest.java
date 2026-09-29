package com.bearingfreq.kinematics;

import com.bearingfreq.model.BearingGeometry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 运动学核心事实的回归测试：
 * 转频翻倍四频齐翻、滚动体个数翻倍保持架频率不变、常规工况 BPFI > BPFO、
 * 接触角余弦项真实参与计算，以及 6205 深沟球轴承手算基准。
 */
class BearingKinematicsTest {

    /** 6205 深沟球轴承（CWRU 基准几何）：9 滚动体，节圆 39.04 mm，滚动体 7.94 mm，接触角 0°。 */
    private static final BearingGeometry DEEP_GROOVE_6205 =
            new BearingGeometry(9, 39.04, 7.94, 0.0);

    @Test
    @DisplayName("转频翻一倍，四个特征频率全部翻倍")
    void doublingRotationFrequencyDoublesAllFourFrequencies() {
        var at25 = BearingKinematics.compute(25.0, DEEP_GROOVE_6205);
        var at50 = BearingKinematics.compute(50.0, DEEP_GROOVE_6205);

        assertThat(at50.bpfoHz()).isCloseTo(2.0 * at25.bpfoHz(), within(1e-9));
        assertThat(at50.bpfiHz()).isCloseTo(2.0 * at25.bpfiHz(), within(1e-9));
        assertThat(at50.ftfHz()).isCloseTo(2.0 * at25.ftfHz(), within(1e-9));
        assertThat(at50.bsfHz()).isCloseTo(2.0 * at25.bsfHz(), within(1e-9));
    }

    @Test
    @DisplayName("滚动体个数翻倍：外圈/内圈通过频率翻倍，保持架频率纹丝不动")
    void doublingRollerCountDoublesPassFrequenciesButLeavesCageFrequency() {
        var fewRollers = new BearingGeometry(8, 40.0, 6.0, 12.0);
        var manyRollers = new BearingGeometry(16, 40.0, 6.0, 12.0);

        var few = BearingKinematics.compute(30.0, fewRollers);
        var many = BearingKinematics.compute(30.0, manyRollers);

        assertThat(many.bpfoHz()).isCloseTo(2.0 * few.bpfoHz(), within(1e-9));
        assertThat(many.bpfiHz()).isCloseTo(2.0 * few.bpfiHz(), within(1e-9));
        // 保持架频率与滚动体个数无关
        assertThat(many.ftfHz()).isCloseTo(few.ftfHz(), within(1e-12));
        // 滚动体自转频率同样与个数无关
        assertThat(many.bsfHz()).isCloseTo(few.bsfHz(), within(1e-12));
    }

    @Test
    @DisplayName("外圈固定的常规工况下，内圈通过频率大于外圈通过频率")
    void innerRacePassFrequencyExceedsOuterUnderNormalOperation() {
        for (double contactAngle : new double[]{0.0, 5.0, 10.0, 15.0}) {
            var geometry = new BearingGeometry(9, 39.04, 7.94, contactAngle);
            var f = BearingKinematics.compute(25.0, geometry);
            assertThat(f.bpfiHz())
                    .as("接触角 %s° 时 BPFI 应大于 BPFO", contactAngle)
                    .isGreaterThan(f.bpfoHz());
        }
    }

    @Test
    @DisplayName("接触角从 0° 增大到 15°，余弦项的变化如实反映到各频率上")
    void contactAngleCosineGenuinelyParticipates() {
        double[] angles = {0.0, 5.0, 10.0, 15.0};
        var previous = BearingKinematics.compute(25.0, geometryAt(angles[0]));
        for (int i = 1; i < angles.length; i++) {
            var current = BearingKinematics.compute(25.0, geometryAt(angles[i]));
            // cos α 随角度增大而减小：BPFO/FTF/BSF 增大，BPFI 减小
            assertThat(current.bpfoHz()).isGreaterThan(previous.bpfoHz());
            assertThat(current.ftfHz()).isGreaterThan(previous.ftfHz());
            assertThat(current.bsfHz()).isGreaterThan(previous.bsfHz());
            assertThat(current.bpfiHz()).isLessThan(previous.bpfiHz());
            previous = current;
        }
        // 若漏掉余弦项，角度怎么变频率都纹丝不动——这里显式钉住两端差异
        var at0 = BearingKinematics.compute(25.0, geometryAt(0.0));
        var at15 = BearingKinematics.compute(25.0, geometryAt(15.0));
        assertThat(at15.bpfoHz()).isNotCloseTo(at0.bpfoHz(), within(1e-6));
        assertThat(at15.bpfiHz()).isNotCloseTo(at0.bpfiHz(), within(1e-6));
        assertThat(at15.ftfHz()).isNotCloseTo(at0.ftfHz(), within(1e-6));
        assertThat(at15.bsfHz()).isNotCloseTo(at0.bsfHz(), within(1e-6));
    }

    @Test
    @DisplayName("6205 深沟球轴承基准算例与手算值一致（BPFI > BPFO）")
    void deepGrooveBallBearing6205BenchmarkMatchesHandCalculation() {
        // 手算：β = 7.94/39.04 = 0.203381；fr = 25 Hz
        // BPFO = 4.5·25·(1−β) = 89.6196 Hz
        // BPFI = 4.5·25·(1+β) = 135.3804 Hz
        // FTF  = 12.5·(1−β)   = 9.9577 Hz
        // BSF  = 39.04/15.88·25·(1−β²) = 58.9188 Hz
        var f = BearingKinematics.compute(25.0, DEEP_GROOVE_6205);

        assertThat(f.bpfoHz()).isCloseTo(89.6196, within(1e-3));
        assertThat(f.bpfiHz()).isCloseTo(135.3804, within(1e-3));
        assertThat(f.ftfHz()).isCloseTo(9.9577, within(1e-3));
        assertThat(f.bsfHz()).isCloseTo(58.9188, within(1e-3));
        assertThat(f.bpfiHz()).isGreaterThan(f.bpfoHz());
    }

    private static BearingGeometry geometryAt(double contactAngleDeg) {
        return new BearingGeometry(9, 39.04, 7.94, contactAngleDeg);
    }
}
