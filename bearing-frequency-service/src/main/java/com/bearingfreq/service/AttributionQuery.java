package com.bearingfreq.service;

import com.bearingfreq.attribution.MeasuredPeak;
import com.bearingfreq.model.BearingGeometry;

import java.util.List;

/**
 * 峰值归属的服务层请求：转频/转速沿用二选一口径，几何来源为「内联几何」或
 * 「已登记轴承档名」二选一，匹配与打滑参数均可缺省（由校验层补默认值）。
 */
public record AttributionQuery(Double rotationFrequencyHz,
                               Double speedRpm,
                               List<MeasuredPeak> peaks,
                               BearingGeometry geometry,
                               String bearingName,
                               Integer maxOrder,
                               Double relativeTolerance,
                               Double frequencyResolutionHz,
                               Double minSlip,
                               Double maxSlip,
                               Double lockedSlip) {
}
