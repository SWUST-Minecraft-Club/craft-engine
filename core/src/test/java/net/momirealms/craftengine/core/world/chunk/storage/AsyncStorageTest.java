package net.momirealms.craftengine.core.world.chunk.storage;

import net.momirealms.craftengine.core.world.*;
import net.momirealms.craftengine.core.world.chunk.CEChunk;
import net.momirealms.craftengine.core.world.chunk.Chunk;
import net.momirealms.sparrow.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.*;

class AsyncStorageTest {
    private static final ChunkPos POS = new ChunkPos(32, -19);

    @Test
    void failedWriteRemainsReadableAndFlushRetriesIt() throws Exception {
        FakeStorage disk = new FakeStorage();
        disk.failures = 1;
        AsyncStorage storage = storage(disk);
        storage.writeChunkTagAt(POS, tag(17));
        assertEquals(17, storage.readChunkTagAt(POS).getInt("value"));
        assertNull(disk.readChunkTagAt(POS));
        storage.flush();
        assertEquals(17, disk.readChunkTagAt(POS).getInt("value"));
        assertEquals(2, disk.writes);
    }

    @Test
    void persistentFailureCannotReportSuccessfulCloseAndCanBeRetried() throws Exception {
        FakeStorage disk = new FakeStorage();
        disk.failures = 10;
        AsyncStorage storage = storage(disk);
        storage.writeChunkTagAt(POS, tag(12));
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertThrows(IOException.class, storage::close));
        assertFalse(disk.closed);
        assertEquals(12, storage.readChunkTagAt(POS).getInt("value"));
        disk.failures = 0;
        storage.close();
        assertTrue(disk.closed);
        assertEquals(12, disk.readChunkTagAt(POS).getInt("value"));
        assertThrows(IOException.class, () -> storage.writeChunkTagAt(POS, tag(19)));
    }

    @Test
    void latestSnapshotWinsAndPendingDataIsDefensivelyCopied() throws Exception {
        FakeStorage disk = new FakeStorage();
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        AsyncStorage storage = new AsyncStorage(disk, AsyncStorageTest::encode, queue::add, (pos, failure) -> {});
        CompoundTag first = tag(1);
        storage.writeChunkTagAt(POS, first);
        first.putInt("value", 100);
        assertEquals(1, storage.readChunkTagAt(POS).getInt("value"));
        storage.readChunkTagAt(POS).putInt("value", 99);
        assertEquals(1, storage.readChunkTagAt(POS).getInt("value"));
        storage.writeChunkTagAt(POS, tag(2));
        assertEquals(1, queue.size());
        queue.remove().run();
        storage.close();
        assertEquals(2, disk.readChunkTagAt(POS).getInt("value"));
        assertEquals(1, disk.writes);
    }

    @Test
    void snapshotArrivingDuringWriteIsPersistedBeforeCompletion() throws Exception {
        FakeStorage disk = new FakeStorage();
        AsyncStorage[] reference = new AsyncStorage[1];
        reference[0] = new AsyncStorage(disk, data -> {
            if (data.getInt("value") == 1) reference[0].writeChunkTagAt(POS, tag(2));
            return encode(data);
        }, Runnable::run, (pos, failure) -> fail(failure));
        reference[0].writeChunkTagAt(POS, tag(1));
        reference[0].close();
        assertEquals(2, disk.writes);
        assertEquals(2, disk.readChunkTagAt(POS).getInt("value"));
    }

    @Test
    void failedDeletionDoesNotResurrectOldDataOnReload() throws Exception {
        FakeStorage disk = new FakeStorage();
        disk.data.put(POS.longKey, tag(9));
        disk.failures = 1;
        AsyncStorage storage = storage(disk);
        storage.clearChunkAt(POS);
        assertNull(storage.readChunkTagAt(POS));
        storage.close();
        assertNull(disk.readChunkTagAt(POS));
    }

    @Test
    void executorRejectionIsReportedAndDoesNotHangFlush() throws Exception {
        FakeStorage disk = new FakeStorage();
        AsyncStorage storage = new AsyncStorage(disk, AsyncStorageTest::encode,
                task -> { throw new RejectedExecutionException("test"); }, (pos, failure) -> {});
        assertThrows(IOException.class, () -> storage.writeChunkTagAt(POS, tag(7)));
        assertEquals(7, storage.readChunkTagAt(POS).getInt("value"));
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertThrows(IOException.class, storage::flush));
        assertFalse(disk.closed);
    }

    @Test
    void interruptionDoesNotCloseStorageUnderAnUnfinishedWrite() throws Exception {
        FakeStorage disk = new FakeStorage();
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        AsyncStorage storage = new AsyncStorage(disk, AsyncStorageTest::encode, queue::add, (pos, failure) -> {});
        storage.writeChunkTagAt(POS, tag(7));
        Thread.currentThread().interrupt();
        try {
            assertThrows(IOException.class, storage::close);
            assertTrue(Thread.currentThread().isInterrupted());
            assertFalse(disk.closed);
        } finally {
            Thread.interrupted();
        }
        queue.remove().run();
        storage.close();
        assertTrue(disk.closed);
    }

    @Test
    void failedWritesDoNotDeadlockBackpressure() {
        FakeStorage disk = new FakeStorage();
        disk.failures = 1000;
        AsyncStorage storage = storage(disk);
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            for (int x = 0; x < 300; x++) storage.writeChunkTagAt(new ChunkPos(x, 32), tag(7));
        });
    }

    private static AsyncStorage storage(FakeStorage disk) {
        return new AsyncStorage(disk, AsyncStorageTest::encode, Runnable::run, (pos, failure) -> {});
    }

    private static byte[] encode(CompoundTag tag) {
        return new byte[]{(byte) tag.getInt("value")};
    }

    private static CompoundTag tag(int value) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("value", value);
        return tag;
    }

    private static final class FakeStorage implements RegionStorage {
        final Map<Long, CompoundTag> data = new HashMap<>();
        int failures;
        int writes;
        boolean closed;

        private void writeAttempt() throws IOException {
            writes++;
            if (failures-- > 0) throw new IOException("Disk unavailable");
        }

        @Override public void writeChunkDataAt(ChunkPos pos, byte[] bytes) throws IOException {
            writeAttempt();
            data.put(pos.longKey, tag(bytes[0]));
        }
        @Override public void clearChunkAt(ChunkPos pos) throws IOException { writeAttempt(); data.remove(pos.longKey); }
        @Override public CompoundTag readChunkTagAt(ChunkPos pos) { return data.get(pos.longKey); }
        @Override public void flush() {}
        @Override public void close() { closed = true; }
        @Override public ChunkFactory chunkFactory() { throw new UnsupportedOperationException(); }
        @Override public Path folder() { throw new UnsupportedOperationException(); }
        @Override public CEChunk readChunkAt(CEWorld world, ChunkPos pos, Chunk access) { throw new UnsupportedOperationException(); }
        @Override public void writeChunkAt(ChunkPos pos, CEChunk chunk) { throw new UnsupportedOperationException(); }
        @Override public void writeChunkTagAt(ChunkPos pos, CompoundTag tag) { throw new UnsupportedOperationException(); }
    }
}
