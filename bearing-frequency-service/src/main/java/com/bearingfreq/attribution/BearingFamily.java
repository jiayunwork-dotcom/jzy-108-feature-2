package com.bearingfreq.attribution;

import java.util.Comparator;

/**
 * 谱峰归属的目标族：转频族 + 四个轴承特征频率族。
 *
 * <p>{@link #SHAFT}（转频及其整数倍）不受打滑修正；
 * {@link #BPFO}、{@link #BPFI}、{@link #FTF}、{@link #BSF} 四族的目标频率
 * 统一按 {@code 理论值 × (1 − 打滑系数)} 修正。
 *
 * <p>枚举声明顺序即族的规范排序（也是全局配对平局裁决时的优先级），
 * 不可随意调整。
 */
public enum BearingFamily {

    /** 转频族：k·fr（k = 1..maxOrder），不受打滑影响。 */
    SHAFT("shaft", false),

    /** 外圈通过频率族 BPFO。 */
    BPFO("bpfo", true),

    /** 内圈通过频率族 BPFI。 */
    BPFI("bpfi", true),

    /** 保持架频率族 FTF。 */
    FTF("ftf", true),

    /** 滚动体自转频率族 BSF。 */
    BSF("bsf", true);

    /** 对外（JSON）使用的稳定标识，与正算接口的命名口径保持一致。 */
    private final String id;

    /** 是否随打滑系数一起被压低（四个轴承部位为 true，转频族为 false）。 */
    private final boolean affectedBySlip;

    BearingFamily(String id, boolean affectedBySlip) {
        this.id = id;
        this.affectedBySlip = affectedBySlip;
    }

    public String id() {
        return id;
    }

    /** 该族频率是否受打滑修正。 */
    public boolean affectedBySlip() {
        return affectedBySlip;
    }

    /** 按 (族规范顺序, 阶次) 排序目标的比较器。 */
    public static Comparator<Target> targetOrder() {
        return Comparator
                .comparingInt((Target t) -> t.family().ordinal())
                .thenComparingInt(Target::order);
    }
}
