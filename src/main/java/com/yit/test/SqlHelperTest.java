package com.yit.test;

import com.yit.common.utils.SqlHelper;
import com.yit.common.utils.TransactionUtil;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Created by sober on 2017/8/3.
 *
 * @author sober
 * @date 2017/08/03
 */
public class SqlHelperTest extends BaseTest {

    @Autowired
    SqlHelper sqlHelper;

    public static void main(String[] args) {
        runTest(SqlHelperTest.class);
    }

    @Override
    public void run() throws Exception {

        //update all
        String sql = "update yitiao_product_promotion set name = ?, type = ? where id = ?";
        Object[] params = new Object[] {"现货1111111111111", 1, 1};
            sqlHelper.exec(sql, params);
    }
}
