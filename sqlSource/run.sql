create table yitiao_product_sku_stock (
                id int NOT NULL auto_increment,
                name varchar(200),
                sku_id int,
                quantity int,
                notify_quantity int,
                is_replenishment tinyint,
                is_active tinyint,
                is_deleted tinyint,
                PRIMARY KEY (id),
                KEY IDX_SKU_ID_AND_IS_ACTIVE (sku_id,is_active)
              )ENGINE=InnoDB AUTO_INCREMENT=0 DEFAULT CHARSET=utf8 COMMENT='sku stock table';

insert into yitiao_product_sku_stock(sku_id,name,quantity,notify_quantity,is_replenishment,is_active,is_deleted) select product_id,'现货',qty,notify_stock_qty,status,1,0 from cataloginventory_stock_item;

update yitiao_product_sku_stock set notify_quantity = 0 where notify_quantity is null;

alter table yitiao_stock_history rename yitiao_product_sku_stock_history;

alter table yitiao_product_sku_stock_history change column product_id sku_id int;

alter table yitiao_product_sku_stock_history add column stock_id int;

update yitiao_product_sku_stock_history history set stock_id  = (select id from yitiao_product_sku_stock stock where history.sku_id = stock.sku_id );