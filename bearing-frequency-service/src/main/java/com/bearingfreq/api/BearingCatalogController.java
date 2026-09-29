package com.bearingfreq.api;

import com.bearingfreq.api.dto.CatalogEntryResponse;
import com.bearingfreq.api.dto.CatalogFrequencyRequestPayload;
import com.bearingfreq.api.dto.CatalogPeakAttributionRequestPayload;
import com.bearingfreq.api.dto.FrequencyResponse;
import com.bearingfreq.api.dto.GeometryPayload;
import com.bearingfreq.api.dto.PeakAttributionResponse;
import com.bearingfreq.api.dto.PeakPayload;
import com.bearingfreq.attribution.AttributionInputValidator;
import com.bearingfreq.attribution.PeakAttributionService;
import com.bearingfreq.model.BearingGeometry;
import com.bearingfreq.service.FrequencyService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 轴承档接口：登记、检索、按档核算特征频率。
 */
@RestController
@RequestMapping("/api/v1/catalog")
public class BearingCatalogController {

    private final FrequencyService frequencyService;
    private final PeakAttributionService attributionService;

    public BearingCatalogController(FrequencyService frequencyService,
                                    PeakAttributionService attributionService) {
        this.frequencyService = frequencyService;
        this.attributionService = attributionService;
    }

    @PutMapping("/{name}")
    public CatalogEntryResponse register(@PathVariable String name,
                                         @RequestBody GeometryPayload payload) {
        BearingGeometry geometry = payload.toGeometry();
        frequencyService.register(name, geometry);
        return new CatalogEntryResponse(name, geometry);
    }

    @GetMapping
    public Map<String, BearingGeometry> listAll() {
        return frequencyService.entries();
    }

    @GetMapping("/{name}")
    public CatalogEntryResponse get(@PathVariable String name) {
        return new CatalogEntryResponse(name, frequencyService.entry(name));
    }

    @PostMapping("/{name}/frequencies")
    public FrequencyResponse computeForEntry(@PathVariable String name,
                                             @RequestBody CatalogFrequencyRequestPayload payload) {
        var result = frequencyService.computeForEntry(
                name,
                payload == null ? null : payload.rotationFrequencyHz(),
                payload == null ? null : payload.speedRpm(),
                payload == null ? null : payload.sidebandOrder());
        return FrequencyResponse.of(name, result);
    }

    /** 按已登记轴承档做峰值归属 + 打滑估计（档不存在走统一 404）。 */
    @PostMapping("/{name}/peak-attributions")
    public PeakAttributionResponse attributeForEntry(@PathVariable String name,
                                                     @RequestBody CatalogPeakAttributionRequestPayload payload) {
        if (payload == null) {
            throw new com.bearingfreq.validation.InvalidBearingInputException("请求体不能为空");
        }
        java.util.List<AttributionInputValidator.RawPeak> peaks =
                payload.peaks() == null ? java.util.List.of() : payload.peaks().stream()
                        .map(p -> {
                            if (p == null || p.frequencyHz() == null || p.amplitude() == null) {
                                throw new com.bearingfreq.validation.InvalidBearingInputException(
                                        "每根峰必须同时提供 frequencyHz 与 amplitude");
                            }
                            return new AttributionInputValidator.RawPeak(p.frequencyHz(), p.amplitude());
                        })
                        .toList();
        var result = attributionService.attribute(
                payload.rotationFrequencyHz(),
                payload.speedRpm(),
                null,
                name,
                peaks,
                payload.maxOrder(),
                payload.relativeTolerance(),
                payload.frequencyResolutionHz(),
                payload.slipMin(),
                payload.slipMax(),
                payload.lockedSlip());
        return PeakAttributionResponse.of(result);
    }
}
