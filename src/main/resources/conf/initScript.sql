update yitiao_product_sku_stock
set notify_quantity = 0
where
notify_quantity is null;