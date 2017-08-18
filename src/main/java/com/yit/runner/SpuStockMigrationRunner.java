package com.yit.runner;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import com.alibaba.dubbo.common.utils.CollectionUtils;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.fastjson.JSON;

import com.yit.common.utils.SqlHelper;
import com.yit.entity.ServiceException;
import com.yit.product.api.ProductService;
import com.yit.product.entity.Product;
import com.yit.product.entity.Product.Option;
import com.yit.product.entity.Product.Option.Value;
import com.yit.product.entity.Product.SKU;
import com.yit.test.BaseTest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 去销售方式  +  库存迁移
 */
public class SpuStockMigrationRunner extends BaseTest {

    private static final Logger logger = LoggerFactory.getLogger(SpuStockMigrationRunner.class);

    //存放原始product的容器
    Product oldProduct = null;

    //存放未删除sku和被删除的sku对应关系的容器
    Map<SKU, List<SKU>> skuRelationMap = new HashMap<>();

    //SPU ID
    List<Integer> spuIdList = new ArrayList<>();

    @Autowired
    ProductService productService;

    @Autowired
    SqlHelper sqlHelper;

    @Override
    public void run() throws Exception {
        //prepare action
        prepareAction();

        //获取所有SPU ID
        String sql = "select id from yitiao_product_spu where is_deleted = 0 order by id asc";
        sqlHelper.exec(sql, (row) -> {
            spuIdList.add(row.getInt("id"));
        });
      /*  List<Integer> mocklist = new ArrayList<>();
        mocklist.add(45);*/
        //循环去销售方式 修改库存结构
        for (Integer spuId : spuIdList) {
            //get product
            Product product = productService.getProductById(spuId);
            oldProduct = JSON.parseObject(JSON.toJSONString(product), Product.class);

            boolean haveSaleOption = product.skuInfo.options.stream().anyMatch(x -> "销售方式".equals(x.label));

            if ((product.skuInfo.options.size() <= 0 && CollectionUtils.isEmpty(product.skuInfo.skus))) {
                continue;
            }
            //坑 这个地方注意if语句的顺序
            if (product.skuInfo.options.size() == 1 && haveSaleOption) {
                //构造无规格
                Option option = makeUpNoOption();
                product.skuInfo.options.add(option);
                //给sku添加valueIds
                product.skuInfo.skus.forEach(sku -> {
                    int[] valueIds = sku.valueIds;
                    List<Integer> collect = Arrays.stream(valueIds).boxed().collect(Collectors.toList());
                    collect.add(option.values.get(0).valueId);
                    int[] newValueIds = collect.stream().mapToInt(x -> x).toArray();
                    sku.valueIds = newValueIds;
                });
                removeSaleOption(product);
            }

            if (product.skuInfo.options.size() > 1 && haveSaleOption) {
                removeSaleOption(product);
            }

            //去重复SKU 记录多库存关系
            removeDuplicateValueIdSku(product);

            //执行SQL,迁移stock,stockHistory
            migrationSpuStock(product);

            //Save Product
            saveNewProduct(product);
        }

    }

    public static void main(String[] args) {
        runTest(SpuStockMigrationRunner.class);
    }

    private void saveNewProduct(Product product) {
        try {
            productService.updateProduct(product, "系统", 0);
            logger.info(String.format("Sava-Product Action.  ID: %s ", product.id));
        } catch (ServiceException e) {
            logger.error(e.toString(), String.format("系统错误,保存Product ID: %s 时出错!", product.id));
        }
        //clear map
        skuRelationMap.clear();
    }

    private void migrationSpuStock(Product product) {
        //刷sku库存名称 默认库存
        String sqlStockName = "update yitiao_product_sku_stock set name = ? ,is_active = ? where sku_id = ? ";
        //订正多库存关系
        String sqlStock = "update yitiao_product_sku_stock set sku_id = ? where sku_id = ?";

        for (Entry<SKU, List<SKU>> entry : skuRelationMap.entrySet()) {
            SKU masterSku = entry.getKey();
            List<SKU> followSku = entry.getValue();
            boolean defaultStock = computeDefaultStock(masterSku.id);
            //master
            sqlHelper.exec(sqlStockName, new Object[] {"现货", defaultStock ? 1 : 0, masterSku.id});
            //follower
            for (SKU sku : followSku) {
                String stockName = getStockName(sku.id);
                boolean defaultStockFollow = computeDefaultStock(sku.id);
                sqlHelper.exec(sqlStockName, new Object[] {stockName, defaultStockFollow ? 1 : 0, sku.id});

                Object[] params2 = new Object[] {masterSku.id, sku.id};
                sqlHelper.exec(sqlStock, params2);
            }
        }

    }

