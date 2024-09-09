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

package com.google.cloud.flink.bigquery.sink.writer;

import org.apache.flink.annotation.Internal;
import org.apache.flink.api.connector.sink2.Sink;
import org.apache.flink.api.connector.sink2.SinkWriter;
import org.apache.flink.metrics.Counter;
import org.apache.flink.metrics.groups.SinkWriterMetricGroup;

import com.google.api.core.ApiFuture;
import com.google.cloud.bigquery.storage.v1.AppendRowsResponse;
import com.google.cloud.bigquery.storage.v1.ProtoRows;
import com.google.cloud.bigquery.storage.v1.ProtoSchema;
import com.google.cloud.bigquery.storage.v1.ProtoSchemaConverter;
import com.google.cloud.bigquery.storage.v1.StreamWriter;
import com.google.cloud.flink.bigquery.common.config.BigQueryConnectOptions;
import com.google.cloud.flink.bigquery.services.BigQueryServices;
import com.google.cloud.flink.bigquery.services.BigQueryServicesFactory;
import com.google.cloud.flink.bigquery.sink.exceptions.BigQueryConnectorException;
import com.google.cloud.flink.bigquery.sink.exceptions.BigQuerySerializationException;
import com.google.cloud.flink.bigquery.sink.serializer.BigQueryProtoSerializer;
import com.google.cloud.flink.bigquery.sink.serializer.BigQuerySchemaProvider;
import com.google.protobuf.ByteString;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.LinkedList;
import java.util.Queue;

/**
 * Base class for developing a BigQuery writer.
 *
 * <p>This class abstracts implementation details common for BigQuery writers which use the {@link
 * StreamWriter}.
 *
 * <p>Key methods to note are "write" (implemented in child classes) and "flush" (implemented here).
 * Write gets called for every record and flush gets invoked at checkpoints. Writes collect records
 * to maximize the payload of BigQuery storage write APIs. Flush method is used to send pending
 * records before checkpoint completion.
 *
 * <p>BigQuery write APIs are invoked asynchronously for better performance, and responses are
 * validated lazily.
 *
 * <p>Serializer's "init" method is called in the writer's constructor because the resulting {@link
 * Descriptor} is not serializable and cannot be propagated to machines hosting writer instances.
 * Hence, this derivation of descriptors must be performed during writer initialization.
 *
 * @param <IN> Type of records to be written to BigQuery.
 */
abstract class BaseWriter<IN> implements SinkWriter<IN> {

    protected final Logger logger = LoggerFactory.getLogger(BaseWriter.class);
    // Multiply 0.95 to keep a buffer from exceeding payload limits.
    private static final long MAX_APPEND_REQUEST_BYTES =
            (long) (StreamWriter.getApiMaxRequestBytes() * 0.95);

    // Number of bytes to be sent in the next append request.
    private long appendRequestSizeBytes;

    // Number of Rows that were a part of the previous append request.
    private BigQueryServices.StorageWriteClient writeClient;
    protected final int subtaskId;
    private final BigQueryConnectOptions connectOptions;
    private final ProtoSchema protoSchema;
    private final BigQueryProtoSerializer serializer;

    // Contains the ApiFuture and the expected offset.
    private final Queue<Pair<ApiFuture<AppendRowsResponse>, Long>> appendResponseFuturesQueue;
    private final ProtoRows.Builder protoRowsBuilder;

    // Counters for metric reporting
    private long previousOffset;
    private final Counter numRecordsSendCounter;
    private final Counter numBytesSendCounter;
    final Counter successfullyAppendedRecordsCounter;
    final Counter numRecordsSendErrorCounter;
    final Counter numRecordsInSinceChkptCounter;
    final Counter successfullyAppendedRecordsSinceChkptCounter;
    StreamWriter streamWriter;
    String streamName;

    BaseWriter(
            int subtaskId,
            BigQueryConnectOptions connectOptions,
            BigQuerySchemaProvider schemaProvider,
            BigQueryProtoSerializer serializer,
            Sink.InitContext context) {
        this.subtaskId = subtaskId;
        this.connectOptions = connectOptions;
        this.protoSchema = getProtoSchema(schemaProvider);
        this.serializer = serializer;
        this.serializer.init(schemaProvider);
        appendRequestSizeBytes = 0L;
        appendResponseFuturesQueue = new LinkedList<>();
        protoRowsBuilder = ProtoRows.newBuilder();
        SinkWriterMetricGroup sinkWriterMetricGroup = context.metricGroup();
        // Count of rows which are successfully appended to Bigquery and will be available for
        // querying.
        // Count of records successfully appended by the Storage Write API.
        successfullyAppendedRecordsCounter =
                sinkWriterMetricGroup.counter("successfullyAppendedRecords");
        numRecordsInSinceChkptCounter = sinkWriterMetricGroup.counter("numRecordsInSinceChkpt");
        successfullyAppendedRecordsSinceChkptCounter =
                sinkWriterMetricGroup.counter("successfullyAppendedRecordsSinceChkpt");
        numRecordsSendCounter = sinkWriterMetricGroup.getNumRecordsSendCounter();
        numBytesSendCounter = sinkWriterMetricGroup.getNumBytesSendCounter();
        numRecordsSendErrorCounter = sinkWriterMetricGroup.getNumRecordsSendErrorsCounter();
        previousOffset = 0;
    }

