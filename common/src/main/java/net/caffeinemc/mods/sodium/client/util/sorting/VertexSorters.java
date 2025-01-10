package net.caffeinemc.mods.sodium.client.util.sorting;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.mojang.blaze3d.vertex.VertexSorting;
import it.unimi.dsi.fastutil.ints.IntArrays;
import it.unimi.dsi.fastutil.longs.LongArrays;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.NotNull;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static net.caffeinemc.mods.sodium.client.util.sorting.AbstractSort.*;

public class VertexSorters {
    public static VertexSorting sortByDistance(Vector3f origin) {
        return new SortByDistance(origin);
    }

    private static class SortByDistance extends AbstractVertexSorter {
        private final float originX, originY, originZ;

        private SortByDistance(Vector3f origin) {
            this.originX = origin.x;
            this.originY = origin.y;
            this.originZ = origin.z;
        }

        @Override
        public float calculateMetric(float x, float y, float z) {
            float dx = (this.originX - x);
            float dy = (this.originY - y);
            float dz = (this.originZ - z);

            return (dx * dx) + (dy * dy) + (dz * dz);
        }
    }

    public record TimeRecord(int size, int sorterIndex, long result) {
    }

    public static ReferenceArrayList<TimeRecord> timeResults = new ReferenceArrayList<>();
    public static boolean printedHeader = false;

    public static String dumpDataToCSV() {
        StringBuilder sb = new StringBuilder();

        // shuffle timeResults to avoid bias
        for (int i = 0; i < timeResults.size(); i++) {
            int randomIndexToSwap = i + (int) (Math.random() * (timeResults.size() - i));
            TimeRecord temp = timeResults.get(randomIndexToSwap);
            timeResults.set(randomIndexToSwap, timeResults.get(i));
            timeResults.set(i, temp);
        }

        if (!printedHeader) {
            // print header
            sb.append("Size,");
            sb.append(String.join(",", Arrays.stream(sorters).map(Sorter::getName).toArray(String[]::new)));
            sb.append('\n');

            printedHeader = true;
        }

        var results = new String[sorters.length];
        for (int i = 0; i < sorters.length; i++) {
            results[i] = "";
        }

        for (TimeRecord record : timeResults) {
            results[record.sorterIndex] = Long.toString(record.result);
            sb.append(record.size).append(',');
            sb.append(String.join(",", results)).append('\n');
            results[record.sorterIndex] = "";
        }

        timeResults.clear();
        return sb.toString();
    }

    private abstract static class Sorter {
        public final String name;
        public int index;

        public Sorter(String name) {
            this.name = name;
        }

        abstract int[] sort(int[] keys);

        public String getName() {
            return this.name;
        }
    }

    private static class OurIndirectRadixSort extends Sorter {
        OurIndirectRadixSort() {
            super("Our Indirect Radix Sort");
        }

        @Override
        public int[] sort(int[] keys) {
            return RadixSort.sort(keys);
        }
    }

    private static class OurIndirectMergeSort extends Sorter {
        OurIndirectMergeSort() {
            super("Our Indirect Merge Sort");
        }

        @Override
        public int[] sort(int[] keys) {
            var result = createIndexBuffer(keys.length);
            MergeSort.mergeSort(result, keys);
            return result;
        }
    }

    private static class FastutilIndirectRadixSort extends Sorter {
        FastutilIndirectRadixSort() {
            super("fastutil Indirect Radix Sort");
        }

        @Override
        public int[] sort(int[] keys) {
            var fastutilRadixSortResult = createIndexBuffer(keys.length);
            IntArrays.radixSortIndirect(fastutilRadixSortResult, keys, false);
            return fastutilRadixSortResult;
        }
    }

    private static class FastutilIndirectQuickSort extends Sorter {
        FastutilIndirectQuickSort() {
            super("fastutil Indirect Quick Sort");
        }

        @Override
        public int[] sort(int[] keys) {
            var fastutilQuickSortResult = createIndexBuffer(keys.length);
            IntArrays.quickSortIndirect(fastutilQuickSortResult, keys);
            return fastutilQuickSortResult;
        }
    }

    private static class JdkPackedSort extends Sorter {
        JdkPackedSort() {
            super("JDK Packed Sort");
        }

        @Override
        public int[] sort(int[] keys) {
            var items = prepareItems(keys);
            Arrays.sort(items);
            return extractIndices(items);
        }
    }

    private static class FastutilPackedMergeSort extends Sorter {
        FastutilPackedMergeSort() {
            super("fastutil Packed Merge Sort");
        }

