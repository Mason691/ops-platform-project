package com.aip.ops.dto;

import java.util.List;

/** 物资商品搜索结果（供应链商品快照）。 */
public record MaterialSearchResult(List<MaterialOptionView> records, long total) {

    public record MaterialOptionView(Long id, String number, String name, String category,
                                     String brand, String model, String unit) {}
}
