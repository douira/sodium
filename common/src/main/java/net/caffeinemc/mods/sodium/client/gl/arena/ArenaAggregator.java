package net.caffeinemc.mods.sodium.client.gl.arena;

import net.caffeinemc.mods.sodium.client.gl.arena.staging.StagingBuffer;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlBufferUsage;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlMutableBuffer;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkMeshFormats;

import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

// TODO: if the required capacity is huge, maybe it shouldn't be shared, or we should overshoot it more?
// TODO: when moving region to a new buffer, return the next shared arena, or decide that the request is too big and return a regular single-owner
// TODO: allow user to change the vram pre-allocation size via config, but auto-scale regardless if it's too small
// TODO: incremental defragmentation
// TODO: compaction when multiple shared arenas together have less than 70% of a single one used
// TODO: deallocate shared arenas when they become empty, don't re-use more than some limited amount of memory when deallocated shared arena becomes freed
public class ArenaAggregator {
    // how much bigger than requested a buffer can be to be considered for reuse
    public static final float MAX_BUFFER_REUSE_SIZE_FACTOR = 1.4f;
    private static final long SHARED_GEOMETRY_SIZE = 256 * 1024L * 1024L;
    private static final long SHARED_INDEX_SIZE = 32 * 1024L * 1024L;

    private static final GlBufferUsage BUFFER_USAGE = GlBufferUsage.STATIC_DRAW;

    final StagingBuffer stagingBuffer;
    private final GlMutableBuffer[] freeBuffers = new GlMutableBuffer[8];
    private static int freeBufferCount = 0;

    // all shared arenas, keyed by stride, and then sorted by the biggest contiguous free block size they have to offer
    private final DataType index = new DataType(Integer.BYTES, SHARED_INDEX_SIZE);
    private final DataType geometry = new DataType(ChunkMeshFormats.COMPACT.getVertexFormat().getStride(), SHARED_GEOMETRY_SIZE);
    private final Collection<DataType> dataTypes = List.of(this.index, this.geometry);

    private class DataType {
        final int stride;
        final long sharedSizeBytes;
        final long sharedSize;
        final SizedTreeMap<SharedGlBufferArena> arenas;

        DataType(int stride, long sharedSizeBytes) {
            this.stride = stride;
            this.sharedSizeBytes = sharedSizeBytes;
            this.sharedSize = sharedSizeBytes / stride;
            this.arenas = new SizedTreeMap<>();
        }

        SharedGlBufferArena createSharedArena(CommandList commands, long requiredCapacity) {
            GlMutableBuffer buffer = ArenaAggregator.this.getBufferOfSizeAtLeast(commands, requiredCapacity * this.stride);
            long actualCapacity = buffer.getSize() / this.stride;
            return new SharedGlBufferArena(ArenaAggregator.this, buffer, actualCapacity, this.stride);
        }

        SharedGlBufferArena ensureSharedArena(CommandList commands, long requiredCapacity) {
            var arena = this.arenas.getHighestFitting(requiredCapacity);
            if (arena == null || arena.getBiggestFreeSegmentSize() < requiredCapacity) {
                arena = createSharedArena(commands, Math.max(requiredCapacity, this.sharedSize));
                this.arenas.addSized(arena);
            }
            return arena;
        }

        long getDeviceUsedMemory() {
            long used = 0;
            for (var arenaEntry : this.arenas.values()) {
                used += arenaEntry.getDeviceUsedMemory();
            }
            return used;
        }

        long getDeviceAllocatedMemory() {
            long allocated = 0;
            for (var arenaEntry : this.arenas.values()) {
                allocated += arenaEntry.getDeviceAllocatedMemory();
            }
            return allocated;
        }
    }

    public ArenaAggregator(StagingBuffer stagingBuffer) {
        this.stagingBuffer = stagingBuffer;
    }

    public RegionAllocatorHandle getGeometryBufferAllocator(CommandList commands, RenderRegion region, int stride, Consumer<CommandList> onBufferChange) {
        return createAllocator(commands, region, stride, onBufferChange);
    }

