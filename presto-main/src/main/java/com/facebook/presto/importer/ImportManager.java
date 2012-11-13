package com.facebook.presto.importer;

import com.facebook.presto.hive.ImportClient;
import com.facebook.presto.metadata.Node;
import com.facebook.presto.metadata.ShardManager;
import com.facebook.presto.server.ShardImport;
import com.google.common.collect.ImmutableList;
import io.airlift.http.client.HttpClient;
import io.airlift.http.client.Request;
import io.airlift.json.JsonCodec;
import io.airlift.log.Logger;

import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.ws.rs.core.MediaType;
import java.net.URI;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.facebook.presto.importer.Threads.threadsNamed;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static io.airlift.http.client.JsonBodyGenerator.jsonBodyGenerator;
import static io.airlift.http.client.Request.Builder.prepareGet;
import static io.airlift.http.client.Request.Builder.preparePut;
import static io.airlift.http.client.StatusResponseHandler.StatusResponse;
import static io.airlift.http.client.StatusResponseHandler.createStatusResponseHandler;
import static java.util.concurrent.Executors.newFixedThreadPool;
import static java.util.concurrent.Executors.newScheduledThreadPool;
import static javax.ws.rs.core.Response.Status;

public class ImportManager
{
    private static final Logger log = Logger.get(ImportManager.class);

    private final ExecutorService partitionExecutor = newFixedThreadPool(50, threadsNamed("import-partition-%s"));
    private final ExecutorService chunkExecutor = newFixedThreadPool(50, threadsNamed("import-chunk-%s"));
    private final ScheduledExecutorService shardExecutor = newScheduledThreadPool(50, threadsNamed("import-shard-%s"));

    private final ImportClient importClient;
    private final ShardManager shardManager;
    private final NodeWorkerQueue nodeWorkerQueue;
    private final HttpClient httpClient;
    private final JsonCodec<ShardImport> shardImportCodec;

    @Inject
    public ImportManager(
            ImportClient importClient,
            ShardManager shardManager,
            NodeWorkerQueue nodeWorkerQueue,
            @ForImportManager HttpClient httpClient,
            JsonCodec<ShardImport> shardImportCodec)
    {
        this.importClient = checkNotNull(importClient, "importClient is null");
        this.shardManager = checkNotNull(shardManager, "shardManager is null");
        this.nodeWorkerQueue = checkNotNull(nodeWorkerQueue, "nodeWorkerQueue is null");
        this.httpClient = checkNotNull(httpClient, "httpClient is null");
        this.shardImportCodec = checkNotNull(shardImportCodec, "shardImportCodec");
    }

    public void importTable(long tableId, String sourceName, String databaseName, String tableName, List<ImportField> fields)
    {
        checkNotNull(sourceName, "sourceName is null");
        checkNotNull(databaseName, "databaseName is null");
        checkNotNull(tableName, "tableName is null");
        checkNotNull(fields, "fields is null");

        checkArgument(!fields.isEmpty(), "fields is empty");
        checkArgument(sourceName.equals("hive"), "bad source name: %s", sourceName);

        shardManager.createImportTable(tableId, sourceName, databaseName, tableName);

        List<String> partitions = importClient.getPartitionNames(databaseName, tableName);
        log.debug("scheduling %s partitions: %s", partitions.size(), tableId);

        for (String partition : partitions) {
            PartitionChunkSupplier supplier = new PartitionChunkSupplier(importClient, databaseName, tableName, partition);
            PartitionJob job = new PartitionJob(tableId, sourceName, partition, supplier, fields);
            partitionExecutor.execute(job);
        }
    }

    @PreDestroy
    public void stop()
    {
        partitionExecutor.shutdown();
        chunkExecutor.shutdown();
        shardExecutor.shutdown();
    }

