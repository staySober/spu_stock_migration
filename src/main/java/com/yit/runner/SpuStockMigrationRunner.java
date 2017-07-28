package com.yit.runner;

import java.util.List;
import java.util.stream.Collectors;

import com.yit.product.api.ProductService;
import com.yit.product.entity.Product;
import com.yit.product.entity.Product.SKU;
import com.yit.product.entity.ProductQueryType;
import com.yit.product.entity.SpuComplete.CompleteStatus;
import com.yit.product.entity.SpuInfoPageResult;
import com.yit.test.BaseTest;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Created by sober on 2017/7/27.
 *
 * @author sober
 * @date 2017/07/27
 *
 * 去销售方式规格
 *
 * 迁移逻辑
 * 同规格不同销售方式的SKU合并成一个, 留下第一个创建的SKU, 销售方式文本写到库存记录里
 * 其他的SKU删掉, 销售方式文本和库存数, 写到库存记录里, 并绑定到第一个SKU上
 * 根据原来的SKU是否上架, SKU创建时间, 来设置哪个是生效库存
 * 如果原来的SPU只有一个销售方式的规格, 则在数据迁移后, 自动给SPU加一个规格叫"无规格", 规格值叫"无规格值"
 */
public class SpuStockMigrationRunner extends BaseTest {

    @Autowired
    ProductService productService;

    @Override
    public void run() throws Exception {
        exec();
    }

    public static void main(String[] args) {
        runTest(SpuStockMigrationRunner.class);
    }

    public void exec() {
        //step 1 :拿到所有SPU ID
        SpuInfoPageResult spuInfoPageResult = productService.searchSpuInfo(ProductQueryType.ALL, null, null, null, null,
            null, CompleteStatus.ALL, null, null);
        List<Integer> spuIds = spuInfoPageResult.rows.stream().map(x -> x.spuId).collect(Collectors.toList());

        //step 2: 查询SPU
        for (Integer spuId : spuIds) {
            Product product = productService.getProductById(spuId);
            //todo 如果option只有一个且是销售方式, 数据迁移后, 自动给SPU加一个规格叫"无规格", 规格值叫"无规格值"
            if (product.skuInfo.options.size() == 1 && product.skuInfo.options.get(0).label.equals("销售方式")) {

            }

            List<SKU> thisSkus = product.skuInfo.skus;
            for (int index = thisSkus.size() - 1; index >= 0; index--) {
                for (int index2 = index - 1; index2 >= 0; index2--) {
                    SKU skuRight = thisSkus.get(index);
                    SKU skuLeft = thisSkus.get(index2);

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

                    //todo 同规格不同销售方式的SKU合并成一个, 留下第一个创建的SKU, 销售方式文本写到库存记录里

                    //该spu option数量
                    int spuOptionCount = product.skuInfo.options.size();
                    boolean twoSkuHaveSameOption = false;

                    for (int i = 0; i < spuOptionCount; i++) {
                        //如果销售方式一样,直接break
                        if (i == saleOptionIndex) {
                            if (skuRight.valueIds[i] == skuLeft.valueIds[i]) {
                                twoSkuHaveSameOption = false;
                                break;
                            } else {
                                twoSkuHaveSameOption = true;
                            }
                        } else {
                            //校验非销售方式的option
                            if (skuRight.valueIds[i] != skuLeft.valueIds[i]) {
                                twoSkuHaveSameOption = false;
                                break;
                            } else {
                                twoSkuHaveSameOption = true;
                            }
                        }
                    }

                    //两个sku为规格相同 销售方式不同
                    if (twoSkuHaveSameOption) {
                        SKU deleteSku = skuLeft.id > skuRight.id ? skuLeft : skuRight;
                        SKU saveSku = skuLeft.id > skuRight.id ? skuRight : skuLeft;

                        //todo 把留下的SKU的销售方式文本写到库存记录里
                        //todo 删除其他的SKU
                    }

                }
            }

        }

    }
}
