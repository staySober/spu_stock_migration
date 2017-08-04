create table yitiao_product_sku_stock (
                id int NOT NULL auto_increment,
                name varchar(200),
                sku_id int,
                quantity int,
                notify_quantity int,
                status tinyint,
                is_active tinyint,
                is_deleted tinyint,
                PRIMARY KEY (id),
                KEY IDX_SKU_ID_AND_IS_ACTIVE (sku_id,is_active)
              )ENGINE=InnoDB AUTO_INCREMENT=0 DEFAULT CHARSET=utf8 COMMENT='sku stock table';

insert into yitiao_product_sku_stock(sku_id,quantity,notify_quantity,status,is_active,is_deleted) select product_id,qty,notify_stock_qty,status,0,0 from cataloginventory_stock_item;

alter table yitiao_stock_history rename yitiao_product_sku_stock_history;

alter table yitiao_product_sku_stock_history rename column product_id to sku_id;

alter table yitiao_product_sku_stock_history add column stock_name varchar(200);