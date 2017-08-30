create table yitiao_product_sku_stock (
                id int NOT NULL auto_increment,
                name varchar(200),
                sku_id int,
                quantity int,
                notify_quantity int,
                priority int,
                is_replenishing tinyint,
                is_active tinyint,
                is_deleted tinyint,
                PRIMARY KEY (id),
                KEY IDX_SKU_ID_AND_IS_ACTIVE (sku_id,is_active)
              )ENGINE=InnoDB AUTO_INCREMENT=0 DEFAULT CHARSET=utf8 COMMENT='sku stock table';


insert into yitiao_product_sku_stock
(
    sku_id,
    name,
    quantity,
    notify_quantity,
    priority,
    is_replenishing,
    is_active,
    is_deleted)
select
      product_id,
      '现货',
      qty,
      notify_stock_qty,
      1,
      status,
      1,
      0
from cataloginventory_stock_item;


update yitiao_product_sku_stock
set notify_quantity = 0
where
notify_quantity is null;


create table yitiao_product_sku_stock_history (
                id int NOT NULL auto_increment,
                stock_id int,
                sku_id int,
                operation_time datetime DEFAULT CURRENT_TIMESTAMP,
                adjust_amount int,
                current_amount int,
                note VARCHAR(200),
                order_number varchar(50),
                operator varchar(50),
                operator_id int,
                PRIMARY KEY (id),
                KEY ix_yitiao_stock_history_product_id (operation_time)
)ENGINE=InnoDB AUTO_INCREMENT = 0 DEFAULT CHARSET=utf8;


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