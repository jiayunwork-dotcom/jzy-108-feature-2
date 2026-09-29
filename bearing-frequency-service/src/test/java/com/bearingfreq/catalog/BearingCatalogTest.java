package com.bearingfreq.catalog;

import com.bearingfreq.model.BearingGeometry;
import com.bearingfreq.validation.InvalidBearingInputException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BearingCatalogTest {

    private static final BearingGeometry G6205 =
            new BearingGeometry(9, 39.04, 7.94, 0.0);
    private static final BearingGeometry G6203 =
            new BearingGeometry(8, 28.5, 6.35, 0.0);

    @Test
    @DisplayName("登记后可按名称检索；未登记的名称检索为空")
    void registerAndFind() {
        var catalog = new BearingCatalog();
        catalog.register("6205", G6205);

        assertThat(catalog.find("6205")).contains(G6205);
        assertThat(catalog.find("9999")).isEmpty();
    }

    @Test
    @DisplayName("两个轴承档各自独立：后登记的档不会污染先登记的档")
    void entriesAreIsolatedFromEachOther() {
        var catalog = new BearingCatalog();
        catalog.register("6205", G6205);
        catalog.register("6203", G6203);

        // 6203 入档之后，6205 的几何参数仍原封不动
        assertThat(catalog.find("6205")).contains(G6205);
        assertThat(catalog.find("6203")).contains(G6203);
        assertThat(catalog.findAll()).containsOnlyKeys("6203", "6205");
    }

    @Test
    @DisplayName("同名再登记视为覆盖")
    void reRegisterOverwrites() {
        var catalog = new BearingCatalog();
        catalog.register("X", G6205);
        catalog.register("X", G6203);
        assertThat(catalog.find("X")).contains(G6203);
    }

    @Test
    @DisplayName("空白名称拒绝入档")
    void blankNameIsRejected() {
        var catalog = new BearingCatalog();
        assertThatThrownBy(() -> catalog.register("  ", G6205))
                .isInstanceOf(InvalidBearingInputException.class)
                .hasMessageContaining("名称不能为空");
    }
}
