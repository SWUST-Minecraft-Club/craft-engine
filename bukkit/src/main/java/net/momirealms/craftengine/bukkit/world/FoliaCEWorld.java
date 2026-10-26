package net.momirealms.craftengine.bukkit.world;

import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import net.momirealms.craftengine.bukkit.util.ServerUtils;
import net.momirealms.craftengine.bukkit.world.chunk.FoliaCEChunk;
import net.momirealms.craftengine.core.util.TickersList;
import net.momirealms.craftengine.core.world.ChunkPos;
import net.momirealms.craftengine.core.world.World;
import net.momirealms.craftengine.core.world.chunk.CEChunk;
import net.momirealms.craftengine.core.world.chunk.storage.StorageAdaptor;
import net.momirealms.craftengine.core.world.chunk.storage.WorldDataStorage;
import org.bukkit.Bukkit;

import java.io.IOException;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class FoliaCEWorld extends BukkitCEWorld {
    private final Map<ChunkPos, TickingChunk> tickingChunkByPos = new ConcurrentHashMap<>(128, 0.5f);
    private final TickersList<TickingChunk> tickingChunks = new TickersList<>();
    private final Queue<TickingChunk> pendingAsyncTickingChunks = new ConcurrentLinkedQueue<>();

    public FoliaCEWorld(World world, StorageAdaptor adaptor) {
        super(world, adaptor);
    }

    public FoliaCEWorld(World world, WorldDataStorage dataStorage) {
        super(world, dataStorage);
    }

    @Override
    public void saveChunks() {
        // Shutdown runs after the region tick threads have stopped. During normal operation each
        // chunk's block entities must be serialized on its owning region, including /save-all.
        if (!ServerUtils.isRunning()) {
            super.saveChunks();
            return;
        }
        org.bukkit.World bukkitWorld = (org.bukkit.World) this.world.platformWorld();
        for (var entry : this.loadedChunkMap.entrySet()) {
            CEChunk chunk = entry.getValue();
            ChunkPos pos = chunk.chunkPos();
            Runnable save = () -> {
                if (this.getChunkAtIfLoaded(pos) != chunk || !chunk.isLoaded()) return;
                try {
                    chunk.save();
                } catch (IOException | RuntimeException failure) {
                    BukkitCraftEngine.instance().logger().warn("Failed to save chunk " + pos + " in " + this.name(), failure);
                }
            };
            if (Bukkit.isOwnedByCurrentRegion(bukkitWorld, pos.x, pos.z)) {
                save.run();
            } else {
                BukkitCraftEngine.instance().scheduler().platform().run(save, this.world, pos.x, pos.z);
            }
        }
    }

    public void syncTick() {
        this.updateLight();

        TickingChunk pending;
        while ((pending = this.pendingAsyncTickingChunks.poll()) != null) {
            this.tickingChunks.add(pending);
        }

        if (!this.tickingChunks.isEmpty()) {
            Object[] chunks = this.tickingChunks.elements();
            for (int i = 0, size = this.tickingChunks.size(); i < size; i++ ) {
                TickingChunk chunk = (TickingChunk) chunks[i];
                if (chunk.isValid()) {
                    ChunkPos chunkPos = chunk.chunkPos();
                    BukkitCraftEngine.instance().scheduler().platform().run(chunk::tick, this.world, chunkPos.x, chunkPos.z);
                } else {
                    this.tickingChunks.markAsRemoved(i);
                    this.tickingChunkByPos.remove(chunk.chunkPos(), chunk);
                }
            }
            this.tickingChunks.removeMarkedEntries();
        }
    }

    public void replaceOrCreateTickingChunk(FoliaCEChunk chunk) {
        this.tickingChunkByPos.compute(chunk.chunkPos, (pos, tickingChunk) -> {
            if (tickingChunk != null && tickingChunk.isValid()) {
                return tickingChunk;
            }
            TickingChunk newTickingChunk = new TickingChunk(chunk);
            this.pendingAsyncTickingChunks.add(newTickingChunk);
            return newTickingChunk;
        });
    }

    @Override
    public void removeLoadedChunk(CEChunk chunk) {
        super.removeLoadedChunk(chunk);
    }

    public static class TickingChunk {
        private final FoliaCEChunk chunk;

        public TickingChunk(FoliaCEChunk chunk) {
            this.chunk = chunk;
        }

        public void tick() {
            if (this.isValid()) this.chunk.tickBlockEntities();
        }

        public boolean isValid() {
            return this.chunk.isLoaded();
        }

        public ChunkPos chunkPos() {
            return this.chunk.chunkPos();
        }
    }
}