        @Override
        public int[] sort(int[] keys) {
            var items = prepareItems(keys);
            LongArrays.mergeSort(items);
            return extractIndices(items);
        }
    }

    private static class FastutilPackedRadixSort extends Sorter {
        FastutilPackedRadixSort() {
            super("fastutil Packed Radix Sort");
        }

        @Override
        public int[] sort(int[] keys) {
            var items = prepareItems(keys);
            LongArrays.radixSort(items);
            return extractIndices(items);
        }
    }

    private static class FastutilPackedQuickSort extends Sorter {
        FastutilPackedQuickSort() {
            super("fastutil Packed Quick Sort");
        }

        @Override
        public int[] sort(int[] keys) {
            var items = prepareItems(keys);
            LongArrays.quickSort(items);
            return extractIndices(items);
        }
    }

    private static Sorter[] sorters = new Sorter[] {
        new OurIndirectRadixSort(),
        new FastutilIndirectRadixSort(),
        new FastutilIndirectQuickSort(),
        new OurIndirectMergeSort(),
        new JdkPackedSort(),
        new FastutilPackedMergeSort(),
        new FastutilPackedRadixSort(),
        new FastutilPackedQuickSort()
    };

    static {
        for (int i = 0; i < sorters.length; i++) {
            sorters[i].index = i;
        }
    }

    /**
     * Sorts the keys given by the subclass by descending value.
     */
    private static abstract class AbstractVertexSorter implements VertexSorting, VertexSortingExtended {
        @Override
        public final int @NotNull [] sort(Vector3f[] positions) {
            final var keys = new int[positions.length];

            for (int index = 0; index < positions.length; index++) {
                keys[index] = ~Float.floatToRawIntBits(this.calculateMetric(positions[index]));
            }

            return RadixSort.sort(keys);
        }

        @Override
        public int[] sort(ByteBuffer buffer, int vertexCount, VertexFormat vertexFormat) {
            // NOTE: Vanilla assumes that our position attribute is *always* at the start of the vertex format. While
            // for all intents and purposes this would be true, there is no reason we can't query it anyway.
            final int attributeOffset = vertexFormat.getOffset(VertexFormatElement.POSITION);

            // Pointer to the position attribute of the 1st and 3rd vertex of a quad
            long pVertexPosition0 = MemoryUtil.memAddress(buffer, attributeOffset);
            long pVertexPosition1 = MemoryUtil.memAddress(buffer, attributeOffset + (vertexFormat.getVertexSize() * 2));

            // Each pointer is advanced by four vertices at a time
            long stride = vertexFormat.getVertexSize() * 4L;

            final int primitiveCount = vertexCount / 4;
            final int[] sortKeys = new int[primitiveCount];

            int randomLength = Mth.clamp((int)(Math.random() * primitiveCount), 1, primitiveCount);

            for (int primitiveId = 0; primitiveId < randomLength; primitiveId++) {
                float x0 = MemoryUtil.memGetFloat(pVertexPosition0 + 0L);
                float y0 = MemoryUtil.memGetFloat(pVertexPosition0 + 4L);
                float z0 = MemoryUtil.memGetFloat(pVertexPosition0 + 8L);

                float x1 = MemoryUtil.memGetFloat(pVertexPosition1 + 0L);
                float y1 = MemoryUtil.memGetFloat(pVertexPosition1 + 4L);
                float z1 = MemoryUtil.memGetFloat(pVertexPosition1 + 8L);

                // Derive centroid using the mid-point of the quad's diagonal edge
                float cx = (x0 + x1) * 0.5F;
                float cy = (y0 + y1) * 0.5F;
                float cz = (z0 + z1) * 0.5F;

                sortKeys[primitiveId] = ~Float.floatToRawIntBits(this.calculateMetric(cx, cy, cz));

                pVertexPosition0 += stride;
                pVertexPosition1 += stride;
            }

            var start = System.nanoTime();
            // pick a random sorter
            int sorterIndex = (int) (Math.random() * sorters.length);
            var result = sorters[sorterIndex].sort(sortKeys);
            var sortTime = System.nanoTime() - start;

            timeResults.add(new TimeRecord(randomLength, sorterIndex, sortTime));

            return createIndexBuffer(primitiveCount);
        }

        protected abstract float calculateMetric(float x, float y, float z);

        protected float calculateMetric(Vector3f vector) {
            return this.calculateMetric(vector.x, vector.y, vector.z);
        }
    }

    public interface VertexSortingExtended {
        int[] sort(ByteBuffer buffer, int vertices, VertexFormat format);
    }
}
