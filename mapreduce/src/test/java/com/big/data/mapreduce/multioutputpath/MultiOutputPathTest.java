package com.big.data.mapreduce.multioutputpath;

import com.cloudera.org.joda.time.DateTime;
import org.apache.commons.io.FileUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Created by kunalgautam on 01.02.17.
 */
public class MultiOutputPathTest {

    private final Configuration conf = new Configuration();
    private static FileSystem fs;
    private static final DateTime NOW = DateTime.now();
    private static String baseDir;
    private static String outputDir;
    private static final String NEW_LINE_DELIMETER = "\n";

    @BeforeClass
    public static void startup() throws Exception {

        Configuration conf = new Configuration();
        //set the fs to file:/// which means the local fileSystem
        conf.set("fs.default.name", "file:///");
        conf.set("mapred.job.tracker", "local");
        fs = FileSystem.getLocal(conf);
        baseDir = "/tmp/fetch/" + UUID.randomUUID().toString();
        outputDir = baseDir + "/output/";

        File tempFile = new File(baseDir + "/input.txt");

        //Write the data into the local filesystem
        FileUtils.writeStringToFile(tempFile, "2", "UTF-8");
        FileUtils.writeStringToFile(tempFile, NEW_LINE_DELIMETER, "UTF-8", true);
        FileUtils.writeStringToFile(tempFile, "3", "UTF-8", true);
        FileUtils.writeStringToFile(tempFile, NEW_LINE_DELIMETER, "UTF-8", true);
        FileUtils.writeStringToFile(tempFile, "4", "UTF-8", true);

    }

    @AfterClass
    public static void cleanup() throws Exception {
        //Delete the local filesystem folder after the Job is done
        fs.delete(new Path(baseDir), true);
    }

    @Test
    public void WordCount() throws Exception {
        MultiOutputPathDriver driver = new MultiOutputPathDriver();

        // Any argument passed with -DKey=Value will be parsed by ToolRunner
        String[] args = new String[]{"-D" + MultiOutputPathDriver.INPUT_PATH + "=" + baseDir, "-D" + MultiOutputPathDriver.OUTPUT_PATH + "=" +
                outputDir};
        driver.main(args);

        //Read the data from the outputfile
        File outputFile = new File(outputDir + EvenOddNumberMapper.EVEN_KEY_PATH + "/part-m-00000");
        String fileToString = FileUtils.readFileToString(outputFile, "UTF-8");
        Set<Integer> integeSet = new HashSet<>();

        //4 lines in output file, with one word per line
        Arrays.stream(fileToString.split(NEW_LINE_DELIMETER)).forEach(e -> {
            integeSet.add(Integer.parseInt(e));
        });

        //4 words .
        Assert.assertEquals(2L, integeSet.size());
        Assert.assertTrue(integeSet.contains(2));
        Assert.assertTrue(integeSet.contains(4));
    }

}
