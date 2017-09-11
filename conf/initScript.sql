update yitiao_product_sku_stock
set notify_quantity = 0
where
notify_quantity is null;

insert into yitiao_product_sku_stock_history
(
    id,
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
    operation_time,
    ajust_amount,
    current_amount,
    note,
    order_number,
    operator,
    operator_id
 from yitiao_stock_history;

update yitiao_product_sku_stock_history history
set
    stock_id  = (
    select
        id
    from yitiao_product_sku_stock stock
    where
        history.sku_id = stock.sku_id
    )
