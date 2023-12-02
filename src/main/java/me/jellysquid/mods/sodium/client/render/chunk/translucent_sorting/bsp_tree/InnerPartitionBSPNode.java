package me.jellysquid.mods.sodium.client.render.chunk.translucent_sorting.bsp_tree;

import java.util.Arrays;

import org.joml.Vector3fc;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import me.jellysquid.mods.sodium.client.model.quad.properties.ModelQuadFacing;

/**
 * TODO:
 * - make partition finding more sophisticated by using distancesByNormal
 * and other information.
 * - pass partition information to child nodes instead of discarding if
 * partition didn't work
 * - detect many coplanar quads and avoid partitioning sorting alltogether. Try
 * to partition so that large groups of coplanar quads are kept together and
 * then not sorted at all.
 * - sort the quads inside a node using the same heuristics as in the global
 * level: convex hulls (like cuboids where the faces point outwards) don't need
 * to be sorted. This could be used to optimize the inside of honey blocks, and
 * other blocks.
 * 
 * Implementation note:
 * - Presorting the points in block-sized buckets doesn't help. It seems the
 * sort algorithm is just fast enough to handle this.
 * - Eliminating the use of partition objects doesn't help. Since there's
 * usually just very few partitions, it's not worth it it seems.
 * - Using fastutil's LongArrays sorting options (radix and quicksort) is slower
 * than using Arrays.sort (which uses DualPivotQuicksort internally), even on
 * worlds with player-built structures.
 * - A simple attempt at lazily writing index data to the buffer didn't yield a
 * performance improvement. Maybe applying it to the multi partition node would
 * be more effective (but also much more complex and slower).
 * 
 * The encoding doesn't currently support negative distances (nor does such
 * support appear to be required). Their ordering is wrong when sorting them by
 * their binary representation. To fix this: "XOR all positive numbers with
 * 0x8000... and negative numbers with 0xffff... This should flip the sign bit
 * on both (so negative numbers go first), and then reverse the ordering on
 * negative numbers." from https://stackoverflow.com/q/43299299
 */
abstract class InnerPartitionBSPNode extends BSPNode {
    final Vector3fc planeNormal;

    InnerPartitionBSPNode(Vector3fc planeNormal) {
        this.planeNormal = planeNormal;
    }

    private static long encodeIntervalPoint(float distance, int quadIndex, int type) {
        return ((long) Float.floatToRawIntBits(distance) << 32) | ((long) type << 30) | quadIndex;
    }

    private static float decodeIntervalPointDistance(long encoded) {
        return Float.intBitsToFloat((int) (encoded >>> 32));
    }

    private static int decodeIntervalPointQuadIndex(long encoded) {
        return (int) (encoded & 0x3FFFFFFF);
    }

    private static int decodeIntervalPointType(long encoded) {
        return (int) (encoded >>> 30) & 0b11;
    }

    public static void validateQuadCount(int quadCount) {
        if (quadCount * 2 > 0x3FFFFFFF) {
            throw new IllegalArgumentException("Too many quads: " + quadCount);
        }
    }

    // the indices of the type are chosen such that tie-breaking items that have the
    // same distance with the type ascending yields a beneficial sort order
    // (END of the current interval, on-edge quads, then the START of the next
    // interval)

    // the start of a quad's extent in this direction
    private static final int INTERVAL_START = 2;

    // end end of a quad's extent in this direction
    private static final int INTERVAL_END = 0;

    // looking at a quad from the side where it has zero thickness
    private static final int INTERVAL_SIDE = 1;

    /**
     * models a partition of the space into a set of quads that lie inside or on the
     * plane with the specified distance. If the distance is -1 this is the "end"
     * partition after the last partition plane.
     */
    private static record Partition(float distance, IntArrayList quadsBefore, IntArrayList quadsOn) {
    }

