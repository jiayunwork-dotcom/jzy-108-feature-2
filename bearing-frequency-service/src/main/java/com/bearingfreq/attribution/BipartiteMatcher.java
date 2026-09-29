package com.bearingfreq.attribution;

import java.util.ArrayList;
import java.util.List;

/**
 * 全局最优一对一配对求解：给定规范序的峰列表、目标表与某个打滑系数，
 * 构造所有「峰—目标」合法边（落在匹配窗口内），并用矩形匈牙利算法在
 * 「配对数最大 → 总代价最小 → 规范序平局裁决」的字典序口径下求出归属。
 *
 * <p>不逐个就近认领：一根峰可同时邻近多个目标、一个目标旁也可有多根峰，
 * 全局最优保证不会为成全一对而把别的峰挤到更差的位置。
 *
 * <h3>代价矩阵构造</h3>
 * <p>设峰数 P、目标数 T（T ≤ 5×maxOrder）。矩阵为 {@code T} 行 ×{@code P + T} 列：
 * 前 P 列是真实峰，后 T 列是每个目标私有的「未配上」哑列。匈牙利分配保证
 * 每行（目标）恰好占一列且各列互异，因此一根峰至多被一个目标认领，
 * 也没有两个目标能配同一根峰；目标落进自己的哑列即表示该目标未配上。
 * <ul>
 *   <li>合法真实边：{@code 相对偏差² + 平局微扰}，微扰为
 *       {@code 1e-12 × (targetRank × (P+1) + peakRank)}，只在总代价相等时
 *       决定裁决，不改变真实代价口径；</li>
 *   <li>非法真实边（超窗口）：{@link #INACCESSIBLE}，远大于任何可行代价；</li>
 *   <li>私有哑列：对应目标自己的哑列为 {@link #UNMATCHED_PENALTY}，
 *       其余目标的哑列为 {@link #INACCESSIBLE}。</li>
 * </ul>
 * 哑列罚分远大于任意真实边代价、又远小于不可达边，因此：
 * 目标宁可多配一对真实边；只有没有任何可达真实边（或争抢后落败）时，
 * 目标才落回自己的哑列（＝未配上）。未被任何目标选中的真实峰即未归属峰。
 *
 * <p>矩阵规模仅 T×(P+T)，而 T 至多 50，矩形匈牙利为 O(T²·(P+T))：
 * 500 峰 × 10 阶下单次数十万次基本运算，性能裕度充足。
 */
final class BipartiteMatcher {

    /** 不可达边（超出匹配窗口 / 占用他人哑列）的代价。 */
    static final double INACCESSIBLE = 1.0e15;

    /** 目标选择「未配上」的罚分：远大于任意真实边代价（真实代价 ≪ 1）。 */
    static final double UNMATCHED_PENALTY = 1.0e3;

    /** 平局裁决微扰系数，保证同输入同结果且不影响真实总代价。 */
    private static final double TIE_BREAK_EPSILON = 1.0e-12;

    private BipartiteMatcher() {
    }

    /**
     * 在给定打滑系数下做一次全局最优归属。
     *
     * @param peaks             规范序峰列表
     * @param targets           规范序目标表
     * @param slip              当前打滑系数
     * @param relativeTolerance 相对容差
     * @param resolutionHz      频谱分辨率（Hz，可为 0）
     * @return 归属结果
     */
    static MatchingResult match(List<MeasuredPeak> peaks, List<Target> targets,
                                double slip, double relativeTolerance, double resolutionHz) {
        int p = peaks.size();
        int t = targets.size();
        int columns = p + t;

        double[][] cost = new double[t][columns];
        Edge[][] edges = new Edge[t][p];
        for (int ti = 0; ti < t; ti++) {
            java.util.Arrays.fill(cost[ti], INACCESSIBLE);
            // 每个目标私有的未配对哑列：第 p + ti 列
            cost[ti][p + ti] = UNMATCHED_PENALTY;
        }

        for (int ti = 0; ti < t; ti++) {
            Target target = targets.get(ti);
            double adjusted = target.adjustedHz(slip);
            double halfWindow = relativeTolerance * adjusted + 0.5 * resolutionHz;
            for (int pi = 0; pi < p; pi++) {
                double peakHz = peaks.get(pi).frequencyHz();
                double deviation = peakHz - adjusted;
                if (Math.abs(deviation) <= halfWindow) {
                    double relative = deviation / adjusted;
                    double rank = (double) ti * columns + pi;
                    Edge edge = new Edge(target, pi, adjusted, deviation, relative,
                            relative * relative + TIE_BREAK_EPSILON * rank);
                    edges[ti][pi] = edge;
                    cost[ti][pi] = edge.cost();
                }
            }
        }

        int[] assignedColumn = HungarianAlgorithm.assign(cost);

        List<Assignment> assignments = new ArrayList<>();
        boolean[] matchedPeak = new boolean[p];
        double totalSquared = 0.0;
        double totalAbsHz = 0.0;
        for (int ti = 0; ti < t; ti++) {
            int col = assignedColumn[ti];
            if (col < p) {
                Edge edge = edges[ti][col];
                if (edge == null) {
                    // 理论上不可达边永不会被选中（罚分与可达边的尺度差极大），
                    // 作为最后防线：选中即视为该目标未配上。
                    continue;
                }
                matchedPeak[col] = true;
                MeasuredPeak peak = peaks.get(col);
                assignments.add(new Assignment(peak, edge.target(), edge.adjustedTargetHz(),
                        edge.deviationHz(), edge.relativeDeviation()));
                // 上报的总代价只含真实相对偏差平方，不含平局微扰。
                totalSquared += edge.relativeDeviation() * edge.relativeDeviation();
                totalAbsHz += Math.abs(edge.deviationHz());
            }
        }
        assignments.sort(Assignment.canonicalOrder());

        List<Integer> matched = new ArrayList<>();
        List<Integer> unmatched = new ArrayList<>();
        for (int pi = 0; pi < p; pi++) {
            (matchedPeak[pi] ? matched : unmatched).add(pi);
        }
        return new MatchingResult(List.copyOf(assignments),
                List.copyOf(matched), List.copyOf(unmatched), totalSquared, totalAbsHz);
    }
}
