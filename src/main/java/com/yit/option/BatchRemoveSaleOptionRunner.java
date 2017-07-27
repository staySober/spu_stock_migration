package com.yit.option;

import java.util.List;
import java.util.stream.Collectors;

import com.yit.product.api.ProductService;
import com.yit.product.entity.Product;
import com.yit.product.entity.Product.SKU;
import com.yit.product.entity.ProductInfo;
import com.yit.product.entity.ProductQueryType;
import com.yit.product.entity.SpuComplete.CompleteStatus;
import com.yit.product.entity.SpuInfo;
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
public class BatchRemoveSaleOptionRunner extends BaseTest {

    @Autowired
    ProductService productService;

    @Override
    public void run() throws Exception {
        exec();
    }

    public static void main(String[] args) {
        runTest(BatchRemoveSaleOptionRunner.class);
    }

    public void exec() {
        //拿到所有SPU ID
        SpuInfoPageResult spuInfoPageResult = productService.searchSpuInfo(ProductQueryType.ALL, null, null, null, null,
            null, CompleteStatus.ALL, null, null);
        List<Integer> spuIds = spuInfoPageResult.rows.stream().map(x -> x.spuId).collect(Collectors.toList());

        //查询SPU
        for (Integer spuId : spuIds) {
            Product product = productService.getProductById(spuId);
            //todo 如果option只有一个且是销售方式, 数据迁移后, 自动给SPU加一个规格叫"无规格", 规格值叫"无规格值"
            if (product.skuInfo.options.size() == 1 && product.skuInfo.options.get(0).label.equals("销售方式")) {

            }

            List<SKU> thisSkus = product.skuInfo.skus;
            for (int index = thisSkus.size() - 1; index >= 0; index--) {
                for (int index2 = index - 1; index2 >= 0; index2--) {
                    //thisSkus.get(index).
                }
            }

        }

    }
}
