package com.yit.runner;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.yit.common.utils.SqlHelper;
import com.yit.product.api.ProductService;
import com.yit.product.entity.Product;
import com.yit.test.BaseTest;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 主迁移程序
 */
public class MainMigration extends BaseTest {

    @Autowired
    ProductService productService;

    @Autowired
    SqlHelper sqlHelper;

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

    public static void main(String[] args) {
        runTest(MainMigration.class);
    }

    @Override
    public void run() throws Exception {

        // 获取最后迁移成功的SPU ID
        LastSpuRecorder.baseTest = this;
        int lastSpuId = LastSpuRecorder.getLastSpuId();

        // 执行初始化SQL
        if (lastSpuId <=0) {
            print("run init SQL");
            writeStringToFile("conf/pointRecord.txt","0");
            sqlHelper.exec(getMigrationSql());
            String absolutePath = new File("conf/initScript.sql").getAbsolutePath();
            String sqls = readStringFromFile(absolutePath);
            for (String sql : sqls.split(";")) {
                if (!StringUtils.isBlank(sql)) {
                    sqlHelper.exec(sql);
                }
            }
        }

        // 迁移SPU
        List<Integer> spuIdList = new ArrayList<>();
        String sql = "select id from yitiao_product_spu where id > " + lastSpuId + " order by id asc";
        sqlHelper.exec(sql, (row) -> {
            spuIdList.add(row.getInt("id"));
        });
        spuIdList.forEach(spuId -> {
            print("migration SPU: %s", spuId);
            SpuMigration spuMigration = new SpuMigration();
            spuMigration.productService = productService;
            spuMigration.sqlHelper = sqlHelper;
            spuMigration.baseTest = this;
            spuMigration.run(spuId);
            LastSpuRecorder.setLastSpuId(spuId);
        });

        // 结束SQL
        print("run end SQL");
        String updateOptionText = "UPDATE yitiao_product_sku SET option_text = '' WHERE option_text LIKE '%无规格%' ";
        sqlHelper.exec(updateOptionText);
        String endSqls = readStringFromFile(new File("conf/endScript.sql").getAbsolutePath());
        for (String endSql : endSqls.split(";")) {
            if (!StringUtils.isBlank(endSql)){
                sqlHelper.exec(endSql);
            }
        }

        //reload spu
        String reloadSpu = "select id from yitiao_product_spu";
        List<Integer> reloadList = new ArrayList<>();
        sqlHelper.exec(reloadSpu, (row) -> {
            int id = row.getInt("id");
            reloadList.add(id);
        });

        for (Integer spuId : reloadList) {
            Product product = new Product();
            product.id = spuId;
            productService.updateProduct(product, "系统", 0);
            print("Spu reload ID : " + spuId);
        }
    }
}
