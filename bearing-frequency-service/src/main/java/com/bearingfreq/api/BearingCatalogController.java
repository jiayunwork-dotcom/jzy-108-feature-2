package com.bearingfreq.api;

import com.bearingfreq.api.dto.CatalogEntryResponse;
import com.bearingfreq.api.dto.CatalogFrequencyRequestPayload;
import com.bearingfreq.api.dto.FrequencyResponse;
import com.bearingfreq.api.dto.GeometryPayload;
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

    public BearingCatalogController(FrequencyService frequencyService) {
        this.frequencyService = frequencyService;
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
}
