package com.bearingfreq.service;

import com.bearingfreq.catalog.BearingCatalog;
import com.bearingfreq.catalog.CatalogEntryNotFoundException;
import com.bearingfreq.model.BearingGeometry;
import com.bearingfreq.validation.InvalidBearingInputException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class FrequencyServiceTest {

    private final FrequencyService service = new FrequencyService(new BearingCatalog());

    @Test
    @DisplayName("编排顺序：先校验后计算，非法几何在计算前被拦下")
    void invalidGeometryIsRejectedBeforeComputation() {
        var bad = new BearingGeometry(9, 39.04, 39.04, 0.0);
        assertThatThrownBy(() -> service.compute(25.0, null, bad, null))
                .isInstanceOf(InvalidBearingInputException.class)
                .hasMessageContaining("滚动体直径必须小于节圆直径");
    }

    @Test
    @DisplayName("speedRpm 换算为转频后参与计算")
    void rpmIsConvertedToHz() {
        var geometry = new BearingGeometry(9, 39.04, 7.94, 0.0);
        var byHz = service.compute(25.0, null, geometry, null);
        var byRpm = service.compute(null, 1500.0, geometry, null);

        assertThat(byRpm.rotationFrequencyHz()).isCloseTo(25.0, within(1e-12));
        assertThat(byRpm.frequencies().bpfoHz())
                .isCloseTo(byHz.frequencies().bpfoHz(), within(1e-9));
    }

    @Test
    @DisplayName("请求边带时按阶次输出，未请求时为空")
    void sidebandsOnlyWhenRequested() {
        var geometry = new BearingGeometry(9, 39.04, 7.94, 0.0);

        var plain = service.compute(25.0, null, geometry, null);
        assertThat(plain.sidebands()).isEmpty();

        var withBands = service.compute(25.0, null, geometry, 2);
        assertThat(withBands.sidebands()).containsOnlyKeys("bpfo", "bpfi", "ftf", "bsf");
        assertThat(withBands.sidebands().get("bpfo")).hasSize(2);
        assertThat(withBands.sidebands().get("bpfo").get(0).lowerHz())
                .isCloseTo(withBands.frequencies().bpfoHz() - 25.0, within(1e-9));
    }

    @Test
    @DisplayName("两个轴承档同时被查询：各自参数只属于本档，互不渗透")
    void catalogEntriesDoNotLeakIntoEachOther() {
        // 除滚动体个数外完全相同的两份几何
        service.register("A", new BearingGeometry(8, 40.0, 6.0, 0.0));
        service.register("B", new BearingGeometry(16, 40.0, 6.0, 0.0));

        var a1 = service.computeForEntry("A", 30.0, null, null);
        var b = service.computeForEntry("B", 30.0, null, null);
        var a2 = service.computeForEntry("A", 30.0, null, null);

        // A 档用 8 个滚动体：BPFO = 8/2·30·(1−0.15) = 102 Hz
        assertThat(a1.frequencies().bpfoHz()).isCloseTo(102.0, within(1e-9));
        // B 档用 16 个滚动体：BPFO = 204 Hz，恰为 A 的两倍
        assertThat(b.frequencies().bpfoHz()).isCloseTo(204.0, within(1e-9));
        // 查过 B 之后再查 A，结果不变——B 的参数没有渗进 A
        assertThat(a2.frequencies().bpfoHz()).isCloseTo(102.0, within(1e-9));
        // 保持架频率与个数无关，两档一致
        assertThat(b.frequencies().ftfHz()).isCloseTo(a1.frequencies().ftfHz(), within(1e-12));
    }

    @Test
    @DisplayName("查询不存在的轴承档：404 语义")
    void unknownCatalogEntryThrows() {
        assertThatThrownBy(() -> service.computeForEntry("NOPE", 25.0, null, null))
                .isInstanceOf(CatalogEntryNotFoundException.class)
                .hasMessageContaining("NOPE");
    }
}
