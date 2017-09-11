package com.yit.runner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import com.alibaba.dubbo.common.utils.CollectionUtils;
import com.alibaba.fastjson.JSON;

import com.yit.common.utils.CommonUtils;
import com.yit.common.utils.SqlHelper;
import com.yit.product.api.ProductService;
import com.yit.product.entity.Product;
import com.yit.product.entity.Product.Option;
import com.yit.product.entity.Product.Option.Value;
import com.yit.product.entity.Product.SKU;
import com.yit.test.BaseTest;

/**
 * 单个SPU迁移程序
 */
public class SpuMigration {

    public ProductService productService;
    public SqlHelper sqlHelper;
    public BaseTest baseTest;

    Product newProduct = null;
    Product oldProduct = null;
    Map<SKU, List<SKU>> skuRelationMap = null;
    List<SkuStock> stockList;

    public void run(int spuId) {
        newProduct = productService.getProductById(spuId);
        oldProduct = JSON.parseObject(JSON.toJSONString(newProduct), Product.class);
        skuRelationMap = new HashMap<>();

        // 添加无规格
        boolean spuOnlyOneOption = newProduct.skuInfo.options.size() == 1;
        boolean haveSaleOption = newProduct.skuInfo.options.stream().anyMatch(x -> "销售方式".equals(x.label));
        if (spuOnlyOneOption && haveSaleOption) {
            addNoOption();
        }

        // 去除销售方式规格
        removeSaleOption();

        // 删除SKU
        deleleSku();

        // 更新库存名字
        stockList = new ArrayList<>();
        oldProduct.skuInfo.skus.forEach(sku -> {
            String stockName = Utils.getStockName(oldProduct, sku.id);
            stockName = CommonUtils.ifNull(stockName, "现货／2个工作日发货");
            SkuStock skuStock = new SkuStock();
            skuStock.id = sku.id;
            skuStock.name = stockName;
            stockList.add(skuStock);
        });

        // 设置库存优先级
        stockList.sort((a, b) -> {
            return Integer.compare(a.id, b.id);
        });
        for (int index = 0; index < stockList.size(); ++index) {
            stockList.get(index).priority = index + 1;
        }

        // 设置生效库存
        stockList.forEach(stock -> {
            stock.isActive = 1;
        });
        for (Entry<SKU, List<SKU>> entry : skuRelationMap.entrySet()) {

            // 计算生效库存ID
            List<SKU> skuList = getSkuList(entry);
            List<SKU> onSaleSkuList = skuList.stream().filter(x -> x.saleInfo.onSale == true).collect(Collectors.toList());
            int stockId = onSaleSkuList.size() > 0 ? onSaleSkuList.get(0).id : skuList.get(0).id;

            skuList.forEach(sku -> {
                SkuStock stock = getStock(sku.id);
                stock.isActive = 0;
            });
            getStock(stockId).isActive = 1;
        }

        // 设置留下来的SKU的状态
        for (Entry<SKU, List<SKU>> entry : skuRelationMap.entrySet()) {
            List<SKU> skuList = getSkuList(entry);
            for (SKU sku : skuList) {
                SkuStock stock = getStock(sku.id);
                if (stock.isActive == 1) {
                    entry.getKey().saleInfo.onSale = sku.saleInfo.onSale;
                }
            }
        }

        // 更新库存关系
        stockList.forEach(stock -> {
            stock.skuId = stock.id;
        });
        for (Entry<SKU, List<SKU>> entry : skuRelationMap.entrySet()) {
            entry.getValue().forEach(sku -> {
                SkuStock stock = getStock(sku.id);
                stock.skuId = entry.getKey().id;
            });
        }

        // 保存库存
        try {
            String sql = baseTest.readStringFromFile("conf/stockUpdateSql.sql");
            stockList.forEach(stock -> {
                sqlHelper.exec(
                    sql,
                    new Object[] {
                        stock.name,
                        stock.skuId,
                        stock.priority,
                        stock.isActive,
                        stock.id });
            });
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }

        // 保存产品
        try {
            productService.updateProduct(newProduct, "去销售方式", 0);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private List<SKU> getSkuList(Entry<SKU, List<SKU>> entry) {
        List<SKU> skuList = new ArrayList<>();
        skuList.add(entry.getKey());
        skuList.addAll(entry.getValue());
        skuList.sort((x, y) -> {
            if (x.id > y.id) {
                return 1;
            } else {
                return -1;
            }
        });
        return skuList;
    }

    /**
     * 获取库存记录
     */
    private SkuStock getStock(int stockId) {
        for (SkuStock stock : stockList) {
            if (stock.id == stockId) {
                return stock;
            }
        }
        return null;
    }

    /**
     * 添加无规格
     */
    private void addNoOption() {
        Option option = new Option();
        option.label = "无规格";
        option.position = 0;
        List<Value> values = new ArrayList<>();
        Value value = new Value();
        value.label = "无规格值";
        value.position = 0;
        value.valueId = 0;
        values.add(value);
        option.values = values;
        newProduct.skuInfo.options.add(option);
        //给sku添加valueIds
        newProduct.skuInfo.skus.forEach(sku -> {
            int[] valueIds = sku.valueIds;
            List<Integer> collect = Arrays.stream(valueIds).boxed().collect(Collectors.toList());
            collect.add(option.values.get(0).valueId);
            int[] newValueIds = collect.stream().mapToInt(x -> x).toArray();
            sku.valueIds = newValueIds;
        });
    }

    /**
     * 去除销售方式
     */
    public void removeSaleOption() {
        int saleOptionIndex = Utils.getOptionIndex(newProduct, "销售方式");
        newProduct.skuInfo.options.remove(saleOptionIndex);

        for (int index = 0; index < newProduct.skuInfo.skus.size(); index++) {
            int[] valueIds = newProduct.skuInfo.skus.get(index).valueIds;
            List<Integer> collect = Arrays.stream(valueIds).boxed().collect(Collectors.toList());
            collect.remove(saleOptionIndex);
            int[] newValueIds = collect.stream().mapToInt(x -> x).toArray();
            newProduct.skuInfo.skus.get(index).valueIds = newValueIds;
        }
    }

    /**
     * 删除SKU
     */
    public void deleleSku() {

        // 分组
        Map<String, List<SKU>> skuGroupList = new HashMap<>();
        for (SKU sku : newProduct.skuInfo.skus) {
            String valudIdString = Arrays.toString(sku.valueIds);

            List<SKU> skuGroup = null;
            if (!skuGroupList.containsKey(valudIdString)) {
                skuGroup = new ArrayList<>();
                skuGroupList.put(valudIdString, skuGroup);
            } else {
                skuGroup = skuGroupList.get(valudIdString);
            }

            skuGroup.add(sku);
        }

        // 删除SKU
        for (Entry<String, List<SKU>> skuGroup : skuGroupList.entrySet()) {
            List<SKU> skuList = skuGroup.getValue();
            if (skuList.size() <= 1) {
                continue;
            }

            // 排序, 只保留第一个SKU
            Collections.sort(skuList, (x, y) -> {
                if (x.id < y.id) {
                    return -1;
                } else {
                    return 1;
                }
            });

            List<SKU> followSkus = skuList.subList(1, skuList.size());
            skuRelationMap.put(skuList.get(0), followSkus);

            // 删除剩余的SKU
            for (SKU followSku : followSkus) {
                newProduct.skuInfo.skus.remove(followSku);
            }
        }
    }
}
