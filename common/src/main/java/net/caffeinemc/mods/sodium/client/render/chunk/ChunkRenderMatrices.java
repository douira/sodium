package net.caffeinemc.mods.sodium.client.render.chunk;

import org.joml.Matrix4fc;
import org.joml.Vector2fc;

public record ChunkRenderMatrices(Matrix4fc projection, Matrix4fc modelView, Vector2fc viewportSize) { }