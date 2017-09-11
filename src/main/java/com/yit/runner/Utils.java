package com.yit.runner;

import com.yit.product.entity.Product;

/**
 * 工具类
 */
public class Utils {

    /**
     * 获取规格项下标
     */
    public static int getOptionIndex(Product product, String optionLabel) {
        for (int i = 0; i < product.skuInfo.options.size(); i++) {
            if (optionLabel.equals(product.skuInfo.options.get(i).label)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 获取销售方式的规格值
     * @param skuId
     * @param product
     * @return
     */
    public static String getStockName(Product product, int skuId) {
        int optionIndex = getOptionIndex(product, "销售方式");
        if (optionIndex == -1) {
            return null;
        }
        Product.SKU sku = product.getSKUById(skuId);
        int valueId = sku.valueIds[optionIndex];
        for (Product.Option.Value value : product.skuInfo.options.get(optionIndex).values) {
            if (value.id == valueId) {
                return value.label;
            }
        }

        return null;
    }
}
