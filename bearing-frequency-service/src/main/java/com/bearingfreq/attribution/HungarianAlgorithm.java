package com.bearingfreq.attribution;

/**
 * 矩形二分图最小权匹配（匈牙利算法 / Kuhn–Munkres 的 Jonker–Volgenant 风格
 * 标号实现），求 {@code n} 行各配一个不同列、使总权最小的方案。
 *
 * <p>约定 {@code n ≤ m}，结果中每行恰好被分配一列，列不重复。
 * 代价矩阵不被修改。算法对相同矩阵走完全确定的增广路径
 * （下标恒按 0..m−1 升序试探），配合调用方在代价里写入的规范序微扰，
 * 平局裁决也是确定的。
 *
 * <p>纯静态工具，无状态、无线程亲和性。
 */
final class HungarianAlgorithm {

    private HungarianAlgorithm() {
    }

    /**
     * @param cost 代价矩阵，{@code cost[i][j]} 为行 i 配列 j 的权；行数须 ≤ 列数
     * @return 长度为行数的数组，{@code result[i]} 为行 i 被分配的列
     */
    static int[] assign(double[][] cost) {
        int n = cost.length;
        if (n == 0) {
            return new int[0];
        }
        int m = cost[0].length;
        if (n > m) {
            throw new IllegalArgumentException("匈牙利算法要求行数不超过列数：" + n + " > " + m);
        }

        double[] u = new double[n + 1];
        double[] v = new double[m + 1];
        int[] p = new int[m + 1];
        int[] way = new int[m + 1];

        for (int i = 1; i <= n; i++) {
            p[0] = i;
            int j0 = 0;
            double[] minv = new double[m + 1];
            java.util.Arrays.fill(minv, Double.POSITIVE_INFINITY);
            boolean[] used = new boolean[m + 1];

            do {
                used[j0] = true;
                int i0 = p[j0];
                double delta = Double.POSITIVE_INFINITY;
                int j1 = 0;
                double[] row = cost[i0 - 1];
                for (int j = 1; j <= m; j++) {
                    if (!used[j]) {
                        double cur = row[j - 1] - u[i0] - v[j];
                        if (cur < minv[j]) {
                            minv[j] = cur;
                            way[j] = j0;
                        }
                        if (minv[j] < delta) {
                            delta = minv[j];
                            j1 = j;
                        }
                    }
                }
                for (int j = 0; j <= m; j++) {
                    if (used[j]) {
                        u[p[j]] += delta;
                        v[j] -= delta;
                    } else {
                        minv[j] -= delta;
                    }
                }
                j0 = j1;
            } while (p[j0] != 0);

            do {
                int j1 = way[j0];
                p[j0] = p[j1];
                j0 = j1;
            } while (j0 != 0);
        }

        int[] result = new int[n];
        for (int j = 1; j <= m; j++) {
            if (p[j] != 0) {
                result[p[j] - 1] = j - 1;
            }
        }
        return result;
    }
}
