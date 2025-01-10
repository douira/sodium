
package net.caffeinemc.mods.sodium.mixin.features.render.immediate.buffer_builder.sorting;

import com.mojang.blaze3d.vertex.*;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.nio.ByteBuffer;

@Mixin(MeshData.class)
public abstract class MeshDataMixin {
    /**
     * @author JellySquid
     * @reason Avoid slow memory accesses
     */
    @Overwrite
    private static Vector3f[] unpackQuadCentroids(ByteBuffer buffer, int vertices, VertexFormat format) {
        long vertexStride = format.getVertexSize();

        long pVertex1 = MemoryUtil.memAddress(buffer, format.getOffset(VertexFormatElement.POSITION));
        long pVertex2 = pVertex1 + (vertexStride * 2);

        int primitiveCount = vertices / 4;
        Vector3f[] centroid = new Vector3f[primitiveCount];

        for (int primitiveId = 0; primitiveId < primitiveCount; primitiveId++) {
            float x1 = MemoryUtil.memGetFloat(pVertex1 + 0L);
            float y1 = MemoryUtil.memGetFloat(pVertex1 + 4L);
            float z1 = MemoryUtil.memGetFloat(pVertex1 + 8L);

            float x2 = MemoryUtil.memGetFloat(pVertex2 + 0L);
            float y2 = MemoryUtil.memGetFloat(pVertex2 + 4L);
            float z2 = MemoryUtil.memGetFloat(pVertex2 + 8L);

            float cx = (x1 + x2) * 0.5F;
            float cy = (y1 + y2) * 0.5F;
            float cz = (z1 + z2) * 0.5F;

            centroid[primitiveId] = new Vector3f(cx, cy, cz);

            pVertex1 += vertexStride;
            pVertex2 += vertexStride;
        }

        return centroid;
    }
}