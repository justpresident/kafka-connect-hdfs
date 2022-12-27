/*
 * Copyright 2018 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.connect.hdfs;

import io.confluent.common.utils.MockTime;
import io.confluent.connect.storage.StorageSinkConnectorConfig;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;
import io.confluent.connect.hdfs.utils.MemoryRecordWriter.Failure;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.sink.SinkRecord;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import io.confluent.connect.hdfs.utils.Data;
import io.confluent.connect.hdfs.utils.MemoryFormat;
import io.confluent.connect.hdfs.utils.MemoryRecordWriter;
import io.confluent.connect.hdfs.utils.MemoryStorage;
import io.confluent.connect.storage.common.StorageCommonConfig;

import static junit.framework.TestCase.assertNotNull;
import static org.junit.Assert.assertEquals;

public class FailureRecoveryTest extends HdfsSinkConnectorTestBase {
  private static final String ZERO_PAD_FMT = "%010d";
  private static final String extension = "";

  private Map<String, String> localProps = new HashMap<>();
  private MockTime time;

  @Before
  public void setUp() throws Exception {
    time = new MockTime();
    time.sleep(System.currentTimeMillis());
    super.setUp();
  }

  @Override
  protected Map<String, String> createProps() {
    Map<String, String> props = super.createProps();
    props.put(StorageCommonConfig.STORAGE_CLASS_CONFIG, MemoryStorage.class.getName());
    props.put(HdfsSinkConnectorConfig.FORMAT_CLASS_CONFIG, MemoryFormat.class.getName());
    props.putAll(localProps);
    return props;
  }

  @Test
  public void testCommitFailure() {

    Collection<SinkRecord> sinkRecords = createRecords(PARTITION, 0, 7);

    DataWriter hdfsWriter = new DataWriter(connectorConfig, context, avroData, time);
    MemoryStorage storage = (MemoryStorage) hdfsWriter.getStorage();
    storage.setFailure(MemoryStorage.Failure.appendFailure);

    hdfsWriter.write(sinkRecords);
    assertEquals(context.timeout(), (long) connectorConfig.getLong(HdfsSinkConnectorConfig.RETRY_BACKOFF_CONFIG));

    Map<String, List<Object>> data = Data.getData();

    String logFile = FileUtils.logFileName(url, logsDir, TOPIC_PARTITION);
    List<Object> content = data.get(logFile);
    assertEquals(null, content);

    hdfsWriter.write(new ArrayList<>());
    content = data.get(logFile);
    assertEquals(null, content);

    time.sleep(context.timeout());
    hdfsWriter.write(new ArrayList<>());
    content = data.get(logFile);
    assertEquals(6, content.size());

    hdfsWriter.close();
    hdfsWriter.stop();
  }

  @Test
  public void testRotateAppendFailure() throws Exception {
    localProps.put(
        HdfsSinkConnectorConfig.ROTATE_SCHEDULE_INTERVAL_MS_CONFIG,
        String.valueOf(TimeUnit.MINUTES.toMillis(10))
    );
    setUp();
    String key = "key";
    Schema schema = createSchema();
    Struct record = createRecord(schema);
    Collection<SinkRecord> sinkRecordsA = new ArrayList<>();
    Collection<SinkRecord> sinkRecordsB = new ArrayList<>();
    for (long offset = 0; offset < 7; offset++) {
      SinkRecord sinkRecord =
          new SinkRecord(TOPIC, PARTITION, Schema.STRING_SCHEMA, key, schema, record, offset);
      (offset < 4 ? sinkRecordsA : sinkRecordsB).add(sinkRecord);
    }
    DataWriter hdfsWriter = new DataWriter(connectorConfig, context, avroData, time);
    MemoryStorage storage = (MemoryStorage) hdfsWriter.getStorage();

    // Simulate a recovery after starting the task
    hdfsWriter.recover(TOPIC_PARTITION);

    hdfsWriter.write(sinkRecordsA);
    // 0,1,2 are committed
    // 3 is in a tmp file

    // Trigger time-based rotation of the file
    time.sleep(2 * (long) connectorConfig.get(StorageSinkConnectorConfig.ROTATE_SCHEDULE_INTERVAL_MS_CONFIG));

    // Simulate an exception thrown from HDFS during WAL append
    storage.setFailure(MemoryStorage.Failure.appendFailure);
    Data.logContents("Before failure");
    hdfsWriter.write(new ArrayList<>());
    Data.logContents("After failure");
    // 3 is in a tmp file with the writer closed

    // Perform a timed backoff so that the writer may retry.
    assertEquals(context.timeout(), (long) connectorConfig.getLong(HdfsSinkConnectorConfig.RETRY_BACKOFF_CONFIG));
    time.sleep(context.timeout());
    storage.setFailure(null);

    // Perform a normal write immediately afterwards
    hdfsWriter.write(sinkRecordsB);
    // 3 is appended to the wal and committed
    // 4, 5, 6 are written to a new file

    Data.logContents("After test");

    long[] validOffsets = {-1, 2, 3, 6};
    for (int i = 1; i < validOffsets.length; i++) {
      long startOffset = validOffsets[i - 1] + 1;
      long endOffset = validOffsets[i];
      String path = FileUtils.committedFileName(
          url,
          topicsDir.getOrDefault(TOPIC, StorageCommonConfig.TOPICS_DIR_DEFAULT),
          TOPIC + "/" + "partition=" + PARTITION,
          TOPIC_PARTITION,
          startOffset,
          endOffset,
          extension,
          ZERO_PAD_FMT
      );
      long size = endOffset - startOffset + 1;
      List<Object> records = Data.getData().get(path);
      assertNotNull(path + " should have been created", records);
      assertEquals(path + " should contain a full batch of records", size, records.size());
    }

    hdfsWriter.close();
    hdfsWriter.stop();
  }

  @Test
  public void testWriterFailureMultiPartitions() {

    ArrayList<SinkRecord> sinkRecords = new ArrayList<>();
    sinkRecords.add(createRecord(PARTITION, 0));
    sinkRecords.add(createRecord(PARTITION2, 0));

    DataWriter hdfsWriter = new DataWriter(connectorConfig, context, avroData, time);
    hdfsWriter.write(sinkRecords);
    sinkRecords.clear();

    sinkRecords.addAll(createRecords(PARTITION, 1, 6));
    sinkRecords.addAll(createRecords(PARTITION2, 1, 6));

    String encodedPartition = "partition=" + PARTITION;
    Map<String, io.confluent.connect.storage.format.RecordWriter> writers = hdfsWriter.getWriters(TOPIC_PARTITION);
    MemoryRecordWriter writer = (MemoryRecordWriter) writers.get(encodedPartition);
    writer.setFailure(MemoryRecordWriter.Failure.writeFailure);
    hdfsWriter.write(sinkRecords);

    assertEquals(context.timeout(), (long) connectorConfig.getLong(HdfsSinkConnectorConfig.RETRY_BACKOFF_CONFIG));

    Map<String, List<Object>> data = Data.getData();
    String directory2 = TOPIC + "/" + "partition=" + PARTITION2;
    long[] validOffsets = {-1, 2, 5};
    for (int i = 1; i < validOffsets.length; i++) {
      long startOffset = validOffsets[i - 1] + 1;
      long endOffset = validOffsets[i];
      String topicsDir = this.topicsDir.get(TOPIC_PARTITION2.topic());
      String path = FileUtils.committedFileName(url, topicsDir, directory2, TOPIC_PARTITION2,
                                                startOffset, endOffset, extension, ZERO_PAD_FMT);
      long size = endOffset - startOffset + 1;
      List<Object> records = data.get(path);
      assertEquals(size, records.size());
    }

    hdfsWriter.write(new ArrayList<>());
    assertEquals(context.timeout(), (long) connectorConfig.getLong(HdfsSinkConnectorConfig.RETRY_BACKOFF_CONFIG));

    Map<String, String> tempFileNames = hdfsWriter.getTempFileNames(TOPIC_PARTITION);
    String tempFileName = tempFileNames.get(encodedPartition);
    List<Object> content = data.get(tempFileName);
    assertEquals(1, content.size());
    for (int i = 0; i < content.size(); ++i) {
      assertEquals(createRecord(PARTITION, i), content.get(i));
    }

    time.sleep(context.timeout());
    hdfsWriter.write(sinkRecords.subList(6, 12)); // remove records for the successful partition
    assertEquals(3, content.size());
    for (int i = 0; i < content.size(); ++i) {
      assertEquals(createRecord(PARTITION, i), content.get(i));
    }

    hdfsWriter.write(new ArrayList<>());
    hdfsWriter.close();
    hdfsWriter.stop();
  }

  @Test
  public void testWriterFailure() {
    HdfsSinkConnectorConfig connectorConfig = new HdfsSinkConnectorConfig(properties);

    ArrayList<SinkRecord> sinkRecords = createRecords(PARTITION, 0, 1);
    DataWriter hdfsWriter = new DataWriter(connectorConfig, context, avroData, time);
    hdfsWriter.write(sinkRecords);

    sinkRecords = createRecords(PARTITION, 1, 6);

    String encodedPartition = "partition=" + PARTITION;
    Map<String, io.confluent.connect.storage.format.RecordWriter> writers = hdfsWriter.getWriters(TOPIC_PARTITION);
    MemoryRecordWriter writer = (MemoryRecordWriter) writers.get(encodedPartition);

    writer.setFailure(MemoryRecordWriter.Failure.writeFailure);
    hdfsWriter.write(sinkRecords);
    assertEquals(context.timeout(), (long) connectorConfig.getLong(HdfsSinkConnectorConfig.RETRY_BACKOFF_CONFIG));

    // nothing happens as we the retry back off hasn't yet passed
    hdfsWriter.write(new ArrayList<>());
    Map<String, List<Object>> data = Data.getData();

    Map<String, String> tempFileNames = hdfsWriter.getTempFileNames(TOPIC_PARTITION);
    String tempFileName = tempFileNames.get(encodedPartition);

    List<Object> content = data.get(tempFileName);
    assertEquals(1, content.size());
    assertEquals(createRecord(PARTITION, 0), content.get(0));

    time.sleep(context.timeout());
    hdfsWriter.write(new ArrayList<>());

    tempFileNames = hdfsWriter.getTempFileNames(TOPIC_PARTITION);
    tempFileName = tempFileNames.get(encodedPartition);

    content = data.get(tempFileName);
    assertEquals(1, content.size());
    SinkRecord refSinkRecord = createRecord(PARTITION, 6);
    assertEquals(refSinkRecord, content.get(0));

    hdfsWriter.write(new ArrayList<>());
    hdfsWriter.close();
    hdfsWriter.stop();
  }

  @Test
  public void testCloseFailure() throws Exception {
    HdfsSinkConnectorConfig connectorConfig = new HdfsSinkConnectorConfig(properties);

    ArrayList<SinkRecord> sinkRecords = createRecords(PARTITION, 0, 1);
    DataWriter hdfsWriter = new DataWriter(connectorConfig, context, avroData, time);
    hdfsWriter.write(sinkRecords);

    sinkRecords = createRecords(PARTITION, 1, 6);

    String encodedPartition = "partition=" + PARTITION;
    Map<String, io.confluent.connect.storage.format.RecordWriter> writers = hdfsWriter.getWriters(TOPIC_PARTITION);
    MemoryRecordWriter writer = (MemoryRecordWriter) writers.get(encodedPartition);

    writer.setFailure(Failure.closeFailure);
    hdfsWriter.write(sinkRecords);
    assertEquals(context.timeout(), (long) connectorConfig.getLong(HdfsSinkConnectorConfig.RETRY_BACKOFF_CONFIG));

    // nothing happens as we the retry back off hasn't yet passed
    sinkRecords = createRecords(PARTITION, 0, 7);
    hdfsWriter.write(sinkRecords);

    time.sleep(context.timeout());
    hdfsWriter.write(new ArrayList<>());

    Map<String, List<Object>> data = Data.getData();
    Map<String, String> tempFileNames = hdfsWriter.getTempFileNames(TOPIC_PARTITION);
    String tempFileName = tempFileNames.get(encodedPartition);

    List<Object> content = data.get(tempFileName);
    assertEquals(1, content.size());
    SinkRecord refSinkRecord = createRecord(PARTITION, 6);
    assertEquals(refSinkRecord, content.get(0));

    writer = (MemoryRecordWriter) writers.get(encodedPartition);
    writer.setFailure(Failure.closeFailure);
    hdfsWriter.write(createRecords(PARTITION, 7, 2));

    time.sleep(context.timeout());
    hdfsWriter.write(new ArrayList<>());
    assertEquals(6, hdfsWriter.getCommittedOffsets().get(new TopicPartition(TOPIC, PARTITION)).longValue());

    hdfsWriter.close();
    hdfsWriter.stop();
  }

  private SinkRecord createRecord(int partition, int offset) {
    String key = "key";
    Schema schema = createSchema();
    Struct record = createRecord(schema);

    return new SinkRecord(TOPIC, partition, Schema.STRING_SCHEMA, key, schema, record, offset);
  }

  private ArrayList<SinkRecord> createRecords(int partition, int startOffset, int numRecords) {
    ArrayList<SinkRecord> records = new ArrayList<>();
    for (int i = startOffset; i < numRecords + startOffset; i++) {
      records.add(createRecord(partition, i));
    }

    return records;
  }
}
