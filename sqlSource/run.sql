
insert into yitiao_product_sku_stock
(
    sku_id,
    name,
    quantity,
    notify_quantity,
    priority,
    created_time,
    is_replenishing,
    is_active,
    is_deleted)
select
      product_id,
      '现货',
      qty,
      notify_stock_qty,
      1,
      now(),
      status,
      1,
      0
from cataloginventory_stock_item;


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
    );

alter table yitiao_product_sku_stock_history drop column sku_id;