    public RegionAllocatorHandle getIndexBufferAllocator(CommandList commands, RenderRegion region, int stride, Consumer<CommandList> onBufferChange) {
        return createAllocator(commands, region, stride, onBufferChange);
    }

    private RegionAllocatorHandle createAllocator(CommandList commands, RenderRegion region, int stride, Consumer<CommandList> onBufferChange) {
        GlBufferArena backingArena = getArenaFittingFor(commands, 0, stride);
        return new RegionAllocatorHandle(region, onBufferChange, backingArena);
    }

    private DataType getDataTypeForStride(int stride) {
        if (stride == this.index.stride) {
            return this.index;
        } else if (stride == this.geometry.stride) {
            return this.geometry;
        } else {
            throw new IllegalArgumentException("Unsupported stride: " + stride);
        }
    }

    GlBufferArena getArenaFittingFor(CommandList commands, long requiredCapacity, int stride) {
        return getDataTypeForStride(stride).ensureSharedArena(commands, requiredCapacity);
    }

    GlBufferArena createDedicatedArena(CommandList commands, long requiredCapacity, int stride) {
        GlMutableBuffer buffer = getBufferOfSizeAtLeast(commands, requiredCapacity * stride);
        long actualCapacity = buffer.getSize() / stride;
        return new GlBufferArena(this, buffer, actualCapacity, stride);
    }

    GlMutableBuffer getBufferOfSizeAtLeast(CommandList commands, long bytes) {
        GlMutableBuffer buffer = null;

        if (freeBufferCount > 0) {
            // get any buffer of at least the requested size but at most MAX_BUFFER_REUSE_SIZE_FACTOR larger
            long maxAcceptableSize = (long) (bytes * MAX_BUFFER_REUSE_SIZE_FACTOR);

            // iterate buffers to get the smallest acceptable one
            int candidateIndex = -1;
            for (int i = 0; i < this.freeBuffers.length; i++) {
                GlMutableBuffer freeBuffer = this.freeBuffers[i];
                if (freeBuffer != null) {
                    long testSize = freeBuffer.getSize();
                    if (testSize >= bytes && testSize <= maxAcceptableSize &&
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
            buffer = commands.createMutableBuffer();
            commands.allocateStorage(buffer, bytes, BUFFER_USAGE);
        }
        return buffer;
    }

    void releaseBufferForReuse(CommandList commands, GlMutableBuffer buffer) {
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
        commands.deleteBuffer(this.freeBuffers[evictIndex]);
        this.freeBuffers[evictIndex] = buffer;
    }

    public void delete(CommandList commands) {
        for (int i = 0; i < this.freeBuffers.length; i++) {
            GlMutableBuffer buffer = this.freeBuffers[i];
            if (buffer != null) {
                commands.deleteBuffer(buffer);
                this.freeBuffers[i] = null;
            }
        }
        freeBufferCount = 0;

        for (var dataType : this.dataTypes) {
            for (var arenaEntry : dataType.arenas.values()) {
                arenaEntry.deleteShared(commands);
            }
            dataType.arenas.clear();
        }
    }

    public long getGeometryDeviceUsedMemory() {
        return this.geometry.getDeviceUsedMemory();
    }

    public long getIndexDeviceUsedMemory() {
        return this.index.getDeviceUsedMemory();
    }

    public long getGeometryDeviceAllocatedMemory() {
        return this.geometry.getDeviceAllocatedMemory();
    }

    public long getIndexDeviceAllocatedMemory() {
        return this.index.getDeviceAllocatedMemory();
    }

    public long getMiscAllocatedMemory() {
        long allocated = 0;
        for (GlMutableBuffer buffer : this.freeBuffers) {
            if (buffer != null) {
                allocated += buffer.getSize();
            }
        }
        return allocated;
    }

    public int getBufferCount() {
        int count = freeBufferCount;
        for (var dataType : this.dataTypes) {
            count += dataType.arenas.size();
        }
        return count;
    }
}
