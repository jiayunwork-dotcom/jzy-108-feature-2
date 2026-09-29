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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 端到端接口测试：特征频率核算、错误响应带原因、轴承档生命周期与档间隔离。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FrequencyApiTest {

    private static final String GEOMETRY_6205 = """
            {"rollerCount":9,"pitchDiameterMm":39.04,"rollerDiameterMm":7.94,"contactAngleDeg":0.0}
            """;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("POST /api/v1/frequencies：返回四个特征频率，且 BPFI > BPFO")
    void computesFourCharacteristicFrequencies() throws Exception {
        String body = "{\"rotationFrequencyHz\":25.0,\"geometry\":" + GEOMETRY_6205 + "}";

        var response = rest.postForEntity("/api/v1/frequencies", json(body), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode root = objectMapper.readTree(response.getBody());
        JsonNode f = root.get("frequencies");
        assertThat(f.get("bpfoHz").asDouble()).isCloseTo(89.6196, within(1e-3));
        assertThat(f.get("bpfiHz").asDouble()).isCloseTo(135.3804, within(1e-3));
        assertThat(f.get("ftfHz").asDouble()).isCloseTo(9.9577, within(1e-3));
        assertThat(f.get("bsfHz").asDouble()).isCloseTo(58.9188, within(1e-3));
        assertThat(f.get("bpfiHz").asDouble()).isGreaterThan(f.get("bpfoHz").asDouble());
        // 未请求边带时不输出边带字段
        assertThat(root.has("sidebands")).isFalse();
    }

    @Test
    @DisplayName("转频翻倍 → 四频齐翻（通过接口验证）")
    void doublingFrequencyDoublesAllOutputsOverHttp() throws Exception {
        String template = "{\"rotationFrequencyHz\":%s,\"geometry\":" + GEOMETRY_6205 + "}";
        JsonNode at25 = objectMapper.readTree(rest.postForEntity(
                "/api/v1/frequencies", json(template.formatted(25.0)), String.class).getBody())
                .get("frequencies");
        JsonNode at50 = objectMapper.readTree(rest.postForEntity(
                "/api/v1/frequencies", json(template.formatted(50.0)), String.class).getBody())
                .get("frequencies");

        for (String key : new String[]{"bpfoHz", "bpfiHz", "ftfHz", "bsfHz"}) {
            assertThat(at50.get(key).asDouble())
                    .isCloseTo(2.0 * at25.get(key).asDouble(), within(1e-6));
        }
    }

    @Test
    @DisplayName("非法几何：400 且响应里讲明缘由")
    void invalidGeometryReturns400WithReason() throws Exception {
        String body = """
                {"rotationFrequencyHz":25.0,"geometry":
                 {"rollerCount":9,"pitchDiameterMm":39.04,"rollerDiameterMm":40.0,"contactAngleDeg":0.0}}
                """;

        var response = rest.postForEntity("/api/v1/frequencies", json(body), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode root = objectMapper.readTree(response.getBody());
        assertThat(root.get("reason").asText()).contains("滚动体直径必须小于节圆直径");
    }

    @Test
    @DisplayName("转频不为正：400 且响应里讲明缘由")
    void nonPositiveFrequencyReturns400WithReason() throws Exception {
        String body = "{\"rotationFrequencyHz\":0.0,\"geometry\":" + GEOMETRY_6205 + "}";

        var response = rest.postForEntity("/api/v1/frequencies", json(body), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode root = objectMapper.readTree(response.getBody());
        assertThat(root.get("reason").asText()).contains("转频必须为正数");
    }

    @Test
    @DisplayName("请求边带时返回围绕转频的边带")
    void sidebandsReturnedWhenRequested() throws Exception {
        String body = "{\"rotationFrequencyHz\":25.0,\"sidebandOrder\":1,\"geometry\":"
                + GEOMETRY_6205 + "}";

        var response = rest.postForEntity("/api/v1/frequencies", json(body), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode root = objectMapper.readTree(response.getBody());
        double bpfo = root.get("frequencies").get("bpfoHz").asDouble();
        JsonNode bpfoBand = root.get("sidebands").get("bpfo").get(0);
        assertThat(bpfoBand.get("lowerHz").asDouble()).isCloseTo(bpfo - 25.0, within(1e-6));
        assertThat(bpfoBand.get("upperHz").asDouble()).isCloseTo(bpfo + 25.0, within(1e-6));
    }

    @Test
    @DisplayName("轴承档生命周期：登记 → 按档核算 → 检索；未知档 404")
    void catalogLifecycle() throws Exception {
        var register = rest.exchange("/api/v1/catalog/6205", HttpMethod.PUT,
                json(GEOMETRY_6205), String.class);
        assertThat(register.getStatusCode()).isEqualTo(HttpStatus.OK);

        var compute = rest.postForEntity("/api/v1/catalog/6205/frequencies",
                json("{\"speedRpm\":1500.0}"), String.class);
        assertThat(compute.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode computed = objectMapper.readTree(compute.getBody());
        assertThat(computed.get("bearingName").asText()).isEqualTo("6205");
        assertThat(computed.get("rotationFrequencyHz").asDouble()).isCloseTo(25.0, within(1e-9));
        assertThat(computed.get("frequencies").get("bpfoHz").asDouble())
                .isCloseTo(89.6196, within(1e-3));

        var get = rest.getForEntity("/api/v1/catalog/6205", String.class);
        assertThat(get.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(get.getBody()).get("geometry").get("rollerCount").asInt())
                .isEqualTo(9);

        var missing = rest.getForEntity("/api/v1/catalog/UNKNOWN", String.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(objectMapper.readTree(missing.getBody()).get("reason").asText())
                .contains("UNKNOWN");
    }

    @Test
    @DisplayName("两个轴承档同时被查询：参数不串档")
    void catalogEntriesStayIsolatedOverHttp() throws Exception {
        rest.exchange("/api/v1/catalog/A", HttpMethod.PUT, json(
                "{\"rollerCount\":8,\"pitchDiameterMm\":40.0,\"rollerDiameterMm\":6.0,\"contactAngleDeg\":0.0}"
        ), String.class);
        rest.exchange("/api/v1/catalog/B", HttpMethod.PUT, json(
                "{\"rollerCount\":16,\"pitchDiameterMm\":40.0,\"rollerDiameterMm\":6.0,\"contactAngleDeg\":0.0}"
        ), String.class);

        double bpfoA = computeBpfo("A");
        double bpfoB = computeBpfo("B");
        double bpfoAAgain = computeBpfo("A");

        assertThat(bpfoA).isCloseTo(102.0, within(1e-6));
        assertThat(bpfoB).isCloseTo(204.0, within(1e-6));
        assertThat(bpfoAAgain).isCloseTo(102.0, within(1e-6));
    }

    private double computeBpfo(String name) throws Exception {
        var response = rest.postForEntity("/api/v1/catalog/" + name + "/frequencies",
                json("{\"rotationFrequencyHz\":30.0}"), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody())
                .get("frequencies").get("bpfoHz").asDouble();
    }

    private static HttpEntity<String> json(String body) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }
}
