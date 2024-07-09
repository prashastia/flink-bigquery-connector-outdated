/*
 * Copyright (C) 2024 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.flink.bigquery.table;

import org.apache.flink.connector.base.DeliveryGuarantee;
import org.apache.flink.runtime.testutils.MiniClusterResourceConfiguration;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.types.logical.LogicalType;
import org.apache.flink.test.junit5.MiniClusterExtension;

import com.google.cloud.flink.bigquery.fakes.StorageClientFaker;
import com.google.cloud.flink.bigquery.sink.BigQuerySinkConfig;
import com.google.cloud.flink.bigquery.sink.serializer.RowDataToProtoSerializer;
import org.apache.avro.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mockito;

import java.io.IOException;

import static org.junit.Assert.assertEquals;

/** Class to test {@link BigQueryDynamicTableSink}. */
public class BigQueryDynamicTableSinkTest {
    static BigQueryDynamicTableSink bigQueryDynamicTableSink = null;
    static LogicalType logicalTypeSchema = null;
    static BigQuerySinkConfig bigQuerySinkConfig = null;

    private static final int PARALLELISM = 2;

    @RegisterExtension
    static final MiniClusterExtension MINI_CLUSTER_RESOURCE =
            new MiniClusterExtension(
                    new MiniClusterResourceConfiguration.Builder()
                            .setNumberTaskManagers(PARALLELISM)
                            .build());

    //    @BeforeAll
    //    public static void beforeTest() throws IOException {
    //        logicalTypeSchema =
    //                DataTypes.ROW(DataTypes.FIELD("number", DataTypes.BIGINT().notNull()))
    //                        .notNull()
    //                        .getLogicalType();
    //        bigQuerySinkConfig =
    //                BigQuerySinkConfig.newBuilder()
    //                        .deliveryGuarantee(DeliveryGuarantee.AT_LEAST_ONCE)
    //                        .connectOptions(StorageClientFaker.createConnectOptionsForWrite(null))
    //                        .serializer(new RowDataToProtoSerializer())
    //                        .build();
    //        //        bigQueryDynamicTableSink =
    //        //                new BigQueryDynamicTableSink(bigQuerySinkConfig, logicalTypeSchema);
    //    }

    @Test
    public void testConstructor() throws IOException {

        LogicalType logicalTypeSchema =
                DataTypes.ROW(DataTypes.FIELD("number", DataTypes.BIGINT().notNull()))
                        .notNull()
                        .getLogicalType();

        BigQuerySinkConfig bigQuerySinkConfig = Mockito.mock(BigQuerySinkConfig.class);
        Mockito.when(bigQuerySinkConfig.getConnectOptions())
                .thenReturn(StorageClientFaker.createConnectOptionsForWrite(null));
        Mockito.when(bigQuerySinkConfig.getSerializer()).thenReturn(new RowDataToProtoSerializer());
        Mockito.when(bigQuerySinkConfig.getDeliveryGuarantee())
                .thenReturn(DeliveryGuarantee.AT_LEAST_ONCE);

        BigQueryDynamicTableSink bigQueryDynamicTableSink =
                new BigQueryDynamicTableSink(bigQuerySinkConfig, logicalTypeSchema);

        BigQuerySinkConfig obtainedSinkConfig = bigQueryDynamicTableSink.getSinkConfig();
        assertEquals(logicalTypeSchema, bigQueryDynamicTableSink.getLogicalType());
        assertEquals(DeliveryGuarantee.AT_LEAST_ONCE, obtainedSinkConfig.getDeliveryGuarantee());
        Schema convertedAvroSchema =
                new Schema.Parser()
                        .parse(
                                "{\"type\":\"record\",\"name\":\"record\","
                                        + "\"namespace\":\"org.apache.flink.avro.generated\","
                                        + "\"fields\": [{\"name\":\"number\",\"type\":\"long\"}]}");
        assertEquals(convertedAvroSchema, obtainedSinkConfig.getSchemaProvider().getAvroSchema());
    }
    //
    //    @Test
    //    public void testCopy() {
    //        BigQueryDynamicTableSink bigQueryDynamicTableSinkCopy =
    //                new BigQueryDynamicTableSink(bigQuerySinkConfig, logicalTypeSchema);
    //
    //        assertEquals(bigQueryDynamicTableSinkCopy, bigQueryDynamicTableSink.copy());
    //    }
    //
    //    @Test
    //    public void testSummaryString() {
    //        assertEquals("BigQuery", bigQueryDynamicTableSink.asSummaryString());
    //    }
    //
    //    @Test
    //    public void testChangelogMode() {
    //        assertEquals(
    //                ChangelogMode.insertOnly(),
    //                bigQueryDynamicTableSink.getChangelogMode(Mockito.mock(ChangelogMode.class)));
    //    }
    //
    //        @Test
    //        public void testSinkRuntimeProvider() {
    //            assertInstanceOf(
    //                    SinkV2Provider.class,
    //                    bigQueryDynamicTableSink.getSinkRuntimeProvider(
    //                            Mockito.mock(DynamicTableSink.Context.class)));
    //        }
}
