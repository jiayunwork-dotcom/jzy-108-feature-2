package com.bearingfreq.attribution;

import com.bearingfreq.kinematics.BearingKinematics;
import com.bearingfreq.kinematics.CharacteristicFrequencies;
import com.bearingfreq.model.BearingGeometry;

import java.util.ArrayList;
import java.util.List;

/**
 * 候选目标表：转频以及 BPFO/BPFI/FTF/BSF 各自第 1..maxOrder 阶整数倍，
 * 共 {@code 5 × maxOrder} 个目标（族间频率可能重合，各目标仍独立参与匹配）。
 *
 * <p>第 1 阶基频直接复用 {@link BearingKinematics#compute}，
 * 不重复抄写运动学公式；打滑修正作用于倍乘之后。
 */
final class TargetTable {

    private TargetTable() {
    }

    /**
     * 按规范顺序（{@link BearingFamily#targetOrder()}）生成全部候选目标。
     *
     * @param rotationFrequencyHz 转频（Hz）
     * @param geometry            轴承几何
     * @param maxOrder            谐波阶次上限
     * @return 不可再假定可修改的目标列表（按规范序排列）
     */
    static List<Target> build(double rotationFrequencyHz, BearingGeometry geometry, int maxOrder) {
        CharacteristicFrequencies f = BearingKinematics.compute(rotationFrequencyHz, geometry);
        List<Target> targets = new ArrayList<>(BearingFamily.values().length * maxOrder);
        for (BearingFamily family : BearingFamily.values()) {
            double baseHz = baseFrequencyHz(family, f, rotationFrequencyHz);
            for (int order = 1; order <= maxOrder; order++) {
                targets.add(new Target(family, order, baseHz, baseHz * order));
            }
        }
        targets.sort(BearingFamily.targetOrder());
        return List.copyOf(targets);
    }

    private static double baseFrequencyHz(BearingFamily family,
                                          CharacteristicFrequencies f,
                                          double rotationFrequencyHz) {
        return switch (family) {
            case SHAFT -> rotationFrequencyHz;
            case BPFO -> f.bpfoHz();
            case BPFI -> f.bpfiHz();
            case FTF -> f.ftfHz();
            case BSF -> f.bsfHz();
        };
    }
}
