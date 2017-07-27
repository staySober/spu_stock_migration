### 库存和SPU/SKU数据迁移实现

1. 在设计方案里, 库存的结构改变了. SPU/SKU的结构也变了. 需要根据设计方案里的迁移策略来做数据表的改造, 已经对旧数据进行迁移.

2. 设计文档: http://confluence.yit.net/pages/viewpage.action?pageId=65735
迁移的代码需要保存, 和需要check in到gitlab里

3. tips: yitiao_stock_history 表也需要做相应更改,product_id 更为 stock_id :存入对应的库存记录