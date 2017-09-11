package com.yit.runner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import com.alibaba.dubbo.common.utils.CollectionUtils;
import com.yit.product.entity.Product;
import com.yit.product.entity.Product.SKU;

/**
 * Migration Utils
 */

public class MigrationUtils {

    public String getMigrationSql() {
        return
            "insert into yitiao_product_sku_stock "
                + "( "
                + "    id,"
                + "    sku_id, "
                + "    name, "
                + "    quantity, "
                + "    notify_quantity, "
                + "    priority, "
                + "    created_time, "
                + "    is_replenishing, "
                + "    is_active, "
                + "    is_deleted) "
                + "select "
                + "      product_id, "
                + "      product_id, "
                + "      '现货／2个工作日发货', "
                + "      qty, "
                + "      notify_stock_qty, "
                + "      1, "
                + "      now(), "
                + "      status, "
                + "      1, "
                + "      0 "
                + "from cataloginventory_stock_item";
    }

    public int computeDefaultStock(Product oldProduct,List<Integer> computeSkuArea) {
        List<SKU> skus = oldProduct.skuInfo.skus.stream().filter(x->computeSkuArea.contains(x.id))
                                                         .collect(Collectors.toList());
        skus.sort((x, y) -> {
            if (x.id > y.id) {
                return 1;
            } else {
                return -1;
            }
        });
        List<SKU> skuList = skus.stream().filter(x -> x.saleInfo.onSale == true).collect(Collectors.toList());

        if (!CollectionUtils.isEmpty(skuList)) {
            return skuList.get(0).id;
        } else {
            return skus.get(0).id;
        }
    }

    public int getSaleOptionIndex(Product product) {
        int saleOptionIndex = -1;
        for (int i = 0; i < product.skuInfo.options.size(); i++) {
            if ("销售方式".equals(product.skuInfo.options.get(i).label)) {
                saleOptionIndex = i;
                break;
            }
        }
        if (saleOptionIndex == -1) {
            throw new RuntimeException(
                String.format("SPU: %s 获取销售方式option下标发生异常,请检查该SPU是否有 销售方式 option!", product.id));
        }

        return saleOptionIndex;
    }

    public void removeSaleOption(Product newProduct) {
        int saleOptionIndex = getSaleOptionIndex(newProduct);
        newProduct.skuInfo.options.remove(saleOptionIndex);

        for (int index = 0; index < newProduct.skuInfo.skus.size(); index++) {
            int[] valueIds = newProduct.skuInfo.skus.get(index).valueIds;
            List<Integer> collect = Arrays.stream(valueIds).boxed().collect(Collectors.toList());
            collect.remove(saleOptionIndex);
            int[] newValueIds = collect.stream().mapToInt(x -> x).toArray();
            newProduct.skuInfo.skus.get(index).valueIds = newValueIds;
        }
    }

    public void removeDuplicateValueIdSku(Migration migration) {
        List<SKU> skus = migration.newProduct.skuInfo.skus;

        //sku asc sort
        Collections.sort(skus, (x, y) -> {
            if (x.id < y.id) {
                return -1;
            } else {
                return 1;
            }
        });

        Map<String, List<SKU>> temp = new HashMap<>();
        for (SKU sku : skus) {
            List<SKU> thisSkus = temp.get(Arrays.toString(sku.valueIds));
            if (CollectionUtils.isEmpty(thisSkus)) {
                List<SKU> newSkus = new ArrayList<>();
                newSkus.add(sku);
                temp.put(Arrays.toString(sku.valueIds), newSkus);
            } else {
                thisSkus.add(sku);
                temp.put(Arrays.toString(sku.valueIds), thisSkus);
            }
        }

        for (Entry<String, List<SKU>> skuRelation : temp.entrySet()) {
            List<SKU> value = skuRelation.getValue();
            //存在重复关系
            if (value.size() > 1) {
                List<SKU> followSkus = value.subList(1, value.size());
                migration.skuRelationMap.put(value.get(0), followSkus);

                //delete duplicate sku
                for (SKU followSku : followSkus) {
                    skus.remove(followSku);
                }
            }
        }

        migration.newProduct.skuInfo.skus = skus;
    }


    public String getStockName(int id, Product oldProduct) {
        final String[] stockName = new String[1];
        oldProduct.skuInfo.skus.forEach(sku -> {
            if (sku.id == id) {
                int saleOptionIndex = getSaleOptionIndex(oldProduct);
                int valueId = sku.valueIds[saleOptionIndex];
                oldProduct.skuInfo.options.get(saleOptionIndex).values.forEach(value -> {
                    if (value.id == valueId) {
                        stockName[0] = value.label;
                    }
                });
            }
        });
        return stockName[0];
    }
}
