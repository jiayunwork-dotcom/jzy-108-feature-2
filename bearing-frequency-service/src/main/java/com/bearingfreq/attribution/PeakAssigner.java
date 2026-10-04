package com.bearingfreq.attribution;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * 峰归属求解器：在「一根峰至多归一个目标、一个目标至多认一根峰、超窗不配」
 * 的全部合法方案中，求字典序最优的一对一配对。
 *
 * <p>优化目标（字典序，前者优先）：
 * <ol>
 *   <li><b>配对数最多</b>——先保证尽量多的峰被认领；</li>
 *   <li><b>总绝对相对偏差最小</b>——Σ|峰频 − 修正目标|/修正目标；</li>
 *   <li><b>平局裁决</b>——优先占用编号更小的目标（族序 shaft→bsf、阶次升序），
 *       其次编号更小的峰（规范化后），保证同输入必得同结果。</li>
 * </ol>
 *
 * <p>配对数优先由最小费用最大流「能增广则增广」的结构天然保证：只要还存在
 * source→sink 的增广路就增广一对，直到不存在增广路。后两级目标编码进边费用：
 * <pre>
 *   合法边费用 = round(相对偏差 × 1e6) × MICRO + (目标编号 × 1000 + 峰编号)
 * </pre>
 * 其中 MICRO 大于任一方案微裁决项总和的上界（2_000_000 &gt; 500×1000+500），
 * 微裁决项永远不会翻转一级单位的优劣，只在一级相等时定序。
 * 用整数费用而非浮点，避免不同增广次序下浮点累加产生 1 ulp 级翻转。
 *
 * <p>算法：单位容量网络上的连续最短路增广（Dijkstra + 节点势）。初始费用全非负，
 * 势更新后约化费用保持非负。峰在求解前按（频率、幅值、原始下标）规范化排序，
 * 故提交顺序不影响结果。
 */
public final class PeakAssigner {

    /** 一级费用的相对偏差量化系数：1 个一级单位 = 1e-6 的相对偏差。 */
    private static final long RELATIVE_QUANTUM = 1_000_000L;

    /** 微裁决项的进位数，必须大于单个方案微裁决项总和的上界。 */
    private static final long MICRO_BASE = 2_000_000L;

    /**
     * 防御性饱和：相对偏差超过 1e6（仅在分辨率相对目标频率极端巨大时出现）
     * 后一级费用不再随偏差增大，避免长整型溢出。此域内微裁决仍可定序。
     */
    private static final long SATURATED_PRIMARY = 1_000_000_000_000L;

    private PeakAssigner() {
    }

