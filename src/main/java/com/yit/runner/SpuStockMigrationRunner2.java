package com.yit.runner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.alibaba.dubbo.common.utils.CollectionUtils;

import com.yit.common.utils.SqlHelper;
import com.yit.product.api.ProductService;
import com.yit.product.entity.Product;
import com.yit.product.entity.Product.Option;
import com.yit.product.entity.Product.Option.Value;
import com.yit.product.entity.Product.SKU;
import com.yit.product.entity.ProductQueryType;
import com.yit.product.entity.SpuComplete.CompleteStatus;
import com.yit.product.entity.SpuInfoPageResult;
import com.yit.test.BaseTest;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Created by sober on 2017/7/28.
 *
 * @author sober
 * @date 2017/07/28
 */
public class SpuStockMigrationRunner2 extends BaseTest {

    @Autowired
    ProductService productService;

    @Autowired
    SqlHelper sqlHelper;

    //存放去除销售方式后product的容器
    List<Product> newProducts = new ArrayList<>();

    //存放去除销售方式后product的容器
    List<Product> oldProducts = new ArrayList<>();

    //存放未删除sku和被删除的sku对应关系的容器
    Map<SKU, List<SKU>> skuRelationMap = new HashMap<>();

    @Override
    public void run() throws Exception {
        exec();
    }

    public static void main(String[] args) {
        runTest(SpuStockMigrationRunner2.class);
    }

    private void exec() {
        //step 1 :拿到所有SPU ID
        SpuInfoPageResult spuInfoPageResult = productService.searchSpuInfo(ProductQueryType.ALL, null, null, null, null,
            null, CompleteStatus.ALL, null, null);
        List<Integer> spuIds = spuInfoPageResult.rows.stream().map(x -> x.spuId).collect(Collectors.toList());

        //step 2: 查询SPU
        for (Integer spuId : spuIds) {
            Product product = productService.getProductById(spuId);
            //存放老数据 减少db query次数
            oldProducts.add(product);

            //如果option为空 sku不存在直接跳过
            if ((product.skuInfo.options.size() <= 0 && CollectionUtils.isEmpty(product.skuInfo.skus))) {
                continue;
            }

            //todo 如果option只有一个且是销售方式, 数据迁移后, 自动给SPU加一个规格叫"无规格", 规格值叫"无规格值"
            if (product.skuInfo.options.size() == 1 && product.skuInfo.options.get(0).label.equals("销售方式")) {
                product.skuInfo.options.remove(0);
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
                product.skuInfo.options.add(option);
                //给sku添加valueIds
                product.skuInfo.skus.forEach(sku -> {
                    int[] valueIds = sku.valueIds;
                    List<Integer> collect = Arrays.stream(valueIds).boxed().collect(Collectors.toList());
                    collect.add(value.valueId);
                    int[] newValueIds = collect.stream().mapToInt(x -> x).toArray();
                    sku.valueIds = newValueIds;
                });
            }
            //获取销售方式下标
            int saleOptionIndex = getSaleOptionIndex(product);

            //step 2.1 剔除Option中的销售方式
            product.skuInfo.options.remove(saleOptionIndex);

            //setp 2.2 剔除sku中valueId中对应的销售方式value
            removeSaleOptionSkuValueId(saleOptionIndex, product.skuInfo.skus);

            newProducts.add(product);
        }

        //step 3: 查询重复的valueIds 留创建最早的sku 干掉其他sku
        removeDuplicateValueIdsSku();

        //step 4: 保存销售规格 库存
        alterStockTable();
        migrationSpuStock();
    }

