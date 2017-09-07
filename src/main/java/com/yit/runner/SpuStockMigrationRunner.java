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

    File pointFile = new File("pointRecord.txt");

    @Override
    public void run() throws Exception {
        //test func
        SingleMigrationTest();
        //main func
        //FullMigration();
    }

    public void SingleMigrationTest() throws IOException {
        SpuMigrationMain(4132);
    }

    public void FullMigration() throws Exception {
        init();
        for (Integer spuId : spuIdList) {
            SpuMigrationMain(spuId);
        }

        sqlHelper.exec(readStringFromFile(new File("conf/endScript.sql").getAbsolutePath()));
        print("Finished!");
    }

    private void init() throws Exception {
        String sql = "select id from yitiao_product_spu where is_deleted = 0 order by id asc";
        Integer pointRecord;
        if (pointFile.exists()) {
            //read point
            pointRecord = Integer.parseInt(readStringFromFile(pointFile.getAbsolutePath()).trim());
        } else {
            OutputStream os = new FileOutputStream(pointFile);
            os.write("0".getBytes());
            os.close();
            pointRecord = 0;

            sqlHelper.exec(getMigrationSql());
            String absolutePath = new File("conf/initScript.sql").getAbsolutePath();
            String sqls = readStringFromFile(absolutePath);
            for (String sql2 : sqls.split(";")) {
                if (!StringUtils.isBlank(sql)) {
                    sqlHelper.exec(sql2);
                }
            }
        }

        sqlHelper.exec(sql, (row) -> {spuIdList.add(row.getInt("id"));});
        //从上一次的断点继续执行
        if (pointRecord != 0) {
            int indexPoint = spuIdList.indexOf(pointRecord);
            spuIdList = spuIdList.subList(indexPoint + 1, spuIdList.size());
            print("Continue from the last operation is carried out. Last SpuId : ---> {" + pointRecord + "}");
        }
    }

    private void SpuMigrationMain(Integer spuId) throws IOException {
        Product product = productService.getProductById(spuId);
        oldProduct = JSON.parseObject(JSON.toJSONString(product), Product.class);

        boolean haveSaleOption = product.skuInfo.options.stream().anyMatch(x -> "销售方式".equals(x.label));

        //spu empty
        if ((product.skuInfo.options.size() <= 0 && CollectionUtils.isEmpty(product.skuInfo.skus))) {
            print("WARING : skip Spu ID : -------> " + spuId + "; spu is empty!");
            return;
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

        //todo Sku上下架的更新 根据使用中的库存对应的老的sku的状态来进行更新留下来的sku的上下架状态
        saveNewProduct(product);
    }

    private void saveNewProduct(Product product) throws IOException {
        try {
            productService.updateProduct(product, "系统", 0);
            print(String.format("Save-Product Action.  ID: %s  succeed", product.id));
        } catch (ServiceException e) {
            print(e.toString(), String.format("系统错误,保存Product ID: %s 时出错!", product.id));
        }
        skuRelationMap.clear();

        //record point
        OutputStream os = new FileOutputStream(pointFile);
        os.write(String.valueOf(product.id).getBytes());
        os.close();
    }

    private void migrationSpuStock() {
        String sqlStockName = "update yitiao_product_sku_stock set name = ? ,is_active = ? where sku_id = ? ";

        String sqlStockRef = "update yitiao_product_sku_stock set sku_id = ? where sku_id = ? ";

        String sqlPriority = "select id from yitiao_product_sku_stock where  sku_id = ? and is_deleted = 0 order by id asc";

        for (Entry<SKU, List<SKU>> entry : skuRelationMap.entrySet()) {
            SKU masterSku = entry.getKey();
            List<SKU> followSku = entry.getValue();

            //master name
            sqlHelper.exec(sqlStockName, new Object[] {getStockName(masterSku.id), computeDefaultStock(masterSku.id) ? 1 : 0, masterSku.id});

            //follower info
            for (SKU sku : followSku) {
                sqlHelper.exec(sqlStockName, new Object[] {getStockName(sku.id), computeDefaultStock(sku.id) ? 1 : 0, sku.id});
                Object[] params2 = new Object[] {masterSku.id, sku.id};
                sqlHelper.exec(sqlStockRef, params2);
            }

            //priority
            List<Integer> idList = new ArrayList<>();
            sqlHelper.exec(sqlPriority, new Object[] {masterSku.id}, (row) -> {
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

        Map<String,List<SKU>> temp = new HashMap<>();
        for (SKU sku : skus) {
            List<SKU> thisSkus = temp.get(Arrays.toString(sku.valueIds));
            if (CollectionUtils.isEmpty(thisSkus)) {
                List<SKU> newSkus = new ArrayList<>();
                newSkus.add(sku);
                temp.put(Arrays.toString(sku.valueIds),newSkus);
            }else {
                thisSkus.add(sku);
                temp.put(Arrays.toString(sku.valueIds),thisSkus);
            }
        }

        for (Entry<String, List<SKU>> skuRelation : temp.entrySet()) {
            List<SKU> value = skuRelation.getValue();
            //存在重复关系
            if (value.size() > 1) {
                List<SKU> followSkus = value.subList(1, value.size());
                skuRelationMap.put(value.get(0),followSkus);

                //delete duplicate sku
                for (SKU followSku : followSkus) {
                    skus.remove(followSku);
                }
            }
        }

        product.skuInfo.skus = skus;
    }

    private void removeSaleOption(Product product) {
        int saleOptionIndex = getSaleOptionIndex(product);
        product.skuInfo.options.remove(saleOptionIndex);

        for (int index = 0; index < product.skuInfo.skus.size(); index++) {
            int[] valueIds = product.skuInfo.skus.get(index).valueIds;
            List<Integer> collect = Arrays.stream(valueIds).boxed().collect(Collectors.toList());
            collect.remove(saleOptionIndex);
            int[] newValueIds = collect.stream().mapToInt(x -> x).toArray();
            product.skuInfo.skus.get(index).valueIds = newValueIds;
        }
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

    private int getSaleOptionIndex(Product product) {
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
                + "      '现货／2个工作日发货', "
                + "      qty, "
                + "      notify_stock_qty, "
                + "      1, "
                + "      now(), "
                + "      status, "
                + "      1, "
                + "      0 "
                + "from cataloginventory_stock_item;";
    }

    public static void main(String[] args) {
        runTest(SpuStockMigrationRunner.class);
    }
}
