package com.yit.runner;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;


/**
 * Created by sober on 2017/8/4.
 *
 * @author sober
 * @date 2017/08/04
 */
public class ReadUtils {

    public static String read(File file) throws IOException {
        BufferedReader in = new BufferedReader(new FileReader(file));
        StringBuffer buffer = new StringBuffer();
        String line = null;
        while ((line = in.readLine()) != null) {
            buffer.append(line);
        }
        in.close();
        return buffer.toString();
    }



}
