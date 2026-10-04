package com.bearingfreq.api;

import com.bearingfreq.api.dto.PeakAttributionRequestPayload;
import com.bearingfreq.api.dto.PeakAttributionResponse;
import com.bearingfreq.api.dto.PeakPayload;
import com.bearingfreq.attribution.MeasuredPeak;
import com.bearingfreq.service.AttributionQuery;
import com.bearingfreq.service.PeakAttributionService;
import com.bearingfreq.validation.InvalidBearingInputException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 峰值归属 + 打滑估计接口：只解析请求、调用编排服务、拼装响应。
 */
@RestController
@RequestMapping("/api/v1")
public class PeakAttributionController {

    private final PeakAttributionService attributionService;

    public PeakAttributionController(PeakAttributionService attributionService) {
        this.attributionService = attributionService;
    }

    @PostMapping("/peak-attribution")
    public PeakAttributionResponse attribute(@RequestBody PeakAttributionRequestPayload payload) {
        if (payload == null) {
            throw new InvalidBearingInputException("请求体不能为空");
        }
        AttributionQuery query = new AttributionQuery(
                payload.rotationFrequencyHz(),
                payload.speedRpm(),
                toPeaks(payload.peaks()),
                payload.geometry() == null ? null : payload.geometry().toGeometry(),
                payload.bearingName(),
                payload.maxOrder(),
                payload.relativeTolerance(),
                payload.frequencyResolutionHz(),
                payload.minSlip(),
                payload.maxSlip(),
                payload.lockedSlip());
        var outcome = attributionService.attribute(query);
        double rotationFrequencyHz = com.bearingfreq.validation.BearingInputValidator
                .requireRotationFrequencyHz(payload.rotationFrequencyHz(), payload.speedRpm());
        return PeakAttributionResponse.of(payload.bearingName(), rotationFrequencyHz, outcome);
    }

    /**
     * 解析峰列表：缺字段不在这里报具体原因，统一转成 NaN 交由校验层
     * 指出「第几根」频率/幅值非法；空元素保留为 null 由校验层处理。
     */
    private List<MeasuredPeak> toPeaks(List<PeakPayload> peakPayloads) {
        if (peakPayloads == null) {
            return List.of();
        }
        List<MeasuredPeak> peaks = new ArrayList<>(peakPayloads.size());
        for (PeakPayload p : peakPayloads) {
            if (p == null) {
                peaks.add(null);
            } else {
                peaks.add(new MeasuredPeak(
                        p.frequencyHz() == null ? Double.NaN : p.frequencyHz(),
                        p.amplitude() == null ? Double.NaN : p.amplitude()));
            }
        }
        return peaks;
    }
}
