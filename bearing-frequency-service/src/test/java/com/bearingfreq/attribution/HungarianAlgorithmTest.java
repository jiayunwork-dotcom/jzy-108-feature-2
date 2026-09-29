package com.bearingfreq.attribution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 匈牙利最小权匹配本身的确定性与基本正确性。
 */
class HungarianAlgorithmTest {

    @Test
    @DisplayName("2×2 已知矩阵取最小权列分配")
    void knownTwoByTwoAssignment() {
        double[][] cost = {
                {1.0, 4.0},
                {2.0, 3.0}
        };
        int[] result = HungarianAlgorithm.assign(cost);
        // 行0→列0(1)、行1→列1(3) 总和 4，优于交叉的 4+2=6
        assertThat(result).containsExactly(0, 1);
    }

    @Test
    @DisplayName("矩形矩阵（行少于列）每行各得一个不重复列，且总权最小")
    void rectangularAssignmentIsMinimal() {
        // 行1 的最优列是 1(0.1)；行0 在列1被占后应取列3(1) 而非列0(5)、列2(9)
        double[][] cost = {
                {5.0, 8.0, 9.0, 1.0},
                {4.0, 0.1, 3.0, 2.0}
        };
        int[] result = HungarianAlgorithm.assign(cost);
        assertThat(result).hasSize(2);
        assertThat(result[0]).isNotEqualTo(result[1]);
        double total = cost[0][result[0]] + cost[1][result[1]];
        assertThat(total).isLessThanOrEqualTo(0.1 + 1.0);
        assertThat(result).containsExactly(3, 1);
    }

    @Test
    @DisplayName("同一矩阵重复求解结果完全相同")
    void repeatedCallsAreIdentical() {
        double[][] cost = {
                {0.0, 0.0, 0.0},
                {0.0, 0.0, 0.0},
                {0.0, 0.0, 0.0}
        };
        int[] first = HungarianAlgorithm.assign(cost);
        for (int i = 0; i < 10; i++) {
            assertThat(HungarianAlgorithm.assign(cost)).isEqualTo(first);
        }
    }

    @Test
    @DisplayName("全平局时按行列下标确定裁决（恒取对角结构）")
    void tiesAreResolvedByIndexOrder() {
        double[][] cost = new double[4][7];
        int[] result = HungarianAlgorithm.assign(cost);
        // 全 0：增广路径按列升序试探，结果确定为前 4 列对角
        assertThat(result).containsExactly(0, 1, 2, 3);
    }
}
