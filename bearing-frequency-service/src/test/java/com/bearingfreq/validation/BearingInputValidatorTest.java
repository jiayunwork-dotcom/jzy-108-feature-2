package com.bearingfreq.validation;

import com.bearingfreq.model.BearingGeometry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 参数合法性检查：不合几何常理的输入必须在计算之前被拦下，并讲明缘由。
 */
class BearingInputValidatorTest {

    private static final BearingGeometry VALID =
            new BearingGeometry(9, 39.04, 7.94, 0.0);

    @Test
    @DisplayName("滚动体直径大于或等于节圆直径：打回并说明几何不成立")
    void rollerDiameterNotSmallerThanPitchDiameterIsRejected() {
        assertThatThrownBy(() -> BearingInputValidator.validateGeometry(
                new BearingGeometry(9, 39.04, 39.04, 0.0)))
                .isInstanceOf(InvalidBearingInputException.class)
                .hasMessageContaining("滚动体直径必须小于节圆直径");
        assertThatThrownBy(() -> BearingInputValidator.validateGeometry(
                new BearingGeometry(9, 39.04, 40.0, 0.0)))
                .isInstanceOf(InvalidBearingInputException.class)
                .hasMessageContaining("滚动体直径必须小于节圆直径");
    }

    @Test
    @DisplayName("滚动体个数小于 3：打回")
    void fewerThanThreeRollersIsRejected() {
        assertThatThrownBy(() -> BearingInputValidator.validateGeometry(
                new BearingGeometry(2, 39.04, 7.94, 0.0)))
                .isInstanceOf(InvalidBearingInputException.class)
                .hasMessageContaining("滚动体个数不得小于 3");
    }

    @Test
    @DisplayName("转频不为正（0、负数、NaN、无穷）：打回")
    void nonPositiveRotationFrequencyIsRejected() {
        for (double bad : new double[]{0.0, -25.0, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThatThrownBy(() -> BearingInputValidator.requirePositiveRotationFrequency(bad))
                    .isInstanceOf(InvalidBearingInputException.class)
                    .hasMessageContaining("转频必须为正数");
        }
    }

    @Test
    @DisplayName("接触角余弦绝对值大于 1：打回（防御性检查）")
    void cosineMagnitudeAboveOneIsRejected() {
        assertThatThrownBy(() -> BearingInputValidator.requireCosineMagnitudeWithinOne(1.5))
                .isInstanceOf(InvalidBearingInputException.class)
                .hasMessageContaining("余弦的绝对值不得大于 1");
        assertThatThrownBy(() -> BearingInputValidator.requireCosineMagnitudeWithinOne(-1.5))
                .isInstanceOf(InvalidBearingInputException.class)
                .hasMessageContaining("余弦的绝对值不得大于 1");
        assertThatThrownBy(() -> BearingInputValidator.requireCosineMagnitudeWithinOne(Double.NaN))
                .isInstanceOf(InvalidBearingInputException.class);
        assertThatCode(() -> BearingInputValidator.requireCosineMagnitudeWithinOne(0.9063))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("接触角超出 0~90 度或不是有限值：打回")
    void implausibleContactAngleIsRejected() {
        for (double bad : new double[]{-0.5, 90.5, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThatThrownBy(() -> BearingInputValidator.requireContactAngle(bad))
                    .isInstanceOf(InvalidBearingInputException.class)
                    .hasMessageContaining("接触角");
        }
    }

    @Test
    @DisplayName("转频与转速必须且只能提供一个；rpm 正确换算为 Hz")
    void rotationFrequencyResolution() {
        assertThat(BearingInputValidator.requireRotationFrequencyHz(25.0, null)).isEqualTo(25.0);
        assertThat(BearingInputValidator.requireRotationFrequencyHz(null, 1500.0)).isEqualTo(25.0);

        assertThatThrownBy(() -> BearingInputValidator.requireRotationFrequencyHz(25.0, 1500.0))
                .isInstanceOf(InvalidBearingInputException.class)
                .hasMessageContaining("二选一");
        assertThatThrownBy(() -> BearingInputValidator.requireRotationFrequencyHz(null, null))
                .isInstanceOf(InvalidBearingInputException.class)
                .hasMessageContaining("必须提供");
        assertThatThrownBy(() -> BearingInputValidator.requireRotationFrequencyHz(null, -600.0))
                .isInstanceOf(InvalidBearingInputException.class)
                .hasMessageContaining("转频必须为正数");
    }

    @Test
    @DisplayName("合法输入顺利通过")
    void validInputPasses() {
        assertThatCode(() -> BearingInputValidator.validateGeometry(VALID))
                .doesNotThrowAnyException();
        assertThatCode(() -> BearingInputValidator.requirePositiveRotationFrequency(25.0))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("边带阶次：缺省为 0，越界打回")
    void sidebandOrderValidation() {
        assertThat(BearingInputValidator.requireSidebandOrder(null)).isZero();
        assertThat(BearingInputValidator.requireSidebandOrder(3)).isEqualTo(3);
        assertThatThrownBy(() -> BearingInputValidator.requireSidebandOrder(-1))
                .isInstanceOf(InvalidBearingInputException.class);
        assertThatThrownBy(() -> BearingInputValidator.requireSidebandOrder(99))
                .isInstanceOf(InvalidBearingInputException.class);
    }
}
