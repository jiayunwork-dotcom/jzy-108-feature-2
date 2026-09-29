package com.bearingfreq.catalog;

/**
 * 查询的轴承档不存在时抛出，由接口层转换为 404 响应。
 */
public class CatalogEntryNotFoundException extends RuntimeException {

    public CatalogEntryNotFoundException(String name) {
        super("轴承档不存在：" + name + "（请先通过 PUT /api/v1/catalog/" + name + " 登记）");
    }
}
