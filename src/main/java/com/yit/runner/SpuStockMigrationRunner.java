package com.yit.runner;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
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
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 去销售方式  +  库存迁移
 */
public class SpuStockMigrationRunner extends BaseTest {

    @Autowired
    ProductService productService;

    @Autowired
    SqlHelper sqlHelper;

    Product oldProduct;

    Map<SKU, List<SKU>> skuRelationMap = new HashMap<>();

    List<Integer> spuIdList = new ArrayList<>();

    @Override
    public void run() throws Exception {
        init();

        for (Integer spuId : spuIdList) {
            Product product = productService.getProductById(spuId);
            oldProduct = JSON.parseObject(JSON.toJSONString(product), Product.class);

            boolean haveSaleOption = product.skuInfo.options.stream().anyMatch(x -> "销售方式".equals(x.label));
            //spu empty
            if ((product.skuInfo.options.size() <= 0 && CollectionUtils.isEmpty(product.skuInfo.skus))) {
                print("WARING : skip Spu ID : -------> " + spuId + "; spu is empty!");
                continue;
            }
            //只有一个销售方式
            if (product.skuInfo.options.size() == 1 && haveSaleOption) {
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
            //多规格且有销售方式
            if (product.skuInfo.options.size() > 1 && haveSaleOption) {
                removeSaleOption(product);
            }

            removeDuplicateValueIdSku(product);
            migrationSpuStock();
            saveNewProduct(product);
            recordPointToText(spuId);
        }

        endProcessed();

    }

    //remember point
    private void recordPointToText(Integer spuId) throws IOException {
        File file = new File("pointRecord.txt");
        OutputStream os = new FileOutputStream(file);
        os.write(String.valueOf(spuId).getBytes());
        os.close();
    }

    //init
    private void init() throws Exception {
        File file = new File("pointRecord.txt");
        Integer pointRecord;
        if (file.exists()) {
            //read point
            pointRecord = Integer.parseInt(readStringFromFile("pointRecord.txt").trim());
        } else {
            OutputStream os = new FileOutputStream(file);
            os.write("".getBytes());
            os.close();

            //prepare action
            prepareAction();
            pointRecord = 0;
        }
        //获取所有SPU ID
        String sql = "select id from yitiao_product_spu where is_deleted = 0 order by id asc";

        sqlHelper.exec(sql, (row) -> {
            spuIdList.add(row.getInt("id"));
        });
        //从上一次的断点继续执行
        if (pointRecord != 0) {
            int indexPoint = spuIdList.indexOf(pointRecord);
            spuIdList = spuIdList.subList(indexPoint + 1, spuIdList.size());
            print("Continue from the last operation is carried out. Last SpuId : ---> {" + pointRecord + "}");
        }
    }

    private void endProcessed() throws Exception {
        sqlHelper.exec(readStringFromFile(new File("conf/endProcess.sql").getAbsolutePath()));
        print("Finished!");
    }

    public static void main(String[] args) {
        runTest(SpuStockMigrationRunner.class);
    }

    private void saveNewProduct(Product product) {
        try {
            productService.updateProduct(product, "系统", 0);
            print(String.format("Save-Product Action.  ID: %s  succeed", product.id));
        } catch (ServiceException e) {
            print(e.toString(), String.format("系统错误,保存Product ID: %s 时出错!", product.id));
        }
        skuRelationMap.clear();
    }

    private void migrationSpuStock() {
        String sqlStockName = "update yitiao_product_sku_stock set name = ? ,is_active = ? where sku_id = ? ";

        String sqlStock = "update yitiao_product_sku_stock set sku_id = ? where sku_id = ?";

        String sql = "select id from yitiao_product_sku_stock where  sku_id = ? and is_deleted = 0 order by id asc";

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
            //priority
            List<Integer> idList = new ArrayList<>();
            sqlHelper.exec(sql, new Object[] {masterSku.id}, (row) -> {
                idList.add(row.getInt("id"));
            });
            for (int i = 0; i < idList.size(); i++) {
                String sql2 = "update yitiao_product_sku_stock set priority = ? where id = ? ";
                sqlHelper.exec(sql2, new Object[] {i + 1, idList.get(i)});
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

        //sku asc sort
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
                if (Arrays.equals(skus.get(index).valueIds, skus.get(index2).valueIds)) {
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
        int saleOptionIndex = getSaleOptionIndex(product);
        product.skuInfo.options.remove(saleOptionIndex);
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

    private void prepareAction() throws Exception {
        sqlHelper.exec(getMigrationSql());
        File file = new File("conf/run.sql");
        String absolutePath = file.getAbsolutePath();
        String sqls = readStringFromFile(absolutePath);
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

    public String getMigrationSql() {
        return
            "insert into yitiao_product_sku_stock "
                + "( "
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
                + "      '现货', "
                + "      qty, "
                + "      notify_stock_qty, "
                + "      1, "
                + "      now(), "
                + "      status, "
                + "      1, "
                + "      0 "
                + "from cataloginventory_stock_item;";
    }
}
