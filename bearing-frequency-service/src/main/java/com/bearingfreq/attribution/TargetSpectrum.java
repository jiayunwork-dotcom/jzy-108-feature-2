package com.bearingfreq.attribution;

import com.bearingfreq.kinematics.BearingKinematics;
import com.bearingfreq.kinematics.CharacteristicFrequencies;
import com.bearingfreq.model.BearingGeometry;

import java.util.ArrayList;
import java.util.List;

/**
 * 候选目标谱的生成：复用 {@link BearingKinematics} 的运动学计算（不重复公式），
 * 生成转频族与四个轴承族各自 1..maxOrder 阶的谐波目标。
 *
 * <p>理论频率只在构造时算一次并缓存；给定打滑系数后用 {@link #atSlip(double)}
 * 线性缩放得到修正目标，避免迭代中反复走运动学公式。
 */
public final class TargetSpectrum {

    private final double rotationFrequencyHz;
    private final int maxOrder;
    /** 理论目标频率，按固定族序与阶次排列（即目标的规范化次序）。 */
    private final List<CandidateTarget> theoretical;

    private TargetSpectrum(double rotationFrequencyHz, int maxOrder,
                           List<CandidateTarget> theoretical) {
        this.rotationFrequencyHz = rotationFrequencyHz;
        this.maxOrder = maxOrder;
        this.theoretical = theoretical;
    }

    /**
     * 依据转频与轴承几何生成全部候选目标（按 s = 0 的理论值缓存）。
     *
     * @param rotationFrequencyHz 转频（Hz）
     * @param geometry            轴承几何（假定已校验）
     * @param maxOrder            谐波阶次上限（含）
     */
    public static TargetSpectrum build(double rotationFrequencyHz,
                                       BearingGeometry geometry, int maxOrder) {
        CharacteristicFrequencies f = BearingKinematics.compute(rotationFrequencyHz, geometry);
        List<CandidateTarget> targets = new ArrayList<>(5 * maxOrder);
        addFamily(targets, FrequencyFamily.SHAFT, maxOrder, rotationFrequencyHz);
        addFamily(targets, FrequencyFamily.BPFO, maxOrder, f.bpfoHz());
        addFamily(targets, FrequencyFamily.BPFI, maxOrder, f.bpfiHz());
        addFamily(targets, FrequencyFamily.FTF, maxOrder, f.ftfHz());
        addFamily(targets, FrequencyFamily.BSF, maxOrder, f.bsfHz());
        return new TargetSpectrum(rotationFrequencyHz, maxOrder, List.copyOf(targets));
    }

    private static void addFamily(List<CandidateTarget> sink, FrequencyFamily family,
                                  int maxOrder, double baseHz) {
        for (int k = 1; k <= maxOrder; k++) {
            double theoreticalHz = baseHz * k;
            sink.add(new CandidateTarget(family, k, theoreticalHz, theoreticalHz));
        }
    }

    public double rotationFrequencyHz() {
        return rotationFrequencyHz;
    }

    public int maxOrder() {
        return maxOrder;
    }

    /** 理论目标数量（5 族 × maxOrder）。 */
    public int size() {
        return theoretical.size();
    }

    /**
     * 按给定打滑系数生成修正后的全部候选目标。
     * 转频族不修正；四个轴承族统一乘 (1 − s)。
     */
    public List<CandidateTarget> atSlip(double slip) {
        List<CandidateTarget> corrected = new ArrayList<>(theoretical.size());
        for (CandidateTarget t : theoretical) {
            double correctedHz = t.family().isSlipAffected()
                    ? t.theoreticalHz() * (1.0 - slip)
                    : t.theoreticalHz();
            corrected.add(new CandidateTarget(t.family(), t.order(),
                    t.theoreticalHz(), correctedHz));
        }
    return List.copyOf(corrected);
    }
}
