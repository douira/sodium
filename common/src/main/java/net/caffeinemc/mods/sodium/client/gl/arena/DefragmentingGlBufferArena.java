package net.caffeinemc.mods.sodium.client.gl.arena;

import net.caffeinemc.mods.sodium.client.gl.buffer.GlMutableBuffer;

// TODO: incremental defragmentation:
// - intra-buffer defragmentation: shake the biggest free segment to the right, and then all the way to the left, repeat. this only requires knowing the biggest free segment and performing a copy on it that's as big as possible.
// if it's not possible to move the biggest free segment around because it's smaller than both of the adjacent used segments, try the next smaller free segment, and so on. if there's no free segment that can be moved (and don't just move them back and forth), then perform inter-buffer defragmentation.
// requires telling owners that specific segments have moved and not just that the buffer has changed and that all segment offsets need to be recalculated.
// - inter-buffer defragmentation is just regular compaction where we copy the entirety of the buffer to a new buffer, or maybe just some regions of the buffer if particular ones are causing all the fragmentation.
public class DefragmentingGlBufferArena extends GlBufferArena {
    // profiling has shown that Long2ReferenceRBTreeMap is 58% slower than TreeMap here
    private final SizedTreeMap<GlBufferSegment> freeSegmentsByLength = new SizedTreeMap<>();

    protected DefragmentingGlBufferArena(ArenaAggregator parent, GlMutableBuffer initialBuffer, long capacity, int stride) {
        super(parent, initialBuffer, capacity, stride);
        this.addFreeSegment(this.head);
    }

    protected void addFreeSegment(GlBufferSegment segment) {
        this.freeSegmentsByLength.addSized(segment);
        this.checkSegmentAssertions(segment);
    }

    protected void removeFreeSegment(GlBufferSegment segment) {
        this.freeSegmentsByLength.removeSized(segment);

        // don't check segment assertions on remove because what we're removing is invalid
    }

    public long getBiggestFreeSegmentSize() {
        return this.freeSegmentsByLength.getHighestSize();
    }

    @Override
    GlBufferSegment takeFree(long size) {
        return this.freeSegmentsByLength.removeFirstFitting(size);
    }

    GlBufferSegment alloc(long size, RegionAllocatorHandle owner) {
        this.checkAssertions();

        GlBufferSegment free = this.takeFree(size);

        if (free == null) {
            return null;
        }

        GlBufferSegment result;

        // exact fit
        if (free.getLength() == size) {
            free.setOwner(owner);

            result = free;
        }
        // free space is larger than requested, return new segment at end of free space
        else {
            result = new GlBufferSegment(this, owner, free.getEnd() - size, size);
            result.setNext(free.getNext());
            result.setPrev(free);

            if (result.getNext() != null) {
                result.getNext().setPrev(result);
            }

            free.setLength(free.getLength() - size);
            free.setNext(result);

            this.addFreeSegment(free);
        }

        this.updateUsed(result.getLength(), owner);
        this.checkAssertions();

        return result;
    }

    @Override
    public void free(GlBufferSegment entry) {
        if (entry.isFree()) {
            throw new IllegalStateException("Already freed");
        }

        var owner = entry.getOwner();
        entry.setFree();

        this.updateUsed(-entry.getLength(), owner);

        GlBufferSegment next = entry.getNext();
        boolean nextFree = next != null && next.isFree();
        GlBufferSegment prev = entry.getPrev();
        boolean prevFree = prev != null && prev.isFree();

        // both free, merge with both
        if (nextFree && prevFree) {
            this.removeFreeSegment(prev);
            this.removeFreeSegment(next);
            prev.setLength(prev.getLength() + entry.getLength() + next.getLength());
            prev.setNext(next.getNext());
            if (next.getNext() != null) {
                next.getNext().setPrev(prev);
            }
            this.addFreeSegment(prev);
        } else if (nextFree) {
            this.removeFreeSegment(next);
            entry.setLength(entry.getLength() + next.getLength());
            entry.setNext(next.getNext());
            if (next.getNext() != null) {
                next.getNext().setPrev(entry);
            }
            this.addFreeSegment(entry);
        } else if (prevFree) {
            this.removeFreeSegment(prev);
            prev.setLength(prev.getLength() + entry.getLength());
            prev.setNext(entry.getNext());
            if (entry.getNext() != null) {
                entry.getNext().setPrev(prev);
            }
            this.addFreeSegment(prev);
        } else {
            this.addFreeSegment(entry);
        }

        this.checkAssertions();
    }
}