    static BSPNode build(BSPWorkspace workspace) {
        var indexes = workspace.indexes;

        ReferenceArrayList<Partition> partitions = new ReferenceArrayList<>();
        LongArrayList points = new LongArrayList((int) (indexes.size() * 1.5));

        // find any aligned partition, search each axis
        var depth = workspace.depth;
        for (int axisCount = 0; axisCount < 3; axisCount++) {
            int axis = (axisCount + depth) % 3;
            var facing = ModelQuadFacing.VALUES[axis];
            var oppositeFacing = facing.getOpposite();
            var oppositeDirection = oppositeFacing.ordinal();

            // collect all the geometry's start and end points in this direction
            points.clear();
            for (int quadIndex : indexes) {
                var quad = workspace.quads[quadIndex];
                var extents = quad.extents();
                var posExtent = extents[axis];
                var negExtent = extents[oppositeDirection];
                if (posExtent == negExtent) {
                    points.add(encodeIntervalPoint(posExtent, quadIndex, INTERVAL_SIDE));
                } else {
                    points.add(encodeIntervalPoint(posExtent, quadIndex, INTERVAL_END));
                    points.add(encodeIntervalPoint(negExtent, quadIndex, INTERVAL_START));
                }
            }

            // sort interval points by distance ascending and then by type. Sorting the
            // longs directly has the same effect because of the encoding.
            Arrays.sort(points.elements(), 0, points.size());

            // find gaps
            partitions.clear();
            float distance = -1;
            IntArrayList quadsBefore = null;
            IntArrayList quadsOn = null;
            int thickness = 0;
            for (long point : points) {
                switch (decodeIntervalPointType(point)) {
                    case INTERVAL_START -> {
                        // unless at the start, flush if there's a gap
                        if (thickness == 0 && (quadsBefore != null || quadsOn != null)) {
                            partitions.add(new Partition(distance, quadsBefore, quadsOn));
                            distance = -1;
                            quadsBefore = null;
                            quadsOn = null;
                        }

                        thickness++;

                        // flush to partition if still writing last partition
                        if (quadsOn != null) {
                            if (distance == -1) {
                                throw new IllegalStateException("distance not set");
                            }
                            partitions.add(new Partition(distance, quadsBefore, quadsOn));
                            distance = -1;
                            quadsOn = null;
                            quadsOn = null;
                        }
                        if (quadsBefore == null) {
                            quadsBefore = new IntArrayList();
                        }
                        quadsBefore.add(decodeIntervalPointQuadIndex(point));
                    }
                    case INTERVAL_END -> {
                        thickness--;
                        if (quadsOn == null) {
                            distance = decodeIntervalPointDistance(point);
                        }
                    }
                    case INTERVAL_SIDE -> {
                        // if this point in a gap, it can be put on the plane itself
                        int pointQuadIndex = decodeIntervalPointQuadIndex(point);
                        if (thickness == 0) {
                            float pointDistance = decodeIntervalPointDistance(point);
                            if (quadsOn == null) {
                                // no partition end created yet, set here
                                quadsOn = new IntArrayList();
                                distance = pointDistance;
                            } else if (distance != pointDistance) {
                                // partition end has passed already, flush for new partition plane distance
                                partitions.add(new Partition(distance, quadsBefore, quadsOn));
                                distance = pointDistance;
                                quadsBefore = null;
                                quadsOn = new IntArrayList();
                            }
                            quadsOn.add(pointQuadIndex);
                        } else {
                            if (quadsBefore == null) {
                                throw new IllegalStateException("there must be started intervals here");
                            }
                            quadsBefore.add(pointQuadIndex);
                            if (quadsOn == null) {
                                distance = decodeIntervalPointDistance(point);
                            }
                        }
                    }
                }
            }

            // check a different axis if everything is in one quadsBefore,
            // which means there are no gaps
            if (quadsBefore != null && quadsBefore.size() == indexes.size()) {
                // TODO: reuse the sorted point array for child nodes (add to workspace)
                continue;
            }

            // check if there's a trailing plane. Otherwise the last plane has distance -1
            // since it just holds the trailing quads
            boolean endsWithPlane = quadsOn != null;

            // flush the last partition, use the -1 distance to indicate the end if it
            // doesn't use quadsOn (which requires a certain distance to be given)
            if (quadsBefore != null || quadsOn != null) {
                partitions.add(new Partition(endsWithPlane ? distance : -1, quadsBefore, quadsOn));
            }

            // check if this can be turned into a binary partition node
            // (if there's at most two partitions and one plane)
            if (partitions.size() <= 2) {
                // get the two partitions
                var inside = partitions.get(0);
                var outside = partitions.size() == 2 ? partitions.get(1) : null;
                if (outside == null || !endsWithPlane) {
                    var partitionDistance = inside.distance();
                    workspace.addAlignedPartitionPlane(axis, partitionDistance);

                    BSPNode insideNode = null;
                    BSPNode outsideNode = null;
                    if (inside.quadsBefore() != null) {
                        insideNode = BSPNode.buildChild(workspace, inside.quadsBefore(), depth);
                    }
                    if (outside != null) {
                        outsideNode = BSPNode.buildChild(workspace, outside.quadsBefore(), depth);
                    }
                    var onPlane = inside.quadsOn() == null ? null : inside.quadsOn().toIntArray();

                    return new InnerBinaryPartitionBSPNode(
                            partitionDistance, ModelQuadFacing.NORMALS[axis],
                            insideNode, outsideNode, onPlane);
                }
            }

            // create a multi-partition node
            int planeCount = endsWithPlane ? partitions.size() : partitions.size() - 1;
            float[] planeDistances = new float[planeCount];
            BSPNode[] partitionNodes = new BSPNode[planeCount + 1];
            int[][] onPlaneQuads = new int[planeCount][];

            // write the partition planes and nodes
            for (int i = 0, count = partitions.size(); i < count; i++) {
                var partition = partitions.get(i);

                // if the partition actually has a plane
                if (endsWithPlane || i < count - 1) {
                    var partitionDistance = partition.distance();
                    workspace.addAlignedPartitionPlane(axis, partitionDistance);

                    // TODO: remove
                    if (partitionDistance == -1) {
                        throw new IllegalStateException("partition distance not set");
                    }

                    planeDistances[i] = partitionDistance;
                }

                if (partition.quadsBefore() != null) {
                    partitionNodes[i] = BSPNode.buildChild(workspace, partition.quadsBefore(), depth);
                }
                if (partition.quadsOn() != null) {
                    onPlaneQuads[i] = partition.quadsOn().toIntArray();
                }
            }

            return new InnerMultiPartitionBSPNode(planeDistances, ModelQuadFacing.NORMALS[axis],
                    partitionNodes, onPlaneQuads);
        }

        // TODO: attempt unaligned partitioning, convex decomposition, etc.

        // test if the geometry intersects with itself
        // if there is self-intersection, return a multi leaf node to just give up
        // sorting this geometry. intersecting geometry is rare but when it does happen,
        // it should simply be accepted and rendered as-is. Whatever artifacts this
        // causes are considered "ok".
        // TODO: also do this test with unaligned quads
        int testsRemaining = 10000;
        for (int quadAIndex = 0; quadAIndex < indexes.size(); quadAIndex++) {
            var quadA = workspace.quads[indexes.getInt(quadAIndex)];
            if (quadA.facing() == ModelQuadFacing.UNASSIGNED) {
                continue;
            }
            for (int quadBIndex = quadAIndex + 1; quadBIndex < indexes.size(); quadBIndex++) {
                var quadB = workspace.quads[indexes.getInt(quadBIndex)];

                if (quadB.facing() == ModelQuadFacing.UNASSIGNED) {
                    continue;
                }

                // aligned quads intersect if their bounding boxes intersect
                boolean intersects = true;
                for (int axis = 0; axis < 3; axis++) {
                    var opposite = axis + 3;
                    var extentsA = quadA.extents();
                    var extentsB = quadB.extents();

                    if (extentsA[axis] < extentsB[opposite]
                            || extentsB[axis] < extentsA[opposite]) {
                        intersects = false;
                        break;
                    }
                }

                if (intersects) {
                    // sort quads by size ascending
                    indexes.sort((a, b) -> {
                        var quadX = workspace.quads[a];
                        var quadY = workspace.quads[b];
                        return Float.compare(quadX.getAlignedSurfaceArea(), quadY.getAlignedSurfaceArea());
                    });
                    return new LeafMultiBSPNode(indexes.toIntArray());
                }

                if (--testsRemaining == 0) {
                    break;
                }
            }

            if (testsRemaining == 0) {
                break;
            }
        }

        throw new BSPBuildFailureException("no partition found");
    }
}
