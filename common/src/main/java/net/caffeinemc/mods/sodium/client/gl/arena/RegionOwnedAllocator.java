package net.caffeinemc.mods.sodium.client.gl.arena;

import net.caffeinemc.mods.sodium.client.gl.buffer.GlBuffer;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;

import java.util.function.Consumer;
import java.util.stream.Stream;

public class RegionOwnedAllocator implements AllocatorBase {
    private final RenderRegion region;
    private final Consumer<CommandList> onBufferChange;
    private GlBufferArena backingArena;
    long used;
    int usedSegments;

    public RegionOwnedAllocator(RenderRegion region, Consumer<CommandList> onBufferChange, GlBufferArena backingArena) {
        this.region = region;
        this.onBufferChange = onBufferChange;
        this.backingArena = backingArena;
    }

    float getFillFractionInv() {
        return this.region.getFillFractionInv();
    }

    void setBackingArena(GlBufferArena arena) {
        this.backingArena = arena;
    }

    @Override
    public long getDeviceAllocatedMemory() {
        return this.backingArena.getDeviceAllocatedMemory();
    }

    @Override
    public long getDeviceUsedMemory() {
        return this.backingArena.getDeviceUsedMemory();
    }

    @Override
    public void free(GlBufferSegment entry) {
        this.backingArena.free(entry);
    }

    @Override
    public void deleteSingleOwner(CommandList commands) {
        // differentiation of single-owner or shared deletion is handled at the arena level
        this.backingArena.deleteSingleOwner(commands);
    }

    @Override
    public boolean isEmpty() {
        return this.backingArena.isEmpty();
    }

    @Override
    public GlBuffer getBufferObject() {
        return this.backingArena.getBufferObject();
    }

    public boolean upload(CommandList commandList, Stream<PendingUpload> stream) {
        var prevBackingArena = this.backingArena;
        var bufferChanged = this.backingArena.upload(commandList, this, stream);
        return bufferChanged || this.backingArena != prevBackingArena;
    }

    public GlBufferArena getBackingArena() {
        return this.backingArena;
    }

    public boolean isSingleOwner() {
        return !(this.backingArena instanceof YoungGenGlBufferArena);
    }

    public void notifyBufferChanged(CommandList commandList) {
        this.onBufferChange.accept(commandList);
    }
}