    private String getStockName(int id) {
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

    private void removeDuplicateValueIdSku(Product product) {
        List<SKU> skus = product.skuInfo.skus;

        //根据ID将sku升序排列
        Collections.sort(skus, (x, y) -> {
            if (x.id < y.id) {
                return -1;
            } else {
                return 1;
            }
        });

        //remove sku by condition
        for (int index = skus.size() - 1; index >= 0; index--) {
            inner:
            for (int index2 = index - 1; index2 >= 0; index2--) {
                //如果valueIds相同
                if (isSame(skus.get(index).valueIds, skus.get(index2).valueIds)) {
                    List<SKU> thisSkuValue = skuRelationMap.get(skus.get(index2));
                    if (thisSkuValue != null) {
                        thisSkuValue.add(skus.get(index));
                        skuRelationMap.put(skus.get(index2), thisSkuValue);
                    } else {
                        List<SKU> newSkus = new ArrayList<>();
                        newSkus.add(skus.get(index));
                        skuRelationMap.put(skus.get(index2), newSkus);
                    }
                    skus.remove(index);
                    break inner;
                }
            }
        }

        product.skuInfo.skus = skus;
    }

    private void removeSaleOption(Product product) {
        //获取销售方式下标
        int saleOptionIndex = getSaleOptionIndex(product);
        //step 2.1 剔除Option中的销售方式
        product.skuInfo.options.remove(saleOptionIndex);
        //setp 2.2 剔除sku中valueId中对应的销售方式value
        removeSaleOptionSkuValueId(saleOptionIndex, product.skuInfo.skus);
    }

    private Option makeUpNoOption() {
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
        return option;
    }

    private void prepareAction() throws IOException {
        String sqls = ReadUtils.read(new File("sqlSource/run.sql"));
        for (String sql : sqls.split(";")) {
            if (!StringUtils.isBlank(sql)) {
                sqlHelper.exec(sql);
            }
        }
    }

    //获取销售方式所对应的index
    private int getSaleOptionIndex(Product product) {
        //销售方式 option 在集合中的下标
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

    //去掉sku中valueId对应的销售方式optionId
    private void removeSaleOptionSkuValueId(int saleOptionIndex, List<SKU> thisSkus) {
        for (int index = 0; index < thisSkus.size(); index++) {
            int[] valueIds = thisSkus.get(index).valueIds;
            List<Integer> collect = Arrays.stream(valueIds).boxed().collect(Collectors.toList());
            collect.remove(saleOptionIndex);
            int[] newValueIds = collect.stream().mapToInt(x -> x).toArray();
            thisSkus.get(index).valueIds = newValueIds;
        }
    }

    //判断两个int[]值是否相同
    private boolean isSame(int[] array1, int[] array2) {
        return Arrays.equals(array1, array2);
    }

    //计算是否是默认库存
    public boolean computeDefaultStock(int skuId) {
        final boolean[] isdefaultStock = new boolean[1];
        oldProduct.skuInfo.skus.forEach(sku -> {
            if (sku.id == skuId) {
                //计算是否是默认库存
                if (!sku.saleInfo.onSale) {
                    isdefaultStock[0] = false;
                } else {
                    //查出所有上架sku,比较id,id最小的设置为默认库存
                    List<SKU> onSaleSku = oldProduct.skuInfo.skus.stream().filter(x -> x.saleInfo.onSale).collect(
                        Collectors.toList());

                    Collections.sort(onSaleSku, (x, y) -> {
                        if (x.id > y.id) {
                            return 1;
                        } else {
                            return -1;
                        }
                    });

                    //根据id判断先后顺序
                    if (onSaleSku.get(0).id == sku.id) {
                        isdefaultStock[0] = true;
                    } else {
                        isdefaultStock[0] = false;
                    }
                }
            }
        });

        return isdefaultStock[0];
    }

}
