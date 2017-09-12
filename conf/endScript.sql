UPDATE yitiao_product_sku
SET option_text = ''
WHERE option_text LIKE '%无规格%';


UPDATE yitiao_product_sku_stock_history
SET operator_id = -1
WHERE operator_id IS NULL;