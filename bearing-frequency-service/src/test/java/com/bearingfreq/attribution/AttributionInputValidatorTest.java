package com.bearingfreq.attribution;

import com.bearingfreq.validation.InvalidBearingInputException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttributionInputValidatorTest {

    private static List<MeasuredPeak> peaks(int n) {
        List<MeasuredPeak> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(new MeasuredPeak(10.0 + i, 1.0));
        }
        return list;
    }

    @Test
    @DisplayName("峰列表为空或超上限被拦")
    void emptyOrTooManyPeaksRejected() {
        assertThatThrownBy(() -> AttributionInputValidator.validatePeaks(List.of()))
                .isInstanceOf(InvalidBearingInputException.class)
                .hasMessageContaining("不能为空");
        assertThatThrownBy(() -> AttributionInputValidator.validatePeaks(null))
                .isInstanceOf(InvalidBearingInputException.class);
        assertThatThrownBy(() -> AttributionInputValidator.validatePeaks(peaks(501)))
                .isInstanceOf(InvalidBearingInputException.class)
                .hasMessageContaining("500");
        // 500 根为合法上限，不抛异常。
        AttributionInputValidator.validatePeaks(peaks(500));
    }

    @Test
    @DisplayName("峰频率不为正、幅值为负：指出是第几根")
    void badPeakReportsIndex() {
        List<MeasuredPeak> badFreq = new ArrayList<>(peaks(3));
        badFreq.set(2, new MeasuredPeak(0.0, 1.0));
        assertThatThrownBy(() -> AttributionInputValidator.validatePeaks(badFreq))
                .isInstanceOf(InvalidBearingInputException.class)
                .hasMessageContaining("第 2 根峰");

        List<MeasuredPeak> badAmp = new ArrayList<>(peaks(3));
        badAmp.set(0, new MeasuredPeak(10.0, -0.5));
        assertThatThrownBy(() -> AttributionInputValidator.validatePeaks(badAmp))
                .isInstanceOf(InvalidBearingInputException.class)
                .hasMessageContaining("第 0 根峰")
                .hasMessageContaining("第 1 根");
    }

    @Test
    @DisplayName("默认值口径：容差 2%、分辨率 0、阶次 10、打滑 [0, 5%]")
    void defaultsAreApplied() {
        assertThat(AttributionInputValidator.requireRelativeTolerance(null))
                .isCloseTo(0.02, org.assertj.core.data.Offset.offset(0.0));
        assertThat(AttributionInputValidator.requireResolutionHz(null)).isZero();
        assertThat(AttributionInputValidator.requireMaxOrder(null)).isEqualTo(10);
        double[] range = AttributionInputValidator.requireSlipRange(null, null);
        assertThat(range[0]).isZero();
        assertThat(range[1]).isCloseTo(0.05, org.assertj.core.data.Offset.offset(0.0));
    }

    @Test
    @DisplayName("容差/分辨率/阶次/打滑范围越界被拦")
    void outOfRangeParametersRejected() {
        assertThatThrownBy(() -> AttributionInputValidator.requireRelativeTolerance(0.0))
                .isInstanceOf(InvalidBearingInputException.class);
        assertThatThrownBy(() -> AttributionInputValidator.requireRelativeTolerance(0.11))
                .isInstanceOf(InvalidBearingInputException.class);
        assertThatThrownBy(() -> AttributionInputValidator.requireResolutionHz(-0.1))
                .isInstanceOf(InvalidBearingInputException.class);
        assertThatThrownBy(() -> AttributionInputValidator.requireMaxOrder(0))
                .isInstanceOf(InvalidBearingInputException.class);
        assertThatThrownBy(() -> AttributionInputValidator.requireMaxOrder(11))
                .isInstanceOf(InvalidBearingInputException.class);
        assertThatThrownBy(() -> AttributionInputValidator.requireSlipRange(0.03, 0.02))
                .isInstanceOf(InvalidBearingInputException.class);
        assertThatThrownBy(() -> AttributionInputValidator.requireSlipRange(-0.01, 0.02))
                .isInstanceOf(InvalidBearingInputException.class);
        assertThatThrownBy(() -> AttributionInputValidator.requireSlipRange(0.0, 0.6))
                .isInstanceOf(InvalidBearingInputException.class);
    }

    @Test
    @DisplayName("锁死打滑值必须落在允许范围内")
    void lockedSlipMustBeInRange() {
        assertThat(AttributionInputValidator.requireLockedSlip(0.02, 0.0, 0.05))
                .isCloseTo(0.02, org.assertj.core.data.Offset.offset(0.0));
        assertThatThrownBy(() -> AttributionInputValidator.requireLockedSlip(0.06, 0.0, 0.05))
                .isInstanceOf(InvalidBearingInputException.class)
                .hasMessageContaining("lockedSlip");
        assertThat(Double.isNaN(
                AttributionInputValidator.requireLockedSlip(null, 0.0, 0.05))).isTrue();
    }

    @Test
    @DisplayName("几何与档名同时给或都不给被拦")
    void geometrySourceXor() {
        assertThatThrownBy(() -> AttributionInputValidator
                .requireExactlyOneGeometrySource(true, "6205"))
                .isInstanceOf(InvalidBearingInputException.class)
                .hasMessageContaining("只能提供一个");
        assertThatThrownBy(() -> AttributionInputValidator
                .requireExactlyOneGeometrySource(false, null))
                .isInstanceOf(InvalidBearingInputException.class)
                .hasMessageContaining("之一");
        // 两种合法给法不抛。
        AttributionInputValidator.requireExactlyOneGeometrySource(true, null);
        AttributionInputValidator.requireExactlyOneGeometrySource(false, "6205");
    }
}
