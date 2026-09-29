package com.bearingfreq.catalog;

import com.bearingfreq.model.BearingGeometry;
import com.bearingfreq.validation.InvalidBearingInputException;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 轴承档：把常用轴承型号的几何参数按名称登记在内存中，供反复调用。
 *
 * <p>纯内存实现，不持久化——服务重启后需重新登记。
 * 每个名称对应一份独立的不可变 {@link BearingGeometry}，
 * 各档之间互不影响，不存在参数串档。
 */
@Component
public class BearingCatalog {

    private final Map<String, BearingGeometry> entries = new ConcurrentHashMap<>();

    /** 登记（或覆盖）一个轴承档。 */
    public void register(String name, BearingGeometry geometry) {
        if (name == null || name.isBlank()) {
            throw new InvalidBearingInputException("轴承档名称不能为空");
        }
        entries.put(name, geometry);
    }

    /** 按名称检索轴承档。 */
    public Optional<BearingGeometry> find(String name) {
        return Optional.ofNullable(entries.get(name));
    }

    /** 列出全部轴承档（按名称排序的只读视图）。 */
    public Map<String, BearingGeometry> findAll() {
        return Collections.unmodifiableMap(new TreeMap<>(entries));
    }
}