    /**
     * 求全局最优一对一配对。
     *
     * @param peaks             实测峰（下标即调用方提交的原始顺序）
     * @param targets           当前打滑下的修正候选目标（须为 TargetSpectrum 的规范化次序）
     * @param relativeTolerance 匹配相对容差（正数）
     * @param resolutionHz      频谱分辨率（非负），窗口半宽 = 相对容差×目标频率 + 分辨率
     * @param slip              本次归属所用打滑系数（随结果回显）
     */
    public static AttributionResult assign(List<MeasuredPeak> peaks,
                                           List<CandidateTarget> targets,
                                           double relativeTolerance,
                                           double resolutionHz,
                                           double slip) {
        int peakCount = peaks.size();
        int targetCount = targets.size();

        // 峰的规范化排列：结果与提交顺序无关。同频时幅值更大（强峰）在前，再同则原始下标小者在前。
        List<Integer> canonicalPeakOrder = new ArrayList<>(peakCount);
        for (int i = 0; i < peakCount; i++) {
            canonicalPeakOrder.add(i);
        }
        canonicalPeakOrder.sort(Comparator
                .comparingDouble((Integer idx) -> peaks.get(idx).frequencyHz())
                .thenComparing(Comparator.comparingDouble(
                        (Integer idx) -> peaks.get(idx).amplitude()).reversed())
                .thenComparingInt(idx -> idx));

        // 网络节点：0 source；1..P 峰（规范化序）；P+1..P+T 目标（规范化序）；末位 sink。
        int source = 0;
        int firstPeakNode = 1;
        int firstTargetNode = firstPeakNode + peakCount;
        int sink = firstTargetNode + targetCount;
        MinCostFlow flow = new MinCostFlow(sink + 1);

        for (int cp = 0; cp < peakCount; cp++) {
            flow.addEdge(source, firstPeakNode + cp, 0L);
        }
        for (int t = 0; t < targetCount; t++) {
            flow.addEdge(firstTargetNode + t, sink, 0L);
        }
        // 合法匹配边：仅落在匹配窗口内才加入网络（超窗 = 不可配）。
        FlowEdge[][] matchEdge = new FlowEdge[peakCount][targetCount];
        for (int cp = 0; cp < peakCount; cp++) {
            int peakIdx = canonicalPeakOrder.get(cp);
            double peakHz = peaks.get(peakIdx).frequencyHz();
            for (int t = 0; t < targetCount; t++) {
                double targetHz = targets.get(t).correctedHz();
                double window = relativeTolerance * targetHz + resolutionHz;
                if (Math.abs(peakHz - targetHz) <= window) {
                    matchEdge[cp][t] = flow.addEdge(firstPeakNode + cp, firstTargetNode + t,
                            edgeCost(peakHz, targetHz, cp, t));
                }
            }
        }

        // 连续最短路增广：每次增广一对，增广不动即配对数已达最大。
        int matched = flow.augmentToMax(source, sink, Math.min(peakCount, targetCount));

        // 从残量为 0 的匹配边回收配对（单位容量 ⇒ 残量 0 即被选中）。
        List<int[]> pairs = new ArrayList<>(matched);
        boolean[] matchedPeak = new boolean[peakCount];
        for (int cp = 0; cp < peakCount; cp++) {
            for (int t = 0; t < targetCount; t++) {
                if (matchEdge[cp][t] != null && matchEdge[cp][t].isSaturated()) {
                    pairs.add(new int[]{canonicalPeakOrder.get(cp), t});
                    matchedPeak[cp] = true;
                }
            }
        }

        List<Assignment> assignments = new ArrayList<>(pairs.size());
        double totalAbsHz = 0.0;
        double totalAbsRel = 0.0;
        for (int[] pair : pairs) {
            int peakIdx = pair[0];
            int t = pair[1];
            MeasuredPeak peak = peaks.get(peakIdx);
            CandidateTarget target = targets.get(t);
            double deviationHz = peak.frequencyHz() - target.correctedHz();
            double relativeDeviation = deviationHz / target.correctedHz();
            assignments.add(new Assignment(peakIdx, peak.frequencyHz(), peak.amplitude(),
                    target.family(), target.order(), target.theoreticalHz(),
                    target.correctedHz(), deviationHz, relativeDeviation));
            totalAbsHz += Math.abs(deviationHz);
            totalAbsRel += Math.abs(relativeDeviation);
        }
        // 配对明细按目标规范化次序（族序、阶次）输出，与内部平局裁决口径一致。
        assignments.sort(Comparator
                .comparingInt((Assignment a) -> a.family().ordinal())
                .thenComparingInt(Assignment::order));

        List<Integer> unassigned = new ArrayList<>();
        for (int cp = 0; cp < peakCount; cp++) {
            if (!matchedPeak[cp]) {
                unassigned.add(canonicalPeakOrder.get(cp));
            }
        }
        unassigned.sort(Integer::compareTo);

        return new AttributionResult(slip, List.copyOf(assignments), List.copyOf(unassigned),
                assignments.size(), totalAbsHz, totalAbsRel);
    }

    private static long edgeCost(double peakHz, double targetHz, int canonicalPeak, int target) {
        double relativeDeviation = Math.abs(peakHz - targetHz) / targetHz;
        long primary = Math.min(Math.round(relativeDeviation * RELATIVE_QUANTUM), SATURATED_PRIMARY);
        long micro = (long) target * 1000L + canonicalPeak;
        return primary * MICRO_BASE + micro;
    }

