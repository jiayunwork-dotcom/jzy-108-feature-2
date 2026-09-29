package com.bearingfreq.api.dto;

import com.bearingfreq.model.BearingGeometry;

/**
 * 轴承档条目：名称 + 几何参数。
 */
public record CatalogEntryResponse(String name, BearingGeometry geometry) {
}
