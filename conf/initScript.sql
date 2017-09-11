update yitiao_product_sku_stock
set notify_quantity = 0
where
notify_quantity is null;

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

