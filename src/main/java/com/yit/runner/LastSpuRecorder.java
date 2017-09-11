package com.yit.runner;

import java.io.File;

import com.yit.test.BaseTest;

/**
 * 最后迁移SPU ID记录器
 */
public class LastSpuRecorder {

    static File pointFile = new File("pointRecord.txt");

    public static BaseTest baseTest = null;

    public static int getLastSpuId() {
        try {
            if (pointFile.exists()) {
                return Integer.parseInt(baseTest.readStringFromFile(pointFile.getAbsolutePath()).trim());
            }
            return 0;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }

    }

    public static void setLastSpuId(int spuId) {
        try {
            baseTest.writeStringToFile(pointFile.getAbsolutePath(), String.valueOf(spuId));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}
