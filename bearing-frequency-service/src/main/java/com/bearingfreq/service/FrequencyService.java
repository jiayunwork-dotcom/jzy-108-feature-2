package com.bearingfreq.service;

import com.bearingfreq.catalog.BearingCatalog;
import com.bearingfreq.catalog.CatalogEntryNotFoundException;
import com.bearingfreq.kinematics.BearingKinematics;
import com.bearingfreq.model.BearingGeometry;
import com.bearingfreq.sideband.Sideband;
import com.bearingfreq.sideband.SidebandAnalyzer;
import com.bearingfreq.validation.BearingInputValidator;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 编排层：先校验输入，再调用运动学核心计算四个特征频率，
 * 按需追加边带信息；同时负责轴承档的登记与检索。
 */
@Service
public class FrequencyService {

    private final BearingCatalog catalog;

    public FrequencyService(BearingCatalog catalog) {
        this.catalog = catalog;
    }

    /** 用请求中直接给出的几何参数核算特征频率。 */
    public ComputationResult compute(Double rotationFrequencyHz, Double speedRpm,
                                     BearingGeometry geometry, Integer sidebandOrder) {
        double fr = BearingInputValidator.requireRotationFrequencyHz(rotationFrequencyHz, speedRpm);
        BearingInputValidator.validateGeometry(geometry);
        int order = BearingInputValidator.requireSidebandOrder(sidebandOrder);

        var frequencies = BearingKinematics.compute(fr, geometry);
        Map<String, List<Sideband>> sidebands = order > 0
                ? SidebandAnalyzer.aroundRotationFrequency(frequencies, fr, order)
                : Map.of();
        return new ComputationResult(fr, frequencies, sidebands);
    }

    /** 登记一个轴承档（几何参数先过校验才允许入档）。 */
    public void register(String name, BearingGeometry geometry) {
        BearingInputValidator.validateGeometry(geometry);
        catalog.register(name, geometry);
    }

    /** 检索轴承档，不存在则 404。 */
    public BearingGeometry entry(String name) {
        return catalog.find(name).orElseThrow(() -> new CatalogEntryNotFoundException(name));
    }

    /** 列出全部轴承档。 */
    public Map<String, BearingGeometry> entries() {
        return catalog.findAll();
    }

    /** 用已登记的轴承档核算特征频率。 */
    public ComputationResult computeForEntry(String name, Double rotationFrequencyHz,
                                             Double speedRpm, Integer sidebandOrder) {
        return compute(rotationFrequencyHz, speedRpm, entry(name), sidebandOrder);
    }
}
