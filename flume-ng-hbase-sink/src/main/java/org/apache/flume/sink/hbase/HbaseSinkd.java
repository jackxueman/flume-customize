package org.apache.flume.sink.hbase;

import com.google.common.base.Throwables;
import org.apache.flume.*;
import org.apache.flume.conf.Configurable;
import org.apache.flume.instrumentation.SinkCounter;
import org.apache.flume.sink.AbstractSink;
import org.apache.flume.sink.hbase.configuration.HbaseInterceptorConfigurationConstants;
import org.apache.flume.sink.hbase.configuration.HbaseSinkConfigurationConstants;
import org.apache.flume.sink.hbase.elasticsearchandhbase.Utiles;
import org.apache.flume.sink.hbase.utile.Utile;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.util.Bytes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by ljh on 2017/9/21.
 */
public class HbaseSinkd extends AbstractSink implements Configurable {
    private static final Logger logger = LoggerFactory
            .getLogger(HbaseSinkd.class);
    private static final int size = 100;
    private final CounterGroup counterGroup = new CounterGroup();
    private String hostNames;
    private String post;
    private String index;
    private String indextype;
    private String fildname;
    private String separator;
    private int batchSize;
    private Configuration configuration;
    private SinkCounter sinkCounter;
    private boolean isLocal = false;
    Utile utile =new  Utile();
    Utiles utiles = new Utiles();
    @Override
    public void configure(Context context) {
        //预留 mysql更新配置
        String[] bb = context.getString(HbaseSinkConfigurationConstants.Host_Names).split(HbaseSinkConfigurationConstants.MH);
        hostNames = bb[0];
        post = bb[1];
        index = context.getString(HbaseSinkConfigurationConstants.Index_Name);//表名
        indextype = context.getString(HbaseSinkConfigurationConstants.Index_Type);//列簇名
        fildname = context.getString(HbaseSinkConfigurationConstants.Fild_Name);//所有字段名称
        separator = context.getString(HbaseSinkConfigurationConstants.SEPARATOR);//分隔符
        batchSize = context.getInteger(HbaseSinkConfigurationConstants.BATCH_SIZE, size);
        if (sinkCounter == null) {
            sinkCounter = new SinkCounter(getName());
        }
    }
    @Override
    public synchronized void start() {
        super.start();
        logger.info("Hbase sink {} started");
        sinkCounter.start();
        try {
            configuration =   utile.cfg(hostNames,post);
            logger.info("Hbase Connect  Success");
            utile.found(configuration,index,indextype);
        } catch (Exception ex) {
            ex.printStackTrace();
            sinkCounter.incrementConnectionFailedCount();
            if (configuration != null) {
                sinkCounter.incrementConnectionClosedCount();
            }
        }
        logger.info("-------link successful-------------");
    }

    @Override
    public Status process() throws EventDeliveryException {
        Status status = Status.READY;
        Channel channel = getChannel();
        Transaction txn = channel.getTransaction();
        List<Put> list =new ArrayList<>();
        try {
            txn.begin();
            HTable table = new HTable(configuration, Bytes.toBytes(index));
            int count;
            for (count = 0; count < batchSize; ++count) {
                Event event = channel.take();
                if (event == null) {
                    break;
                }
//                String rng = new String(event.getBody());
//                String[] by = StringUtils.splitPreserveAllTokens(rng, HbaseInterceptorConfigurationConstants.FH);
                list = utiles.putEvent(event,HbaseInterceptorConfigurationConstants.FH,fildname,indextype,list);
            }
            if (count <= 0) {
                sinkCounter.incrementBatchEmptyCount();
                counterGroup.incrementAndGet("channel.underflow");
                status = Status.BACKOFF;
            } else {
                if (count < batchSize) {
                    sinkCounter.incrementBatchUnderflowCount();
                    status = Status.BACKOFF;
                } else {
                    sinkCounter.incrementBatchCompleteCount();
                }

                sinkCounter.addToEventDrainAttemptCount(count);
            }
            if(list != null){
                table.put(list);
            }
            txn.commit();
            sinkCounter.addToEventDrainSuccessCount(count);
            counterGroup.incrementAndGet("transaction.success");
        } catch (Throwable ex) {
            try {
                txn.rollback();
                counterGroup.incrementAndGet("transaction.rollback");//事务回滚
            } catch (Exception ex2) {
                logger.error(
                        "Exception in rollback. Rollback might not have been successful.",
                        ex2);//回滚异常
            }
            if (ex instanceof Error || ex instanceof RuntimeException) {
                logger.error("Failed to commit transaction. Transaction rolled back.",
                        ex);//无法提交事务回滚
                Throwables.propagate(ex);
            } else {
                logger.error("Failed to commit transaction. Transaction rolled back.",
                        ex);
                throw new EventDeliveryException(
                        "Failed to commit transaction. Transaction rolled back.", ex);//无法提交事务回滚
            }
        } finally {
            txn.close();
        }
        return status;
    }

    @Override
    public synchronized void stop() {
        super.stop();

        sinkCounter.stop();
    }
}
