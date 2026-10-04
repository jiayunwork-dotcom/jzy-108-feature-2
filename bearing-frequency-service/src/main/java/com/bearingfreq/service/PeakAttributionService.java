package com.bearingfreq.service;

import com.bearingfreq.attribution.AttributionInputValidator;
import com.bearingfreq.attribution.EngineOutcome;
import com.bearingfreq.attribution.MeasuredPeak;
import com.bearingfreq.attribution.SlipAttributionEngine;
import com.bearingfreq.attribution.TargetSpectrum;
import com.bearingfreq.catalog.BearingCatalog;
import com.bearingfreq.catalog.CatalogEntryNotFoundException;
import com.bearingfreq.model.BearingGeometry;
import com.bearingfreq.validation.BearingInputValidator;
import org.springframework.stereotype.Service;

/**
 * 峰值归属 + 打滑估计的编排层：输入校验（先于一切计算）→ 解析几何来源 →
 * 复用运动学生成候选目标谱 → 打滑迭代归属。本层不写配对与打滑算法。
 */
@Service
public class PeakAttributionService {

    private final BearingCatalog catalog;

    public PeakAttributionService(BearingCatalog catalog) {
        this.catalog = catalog;
    }

    /** 执行峰值归属（内联几何或引用轴承档，二选一在解析前已被校验拦截）。 */
    public EngineOutcome attribute(AttributionQuery query) {
        double fr = BearingInputValidator.requireRotationFrequencyHz(
                query.rotationFrequencyHz(), query.speedRpm());
        AttributionInputValidator.validatePeaks(query.peaks());
        int maxOrder = AttributionInputValidator.requireMaxOrder(query.maxOrder());
        double tolerance = AttributionInputValidator.requireRelativeTolerance(
                query.relativeTolerance());
        double resolutionHz = AttributionInputValidator.requireResolutionHz(
                query.frequencyResolutionHz());
        double[] slipRange = AttributionInputValidator.requireSlipRange(
                query.minSlip(), query.maxSlip());
        double minSlip = slipRange[0];
        double maxSlip = slipRange[1];
        double locked = AttributionInputValidator.requireLockedSlip(
                query.lockedSlip(), minSlip, maxSlip);
        Double lockedSlip = Double.isNaN(locked) ? null : locked;
        AttributionInputValidator.requireExactlyOneGeometrySource(
                query.geometry() != null, query.bearingName());

        // 内联几何先做几何合法性校验；引用档名不存在沿用 404 语义。
        BearingGeometry geometry = query.geometry() != null
                ? resolveInline(query.geometry())
                : catalog.find(query.bearingName())
                        .orElseThrow(() -> new CatalogEntryNotFoundException(query.bearingName()));

        TargetSpectrum spectrum = TargetSpectrum.build(fr, geometry, maxOrder);
        return SlipAttributionEngine.run(query.peaks(), spectrum, tolerance, resolutionHz,
                minSlip, maxSlip, lockedSlip);
    }

    private BearingGeometry resolveInline(BearingGeometry geometry) {
        BearingInputValidator.validateGeometry(geometry);
        return geometry;
    }
}
