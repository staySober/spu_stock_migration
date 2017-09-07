package com.yit.runner;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
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

    @Autowired
    MigrationUtils migrationUtils;

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
            Migration migration = new Migration();
            migration.newProduct = productService.getProductById(spuId);
            migration.oldProduct = JSON.parseObject(JSON.toJSONString(migration.newProduct), Product.class);
            migration.skuRelationMap = new HashMap<>();

            SpuMigrationMain(migration);
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

            sqlHelper.exec(migrationUtils.getMigrationSql());
            String absolutePath = new File("conf/initScript.sql").getAbsolutePath();
            String sqls = readStringFromFile(absolutePath);
            for (String sql2 : sqls.split(";")) {
                if (!StringUtils.isBlank(sql)) {
                    sqlHelper.exec(sql2);
                }
            }
        }

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

    private void SpuMigrationMain(Migration migration) throws IOException {
        boolean haveSaleOption = migration.newProduct.skuInfo.options.stream().anyMatch(x -> "销售方式".equals(x.label));

        //spu empty
        if ((migration.newProduct.skuInfo.options.size() <= 0 && CollectionUtils.isEmpty(migration.newProduct.skuInfo.skus))) {
            print("WARING : skip Spu ID : -------> " + migration.oldProduct.id + "; spu is empty!");
            return;
        }

        //只有一个销售方式
        if (migration.newProduct.skuInfo.options.size() == 1 && haveSaleOption) {
            Option option = migrationUtils.makeUpNoOption();
            migration.newProduct.skuInfo.options.add(option);
            //给sku添加valueIds
            migration.newProduct.skuInfo.skus.forEach(sku -> {
                int[] valueIds = sku.valueIds;
                List<Integer> collect = Arrays.stream(valueIds).boxed().collect(Collectors.toList());
                collect.add(option.values.get(0).valueId);
                int[] newValueIds = collect.stream().mapToInt(x -> x).toArray();
                sku.valueIds = newValueIds;
            });
            migrationUtils.removeSaleOption(migration.newProduct);
        }

        //多规格且有销售方式
        if (migration.newProduct.skuInfo.options.size() > 1 && haveSaleOption) {
            migrationUtils.removeSaleOption(migration.newProduct);
        }

        migrationUtils.removeDuplicateValueIdSku(migration.newProduct,migration.skuRelationMap);

        migrationSpuStock(migration);

        setMultiStockPriority(migration);

        setStockDefaultActive(migration);

        updateSkuSaleStatus(migration);

        updateStockRelation(migration);

        saveNewProduct(migration);
    }

    private void updateSkuSaleStatus(Migration migration) {
        int skuId = migrationUtils.computeDefaultStock(migration.oldProduct);
        SKU activeSku = migration.oldProduct.skuInfo.skus.stream().filter(x -> x.id == skuId).findFirst().get();
        boolean isOnsale = activeSku.saleInfo.onSale;
        for (Entry<SKU, List<SKU>> entry : migration.skuRelationMap.entrySet()) {
            SKU maskterSku = entry.getKey();
            migration.newProduct.skuInfo.skus.forEach(sku -> {
                if (sku.id == maskterSku.id) {
                    sku.saleInfo.onSale = isOnsale;
                }
            });
        }
    }

    private void updateStockRelation(Migration migration) {
        String sqlStockRef = "update yitiao_product_sku_stock set sku_id = ? where sku_id = ? ";
        for (Entry<SKU, List<SKU>> entry : migration.skuRelationMap.entrySet()) {
            SKU masterSku = entry.getKey();
            List<SKU> followSku = entry.getValue();
            for (SKU sku : followSku) {
                sqlHelper.exec(sqlStockRef, new Object[] {masterSku.id, sku.id});
            }
        }
    }

    private void saveNewProduct(Migration migration) throws IOException {
        try {
            productService.updateProduct(migration.newProduct, "系统", 0);
            print(String.format("Save-Product Action.  ID: %s  succeed", migration.newProduct.id));
        } catch (ServiceException e) {
            print(e.toString(), String.format("系统错误,保存Product ID: %s 时出错!", migration.newProduct.id));
        }

        //record point
        OutputStream os = new FileOutputStream(pointFile);
        os.write(String.valueOf(migration.newProduct.id).getBytes());
        os.close();
    }

    private void migrationSpuStock(Migration migration) {
        String sqlStockName = "update yitiao_product_sku_stock set name = ?  where sku_id = ? ";

        for (Entry<SKU, List<SKU>> entry : migration.skuRelationMap.entrySet()) {
            SKU masterSku = entry.getKey();
            List<SKU> followSku = entry.getValue();

            //todo test
            String stockName = migrationUtils.getStockName(masterSku.id, migration.oldProduct);
            //master name
            sqlHelper.exec(sqlStockName, new Object[] {migrationUtils.getStockName(masterSku.id,migration.oldProduct), masterSku.id});

            //follower info
            for (SKU sku : followSku) {
                sqlHelper.exec(sqlStockName, new Object[] {migrationUtils.getStockName(sku.id,migration.oldProduct), sku.id});

            }
        }
    }

    private void setStockDefaultActive(Migration migration) {
        String sqlStockActive = "update yitiao_product_sku_stock set is_active = ? where sku_id = ? ";
        for (Entry<SKU, List<SKU>> entry : migration.skuRelationMap.entrySet()) {
            SKU masterSku = entry.getKey();
            List<SKU> followSkus = entry.getValue();
            //获取默认库存id
            int defaultSkuId = migrationUtils.computeDefaultStock(migration.oldProduct);
            sqlHelper.exec(sqlStockActive, new Object[] {defaultSkuId == masterSku.id ? 1 : 0, masterSku.id});

            for (SKU sku : followSkus) {
                sqlHelper.exec(sqlStockActive, new Object[] {defaultSkuId == sku.id ? 1 : 0, sku.id});
            }
        }
    }

    private void setMultiStockPriority(Migration migration) {
        String sqlPriority
            = "select id from yitiao_product_sku_stock where  sku_id = ? and is_deleted = 0 order by id asc";
        for (Entry<SKU, List<SKU>> entry : migration.skuRelationMap.entrySet()) {

            SKU masterSku = entry.getKey();
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

    public static void main(String[] args) {
        runTest(SpuStockMigrationRunner.class);
    }
}
