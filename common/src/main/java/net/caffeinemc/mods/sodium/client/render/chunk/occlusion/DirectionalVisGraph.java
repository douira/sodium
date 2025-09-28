package net.caffeinemc.mods.sodium.client.render.chunk.occlusion;

import net.caffeinemc.mods.sodium.client.util.collections.BitArray;
import net.minecraft.client.renderer.chunk.VisibilitySet;

public class DirectionalVisGraph implements VisibilityGraph {
    private static final int DX = 1;
    private static final int DZ = 16;
    private static final int DY = 16 * 16;
    private static final int SIZE = 16 * 16 * 16;

    private final BitArray blocks = new BitArray(SIZE);
    private int filled = 0;

    private static int getIndex(int x, int y, int z) {
        return x | (z << 4) | (y << 8);
    }

    private static int getX(int index) {
        return index & 15;
    }

    private static int getY(int index) {
        return (index >> 8) & 15;
    }

    private static int getZ(int index) {
        return (index >> 4) & 15;
    }

    @Override
    public void setOpaque(int x, int y, int z) {
        this.blocks.set(getIndex(x, y, z));
        this.filled++;
    }

    @Override
    public VisibilitySet resolve() {
        var visibilitySet = new VisibilitySet();

        // if all blocks are filled, nothing is visible
        if (this.filled == SIZE) {
            visibilitySet.setAll(false);
            return visibilitySet;
        }

        // if fewer blocks are filled than necessary to block visibility between two faces, all faces are visible to each other
        if (this.filled < 256) {
            visibilitySet.setAll(true);
            return visibilitySet;
        }

        // DFS from each face, and going backwards in the direction of the origin face is not permitted
        // TODO: technically it can't actually get SIZE long. The real max length is the maximum path length through the cube without touching adjacent blocks (under certain direction ordering restrictions)
        var stackPos = new short[SIZE];
        var stackDirs = new byte[SIZE];
        for (int originDirection = 0; originDirection < GraphDirection.COUNT; originDirection++) {
            var minX = 0;
            var minY = 0;
            var minZ = 0;
            var maxX = 15;
            var maxY = 15;
            var maxZ = 15;

            switch (originDirection) {
                case GraphDirection.DOWN -> maxY = 0;
                case GraphDirection.UP -> minY = 15;
                case GraphDirection.NORTH -> maxZ = 0;
                case GraphDirection.SOUTH -> minZ = 15;
                case GraphDirection.WEST -> maxX = 0;
                case GraphDirection.EAST -> minX = 15;
            }

            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    for (int x = minX; x <= maxX; x++) {
                        int index = getIndex(x, y, z);
                        if (!this.blocks.get(index)) {
                            search(visibilitySet, stackPos, stackDirs, originDirection, x, y, z);
                        }
                    }
                }
            }
        }
        
        return visibilitySet;
    }

    private void search(VisibilitySet visibilitySet, short[] stackPos, byte[] stackDirs, int originFace, int startX, int startY, int startZ) {
        var visited = this.blocks.copy();

        int stackSize = 0;
        int originIndex = getIndex(startX, startY, startZ);
        stackPos[stackSize++] = (short) originIndex;
        stackDirs[0] = -1;
        visited.set(originIndex);

        int connectedFaces = GraphDirectionSet.of(originFace);

        while (stackSize > 0) {
            int stackIndex = stackSize - 1;

            int currentIndex = stackPos[stackIndex];
            int currentX = getX(currentIndex);
            int currentY = getY(currentIndex);
            int currentZ = getZ(currentIndex);
            int completedDir = stackDirs[stackIndex];

            int nextDir = completedDir + 1;

            // backtrack when all directions have been tried
            if (nextDir == GraphDirection.COUNT) {
                stackSize--;
                continue;
            }

            stackDirs[stackIndex] = (byte) nextDir;

            // skip going back towards the origin face
            if (nextDir == originFace) {
                // fast path backtracking when the last direction is skipped
                if (nextDir + 1 == GraphDirection.COUNT) {
                    stackSize--;
                }
                continue;
            }

            int neighborX = currentX + GraphDirection.x(nextDir);
            int neighborY = currentY + GraphDirection.y(nextDir);
            int neighborZ = currentZ + GraphDirection.z(nextDir);

            // check reaching the edge of the chunk
            if (neighborX < 0 || neighborX >= 16 || neighborY < 0 || neighborY >= 16 || neighborZ < 0 || neighborZ >= 16) {
                // reached the edge, mark visibility between origin face and this face
                connectedFaces |= GraphDirectionSet.of(nextDir);

                // stop searching if all faces are connected
                if (connectedFaces == GraphDirectionSet.ALL) {
                    break;
                }

                continue;
            }

            int neighborIndex = getIndex(neighborX, neighborY, neighborZ);
            if (visited.getAndSet(neighborIndex)) {
                // already visited or opaque
                continue;
            }

            // visit the neighbor
            stackPos[stackSize] = (short) neighborIndex;
            stackDirs[stackSize] = -1;
            stackSize++;
        }

        for (int direction = 0; direction < GraphDirection.COUNT; direction++) {
            if (GraphDirectionSet.contains(connectedFaces, direction)) {
                visibilitySet.set(GraphDirection.toEnum(originFace), GraphDirection.toEnum(direction), true);
            }
        }
    }
}
