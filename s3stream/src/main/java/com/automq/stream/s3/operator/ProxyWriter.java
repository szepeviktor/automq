/*
 * Copyright 2024, AutoMQ CO.,LTD.
 *
 * Use of this software is governed by the Business Source License
 * included in the file BSL.md
 *
 * As of the Change Date specified in that file, in accordance with
 * the Business Source License, use of this software will be governed
 * by the Apache License, Version 2.0
 */

package com.automq.stream.s3.operator;

import com.automq.stream.s3.ByteBufAlloc;
import com.automq.stream.s3.metadata.S3ObjectMetadata;
import com.automq.stream.s3.metrics.MetricsLevel;
import com.automq.stream.s3.metrics.TimerUtil;
import com.automq.stream.s3.metrics.stats.S3ObjectStats;
import com.automq.stream.s3.operator.ObjectStorage.WriteOptions;
import com.automq.stream.utils.FutureUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * If object data size is less than ObjectWriter.MAX_UPLOAD_SIZE, we should use single upload to upload it.
 * Else, we should use multi-part upload to upload it.
 */
class ProxyWriter implements Writer {
    final ObjectWriter objectWriter = new ObjectWriter();
    private final WriteOptions writeOptions;
    private final AbstractObjectStorage objectStorage;
    private final String path;
    private final long minPartSize;
    Writer multiPartWriter = null;

    public ProxyWriter(WriteOptions writeOptions, AbstractObjectStorage objectStorage, String path, long minPartSize) {
        this.writeOptions = writeOptions;
        this.objectStorage = objectStorage;
        this.path = path;
        this.minPartSize = minPartSize;
    }

    public ProxyWriter(WriteOptions writeOptions, AbstractObjectStorage objectStorage, String path) {
        this(writeOptions, objectStorage, path, Writer.MIN_PART_SIZE);
    }

    @Override
    public CompletableFuture<Void> write(ByteBuf part) {
        if (multiPartWriter != null) {
            return multiPartWriter.write(part);
        } else {
            objectWriter.write(part);
            if (objectWriter.isFull()) {
                newMultiPartWriter();
            }
            return objectWriter.cf;
        }
    }

    @Override
    public void copyOnWrite() {
        if (multiPartWriter != null) {
            multiPartWriter.copyOnWrite();
        } else {
            objectWriter.copyOnWrite();
        }
    }

    @Override
    public void copyWrite(S3ObjectMetadata s3ObjectMetadata, long start, long end) {
        if (multiPartWriter == null) {
            newMultiPartWriter();
        }
        multiPartWriter.copyWrite(s3ObjectMetadata, start, end);
    }

    @Override
    public boolean hasBatchingPart() {
        if (multiPartWriter != null) {
            return multiPartWriter.hasBatchingPart();
        } else {
            return objectWriter.hasBatchingPart();
        }
    }

    @Override
    public CompletableFuture<Void> close() {
        if (multiPartWriter != null) {
            return multiPartWriter.close();
        } else {
            return objectWriter.close();
        }
    }

    @Override
    public CompletableFuture<Void> release() {
        if (multiPartWriter != null) {
            return multiPartWriter.release();
        } else {
            return objectWriter.release();
        }
    }

    private void newMultiPartWriter() {
        this.multiPartWriter = new MultiPartWriter(writeOptions, objectStorage, path, minPartSize);
        if (objectWriter.data.readableBytes() > 0) {
            FutureUtil.propagate(multiPartWriter.write(objectWriter.data), objectWriter.cf);
        } else {
            objectWriter.data.release();
            objectWriter.cf.complete(null);
        }
    }

    class ObjectWriter implements Writer {
        // max upload size, when object data size is larger MAX_UPLOAD_SIZE, we should use multi-part upload to upload it.
        static final long MAX_UPLOAD_SIZE = 32L * 1024 * 1024;
        CompletableFuture<Void> cf = new CompletableFuture<>();
        CompositeByteBuf data = ByteBufAlloc.compositeByteBuffer();
        TimerUtil timerUtil = new TimerUtil();

        @Override
        public CompletableFuture<Void> write(ByteBuf part) {
            data.addComponent(true, part);
            return cf;
        }

        @Override
        public void copyOnWrite() {
            int size = data.readableBytes();
            if (size > 0) {
                ByteBuf buf = ByteBufAlloc.byteBuffer(size, writeOptions.allocType());
                buf.writeBytes(data.duplicate());
                CompositeByteBuf copy = ByteBufAlloc.compositeByteBuffer().addComponent(true, buf);
                this.data.release();
                this.data = copy;
            }
        }

        @Override
        public void copyWrite(S3ObjectMetadata s3ObjectMetadata, long start, long end) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean hasBatchingPart() {
            return true;
        }

        @Override
        public CompletableFuture<Void> close() {
            S3ObjectStats.getInstance().objectStageReadyCloseStats.record(timerUtil.elapsedAs(TimeUnit.NANOSECONDS));
            int size = data.readableBytes();
            FutureUtil.propagate(objectStorage.write(writeOptions, path, data), cf);
            cf.whenComplete((nil, e) -> {
                S3ObjectStats.getInstance().objectStageTotalStats.record(timerUtil.elapsedAs(TimeUnit.NANOSECONDS));
                S3ObjectStats.getInstance().objectNumInTotalStats.add(MetricsLevel.DEBUG, 1);
                S3ObjectStats.getInstance().objectUploadSizeStats.record(size);
            });
            return cf;
        }

        @Override
        public CompletableFuture<Void> release() {
            data.release();
            return CompletableFuture.completedFuture(null);
        }

        public boolean isFull() {
            return data.readableBytes() > MAX_UPLOAD_SIZE;
        }
    }
}
