package com.bearingfreq.attribution;

import com.bearingfreq.catalog.BearingCatalog;
import com.bearingfreq.catalog.CatalogEntryNotFoundException;
import com.bearingfreq.model.BearingGeometry;
import org.springframework.stereotype.Service;

/**
 * 峰值归属编排：校验（几何/档名互斥、峰与各数值口径）→ 解析轴承档
 * （沿用 {@link BearingCatalog}，不存在按现有口径抛 404）→ 跑归属—打滑迭代。
 *
 * <p>不含任何公式与匹配细节，只负责把入参组织进
 * {@link AttributionInputValidator} 与 {@link AttributionEngine}。
 */
@Service
public class PeakAttributionService {

    private final BearingCatalog catalog;

    public PeakAttributionService(BearingCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * 执行一次「峰值归属 + 打滑估计」。
     *
     * @param rotationFrequencyHz 转频（Hz），与 speedRpm 二选一
     * @param speedRpm            转速（rpm）
     * @param geometry            内联几何（与 bearingName 互斥）
     * @param bearingName         已登记轴承档名称（与 geometry 互斥）
     * @param rawPeaks            实测峰（频率 Hz、幅值）
     * @param maxOrder            谐波阶次上限
     * @param relativeTolerance   相对容差
     * @param resolutionHz        频谱分辨率（Hz）
     * @param slipMin             打滑范围下限
     * @param slipMax             打滑范围上限
     * @param lockedSlip          锁死打滑值
     * @return 归属与打滑结果
     */
    public AttributionResult attribute(
            Double rotationFrequencyHz, Double speedRpm,
            BearingGeometry geometry, String bearingName,
            java.util.List<AttributionInputValidator.RawPeak> rawPeaks,
            Integer maxOrder, Double relativeTolerance, Double resolutionHz,
            Double slipMin, Double slipMax, Double lockedSlip) {

        AttributionParameters params = AttributionInputValidator.validate(
                rotationFrequencyHz, speedRpm, geometry, bearingName, rawPeaks,
                maxOrder, relativeTolerance, resolutionHz, slipMin, slipMax, lockedSlip);

        BearingGeometry resolvedGeometry = params.geometry();
        String resolvedName = params.bearingName();
        if (resolvedGeometry == null) {
            // 引用轴承档：不存在照现有做法回 404
            resolvedGeometry = catalog.find(resolvedName)
                    .orElseThrow(() -> new CatalogEntryNotFoundException(resolvedName));
        }

        AttributionParameters withGeometry = new AttributionParameters(
                params.rotationFrequencyHz(), resolvedGeometry, resolvedName,
                params.peaks(), params.maxOrder(), params.relativeTolerance(),
                params.frequencyResolutionHz(), params.slipMin(), params.slipMax(),
                params.lockedSlip());
        return AttributionEngine.run(withGeometry);
    }
}
