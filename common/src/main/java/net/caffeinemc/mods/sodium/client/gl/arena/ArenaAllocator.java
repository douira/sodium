package net.caffeinemc.mods.sodium.client.gl.arena;

import net.caffeinemc.mods.sodium.client.gl.arena.staging.StagingBuffer;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlBufferUsage;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlMutableBuffer;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;

public class ArenaAllocator {
    // how much bigger than requested a buffer can be to be considered for reuse
    public static final float MAX_BUFFER_REUSE_SIZE_FACTOR = 1.4f;

    private static final GlBufferUsage BUFFER_USAGE = GlBufferUsage.STATIC_DRAW;

    final StagingBuffer stagingBuffer;
    private final GlMutableBuffer[] freeBuffers = new GlMutableBuffer[8];
    private static int freeBufferCount = 0;

    public ArenaAllocator(StagingBuffer stagingBuffer) {
        this.stagingBuffer = stagingBuffer;
    }

    public GlBufferArena createArena(CommandList commands, RenderRegion user, int requestedCapacity, int stride) {
        GlMutableBuffer initialBuffer = getBufferOfSizeAtLeast(commands, (long) requestedCapacity * stride);
        long initialCapacity = initialBuffer.getSize() / stride;
        return new GlBufferArena(this, initialBuffer, initialCapacity, stride);
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
    }
}
