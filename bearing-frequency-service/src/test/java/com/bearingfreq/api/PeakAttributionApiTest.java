package com.bearingfreq.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 峰值归属接口的端到端测试：正常归属、打滑反演、校验错误、轴承档引用与隔离。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PeakAttributionApiTest {

    private static final String GEOMETRY_6205 =
            "{\"rollerCount\":9,\"pitchDiameterMm\":39.04,\"rollerDiameterMm\":7.94,\"contactAngleDeg\":0.0}";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("内联几何 + 锁死打滑：返回归属明细、修正目标与带符号偏差")
    void assignsWithLockedSlip() throws Exception {
        // 25 Hz 转频，BPFO 理论 89.6196；锁死 s=0.02 时修正目标 87.8272，给一根 87.9 的峰。
        double corrected = 89.6196 * 0.98;
        String body = """
                {"rotationFrequencyHz":25.0,"lockedSlip":0.02,"geometry":%s,
                 "peaks":[{"frequencyHz":%.4f,"amplitude":3.5},
                          {"frequencyHz":25.0,"amplitude":1.0},
                          {"frequencyHz":333.33,"amplitude":0.2}]}
                """.formatted(GEOMETRY_6205, corrected + 0.07);

        var response = rest.postForEntity("/api/v1/peak-attribution", json(body), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode root = objectMapper.readTree(response.getBody());
        assertThat(root.get("slipCoefficient").asDouble()).isCloseTo(0.02, within(1e-12));
        assertThat(root.get("converged").asBoolean()).isTrue();
        assertThat(root.get("iterations").asInt()).isEqualTo(1);
        assertThat(root.has("bearingName")).isFalse();

        JsonNode assignments = root.get("assignments");
        assertThat(root.get("matchedCount").asInt()).isEqualTo(2);
        JsonNode bpfo = findAssignment(assignments, "bpfo", 1);
        assertThat(bpfo.get("peakIndex").asInt()).isZero();
        assertThat(bpfo.get("amplitude").asDouble()).isCloseTo(3.5, within(1e-12));
        double theoretical = bpfo.get("theoreticalTargetHz").asDouble();
        assertThat(theoretical).isCloseTo(89.6196, within(1e-3));
        assertThat(bpfo.get("correctedTargetHz").asDouble())
                .isCloseTo(theoretical * 0.98, within(1e-9));
        // JSON 里峰频按 4 位小数给出，以返回的峰频为准核偏差。
        double expectedDeviation = bpfo.get("peakFrequencyHz").asDouble()
                - bpfo.get("correctedTargetHz").asDouble();
        assertThat(bpfo.get("deviationHz").asDouble())
                .isCloseTo(expectedDeviation, within(1e-9));
        assertThat(bpfo.get("relativeDeviation").asDouble())
                .isCloseTo(expectedDeviation / bpfo.get("correctedTargetHz").asDouble(),
                        within(1e-9));
        // 333.33 无目标可配，原始下标 2 未归属。
        assertThat(root.get("unassignedPeakIndexes").get(0).asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("speedRpm 口径可用；自由估计能反演已知打滑")
    void freeEstimationRecoversSlipOverHttp() throws Exception {
        // 1500 rpm = 25 Hz；BPFO1/BPFI2 压低 1%。
        double bpfo = 89.6196 * 0.99;
        double bpfi2 = 135.3804 * 2 * 0.99;
        String body = """
                {"speedRpm":1500.0,"geometry":%s,
                 "peaks":[{"frequencyHz":%.5f,"amplitude":1.0},
                          {"frequencyHz":%.5f,"amplitude":1.0},
                          {"frequencyHz":25.0,"amplitude":1.0}]}
                """.formatted(GEOMETRY_6205, bpfo, bpfi2);

        var response = rest.postForEntity("/api/v1/peak-attribution", json(body), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode root = objectMapper.readTree(response.getBody());
        assertThat(root.get("rotationFrequencyHz").asDouble()).isCloseTo(25.0, within(1e-9));
        assertThat(root.get("converged").asBoolean()).isTrue();
        assertThat(root.get("slipCoefficient").asDouble()).isCloseTo(0.01, within(2e-3));
        assertThat(root.get("statusReason").asText()).contains("收敛");
    }

    @Test
    @DisplayName("引用已登记轴承档归属；未知档 404")
    void catalogBackedAttributionAnd404() throws Exception {
        rest.exchange("/api/v1/catalog/6205", HttpMethod.PUT, json(GEOMETRY_6205), String.class);
        String body = """
                {"rotationFrequencyHz":25.0,"bearingName":"6205",
                 "peaks":[{"frequencyHz":89.6196,"amplitude":1.0}]}
                """;

        var response = rest.postForEntity("/api/v1/peak-attribution", json(body), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode root = objectMapper.readTree(response.getBody());
        assertThat(root.get("bearingName").asText()).isEqualTo("6205");
        assertThat(findAssignment(root.get("assignments"), "bpfo", 1)).isNotNull();

        String missing = """
                {"rotationFrequencyHz":25.0,"bearingName":"NOPE",
                 "peaks":[{"frequencyHz":89.6196,"amplitude":1.0}]}
                """;
        var notFound = rest.postForEntity("/api/v1/peak-attribution", json(missing), String.class);
        assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(objectMapper.readTree(notFound.getBody()).get("reason").asText())
                .contains("NOPE");
    }

    @Test
    @DisplayName("非法输入：400 且 reason 指出具体原因（坏峰带第几根、参数越界、来源冲突）")
    void invalidRequestsReturn400WithReason() throws Exception {
        assert400("""
                {"rotationFrequencyHz":25.0,"geometry":%s,"peaks":[]}
                """.formatted(GEOMETRY_6205), "不能为空");

        assert400("""
                {"rotationFrequencyHz":25.0,"geometry":%s,
                 "peaks":[{"frequencyHz":25.0,"amplitude":1.0},
                          {"frequencyHz":-1.0,"amplitude":1.0}]}
                """.formatted(GEOMETRY_6205), "第 1 根峰");

        assert400("""
                {"rotationFrequencyHz":25.0,"geometry":%s,
                 "peaks":[{"frequencyHz":25.0,"amplitude":-2.0}]}
                """.formatted(GEOMETRY_6205), "第 0 根峰");

        assert400("""
                {"rotationFrequencyHz":25.0,"geometry":%s,"maxOrder":11,
                 "peaks":[{"frequencyHz":25.0,"amplitude":1.0}]}
                """.formatted(GEOMETRY_6205), "maxOrder");

        assert400("""
                {"rotationFrequencyHz":25.0,"geometry":%s,"relativeTolerance":0.5,
                 "peaks":[{"frequencyHz":25.0,"amplitude":1.0}]}
                """.formatted(GEOMETRY_6205), "relativeTolerance");

        assert400("""
                {"rotationFrequencyHz":25.0,"geometry":%s,"lockedSlip":0.5,
                 "peaks":[{"frequencyHz":25.0,"amplitude":1.0}]}
                """.formatted(GEOMETRY_6205), "lockedSlip");

        assert400("""
                {"rotationFrequencyHz":25.0,"geometry":%s,"bearingName":"6205",
                 "peaks":[{"frequencyHz":25.0,"amplitude":1.0}]}
                """.formatted(GEOMETRY_6205), "只能提供一个");

        assert400("""
                {"rotationFrequencyHz":25.0,
                 "peaks":[{"frequencyHz":25.0,"amplitude":1.0}]}
                """, "之一");
    }

    private void assert400(String body, String reasonFragment) throws Exception {
        var response = rest.postForEntity("/api/v1/peak-attribution", json(body), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode root = objectMapper.readTree(response.getBody());
        assertThat(root.get("reason").asText()).contains(reasonFragment);
    }

    private static JsonNode findAssignment(JsonNode assignments, String family, int order) {
        for (JsonNode a : assignments) {
            if (family.equals(a.get("family").asText()) && a.get("order").asInt() == order) {
                return a;
            }
        }
        return null;
    }

    private static HttpEntity<String> json(String body) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }
}