    /**
     * 单位容量最小费用流：Dijkstra + 节点势。初始费用全非负，每次势更新后
     * 约化费用 cost + h[u] − h[v] 保持非负，Dijkstra 始终适用。
     */
    private static final class MinCostFlow {

        private final List<List<FlowEdge>> graph;
        private final long[] potential;
        private final long[] distance;
        private final FlowEdge[] predecessor;

        private MinCostFlow(int nodeCount) {
            this.graph = new ArrayList<>(nodeCount);
            for (int i = 0; i < nodeCount; i++) {
                graph.add(new ArrayList<>());
            }
            this.potential = new long[nodeCount];
            this.distance = new long[nodeCount];
            this.predecessor = new FlowEdge[nodeCount];
        }

        private FlowEdge addEdge(int from, int to, long cost) {
            FlowEdge forward = new FlowEdge(from, to, cost, 1);
            FlowEdge backward = new FlowEdge(to, from, -cost, 0);
            forward.reverse = backward;
            backward.reverse = forward;
            graph.get(from).add(forward);
            graph.get(to).add(backward);
            return forward;
        }

        /**
         * 沿最短路逐次增广（每轮单位流量），直到 sink 不可达或达到配对上限。
         *
         * @return 实际增广的流量（配对数）
         */
        private int augmentToMax(int source, int sink, int maxFlow) {
            int flow = 0;
            while (flow < maxFlow) {
                Arrays.fill(distance, Long.MAX_VALUE);
                Arrays.fill(predecessor, null);
                distance[source] = 0L;
                // 平局按节点编号升序弹出，保证遍历次序确定。
                PriorityQueue<long[]> queue = new PriorityQueue<>(
                        Comparator.comparingLong((long[] a) -> a[0]).thenComparingLong(a -> a[1]));
                queue.add(new long[]{0L, source});
                while (!queue.isEmpty()) {
                    long[] head = queue.poll();
                    long dist = head[0];
                    int node = (int) head[1];
                    if (dist != distance[node]) {
                        continue;
                    }
                    for (FlowEdge e : graph.get(node)) {
                        if (!e.hasCapacity()) {
                            continue;
                        }
                        long reduced = e.cost + potential[node] - potential[e.to];
                        long next = dist + reduced;
                        if (next < distance[e.to]
                                || (next == distance[e.to] && preferPredecessor(
                                        predecessor[e.to], e, node))) {
                            distance[e.to] = next;
                            predecessor[e.to] = e;
                            queue.add(new long[]{next, e.to});
                        }
                    }
                }
                if (distance[sink] == Long.MAX_VALUE) {
                    break;
                }
                for (int v = 0; v < distance.length; v++) {
                    if (distance[v] != Long.MAX_VALUE) {
                        potential[v] += distance[v];
                    }
                }
                // 沿前驱边翻转单位流量。
                int node = sink;
                while (node != source) {
                    FlowEdge used = predecessor[node];
                    used.push();
                    node = used.from;
                }
                flow++;
            }
            return flow;
        }

        /**
         * 等距时是否用候选边替换已记录的前驱：取前驱节点编号更小的路径，
         * 使等费用最短路按节点序确定，消除「同费用、不同配对」的任意性。
         */
        private static boolean preferPredecessor(FlowEdge current, FlowEdge candidate,
                                                 int candidateFrom) {
            return current == null || candidateFrom < current.from;
        }
    }

    /** 网络中的一条有向边；单位容量，初始容量正向 1/反向 0，反向边由 {@link #reverse} 互联。 */
    private static final class FlowEdge {
        private final int from;
        private final int to;
        private final long cost;
        private FlowEdge reverse;
        private int capacity;

        private FlowEdge(int from, int to, long cost, int initialCapacity) {
            this.from = from;
            this.to = to;
            this.cost = cost;
            this.capacity = initialCapacity;
        }

        private boolean hasCapacity() {
            return capacity == 1;
        }

        private boolean isSaturated() {
            return capacity == 0;
        }

        /** 沿本边推送单位流量：正向残量减一，反向残量加一。 */
        private void push() {
            capacity = 0;
            reverse.capacity = 1;
        }
    }
}
