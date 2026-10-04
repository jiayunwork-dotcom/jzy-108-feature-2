package com.bearingfreq.attribution;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 候选目标的频率族：转频族与四个轴承特征频率族。
 *
 * <p>{@code slipAffected} 标记该族是否受打滑影响：四个轴承部位的频率
 * 在打滑时统一按 (1 − s) 比例偏低；转频及其整数倍由转速直接决定，不参与打滑修正。
 */
public enum FrequencyFamily {

    /** 转频族（shaft）：k·fr，不受打滑修正。 */
    SHAFT("shaft", false),
    /** 外圈通过频率族（BPFO）。 */
    BPFO("bpfo", true),
    /** 内圈通过频率族（BPFI）。 */
    BPFI("bpfi", true),
    /** 保持架频率族（FTF）。 */
    FTF("ftf", true),
    /** 滚动体自转频率族（BSF）。 */
    BSF("bsf", true);

    private final String id;
    private final boolean slipAffected;

    FrequencyFamily(String id, boolean slipAffected) {
        this.id = id;
        this.slipAffected = slipAffected;
    }

    /** 接口与结果中使用的稳定标识。 */
    @JsonValue
    public String getId() {
        return id;
    }

    /** 该族频率是否随打滑统一偏低。 */
    public boolean isSlipAffected() {
        return slipAffected;
    }
}
