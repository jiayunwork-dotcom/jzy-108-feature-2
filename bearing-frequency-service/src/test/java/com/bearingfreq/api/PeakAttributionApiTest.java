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
 * 峰值归属接口端到端测试：内联几何与轴承档两种入口、结果结构、
 * 错误响应（400 带原因 / 未知档 404）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PeakAttributionApiTest {

    private static final String GEOMETRY_6205 =
            "{\"rollerCount\":9,\"pitchDiameterMm\":39.04,\"rollerDiameterMm\":7.94,"
                    + "\"contactAngleDeg\":0.0}";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("内联几何归属：纯理论峰返回零打滑、规范结果块")
    void inlineGeometryAttribution() throws Exception {
        // fr=25，shaft1=25、BPFO1≈89.6196、FTF1≈9.9577
        String body = """
                {
                  "rotationFrequencyHz": 25.0,
                  "maxOrder": 1,
                  "relativeTolerance": 0.02,
                  "peaks": [
                    {"frequencyHz": 25.0, "amplitude": 1.0},
                    {"frequencyHz": 89.61962, "amplitude": 2.0},
                    {"frequencyHz": 9.95774, "amplitude": 0.5},
                    {"frequencyHz": 999.0, "amplitude": 7.0}
                  ],
                  "geometry":""" + GEOMETRY_6205.replace("\n", "") + "\n}";

        var response = rest.postForEntity("/api/v1/peak-attributions", json(body), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode root = objectMapper.readTree(response.getBody());

        assertThat(root.get("converged").asBoolean()).isTrue();
        assertThat(root.get("slipCoefficient").asDouble()).isCloseTo(0.0, within(1e-9));
        assertThat(root.get("matchedPeakCount").asInt()).isEqualTo(3);
        assertThat(root.get("unmatchedPeakCount").asInt()).isEqualTo(1);
        assertThat(root.get("maxOrder").asInt()).isOne();
        assertThat(root.get("relativeTolerance").asDouble()).isEqualTo(0.02);
        assertThat(root.has("bearingName")).isFalse();

        // 每根峰都有与提交顺序对齐的视图
        JsonNode peaks = root.get("peaks");
        assertThat(peaks).hasSize(4);
        assertThat(peaks.get(0).get("submittedIndex").asInt()).isZero();
        assertThat(peaks.get(0).get("assigned").asBoolean()).isTrue();
        assertThat(peaks.get(0).get("family").asText()).isEqualTo("shaft");
        assertThat(peaks.get(3).get("assigned").asBoolean()).isFalse();
        assertThat(peaks.get(3).get("family").isNull()).isTrue();

        // 未归属块
        JsonNode unmatched = root.get("unmatchedPeaks");
        assertThat(unmatched).hasSize(1);
        assertThat(unmatched.get(0).get("frequencyHz").asDouble()).isEqualTo(999.0);

        // assignments 块：BPFO1 归属带修正目标与带符号偏差
        JsonNode assignments = root.get("assignments");
        assertThat(assignments).hasSize(3);
        JsonNode bpfo = findAssignment(assignments, "bpfo", 1);
        assertThat(bpfo.get("adjustedTargetHz").asDouble()).isCloseTo(89.6196, within(1e-3));
        assertThat(bpfo.get("deviationHz").asDouble()).isCloseTo(0.0, within(1e-3));
        // 零打滑时理论目标与修正目标一致
        assertThat(bpfo.get("theoreticalTargetHz").asDouble())
                .isCloseTo(bpfo.get("adjustedTargetHz").asDouble(), within(1e-6));
    }

    @Test
    @DisplayName("锁死打滑：只做一次归属，iterations=1，目标按打滑修正")
    void lockedSlipSinglePass() throws Exception {
        String body = """
                {
                  "speedRpm": 1500.0,
                  "maxOrder": 1,
                  "lockedSlip": 0.05,
                  "peaks": [
                    {"frequencyHz": 85.13864, "amplitude": 1.0}
                  ],
                  "geometry":""" + GEOMETRY_6205.replace("\n", "") + "\n}";

        var response = rest.postForEntity("/api/v1/peak-attributions", json(body), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode root = objectMapper.readTree(response.getBody());
        assertThat(root.get("iterations").asInt()).isOne();
        assertThat(root.get("slipCoefficient").asDouble()).isEqualTo(0.05);
        assertThat(root.get("terminationReason").asText()).isEqualTo("LOCKED_SLIP");
        JsonNode bpfo = findAssignment(root.get("assignments"), "bpfo", 1);
        // BPFO1 修正后 = 89.6196 × 0.95 ≈ 85.1386
        assertThat(bpfo.get("adjustedTargetHz").asDouble()).isCloseTo(85.1386, within(1e-3));
    }

    @Test
    @DisplayName("已知打滑自由估计：收敛并反推，干扰峰未归属")
    void freeSlipEstimationOverHttp() throws Exception {
        // fr=25、真实打滑 3%，给出 BPFO1、BPFI1 修正峰与一根转频峰、一根干扰峰
        String body = """
                {
                  "rotationFrequencyHz": 25.0,
                  "maxOrder": 1,
                  "relativeTolerance": 0.03,
                  "peaks": [
                    {"frequencyHz": 25.0, "amplitude": 1.0},
                    {"frequencyHz": 86.9310, "amplitude": 1.0},
                    {"frequencyHz": 131.3190, "amplitude": 1.0},
                    {"frequencyHz": 2222.0, "amplitude": 9.0}
                  ],
                  "geometry":""" + GEOMETRY_6205.replace("\n", "") + "\n}";

        var response = rest.postForEntity("/api/v1/peak-attributions", json(body), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode root = objectMapper.readTree(response.getBody());
        assertThat(root.get("converged").asBoolean()).isTrue();
        assertThat(root.get("slipCoefficient").asDouble()).isCloseTo(0.03, within(6e-4));
        assertThat(root.get("matchedPeakCount").asInt()).isEqualTo(3);
        assertThat(root.get("unmatchedPeakCount").asInt()).isOne();

        // 转频峰不走打滑修正
        JsonNode shaft = findAssignment(root.get("assignments"), "shaft", 1);
        assertThat(shaft.get("adjustedTargetHz").asDouble()).isEqualTo(25.0);
    }

    @Test
    @DisplayName("轴承档入口与内联几何结果一致；未知档 404；两入口互斥 400")
    void catalogEntryEquivalentAndErrors() throws Exception {
        var register = rest.exchange("/api/v1/catalog/6205", HttpMethod.PUT,
                json(GEOMETRY_6205), String.class);
        assertThat(register.getStatusCode()).isEqualTo(HttpStatus.OK);

        String inlineBody = "{\"rotationFrequencyHz\":25.0,\"maxOrder\":1,\"peaks\":["
                + "{\"frequencyHz\":89.61962,\"amplitude\":1.0}],"
                + "\"geometry\":" + GEOMETRY_6205 + "}";
        String catalogBody = "{\"rotationFrequencyHz\":25.0,\"maxOrder\":1,\"peaks\":["
                + "{\"frequencyHz\":89.61962,\"amplitude\":1.0}]}";

        JsonNode inline = objectMapper.readTree(
                rest.postForEntity("/api/v1/peak-attributions", json(inlineBody), String.class)
                        .getBody());
        JsonNode viaCatalog = objectMapper.readTree(
                rest.postForEntity("/api/v1/catalog/6205/peak-attributions",
                        json(catalogBody), String.class).getBody());

        assertThat(viaCatalog.get("bearingName").asText()).isEqualTo("6205");
        assertThat(viaCatalog.get("slipCoefficient").asDouble())
                .isCloseTo(inline.get("slipCoefficient").asDouble(), within(1e-12));
        assertThat(viaCatalog.get("matchedPeakCount").asInt())
                .isEqualTo(inline.get("matchedPeakCount").asInt());
        assertThat(viaCatalog.get("totalSquaredRelativeDeviation").asDouble())
                .isCloseTo(inline.get("totalSquaredRelativeDeviation").asDouble(), within(1e-18));

        // 未知档：404
        var missing = rest.postForEntity("/api/v1/catalog/UNKNOWN/peak-attributions",
                json(catalogBody), String.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(objectMapper.readTree(missing.getBody()).get("reason").asText())
                .contains("UNKNOWN");

        // 几何与档名同时给 / 都不给：400
        String both = "{\"rotationFrequencyHz\":25.0,\"bearingName\":\"6205\","
                + "\"geometry\":" + GEOMETRY_6205
                + ",\"peaks\":[{\"frequencyHz\":25,\"amplitude\":1}]}";
        assertThat(rest.postForEntity("/api/v1/peak-attributions", json(both), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        String neither = "{\"rotationFrequencyHz\":25.0,"
                + "\"peaks\":[{\"frequencyHz\":25,\"amplitude\":1}]}";
        var neitherResp = rest.postForEntity("/api/v1/peak-attributions", json(neither), String.class);
        assertThat(neitherResp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(objectMapper.readTree(neitherResp.getBody()).get("reason").asText())
                .contains("必须提供");
    }

    @Test
    @DisplayName("非法输入 400 且讲明原因：空峰、负频率指出第几根、参数越界、锁死值越界")
    void invalidRequestsReturn400WithReasons() throws Exception {
        expect400("{\"rotationFrequencyHz\":25.0,\"geometry\":" + GEOMETRY_6205
                        + ",\"peaks\":[]}", "peaks 不能为空");
        expect400("{\"rotationFrequencyHz\":25.0,\"geometry\":" + GEOMETRY_6205
                        + ",\"peaks\":[{\"frequencyHz\":25,\"amplitude\":1},"
                        + "{\"frequencyHz\":-1,\"amplitude\":1}]}",
                "第 2 根峰频率");
        expect400("{\"rotationFrequencyHz\":25.0,\"geometry\":" + GEOMETRY_6205
                        + ",\"peaks\":[{\"frequencyHz\":25,\"amplitude\":-2}]}",
                "第 1 根峰幅值");
        expect400("{\"rotationFrequencyHz\":25.0,\"maxOrder\":11,\"geometry\":"
                        + GEOMETRY_6205 + ",\"peaks\":[{\"frequencyHz\":25,\"amplitude\":1}]}", "maxOrder");
        expect400("{\"rotationFrequencyHz\":25.0,\"relativeTolerance\":0.5,\"geometry\":"
                        + GEOMETRY_6205 + ",\"peaks\":[{\"frequencyHz\":25,\"amplitude\":1}]}", "relativeTolerance");
        expect400("{\"rotationFrequencyHz\":25.0,\"lockedSlip\":0.5,\"geometry\":"
                        + GEOMETRY_6205 + ",\"peaks\":[{\"frequencyHz\":25,\"amplitude\":1}]}", "lockedSlip");
    }

    private void expect400(String body, String reasonFragment) throws Exception {
        var response = rest.postForEntity("/api/v1/peak-attributions", json(body), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode root = objectMapper.readTree(response.getBody());
        assertThat(root.get("reason").asText()).contains(reasonFragment);
    }

    private static JsonNode findAssignment(JsonNode assignments, String family, int order) {
        for (JsonNode a : assignments) {
            if (a.get("family").asText().equals(family) && a.get("order").asInt() == order) {
                return a;
            }
        }
        throw new AssertionError("缺少归属：" + family + " " + order);
    }

    private static HttpEntity<String> json(String body) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }
}
