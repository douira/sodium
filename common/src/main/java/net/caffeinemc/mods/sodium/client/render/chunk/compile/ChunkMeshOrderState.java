package net.caffeinemc.mods.sodium.client.render.chunk.compile;

import it.unimi.dsi.fastutil.shorts.ShortArrayFIFOQueue;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.GraphDirection;
import net.caffeinemc.mods.sodium.client.util.collections.BitArray;

public class ChunkMeshOrderState {
    private int unvisitedCount = 16 * 16 * 16;
    private final BitArray visited = new BitArray(this.unvisitedCount);
    private final ShortArrayFIFOQueue queue = new ShortArrayFIFOQueue(16 * 16 * 3);
    private final ChunkBuildBuffers buffers;
    private int faceStartsIndex = 0;
    private int position = -1;
    private int x, y, z;

    public ChunkMeshOrderState(ChunkBuildBuffers buffers) {
        this.buffers = buffers;
    }

    public boolean findNextPosition() {
        // First flood fill runs until everything has been visited starting from the blocks on the faces of the section.
        // Then all remaining unvisited blocks are visited with the buffer set to local mode.

        while (this.queue.isEmpty()) {
            // fill queue with face starts if there are any left
            if (this.faceStartsIndex < GraphDirection.COUNT) {
                for (var index : FACE_INDEXES[this.faceStartsIndex++]) {
                    this.enqueueIfUnvisited(index);
                }
            } else if (this.unvisitedCount > 0) {
                // if this is the first time emitting an unvisited block, switch the buffer to local mode
                if (this.faceStartsIndex++ == GraphDirection.COUNT) {
                    this.buffers.activateLocalCategory();

                    // reset the position to iterate unvisited blocks from the start
                    this.position = -1;
                }

                // find the next unvisited block
                this.setPosition(this.visited.nextClearBit(this.position + 1));

                // update unvisited count since these blocks are not processed through the queue
                this.unvisitedCount--;
                return true;
            } else {
                // all blocks visited
                return false;
            }
        }

        // process a single block from the queue (was already marked as visited when it was enqueued)
        this.setPosition(this.queue.dequeueShort());
        return true;
    }

    private void enqueueIfUnvisited(int index) {
        if (!this.visited.getAndSet(index)) {
            this.queue.enqueue((short) index);
            this.unvisitedCount--;
        }
    }

    private void visitNeighbor(int dx, int dy, int dz) {
        var x = this.x + dx;
        var y = this.y + dy;
        var z = this.z + dz;

        // check that the neighbor coordinates are within bounds
        if ((x & 0b1111) == x && (y & 0b1111) == y && (z & 0b1111) == z) {
            this.enqueueIfUnvisited(LocalIndex.pack(x, y, z));
        }
    }

    public void processNonSolidBlock() {
        // visit all neighbors
        this.visitNeighbor(-1, 0, 0);
        this.visitNeighbor(1, 0, 0);
        this.visitNeighbor(0, -1, 0);
        this.visitNeighbor(0, 1, 0);
        this.visitNeighbor(0, 0, -1);
        this.visitNeighbor(0, 0, 1);
    }

    private void setPosition(int position) {
        this.position = position;
        this.x = LocalIndex.unpackX(position);
        this.y = LocalIndex.unpackY(position);
        this.z = LocalIndex.unpackZ(position);
    }

    public int getLocalX() {
        return this.x;
    }

    public int getLocalY() {
        return this.y;
    }

    public int getLocalZ() {
        return this.z;
    }

    public static class LocalIndex {
        private static int pack(int x, int y, int z) {
            return (y << 8) | (z << 4) | (x << 0);
        }

        private static int unpackX(int packed) {
            return (packed >>> 0) & 0xF;
        }

        private static int unpackY(int packed) {
            return (packed >>> 8) & 0xF;
        }

        private static int unpackZ(int packed) {
            return (packed >>> 4) & 0xF;
        }
    }

    private static final short[][] FACE_INDEXES = new short[GraphDirection.COUNT][16 * 16];

    static {
        int yz = 0;

        // X-axis
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                FACE_INDEXES[GraphDirection.WEST][yz] = (short) LocalIndex.pack(0, y, z);
                FACE_INDEXES[GraphDirection.EAST][yz] = (short) LocalIndex.pack(15, y, z);
                yz++;
            }
        }

        int xz = 0;

        // Y-axis
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                FACE_INDEXES[GraphDirection.DOWN][xz] = (short) LocalIndex.pack(x, 0, z);
                FACE_INDEXES[GraphDirection.UP][xz] = (short) LocalIndex.pack(x, 15, z);
                xz++;
            }
        }

        int xy = 0;

        // Z-axis
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                FACE_INDEXES[GraphDirection.NORTH][xy] = (short) LocalIndex.pack(x, y, 0);
                FACE_INDEXES[GraphDirection.SOUTH][xy] = (short) LocalIndex.pack(x, y, 15);
                xy++;
            }
        }
    }
}
