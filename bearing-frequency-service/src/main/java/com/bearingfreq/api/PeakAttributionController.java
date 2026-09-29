package com.bearingfreq.api;

import com.bearingfreq.api.dto.PeakAttributionRequestPayload;
import com.bearingfreq.api.dto.PeakAttributionResponse;
import com.bearingfreq.api.dto.PeakPayload;
import com.bearingfreq.attribution.AttributionInputValidator;
import com.bearingfreq.attribution.PeakAttributionService;
import com.bearingfreq.model.BearingGeometry;
import com.bearingfreq.validation.InvalidBearingInputException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 峰值归属 + 打滑估计接口：只解析请求、调用归属服务、拼装响应。
 *
 * <p>几何与档名互斥的判定在校验层；引用不存在的档由全局异常处理按 404 返回。
 */
@RestController
@RequestMapping("/api/v1")
public class PeakAttributionController {

    private final PeakAttributionService attributionService;

    public PeakAttributionController(PeakAttributionService attributionService) {
        this.attributionService = attributionService;
    }

    @PostMapping("/peak-attributions")
    public PeakAttributionResponse attribute(@RequestBody PeakAttributionRequestPayload payload) {
        if (payload == null) {
            throw new InvalidBearingInputException("请求体不能为空");
        }
        BearingGeometry geometry = payload.geometry() != null
                ? payload.geometry().toGeometry() : null;
        List<AttributionInputValidator.RawPeak> peaks = toRawPeaks(payload.peaks());

        var result = attributionService.attribute(
                payload.rotationFrequencyHz(),
                payload.speedRpm(),
                geometry,
                payload.bearingName(),
                peaks,
                payload.maxOrder(),
                payload.relativeTolerance(),
                payload.frequencyResolutionHz(),
                payload.slipMin(),
                payload.slipMax(),
                payload.lockedSlip());
        return PeakAttributionResponse.of(result);
    }

    private static List<AttributionInputValidator.RawPeak> toRawPeaks(List<PeakPayload> payload) {
        if (payload == null) {
            return List.of();
        }
        return payload.stream()
                .map(p -> {
                    if (p == null || p.frequencyHz() == null || p.amplitude() == null) {
                        throw new InvalidBearingInputException(
                                "每根峰必须同时提供 frequencyHz 与 amplitude");
                    }
                    return new AttributionInputValidator.RawPeak(p.frequencyHz(), p.amplitude());
                })
                .toList();
    }
}
