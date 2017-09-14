UPDATE yitiao_product_sku
SET option_text = ''
WHERE option_text LIKE '%无规格%';

UPDATE yitiao_product_sku_stock_history
SET operator_id = -1
WHERE operator_id IS NULL;

insert into yitiao_product_sku_stock_history
(
    id,
    stock_id,
    sku_id,
    operation_time,
    adjust_amount,
    current_amount,
    note,
    order_number,
    operator,
    operator_id
)
 select
    id,
    product_id,
    product_id,
    operation_time,
    ajust_amount,
    current_amount,
    note,
    order_number,
    operator,
    operator_id
 from yitiao_stock_history;

UPDATE yitiao_product_sku_stock stock
INNER JOIN cataloginventory_stock_item item
SET stock.quantity = item.qty
WHERE
	stock.id = item.product_id;

update yitiao_product_sku_stock set created_time = now();