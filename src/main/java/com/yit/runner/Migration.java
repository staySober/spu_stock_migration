package com.yit.runner;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.yit.product.entity.Product;
import com.yit.product.entity.Product.SKU;

/**
 * Migration 实体
 */
public class Migration {

    public Product oldProduct;

    public Product newProduct;

    public Map<SKU, List<SKU>> skuRelationMap;
}