    /** Append pending records and validate all remaining append responses. */
    @Override
    public void flush(boolean endOfInput) {
        if (appendRequestSizeBytes > 0) {
            append();
        }
        logger.debug("Validating all pending append responses in subtask {}", subtaskId);
        validateAppendResponses(true);
        // .flush() is called at checkpoint, resetting the counters after all tasks are done.
        // Set to 0.
        numRecordsInSinceChkptCounter.dec(numRecordsInSinceChkptCounter.getCount());
        successfullyAppendedRecordsSinceChkptCounter.dec(
                successfullyAppendedRecordsSinceChkptCounter.getCount());
    }

    /** Close resources maintained by this writer. */
    @Override
    public void close() {
        logger.debug("Closing writer in subtask {}", subtaskId);
        if (protoRowsBuilder != null) {
            protoRowsBuilder.clear();
        }
        if (appendResponseFuturesQueue != null) {
            appendResponseFuturesQueue.clear();
        }
        if (streamWriter != null) {
            streamWriter.close();
        }
        if (writeClient != null) {
            writeClient.close();
        }
    }

    /** Invoke BigQuery storage API for appending data to a table. */
    abstract ApiFuture sendAppendRequest(ProtoRows protoRows);

    /** Checks append response for errors. */
    abstract void validateAppendResponse(
            Pair<ApiFuture<AppendRowsResponse>, Long> appendResponseFuture);

    /** Add serialized record to append request. */
    void addToAppendRequest(ByteString protoRow) {
        protoRowsBuilder.addSerializedRows(protoRow);
        appendRequestSizeBytes += getProtoRowBytes(protoRow);
    }

    /** Send append request to BigQuery storage and prepare for next append request. */
    void append() {
        ApiFuture responseFuture = sendAppendRequest(protoRowsBuilder.build());
        long rowsNext = protoRowsBuilder.getSerializedRowsCount();
        // Every request also contains the number of rows it is attempting to add.
        appendResponseFuturesQueue.add(Pair.of(responseFuture, previousOffset + rowsNext));
        // ------ Increment the Flink metric Group Counters
        numRecordsSendCounter.inc(rowsNext);
        numBytesSendCounter.inc(getAppendRequestSizeBytes());
        previousOffset += rowsNext;

        logger.debug(
                String.format(
                        "numRecordsSendCounter updated: %d", numRecordsSendCounter.getCount()));
        logger.debug(
                String.format("numBytesSendCounter updated: %d", numBytesSendCounter.getCount()));
        // ----------------

        protoRowsBuilder.clear();
        appendRequestSizeBytes = 0L;
    }

    /** Creates a StreamWriter for appending to BigQuery table. */
    StreamWriter createStreamWriter(boolean enableConnectionPool) {
        logger.debug("Creating BigQuery StreamWriter in subtask {}", subtaskId);
        try {
            writeClient = BigQueryServicesFactory.instance(connectOptions).storageWrite();
            return writeClient.createStreamWriter(streamName, protoSchema, enableConnectionPool);
        } catch (IOException e) {
            logger.error("Unable to create StreamWriter for stream {}", streamName);
            throw new BigQueryConnectorException("Unable to create StreamWriter", e);
        }
    }

    /** Checks if serialized record can fit in current append request. */
    boolean fitsInAppendRequest(ByteString protoRow) {
        return appendRequestSizeBytes + getProtoRowBytes(protoRow) <= MAX_APPEND_REQUEST_BYTES;
    }

    /**
     * Serializes a record to BigQuery's proto format.
     *
     * @param element Record to serialize.
     * @return ByteString.
     * @throws BigQuerySerializationException If serialization to proto format failed.
     */
    ByteString getProtoRow(IN element) throws BigQuerySerializationException {
        ByteString protoRow = serializer.serialize(element);
        if (getProtoRowBytes(protoRow) > MAX_APPEND_REQUEST_BYTES) {
            logger.error(
                    "A single row of size %d bytes exceeded the allowed maximum of %d bytes for an append request",
                    getProtoRowBytes(protoRow), MAX_APPEND_REQUEST_BYTES);
            throw new BigQuerySerializationException(
                    "Record size exceeds BigQuery append request limit");
        }
        return protoRow;
    }

    /** Computes {@link ProtoSchema} for BigQuery table. */
    private static ProtoSchema getProtoSchema(BigQuerySchemaProvider schemaProvider) {
        return ProtoSchemaConverter.convert(schemaProvider.getDescriptor());
    }

    /** Gets size of serialized proto row. */
    private int getProtoRowBytes(ByteString protoRow) {
        // Protobuf overhead is at least 2 bytes per row.
        return protoRow.size() + 2;
    }

    /**
     * Throws a RuntimeException if an error is found in appends thus far. Since responses arrive in
     * order, we proceed to check the next response only after the previous one has arrived.
     */
    void validateAppendResponses(boolean waitForResponse) {
        Pair<ApiFuture<AppendRowsResponse>, Long> appendResponseFuture;
        while ((appendResponseFuture = appendResponseFuturesQueue.peek()) != null) {
            if (waitForResponse || appendResponseFuture.getLeft().isDone()) {
                appendResponseFuturesQueue.poll();
                validateAppendResponse(appendResponseFuture);
            } else {
                break;
            }
        }
    }

    /**
     * Following "getters" expose some internal fields required for testing.
     *
     * <p>In addition to keeping these methods package private, ensure that exposed field cannot be
     * changed in a way that alters the class instance's state.
     *
     * <p>Do NOT use these methods outside tests!
     */
    @Internal
    long getAppendRequestSizeBytes() {
        return appendRequestSizeBytes;
    }

    @Internal
    Queue<Pair<ApiFuture<AppendRowsResponse>, Integer>> getAppendResponseFuturesQueue() {
        return new LinkedList(appendResponseFuturesQueue);
    }

    @Internal
    ProtoRows getProtoRows() {
        return protoRowsBuilder.build();
    }
}
