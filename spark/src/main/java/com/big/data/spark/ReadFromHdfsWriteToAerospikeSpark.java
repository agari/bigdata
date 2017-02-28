package com.big.data.spark;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.Bin;
import com.aerospike.client.Key;
import com.aerospike.client.policy.WritePolicy;
import com.big.data.avro.schema.Employee;
import com.databricks.spark.avro.SchemaConverters;
import org.apache.commons.io.IOUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.sql.DataFrame;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SQLContext;
import org.apache.spark.sql.types.StructType;

import java.io.Closeable;
import java.io.IOException;

/**
 * Created by kunalgautam on 28.02.17.
 */
public class ReadFromHdfsWriteToAerospikeSpark extends Configured implements Tool, Closeable {

    public static final String INPUT_PATH = "spark.input.path";
    public static final String OUTPUT_PATH = "spark.output.path";
    public static final String IS_RUN_LOCALLY = "spark.is.run.local";
    public static final String DEFAULT_FS = "spark.default.fs";
    public static final String NUM_PARTITIONS = "spark.num.partitions";

    //Aerospike related properties
    public static final String AEROSPIKE_HOSTNAME = "aerospike.host.name";
    public static final String AEROSPIKE_PORT = "aerospike.port";
    public static final String AEROSPIKE_NAMESPACE = "aerospike.name.space";
    public static final String AEROSPIKE_SETNAME = "aerospike.set.name";

    // For Dem key is emp_id and value is emp_name
    public static final String KEY_NAME = "avro.key.name";
    public static final String VALUE_NAME = "avro.value.name";

    private static final String NEW_LINE_DELIMETER = "\n";

    private SQLContext sqlContext;
    private JavaSparkContext javaSparkContext;

    protected <T> JavaSparkContext getJavaSparkContext(final boolean isRunLocal,
                                                       final String defaultFs,
                                                       final Class<T> tClass) {
        final SparkConf sparkConf = new SparkConf()
                //Set spark conf here , after one gets spark context you can set hadoop configuration for InputFormats
                .setAppName(tClass.getSimpleName())
                .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer");

        if (isRunLocal) {
            sparkConf.setMaster("local[*]");
        }

        final JavaSparkContext sparkContext = new JavaSparkContext(sparkConf);

        if (defaultFs != null) {
            sparkContext.hadoopConfiguration().set("fs.defaultFS", defaultFs);
        }

        return sparkContext;
    }

    @Override
    public int run(String[] args) throws Exception {

        //The arguments passed has been split into Key value by ToolRunner
        Configuration conf = getConf();
        String inputPath = conf.get(INPUT_PATH);
        String outputPath = conf.get(OUTPUT_PATH);
        String aerospikeHostname = conf.get(AEROSPIKE_HOSTNAME);
        int aerospikePort = conf.getInt(AEROSPIKE_PORT, 3000);
        String namespace = conf.get(AEROSPIKE_NAMESPACE);
        String setName = conf.get(AEROSPIKE_SETNAME);

        String keyName = conf.get(KEY_NAME);
        String valueName = conf.get(VALUE_NAME);

        //Get spark context, This is the central context , which can be wrapped in Any Other context
        javaSparkContext = getJavaSparkContext(conf.getBoolean(IS_RUN_LOCALLY, Boolean.FALSE), conf.get(DEFAULT_FS), MapSideJoin.class);
        sqlContext = new SQLContext(javaSparkContext);

        // No input path has been read, no job has not been started yet .
        //To set any configuration use javaSparkContext.hadoopConfiguration().set(Key,value);
        // To set any custom inputformat use javaSparkContext.newAPIHadoopFile() and get a RDD

        // Avro schema to StructType conversion
        final StructType outPutSchemaStructType = (StructType) SchemaConverters.toSqlType(Employee.getClassSchema()).dataType();
        final StructType inputSchema = (StructType) SchemaConverters.toSqlType(Employee.getClassSchema()).dataType();

        // read data from parquetfile, the schema of the data is taken from the avro schema
        DataFrame inputDf = sqlContext.read().schema(inputSchema).parquet(inputPath);

        // convert DataFrame into JavaRDD
        // the rows read from the parquetfile is converted into a Row object . Row has same schema as that of the parquet file roe
        JavaRDD<Row> rowJavaRDD = inputDf.javaRDD();

        // Data read from parquet has same schema as that of avro (Empoyee Avro). Key is employeeId and value is EmployeeName
        // In the map there is no special function to initialize or shutdown the Aerospike client.
        JavaRDD<Row> returnedRowJavaRDD = rowJavaRDD.map(new InsetIntoAerospike(aerospikeHostname, aerospikePort, namespace, setName, keyName,
                                                                                valueName));

        DataFrame outputDf = sqlContext.createDataFrame(returnedRowJavaRDD, outPutSchemaStructType);

        // Convert JavaRDD to dataframe and save into parquet file
        outputDf
                .write()
                .format(Employee.class.getCanonicalName())
                .parquet(outputPath);

        return 0;
    }

    // Do remember all the lambda function are instantiated on driver, serialized and sent to driver.
    // No need to initialize the Service(Aerospike , Hbase on driver ) hence making it transiet
    // In the map , for each record insert into Aerospike , this can be coverted into batch too
    public static class InsetIntoAerospike implements Function<Row, Row> {

        // not making it static , as it will not be serialized and sent to executors
        private final String aerospikeHostName;
        private final int aerospikePortNo;
        private final String aerospikeNamespace;
        private final String aerospikeSetName;
        private final String keyColumnName;
        private final String valueColumnName;

        public InsetIntoAerospike(String hostName, int portNo, String nameSpace, String setName, String keyColumnName, String valueColumnName) {
            this.aerospikeHostName = hostName;
            this.aerospikePortNo = portNo;
            this.aerospikeNamespace = nameSpace;
            this.aerospikeSetName = setName;
            this.keyColumnName = keyColumnName;
            this.valueColumnName = valueColumnName;
        }

        // The Aerospike client is not serializable and neither there is a need to instatiate on driver
        private transient AerospikeClient client;
        private transient WritePolicy policy;

        @Override
        public Row call(Row v1) throws Exception {
            // Intitialize on the first call
            if (client == null) {
                policy = new WritePolicy();
                client = new AerospikeClient(aerospikeHostName, aerospikePortNo);
            }

            // As rows have schema with fieldName and Values being part of the Row
            Key key = new Key(aerospikeNamespace, aerospikeSetName, (Integer)v1.get(v1.fieldIndex(keyColumnName)));
            Bin bin = new Bin(valueColumnName, (String) v1.get(v1.fieldIndex(valueColumnName)));
            client.put(policy, key,bin);

            return v1;
        }
    }

    @Override
    public void close() throws IOException {
        IOUtils.closeQuietly(javaSparkContext);
    }

    public static void main(String[] args) throws Exception {
        ToolRunner.run(new ReadFromHdfsWriteToAerospikeSpark(), args);
    }

}