    //库存数据迁移
    private void migrationSpuStock() {
        for (Map.Entry<SKU, List<SKU>> thisSkus : skuRelationMap.entrySet()) {
            SKU masterSku = thisSkus.getKey();
            List<SKU> followSkus = thisSkus.getValue();
            SKUStockInfo oldSkuInfoMaster = getOldSkuInfoById(masterSku.id);
            //update master
            String sql1 = "update cataloginventory_stock_item set stock_name = ?, is_active = ? where product_id = ?";
            Object[] params1 = new Object[] {oldSkuInfoMaster.saleOption, oldSkuInfoMaster.isdefaultStock ? 1 : 0, oldSkuInfoMaster.id};
            sqlHelper.exec(sql1, params1);

            //update follower
            String sql2
                = "update cataloginventory_stock_item set stock_name = ?, product_id = ?, is_active = ? where "
                + "product_id = ?";
            followSkus.forEach(sku -> {
                SKUStockInfo oldSkuInfoFollower = getOldSkuInfoById(sku.id);
                Object[] params2 = new Object[] {oldSkuInfoFollower.saleOption, masterSku.id, oldSkuInfoFollower.isdefaultStock ? 1 : 0, oldSkuInfoFollower.id};
                sqlHelper.exec(sql2, params2);
            });
        }
    }

    //alter stock表
    private void alterStockTable() {
        sqlHelper.exec("alter table cataloginventory_stock_item add column stock_name varchar(200)");
        sqlHelper.exec("alter table cataloginventory_stock_item add column is_active tinyint default 0");
        sqlHelper.exec("alter table cataloginventory_stock_item add column is_deleted tinyint default 0");
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

    //移除ValueIds重复的sku  并记录删除的与留下的sku的对应关系
    private void removeDuplicateValueIdsSku() {
        newProducts.forEach(spu -> {
            List<SKU> skus = spu.skuInfo.skus;
            //按照skuId排序
            sortSku(skus);
            for (int index = skus.size() - 1; index >= 0; index--) {
                for (int index2 = index - 1; index2 >= 0; index2--) {
                    //如果valueIds相同
                    if (isSame(skus.get(index).valueIds, skus.get(index2).valueIds)) {
                        List<SKU> thisSkuValue = skuRelationMap.get(skus.get(index2));
                        if (thisSkuValue != null) {
                            thisSkuValue.add(skus.get(index));
                            skuRelationMap.put(skus.get(index2), thisSkuValue);
                        } else {
                            List<SKU> newSkus = new ArrayList<SKU>();
                            newSkus.add(skus.get(index));
                            skuRelationMap.put(skus.get(index2), newSkus);
                        }
                        skus.remove(index);
                    }
                }
            }

        });
    }

    //根据id将Sku升序排列
    private void sortSku(List<SKU> skus) {
        Collections.sort(skus, (x, y) -> {
            if (x.id < y.id) {
                return -1;
            } else {
                return 1;
            }
        });
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

    //判断两个int[]值是否相同
    private boolean isSame(int[] array1, int[] array2) {
        return Arrays.equals(array1, array2);
    }

    public SKUStockInfo getOldSkuInfoById(int skuId) {
        SKUStockInfo skuStockInfo = new SKUStockInfo();
        oldProducts.stream().forEach(spu -> {
            spu.skuInfo.skus.forEach(sku -> {
                if (sku.id == skuId) {
                    int saleOptionIndex = getSaleOptionIndex(spu);
                    int valueId = sku.valueIds[saleOptionIndex];
                    spu.skuInfo.options.get(saleOptionIndex).values.forEach(value -> {
                        if (value.valueId == valueId) {
                            skuStockInfo.saleOption = value.label;
                        }
                    });
                    skuStockInfo.id = sku.id;
                    //计算是否是默认库存
                    if (!sku.saleInfo.onSale) {
                        skuStockInfo.isdefaultStock = false;
                    } else {
                        //查出所有上架sku,比较id,id最小的设置为默认库存
                        List<SKU> onSaleSku = spu.skuInfo.skus.stream().filter(x -> x.saleInfo.onSale).collect(
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
                            skuStockInfo.isdefaultStock = true;
                        } else {
                            skuStockInfo.isdefaultStock = false;
                        }
                    }
                }
            });
        });

        return skuStockInfo;
    }

    //template Class
    class SKUStockInfo {

        public int id;

        public String saleOption;

        public boolean isdefaultStock;
    }
}
