package net.caffeinemc.mods.sodium.client.gl.arena;

import it.unimi.dsi.fastutil.ints.Int2ReferenceArrayMap;
import net.caffeinemc.mods.sodium.client.gl.arena.staging.StagingBuffer;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlBufferUsage;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlMutableBuffer;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkMeshFormats;

import java.util.function.Consumer;

public class ArenaAggregator {
    // how much bigger than requested a buffer can be to be considered for reuse
    public static final float MAX_BUFFER_REUSE_SIZE_FACTOR = 1.4f;
    private static final long SHARED_GEOMETRY_SIZE = 500 * 1024L * 1024L;
    private static final long SHARED_INDEX_SIZE = 50 * 1024L * 1024L;

    private static final GlBufferUsage BUFFER_USAGE = GlBufferUsage.STATIC_DRAW;

    final StagingBuffer stagingBuffer;
    private final GlMutableBuffer[] freeBuffers = new GlMutableBuffer[8];
    private static int freeBufferCount = 0;

    private final Int2ReferenceArrayMap<SharedGlBufferArena> sharedArenas = new Int2ReferenceArrayMap<>();

    public ArenaAggregator(StagingBuffer stagingBuffer) {
        this.stagingBuffer = stagingBuffer;
    }

    public RegionOwnedAllocator createOwnedGeometryAllocator(CommandList commands, RenderRegion region, int stride, Consumer<CommandList> onBufferChange) {
        return createOwnedAllocator(commands, region, (int) SHARED_GEOMETRY_SIZE, stride, onBufferChange);
    }

    public RegionOwnedAllocator createOwnedIndexAllocator(CommandList commands, RenderRegion region, int stride, Consumer<CommandList> onBufferChange) {
        return createOwnedAllocator(commands, region, (int) SHARED_INDEX_SIZE, stride, onBufferChange);
    }

    private RegionOwnedAllocator createOwnedAllocator(CommandList commands, RenderRegion region, int bytes, int stride, Consumer<CommandList> onBufferChange) {
        // find or create shared arena for this stride
        SharedGlBufferArena sharedArena = this.sharedArenas.get(stride);
        if (sharedArena == null) {
            sharedArena = createSharedArenaOfSizeAtLeast(commands, bytes, stride);
            this.sharedArenas.put(stride, sharedArena);
        }
        return new RegionOwnedAllocator(region, onBufferChange, sharedArena);
    }

    GlBufferArena createArenaOfSizeAtLeast(CommandList commandList, long bytes, int stride) {
        GlMutableBuffer buffer = getBufferOfSizeAtLeast(commandList, bytes);
        long capacity = buffer.getSize() / stride;
        return new GlBufferArena(this, buffer, capacity, stride);
    }

    SharedGlBufferArena createSharedArenaOfSizeAtLeast(CommandList commandList, long bytes, int stride) {
        GlMutableBuffer buffer = getBufferOfSizeAtLeast(commandList, bytes);
        long capacity = buffer.getSize() / stride;
        return new SharedGlBufferArena(this, buffer, capacity, stride);
    }

    GlMutableBuffer getBufferOfSizeAtLeast(CommandList commandList, long size) {
        GlMutableBuffer buffer = null;

        if (freeBufferCount > 0) {
            // get any buffer of at least the requested size but at most MAX_BUFFER_REUSE_SIZE_FACTOR larger
            long maxAcceptableSize = (long) (size * MAX_BUFFER_REUSE_SIZE_FACTOR);

            // iterate buffers to get the smallest acceptable one
            int candidateIndex = -1;
            for (int i = 0; i < this.freeBuffers.length; i++) {
                GlMutableBuffer freeBuffer = this.freeBuffers[i];
                if (freeBuffer != null) {
                    long testSize = freeBuffer.getSize();
                    if (testSize >= size && testSize <= maxAcceptableSize &&
                            (buffer == null || testSize < buffer.getSize())) {
                        candidateIndex = i;
                        buffer = freeBuffer;
                    }
                }
            }
            if (buffer != null) {
                this.freeBuffers[candidateIndex] = null;
                freeBufferCount--;
            }
        }

        if (buffer == null) {
            buffer = commandList.createMutableBuffer();
            commandList.allocateStorage(buffer, size, BUFFER_USAGE);
        }
        return buffer;
    }

    void releaseBufferForReuse(CommandList commandList, GlMutableBuffer buffer) {
        // find an empty slot if there is one
        if (freeBufferCount < this.freeBuffers.length) {
            for (int i = 0; i < this.freeBuffers.length; i++) {
                if (this.freeBuffers[i] == null) {
                    this.freeBuffers[i] = buffer;
                    freeBufferCount++;
                    return;
                }
            }
        }

        // evict randomly if no empty slot available
        int evictIndex = (int) (Math.random() * this.freeBuffers.length);
        commandList.deleteBuffer(this.freeBuffers[evictIndex]);
        this.freeBuffers[evictIndex] = buffer;
    }

    public void delete(CommandList commandList) {
        for (int i = 0; i < this.freeBuffers.length; i++) {
            GlMutableBuffer buffer = this.freeBuffers[i];
            if (buffer != null) {
                commandList.deleteBuffer(buffer);
                this.freeBuffers[i] = null;
            }
        }
        freeBufferCount = 0;

        for (SharedGlBufferArena arena : this.sharedArenas.values()) {
            arena.deleteShared(commandList);
        }
        this.sharedArenas.clear();
    }

    public long getGeometryDeviceUsedMemory() {
        var arena = this.sharedArenas.get(ChunkMeshFormats.COMPACT.getVertexFormat().getStride());
        return arena == null ? 0 : arena.getDeviceUsedMemory();
    }

    public long getIndexDeviceUsedMemory() {
        var arena = this.sharedArenas.get(Integer.BYTES);
        return arena == null ? 0 : arena.getDeviceUsedMemory();
    }

    public long getGeometryDeviceAllocatedMemory() {
        long allocated = 0;
        var arena = this.sharedArenas.get(ChunkMeshFormats.COMPACT.getVertexFormat().getStride());
        if (arena != null) {
            allocated += arena.getDeviceAllocatedMemory();
        }

        for (GlMutableBuffer buffer : this.freeBuffers) {
            if (buffer != null) {
                allocated += buffer.getSize();
            }
        }
        return allocated;
    }

    public long getIndexDeviceAllocatedMemory() {
        var arena = this.sharedArenas.get(Integer.BYTES);
        return arena == null ? 0 : arena.getDeviceAllocatedMemory();
    }

    public int getBufferCount() {
        return this.sharedArenas.size() + freeBufferCount;
    }
}
