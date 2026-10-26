package net.momirealms.craftengine.core.world.chunk.storage;

import ca.spottedleaf.concurrentutil.map.concurrent.longs.ConcurrentChainedLong2ReferenceHashTable;
import net.momirealms.craftengine.core.plugin.CraftEngine;
import net.momirealms.craftengine.core.plugin.config.Config;
import net.momirealms.craftengine.core.world.CEWorld;
import net.momirealms.craftengine.core.world.ChunkPos;
import net.momirealms.craftengine.core.world.WorldSettings;
import net.momirealms.craftengine.core.world.chunk.CEChunk;
import net.momirealms.craftengine.core.world.chunk.Chunk;
import net.momirealms.craftengine.core.world.chunk.serialization.DefaultChunkSerializer;
import net.momirealms.sparrow.nbt.CompoundTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiConsumer;

public final class AsyncStorage implements WorldDataStorage {
    private static final int MAX_PENDING_WRITES = 256;
    private static final ExecutorService WRITE_EXECUTOR;

    static {
        AtomicInteger threadCounter = new AtomicInteger();
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "craft-engine-chunk-io-" + threadCounter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
        WRITE_EXECUTOR = Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors() / 8), threadFactory);
    }

    private final RegionStorage storage;
    private final ChunkEncoder encoder;
    private final Executor executor;
    private final BiConsumer<ChunkPos, Throwable> errorHandler;
    private final ConcurrentChainedLong2ReferenceHashTable<PendingWrite> pendingWrites = ConcurrentChainedLong2ReferenceHashTable.createWithCapacity(64);
    private final Object flushLock = new Object();
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();
    private volatile boolean closed;

    public AsyncStorage(RegionStorage storage) {
        this(storage, encoder(CompressionMethod.fromId(Config.compressionMethod())), WRITE_EXECUTOR,
                (pos, failure) -> CraftEngine.instance().logger().warn("Failed to save chunk at " + pos, failure));
    }

    AsyncStorage(RegionStorage storage, ChunkEncoder encoder, Executor executor, BiConsumer<ChunkPos, Throwable> errorHandler) {
        this.storage = storage;
        this.encoder = encoder;
        this.executor = executor;
        this.errorHandler = errorHandler;
    }

    private static ChunkEncoder encoder(CompressionMethod compression) {
        return tag -> RegionFile.encodeChunkData(compression, tag);
    }

    @Override
    public WorldSettings readSettings() throws IOException {
        return this.storage.readSettings();
    }

    @Override
    public void writeSettings(WorldSettings settings) throws IOException {
        this.lifecycle.readLock().lock();
        try {
            if (this.closed) throw new IOException("Chunk storage is closed");
            this.storage.writeSettings(settings);
        } finally {
            this.lifecycle.readLock().unlock();
        }
    }

    @Override
    public @NotNull CEChunk readChunkAt(@NotNull CEWorld world, @NotNull ChunkPos pos, @Nullable Chunk chunkAccess) throws IOException {
        PendingWrite pending = this.pendingWrites.get(pos.longKey);
        if (pending != null) {
            synchronized (pending) {
                if (!pending.completed) {
                    if (pending.tag == null) {
                        // 在途删除，等同于磁盘上不存在
                        return this.storage.chunkFactory().create(world, pos);
                    }
                    return DefaultChunkSerializer.deserialize(this.storage.chunkFactory(), world, pos, pending.tag);
                }
            }
        }
        return this.storage.readChunkAt(world, pos, chunkAccess);
    }

    @Override
    public @Nullable CompoundTag readChunkTagAt(@NotNull ChunkPos pos) throws IOException {
        PendingWrite pending = this.pendingWrites.get(pos.longKey);
        if (pending != null) {
            synchronized (pending) {
                if (!pending.completed) {
                    // 防御性拷贝，调用方可能修改返回的 tag
                    return pending.tag == null ? null : pending.tag.copy();
                }
            }
        }
        return this.storage.readChunkTagAt(pos);
    }

    @Override
    public void writeChunkAt(@NotNull ChunkPos pos, @NotNull CEChunk chunk) throws IOException {
        // 序列化必须在调用线程上做，活区块数据不允许异步线程读取
        CompoundTag tag = DefaultChunkSerializer.serialize(chunk);
        submit(pos, tag, chunk);
    }

    @Override
    public void writeChunkTagAt(@NotNull ChunkPos pos, @Nullable CompoundTag nbt) throws IOException {
        submit(pos, nbt == null ? null : nbt.copy(), null);
    }

    @Override
    public void clearChunkAt(@NotNull ChunkPos pos) throws IOException {
        submit(pos, null, null);
    }

    private void submit(ChunkPos pos, @Nullable CompoundTag tag, @Nullable CEChunk chunk) throws IOException {
        this.lifecycle.readLock().lock();
        try {
            this.enqueue(pos, tag, chunk);
        } finally {
            this.lifecycle.readLock().unlock();
        }
        applyBackpressure();
    }

    private void enqueue(ChunkPos pos, @Nullable CompoundTag tag, @Nullable CEChunk chunk) throws IOException {
        if (this.closed) throw new IOException("Chunk storage is closed");
        long key = pos.longKey;
        PendingWrite pending;
        boolean schedule = false;
        while (true) {
            pending = this.pendingWrites.compute(key, (k, old) -> old == null || old.completed ? new PendingWrite(pos) : old);
            synchronized (pending) {
                if (pending.completed) {
                    // 与完成路径的摘除操作竞态，重试拿到一个全新的任务
                    continue;
                }
                pending.tag = tag;
                pending.failure = null;
                if (chunk != null) {
                    pending.chunk = chunk;
                }
                if (!pending.running) {
                    pending.running = true;
                    schedule = true;
                }
            }
            break;
        }
        if (schedule) {
            this.scheduleWrite(key, pending);
        }
    }

    private void scheduleWrite(long key, PendingWrite pending) throws IOException {
        try {
            this.executor.execute(() -> runWrite(key, pending));
        } catch (RuntimeException failure) {
            synchronized (pending) {
                pending.running = false;
                pending.failure = new IOException("Failed to schedule chunk write at " + pending.pos, failure);
                if (pending.chunk != null) pending.chunk.setUnsaved(true);
            }
            this.signalWrites();
            throw new IOException("Chunk IO executor rejected a write", failure);
        }
    }

    private void runWrite(long key, PendingWrite pending) {
        while (true) {
            CompoundTag tag;
            synchronized (pending) {
                tag = pending.tag;
            }
            try {
                if (tag == null) {
                    this.storage.clearChunkAt(pending.pos);
                } else {
                    this.storage.writeChunkDataAt(pending.pos, this.encoder.encode(tag));
                }
            } catch (Throwable failure) {
                synchronized (pending) {
                    if (pending.tag == tag) {
                        pending.failure = new IOException("Failed to save chunk at " + pending.pos, failure);
                    }
                    if (pending.chunk != null) pending.chunk.setUnsaved(true);
                }
                try {
                    this.errorHandler.accept(pending.pos, failure);
                } catch (Throwable loggingFailure) {
                    failure.addSuppressed(loggingFailure);
                }
            }
            synchronized (pending) {
                // A newer snapshot arrived while writing; persist it before completing this task.
                if (pending.tag != tag) continue;
                pending.running = false;
                if (pending.failure == null) {
                    pending.completed = true;
                    this.pendingWrites.remove(key, pending);
                }
            }
            this.signalWrites();
            return;
        }
    }

    private void signalWrites() {
        synchronized (this.flushLock) {
            this.flushLock.notifyAll();
        }
    }

    private void applyBackpressure() {
        if (this.pendingWrites.size() < MAX_PENDING_WRITES) return;
        long start = System.currentTimeMillis();
        boolean warned = false;
        while (this.pendingWrites.size() >= MAX_PENDING_WRITES) {
            // A failed write stays readable and retryable; waiting for its removal would deadlock.
            if (this.hasFailedWrite()) return;
            synchronized (this.flushLock) {
                try {
                    this.flushLock.wait(50L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (!warned && System.currentTimeMillis() - start > 10_000L) {
                warned = true;
                CraftEngine.instance().logger().warn("Chunk IO executor is falling behind, pending writes: " + this.pendingWrites.size());
            }
        }
    }

    private boolean hasFailedWrite() {
        for (var entry : this.pendingWrites.entrySet()) {
            synchronized (entry.getValue()) {
                if (entry.getValue().failure != null) return true;
            }
        }
        return false;
    }

    private void retryFailedWrites() throws IOException {
        for (var entry : this.pendingWrites.entrySet()) {
            PendingWrite pending = entry.getValue();
            synchronized (pending) {
                if (pending.running || pending.completed || pending.failure == null) continue;
                pending.failure = null;
                pending.running = true;
            }
            this.scheduleWrite(entry.getKey(), pending);
        }
    }

    private void awaitPendingWrites() throws IOException {
        while (!this.pendingWrites.isEmpty()) {
            IOException failure = null;
            boolean running = false;
            for (var entry : this.pendingWrites.entrySet()) {
                PendingWrite pending = entry.getValue();
                synchronized (pending) {
                    running |= pending.running;
                    if (pending.failure != null) {
                        if (failure == null) failure = new IOException("Chunk writes have not reached disk");
                        failure.addSuppressed(pending.failure);
                    }
                }
            }
            if (!running && failure != null) throw failure;
            synchronized (this.flushLock) {
                if (this.pendingWrites.isEmpty()) break;
                try {
                    this.flushLock.wait(100L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for chunk writes", e);
                }
            }
        }
    }

    @Override
    public void flush() throws IOException {
        this.lifecycle.readLock().lock();
        try {
            if (this.closed) return;
            this.retryFailedWrites();
            this.awaitPendingWrites();
            this.storage.flush();
        } finally {
            this.lifecycle.readLock().unlock();
        }
    }

    @Override
    public void close() throws IOException {
        this.lifecycle.writeLock().lock();
        try {
            if (this.closed) return;
            this.flush();
            this.storage.close();
            this.closed = true;
        } finally {
            this.lifecycle.writeLock().unlock();
        }
    }

    @FunctionalInterface
    interface ChunkEncoder {
        byte[] encode(CompoundTag tag) throws IOException;
    }

    private static final class PendingWrite {
        final ChunkPos pos;
        @Nullable CompoundTag tag;  // null 表示删除；仅在 synchronized (this) 下访问
        @Nullable CEChunk chunk;    // 用于写失败时回置脏标记
        boolean running;
        boolean completed;
        @Nullable IOException failure;

        PendingWrite(ChunkPos pos) {
            this.pos = pos;
        }
    }
}
