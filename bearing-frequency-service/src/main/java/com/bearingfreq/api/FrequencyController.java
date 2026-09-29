package com.bearingfreq.api;

import com.bearingfreq.api.dto.FrequencyRequestPayload;
import com.bearingfreq.api.dto.FrequencyResponse;
import com.bearingfreq.service.FrequencyService;
import com.bearingfreq.validation.InvalidBearingInputException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 特征频率核算接口：只解析请求、调用编排服务、拼装响应。
 */
@RestController
@RequestMapping("/api/v1")
public class FrequencyController {

    private final FrequencyService frequencyService;

    public FrequencyController(FrequencyService frequencyService) {
        this.frequencyService = frequencyService;
    }

    @PostMapping("/frequencies")
    public FrequencyResponse compute(@RequestBody FrequencyRequestPayload payload) {
        if (payload == null || payload.geometry() == null) {
            throw new InvalidBearingInputException("请求体必须包含 geometry（轴承几何参数）");
        }
        var result = frequencyService.compute(
                payload.rotationFrequencyHz(),
                payload.speedRpm(),
                payload.geometry().toGeometry(),
                payload.sidebandOrder());
        return FrequencyResponse.of(result);
    }
}