    private class PartitionJob
            implements Runnable
    {
        private final long tableId;
        private final List<ImportField> fields;
        private final String sourceName;
        private final String partitionName;
        private final PartitionChunkSupplier supplier;

        public PartitionJob(long tableId, String sourceName, String partitionName, PartitionChunkSupplier supplier, List<ImportField> fields)
        {
            this.tableId = tableId;
            this.sourceName = checkNotNull(sourceName, "sourceName is null");
            this.partitionName = checkNotNull(partitionName, "partitionName is null");
            this.supplier = checkNotNull(supplier, "supplier is null");
            this.fields = ImmutableList.copyOf(checkNotNull(fields, "fields is null"));
        }

        @Override
        public void run()
        {
            List<byte[]> chunks = supplier.get();
            List<Long> shardIds = shardManager.createImportPartition(tableId, partitionName, chunks);
            log.debug("retrieved partition chunks: %s: %s: %s", partitionName, chunks.size(), shardIds);

            for (int i = 0; i < chunks.size(); i++) {
                byte[] chunk = chunks.get(i);
                long shardId = shardIds.get(i);
                chunkExecutor.execute(new ChunkJob(sourceName, shardId, chunk, fields));
            }
        }
    }

    private class ChunkJob
            implements Runnable
    {
        private final long shardId;
        private final ShardImport shardImport;

        public ChunkJob(String sourceName, long shardId, byte[] chunk, List<ImportField> fields)
        {
            this.shardId = shardId;
            this.shardImport = new ShardImport(sourceName, chunk, fields);
        }

        @Override
        public void run()
        {
            log.debug("acquiring worker for shard: %s", shardId);
            Node worker;
            try {
                worker = nodeWorkerQueue.acquireNodeWorker();
            }
            catch (InterruptedException e) {
                log.warn("interrupted while acquiring node worker");
                Thread.currentThread().interrupt();
                return;
            }
            log.debug("acquired worker for shard: %s: %s", shardId, worker);

            if (!initiateShardCreation(worker)) {
                nodeWorkerQueue.releaseNodeWorker(worker);
                chunkExecutor.execute(this);
            }
        }

        private boolean initiateShardCreation(Node worker)
        {
            URI shardUri = URI.create(worker.getHttpUri() + "/v1/shard/" + shardId);
            Request request = preparePut()
                    .setUri(shardUri)
                    .setHeader("Content-Type", MediaType.APPLICATION_JSON)
                    .setBodyGenerator(jsonBodyGenerator(shardImportCodec, shardImport))
                    .build();

            StatusResponse response;
            try {
                response = httpClient.execute(request, createStatusResponseHandler());
            }
            catch (RuntimeException e) {
                log.warn(e, "request failed: %s", shardId);
                return false;
            }

            if (response.getStatusCode() != Status.ACCEPTED.getStatusCode()) {
                log.warn("unexpected response status: %s: %s", shardId, response.getStatusCode());
                return false;
            }

            log.debug("initiated shard creation: %s", shardId);
            shardExecutor.schedule(new ShardJob(shardId, worker), 1, TimeUnit.SECONDS);
            return true;
        }
    }

    private class ShardJob
            implements Runnable
    {
        private final long shardId;
        private final Node worker;

        private ShardJob(long shardId, Node worker)
        {
            this.shardId = shardId;
            this.worker = checkNotNull(worker, "worker is null");
        }

        @Override
        public void run()
        {
            if (!isShardComplete()) {
                // TODO: after some period, give up and re-queue chunk job
                shardExecutor.schedule(this, 1, TimeUnit.SECONDS);
            }

            shardManager.commitShard(shardId, worker.getNodeIdentifier());
            nodeWorkerQueue.releaseNodeWorker(worker);
            log.info("shard imported: %s", shardId);
        }

        private boolean isShardComplete()
        {
            URI shardUri = URI.create(worker.getHttpUri() + "/v1/shard/" + shardId);
            Request request = prepareGet().setUri(shardUri).build();

            Status status;
            try {
                StatusResponse response = httpClient.execute(request, createStatusResponseHandler());
                status = Status.fromStatusCode(response.getStatusCode());
            }
            catch (RuntimeException e) {
                log.warn(e, "request failed: %s", shardId);
                return false;
            }
            log.debug("shard status: %s: %s", shardId, status);

            if (status == Status.ACCEPTED) {
                // still in progress
                return false;
            }

            if (status == Status.OK) {
                return true;
            }

            log.warn("unexpected response status: %s: %s", shardId, status.getStatusCode());
            return false;
        }
    }
}